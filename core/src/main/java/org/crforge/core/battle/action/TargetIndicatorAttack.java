package org.crforge.core.battle.action;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * An action that lasts on a character and shoots a projectile at a target it marks first, as the
 * Goblin Machine's rocket does. It does nothing as it starts but list its run, which keeps its own
 * load, its own cooldown and its attacks - each a time, a signal and a projectile - apart from the
 * character's targeting: it asks the targeting component only whether it is on.
 *
 * <p>Each step first takes the stop tags off the run and, with the targeting component on, adds the
 * step the owner's hit speed makes of 50 to the load. NO_ATTACK on the owner or the component off
 * (a stun, the deploy) ends every attack, ending its signal, sets the stop tags and puts a started
 * cooldown back to 0, and the step ends. Nothing more happens until the load is past LoadTime.
 * Then, with no attack and the cooldown out or never started, the finder begins an attack, or the
 * cooldown is set to -1 and it asks again on the next step. Each attack is stepped:
 *
 * <ul>
 *   <li>without a live signal the finder is asked again (once a step, whoever asks): with nobody
 *       the attack is finished; otherwise the signal is made at the target's point and the
 *       indication action is scheduled on the owner, the signal as its cause;
 *   <li>with its projectile still live nothing happens;
 *   <li>at AttackDelay the projectile is launched from the owner's point plus its facing scaled to
 *       the look-direction offset, at the start height, to the signal's point; the cooldown goes
 *       back to 0, the stop tags are set and the shoot action is scheduled on the owner, the
 *       projectile as its cause;
 *   <li>a step later the attack is finished, which ends its signal.
 * </ul>
 *
 * <p>The finished attacks are removed from the last, then every attack's time and a started
 * cooldown gain the step. The finder queries around the owner's point to the range beyond its
 * radius, testing a building by its square, keeps the objects whose centre lies between the minimum
 * range and the range beyond both radii, ends included, and takes the least squared distance less
 * the object's const-priority offset, floored at 0, the earlier of equals; it asks no validator.
 * When the owner leaves the battle the run stops: every attack ends and the stop tags are set. A
 * projectile in flight still lands.
 *
 * <p>Refused as the row is built: an indication delay, a negative attack delay, a minimum range
 * below 1, a following signal, a homing projectile, a singleton, a next action, tags and the gates.
 * As it starts: an owner other than a character, and a clone.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the load with the component on, the abort and its cooldown, the"
            + " finder once a step with its ring and nearest, the signal at the target's point, the"
            + " shot from behind the facing at AttackDelay, the cooldown from the shot, the end"
            + " once the projectile has gone, the stop tags and the stop as the owner leaves; held"
            + " by goblin_machine_knight, a Musketeer marked beyond a Knight inside the ring, and"
            + " goblin_machine_tower, a princess tower marked and shot while the machine walks,"
            + " then hit from inside the ring, the run stopped at its death. Held by the tests"
            + " alone: the ring's edges, an abort by a stun, and a signal whose target has walked"
            + " off. Not held by a reference: the step under a hit-speed buff. Refused: an"
            + " indication delay, a negative attack delay, a minimum range below 1, a following"
            + " signal, a homing projectile, a singleton, a next action, tags, the gates, an"
            + " owner other than a character and a clone.")
public final class TargetIndicatorAttack extends RowAction {

  /** The step every time takes, before the owner's buffs scale it, in milliseconds. */
  private static final int STEP_MS = 50;

  /** The id an attack keeps for a signal or a projectile it has not made yet. */
  public static final int NONE = -1;

  /**
   * The row's own columns.
   *
   * @param loadTimeMs how long the load must run before the first attack
   * @param attackDelayMs how long after the signal the projectile is launched
   * @param attackCooldownMs how long after a shot the next attack may begin
   * @param range how far beyond both radii the finder reaches
   * @param minimumRange how far beyond both radii an object must stand at least
   * @param targetFilter the filter the finder's query asks
   * @param targetAoE the row of the signal
   * @param projectile the row of the projectile
   * @param projectileStartZ the height the projectile starts at
   * @param lookOffset how far along the owner's facing the projectile starts, behind it when
   *     negative
   * @param stopTags the tags a shot or an end sets on the run until its next step
   * @param targetStartIndicationAction what runs on the owner as a signal is made, or null
   * @param onProjectileShootAction what runs on the owner as the projectile is launched, or null
   */
  @Builder
  public record Columns(
      int loadTimeMs,
      int attackDelayMs,
      int attackCooldownMs,
      int range,
      int minimumRange,
      GameObjectFilter targetFilter,
      String targetAoE,
      String projectile,
      int projectileStartZ,
      int lookOffset,
      long stopTags,
      BattleAction targetStartIndicationAction,
      BattleAction onProjectileShootAction) {}

  /**
   * The run's fields as they stand.
   *
   * @param loadMs the load
   * @param cooldownMs the cooldown, -1 when it has not started
   * @param timesMs each attack's time
   * @param signals each attack's signal id, or {@link #NONE}
   * @param projectiles each attack's projectile id, or {@link #NONE}
   * @param stopTags true while the stop tags are set on the run
   */
  public record Fields(
      int loadMs,
      int cooldownMs,
      List<Integer> timesMs,
      List<Integer> signals,
      List<Integer> projectiles,
      boolean stopTags) {}

  /** What a run tells the battle it did. */
  public sealed interface Event {}

  /** The run of the row started, in the pending pass of the given phase. */
  public record Started(String action, int phase) implements Event {}

  /** The finder found an object: the ids the query listed, and the one it took. */
  public record Found(List<Integer> listed, int found) implements Event {}

  /**
   * A signal made at the target's point.
   *
   * @param target the target's id
   * @param signal the signal's id
   * @param x its point along the width
   * @param y its point along the length
   * @param packedLevel its level, packed against its own rarity
   */
  public record Signalled(int target, int signal, int x, int y, int packedLevel) implements Event {}

  /**
   * A projectile launched at a signal.
   *
   * @param projectile the projectile's id
   * @param signal the signal's id
   * @param x its start along the width
   * @param y its start along the length
   * @param z its start's height
   * @param aimX its aim along the width
   * @param aimY its aim along the length
   * @param packedLevel its level, packed against its own rarity
   * @param facingX the owner's facing along the width
   * @param facingY the owner's facing along the length
   */
  public record Shot(
      int projectile,
      int signal,
      int x,
      int y,
      int z,
      int aimX,
      int aimY,
      int packedLevel,
      int facingX,
      int facingY)
      implements Event {}

  /** A live signal ended as its attack ended. */
  public record SignalEnded(int signal) implements Event {}

  /**
   * A step that did more than ask the finder for nobody or end no attack: its fields before and
   * after, and what it called, in order.
   */
  public record Stepped(Fields before, List<String> calls, Fields after) implements Event {}

  /** The run stopped as its owner left: what it called, and its fields after. */
  public record Stopped(List<String> calls, Fields after) implements Event {}

  /** The row's own columns. */
  @Getter private final Columns columns;

  /**
   * @param row the row's shared columns
   * @param columns its own columns
   */
  public TargetIndicatorAttack(ActionRow row, Columns columns) {
    super(row);
    this.columns = columns;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    TargetIndicatorHost host = holder.getOwner().targetIndicatorHost();
    host.log(new Started(name(), holder.passPhase()));
    return new Run(host);
  }

  /** One run: the load, the cooldown and the attacks. */
  private final class Run extends ActionInstance {

    private final TargetIndicatorHost host;
    private int loadMs;
    private int cooldownMs = -1;
    private final List<Integer> timesMs = new ArrayList<>();
    private final List<Integer> signals = new ArrayList<>();
    private final List<Integer> projectiles = new ArrayList<>();
    private final List<Integer> finished = new ArrayList<>();

    /** True once the finder has answered in this step; it is asked at most once a step. */
    private boolean cached;

    private int target = NONE;
    private final List<String> calls = new ArrayList<>();

    private Run(TargetIndicatorHost host) {
      super(TargetIndicatorAttack.this);
      this.host = host;
    }

    private Fields fields() {
      return new Fields(
          loadMs,
          cooldownMs,
          List.copyOf(timesMs),
          List.copyOf(signals),
          List.copyOf(projectiles),
          columns.stopTags() != 0 && (getTags() & columns.stopTags()) == columns.stopTags());
    }

    @Override
    protected void update(ActionHolder holder) {
      Fields before = fields();
      calls.clear();
      step();
      // A step that only asked the finder for nobody, or ended no attack, is not told.
      if (calls.stream().anyMatch(c -> !c.equals("find null") && !c.equals("abort 0"))) {
        host.log(new Stepped(before, List.copyOf(calls), fields()));
      }
    }

    private void step() {
      cached = false;
      int rate = host.timeStep(STEP_MS);
      clearTags(columns.stopTags());
      if (host.active()) {
        loadMs += rate;
      }
      if (host.noAttack() || !host.active()) {
        calls.add("abort " + timesMs.size());
        endAttacks();
        if (cooldownMs >= 0) {
          cooldownMs = 0;
        }
        return;
      }
      if (loadMs <= columns.loadTimeMs()) {
        return;
      }
      if (timesMs.isEmpty() && (cooldownMs < 0 || cooldownMs >= columns.attackCooldownMs())) {
        if (find() != NONE) {
          timesMs.add(0);
          signals.add(NONE);
          projectiles.add(NONE);
          calls.add("begin");
        } else {
          cooldownMs = -1;
        }
      }
      // The count is read again each round.
      for (int i = 0; i < timesMs.size(); i++) {
        attack(i);
      }
      for (int k = finished.size() - 1; k >= 0; k--) {
        remove(finished.get(k));
      }
      finished.clear();
      timesMs.replaceAll(t -> t + rate);
      if (cooldownMs >= 0) {
        cooldownMs += rate;
      }
    }

    /** One attack's step. */
    private void attack(int i) {
      int signal = signals.get(i);
      int projectile = projectiles.get(i);
      boolean signalLive = signal != NONE && host.live(signal);
      boolean gone = projectile == NONE || !host.live(projectile);
      if (!signalLive) {
        int found = find();
        if (found == NONE) {
          finished.add(i);
          calls.add("finished " + i + " no target");
          return;
        }
        // The indication delay is 0, so the signal is made on the finder's step.
        int made = host.signal(columns.targetAoE(), found);
        calls.add("signal " + i + " " + found + " " + made);
        if (columns.targetStartIndicationAction() != null) {
          host.schedule(columns.targetStartIndicationAction(), made);
        }
        signals.set(i, made);
        return;
      }
      if (!gone) {
        return;
      }
      int t = timesMs.get(i);
      if (t < columns.attackDelayMs()) {
        return;
      }
      if (t >= columns.attackDelayMs() + STEP_MS) {
        finished.add(i);
        calls.add("finished " + i + " done");
        return;
      }
      int[] look = host.facing();
      FixedMath.normalize(look, columns.lookOffset());
      int x = look[0] + host.ownerX();
      int y = look[1] + host.ownerY();
      int launched = host.launch(columns.projectile(), signal, x, y, columns.projectileStartZ());
      calls.add(
          "shoot %d %d %d %d %d %d"
              .formatted(i, signal, launched, x, y, columns.projectileStartZ()));
      projectiles.set(i, launched);
      cooldownMs = 0;
      addTags(columns.stopTags());
      if (columns.onProjectileShootAction() != null) {
        host.schedule(columns.onProjectileShootAction(), launched);
      }
    }

    /** The finder's answer, asked at most once a step. */
    private int find() {
      if (cached) {
        return target;
      }
      target = ringFind();
      cached = true;
      calls.add("find " + (target == NONE ? "null" : String.valueOf(target)));
      return target;
    }

    /**
     * The ring finder: the query to the range beyond the owner's radius, each object kept by the
     * ring test, then the least squared distance less the object's const-priority offset, floored
     * at 0, strictly less; the earlier of equals.
     */
    private int ringFind() {
      int x = host.ownerX();
      int y = host.ownerY();
      int outer = columns.range() + host.ownerRadius();
      int inner = columns.minimumRange() + host.ownerRadius();
      List<Integer> listed = host.query(outer, columns.targetFilter());
      int best = NONE;
      int bestDistance = Integer.MAX_VALUE;
      for (int id : listed) {
        if (!inRing(id, x, y, outer, inner)) {
          continue;
        }
        int d = FixedMath.squaredDistance(host.x(id), host.y(id), x, y) - host.priority(id);
        d = Math.max(d, 0);
        if (d < bestDistance) {
          best = id;
          bestDistance = d;
        }
      }
      if (best != NONE) {
        host.log(new Found(List.copyOf(listed), best));
      }
      return best;
    }

    /**
     * The ring test: the object's squared distance at most its radius plus the outer reach,
     * squared, and at least its radius plus the inner reach, squared.
     */
    private boolean inRing(int id, int x, int y, int outer, int inner) {
      int r = host.radius(id);
      int far = (r + outer) * (r + outer);
      int near = (r + inner) * (r + inner);
      int d = FixedMath.squaredDistance(x, y, host.x(id), host.y(id));
      return d <= far && d >= near;
    }

    /** Sets the stop tags and removes every attack, from the last. */
    private void endAttacks() {
      addTags(columns.stopTags());
      for (int j = timesMs.size() - 1; j >= 0; j--) {
        remove(j);
      }
    }

    /** Removes an attack, ending its signal while it is live. */
    private void remove(int j) {
      timesMs.remove(j);
      int signal = signals.remove(j);
      if (signal != NONE && host.live(signal)) {
        host.endSignal(signal);
      }
      projectiles.remove(j);
      calls.add("remove " + j + " " + signal);
    }

    @Override
    protected void stop(ActionHolder holder) {
      calls.clear();
      calls.add("stop " + timesMs.size());
      endAttacks();
      host.log(new Stopped(List.copyOf(calls), fields()));
    }
  }
}
