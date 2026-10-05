package org.crforge.core.battle.action;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntityState;

/**
 * An action that makes a guard behind an area effect, lets it finish a short deploy and then sends
 * it charging ahead, pushing and hitting what it passes, as the Little Prince's ability does.
 *
 * <p>It runs twice per use. The first run lasts on the area effect: its start makes the guard and
 * the second run, and its first step finishes it. The guard appears behind the area effect's point,
 * toward its own side, moved off water, at the area effect's level and side; the second run is
 * listed on it with its four tags, it is added with its registration visit, faced toward the area
 * effect's point and put into its deploy, whose entry ends with the combat gate.
 *
 * <p>The second run waits while more than one step of the deploy is left, keeping its shadow tag
 * meanwhile. From the deploy's last step on, every step asks the object query around the guard and
 * pushes each object with a movement component away from it, the gates skipped, to the push's
 * strength less the current separation and, while a push is in flight, keeping the longer one; then
 * it hits each object once per run, unless it is a character that may not be touched or it has no
 * hit points, for the push damage at the guard's level. The first such step cuts the deploy short
 * and starts the charge to the area effect's point carried the target radius ahead, toward the
 * enemy side, clamped into the arena. The run then goes on until the step after the guard leaves
 * the dashing state, which it pushes on too, and finishes.
 *
 * <p>On a data version whose game makes the charge's pushes and hits an area effect's (see {@link
 * GuardHost#makesArea()}), the second run reads none of the push columns: on the step its push pass
 * would run it makes the row's area effect (SpawnAEO), once, at the guard's point, the guard its
 * source and the object it follows, and ends it as the run finishes; the area effect pushes and
 * hits. An area effect that leaves before is made again on the next such step. Without SpawnAEO
 * nothing is pushed or hit.
 *
 * <p>Refused as the row is built: tags, a singleton, a next action, the gates, a step by the hit
 * speed and a phase of its own, none of which the shipped row sets; and a row without a filter or
 * whose guard is not a character, and an area effect whose row sets a column not modelled. As it
 * starts: an owner other than an area effect, and an area effect on a data version whose guard run
 * pushes and hits by itself, which reads no such column.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the guard's point behind the area effect, its level and side, the"
            + " second run's tags and point, the registration visit before the deploy, the facing"
            + " and the deploy's entry; the wait while more than one step is left, the push pass"
            + " with the gates skipped, the separation and the longer push kept, a hit once per"
            + " run, the deploy cut, the charge point and the finish after the dash. Held by"
            + " little_prince_ability_giant and little_prince_ability_knights. The area effect made"
            + " in place of the push pass, on the newer data version, at the charge's step, ended"
            + " by the finish: held by ability_little_prince, where it stands on the guard from"
            + " its charge's step to its run's finish. Not modelled: the statistics the start"
            + " reports and the hit's presentation. Refused: tags, a"
            + " singleton, a next action, the gates, a step by the hit speed, a phase of its own,"
            + " a row without a filter and an owner other than an area effect.")
public final class SpawnGuard extends RowAction {

  /** The step every timer takes, in milliseconds. */
  private static final int STEP_MS = 50;

  /**
   * The row's own columns.
   *
   * @param spawnData the guard's row
   * @param appearBehindAtDistance how far behind the area effect's point the guard appears
   * @param targetRadius how far ahead of that point the guard charges
   * @param pushBackStrength how far a push carries, before the separation is taken off
   * @param pushBackRadius the radius of the query around the guard
   * @param continuousPushBack true to push again while a push is in flight, keeping the longer one
   * @param distanceProportionalPush true to take the current separation off a push
   * @param pushBackDamage the damage of a hit, at the first level
   * @param hitFilter the filter its query asks
   * @param spawnAeo the area effect the second run makes in place of its push pass, on a data
   *     version whose guard run makes one; null for none
   * @param guardTags the tags the second run sets for as long as it is listed
   * @param shadowTag the tag it sets while more than one step of the deploy is left
   */
  @Builder
  public record Columns(
      String spawnData,
      int appearBehindAtDistance,
      int targetRadius,
      int pushBackStrength,
      int pushBackRadius,
      boolean continuousPushBack,
      boolean distanceProportionalPush,
      int pushBackDamage,
      GameObjectFilter hitFilter,
      String spawnAeo,
      long guardTags,
      long shadowTag) {}

  /** What the first run asks of the area effect it runs on. */
  public interface Maker {

    /**
     * Makes the guard and lists its run, as the first run's start does.
     *
     * @param action the row
     * @param phase the pending pass the first run starts in
     */
    void makeGuard(SpawnGuard action, int phase);

    /** The first run's step, which finishes it, told to the battle's observers. */
    void firstStepped(SpawnGuard action);
  }

  /** The row's own columns. */
  @Getter private final Columns columns;

  /**
   * @param row the row's shared columns
   * @param columns its own columns
   */
  public SpawnGuard(ActionRow row, Columns columns) {
    super(row);
    this.columns = columns;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    Maker maker = holder.getOwner().guardMaker(this);
    maker.makeGuard(this, holder.passPhase());
    return new FirstRun(maker);
  }

  /**
   * The second run, on the guard: what its steps start from is the area effect's point.
   *
   * @param host the guard's answers
   * @param x the area effect's point along the width
   * @param y the area effect's point along the length
   * @return the run, carrying its tags; the caller lists it on the guard
   */
  public ActionInstance guardRun(GuardHost host, int x, int y) {
    if (columns.spawnAeo() != null && !host.makesArea()) {
      throw new UnsupportedOperationException(
          name() + " sets SpawnAEO, which its data version's guard run does not read");
    }
    GuardRun run = new GuardRun(host, x, y);
    run.addTags(columns.guardTags() | columns.shadowTag());
    return run;
  }

  /** The first run, on the area effect: its first step finishes it. */
  private final class FirstRun extends ActionInstance {

    private final Maker maker;

    private FirstRun(Maker maker) {
      super(SpawnGuard.this);
      this.maker = maker;
    }

    @Override
    protected void update(ActionHolder holder) {
      finish();
      maker.firstStepped(SpawnGuard.this);
    }
  }

  /** The second run, on the guard. */
  private final class GuardRun extends ActionInstance {

    private final GuardHost host;
    private int x;
    private int y;
    private boolean charging;
    private final List<Integer> hit = new ArrayList<>();

    /** The id of the area effect the run made, or -1 for none or one that has left. */
    private int area = -1;

    private GuardRun(GuardHost host, int x, int y) {
      super(SpawnGuard.this);
      this.host = host;
      this.x = x;
      this.y = y;
    }

    @Override
    protected void update(ActionHolder holder) {
      List<String> calls = new ArrayList<>();
      step(calls, holder.passPhase());
      host.stepped(charging, getTags(), isFinished(), calls);
    }

    /** An area effect it made that leaves is forgotten, so the next step makes it again. */
    @Override
    protected void objectLeft(int leftId) {
      if (leftId == area) {
        area = -1;
      }
    }

    private void step(List<String> calls, int phase) {
      if (host.state() == GridEntityState.DEPLOYING) {
        int left = host.deployCountdownMs();
        if (left > STEP_MS) {
          addTags(columns.shadowTag());
          if (charging) {
            chargingEnd(calls);
          }
          return;
        }
        clearTags(columns.shadowTag());
      } else {
        clearTags(columns.shadowTag());
      }
      if (host.makesArea()) {
        // The newer game's run reads no push column: the area effect it makes pushes and hits.
        if (columns.spawnAeo() != null && area < 0) {
          area = host.spawnArea(name(), columns.spawnAeo(), phase);
          calls.add("area " + columns.spawnAeo() + " " + area);
        }
      } else if (columns.pushBackStrength() >= 1) {
        pushPass(calls);
      }
      if (charging) {
        chargingEnd(calls);
        return;
      }
      if (host.state() == GridEntityState.DEPLOYING) {
        host.cutDeploy();
        calls.add("deploy_cut");
      }
      if (!host.hasTargeting()) {
        end(calls);
        calls.add("finish no_component");
        return;
      }
      // The point is carried ahead before the clamp, and kept as it was carried.
      y = host.teamSign() * columns.targetRadius() + y;
      int[] clamped = host.clamp(x, y);
      x = clamped[0];
      y = clamped[1];
      calls.add("charge " + x + " " + y);
      host.charge(x, y);
      charging = true;
    }

    /** While the guard dashes the run goes on; out of the dash it finishes. */
    private void chargingEnd(List<String> calls) {
      if (host.state() != GridEntityState.DASHING) {
        end(calls);
        calls.add("finish charge_over");
      }
    }

    /** Finishes the run, ending the area effect it made, which is still there. */
    private void end(List<String> calls) {
      finish();
      if (area >= 0) {
        host.endArea(area);
        calls.add("area_end " + area);
      }
    }

    /**
     * The query around the guard; each object with a movement component pushed, then each hit once
     * per run.
     */
    private void pushPass(List<String> calls) {
      List<Integer> objects = host.query(columns.pushBackRadius(), columns.hitFilter());
      int amount = host.damageAtLevel(columns.pushBackDamage());
      calls.add(
          "query " + String.join(",", objects.stream().map(host::name).toList()) + " " + amount);
      for (int id : objects) {
        int pushed =
            host.push(
                id,
                columns.pushBackStrength(),
                columns.distanceProportionalPush(),
                columns.continuousPushBack());
        if (pushed >= 0) {
          calls.add("push " + host.name(id) + " " + pushed);
        }
        if (amount <= 0) {
          // The damage-free loop asks whether a character may be touched and reads nothing back.
          continue;
        }
        if (host.character(id) && host.untouchable(id)) {
          calls.add("untouchable " + host.name(id));
          continue;
        }
        if (hit.contains(id) || !host.hasHitPoints(id)) {
          continue;
        }
        hit.add(id);
        host.hit(id, amount);
        calls.add("hit " + host.name(id) + " " + amount);
      }
    }
  }
}
