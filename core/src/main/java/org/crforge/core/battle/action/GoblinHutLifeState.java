package org.crforge.core.battle.action;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * The Goblin Hut's life state, its starting action: a run that waits for an enemy within the hut's
 * reach and, while it holds one, spawns a goblin toward it at an interval. Its perform does
 * nothing; the run keeps four words - a timer, the target's id, a lost byte and a spawn count - and
 * is started inside the pending pass that starts the row, then stepped by every run pass from that
 * tick on.
 *
 * <p>The start asks for a target: one found is kept and spawned at once, after the start-spawning
 * action; with none, the start-waiting action and the run waits. Each step, with the owner's
 * targeting component off (a stun), the target is let go as lost and nothing else happens: the
 * timer holds. Otherwise:
 *
 * <ul>
 *   <li><b>Waiting</b> (no target, not lost): a target found is kept and spawned at once, after the
 *       start-spawning action; the timer does not advance.
 *   <li><b>Holding a target</b>: the timer advances by the step the owner's spawn speed makes of 50
 *       first; then a target the keep test refuses is let go as lost, and the step ends.
 *   <li><b>Lost</b>: the timer advances; a target found is taken without a spawn; with none, once
 *       the timer reaches the interval, the start-waiting action, the timer back to 0 and waiting.
 *   <li>Then, holding a target or lost below the interval: at the interval a spawn and the interval
 *       off the timer; the row's effect tag, which only the hut's own effect rows read.
 * </ul>
 *
 * <p>The finder queries the object index around the owner within its reach - its collision radius
 * plus its range - testing a building by its square, through the row's filter, and takes the
 * strictly nearest by squared centre distance, in 32 bits, that the owner's validator accepts; the
 * earlier of equals. The keep test asks the same reach plus the target's radius with at most where
 * the finder is strict, and the validator again. A target that leaves the battle marks the run
 * lost, its id kept.
 *
 * <p>Each spawn makes as many children as the row's count, each at the spawn offset from the owner
 * toward the target, the vector turned: for a single child by the row's angle, its sign by the side
 * the target lies on and flipped on every odd spawn; for several, fanned from 45 degrees down to
 * -45. The point is kept 250 inside the arena and moved off water, and the child is made there.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled line for line: the start, the step's branches, the finder, the keep test, the"
            + " notice of a leaving target, the spawn point and the one-child spawn; held by the"
            + " reference battle card_GoblinHut. Not modelled: the turn of the owner toward its"
            + " target before each spawn, which only changes its facing, and the effect tag,"
            + " which only the owner's own effect rows read.")
public final class GoblinHutLifeState extends RowAction {

  /** The step the timer advances by, before the owner's buffs scale it. */
  private static final int STEP_MS = 50;

  /** The kept target id of a run that holds none. */
  public static final int NO_TARGET = -1;

  /** How far inside the arena's edges a spawn point is kept. */
  private static final int EDGE = 250;

  /**
   * The row's own columns.
   *
   * @param spawnIntervalMs the time between spawns while a target is held
   * @param spawnData the row of the children
   * @param spawnNumber how many children each spawn makes
   * @param spawnOffset how far from the owner toward the target they are made
   * @param singleDeployOffsetAngle the degrees a single child's point is turned by
   * @param objectFilter the filter the finder asks
   * @param onSpawnAction what runs on the owner after each spawn, or null
   * @param onStartSpawningAction what runs on the owner as it takes a target to spawn at, or null
   * @param onStartWaitingAction what runs on the owner as it starts to wait, or null
   * @param toggleEffectTag the name of the tag each spawning step sets on the owner, or null
   */
  @Builder
  public record Columns(
      int spawnIntervalMs,
      String spawnData,
      int spawnNumber,
      int spawnOffset,
      int singleDeployOffsetAngle,
      GameObjectFilter objectFilter,
      BattleAction onSpawnAction,
      BattleAction onStartSpawningAction,
      BattleAction onStartWaitingAction,
      String toggleEffectTag) {}

  /** The run's four words. */
  public record Memory(int timerMs, int target, boolean lost, int count) {}

  /** What a run tells the battle it did. */
  public sealed interface Event {}

  /**
   * The run's start or one of its steps: its words before and after and what it called, in order.
   *
   * @param start true for the start, which has no words before
   */
  public record Stepped(boolean start, Memory before, List<String> calls, Memory after)
      implements Event {}

  /** A finder that the query answered: the ids listed, and the one taken or {@link #NO_TARGET}. */
  public record Found(List<Integer> listed, int found) implements Event {}

  /** One child's point, before and after the relocation off water. */
  public record SpawnPoint(int count, int index, int target, int x, int y, int atX, int atY)
      implements Event {}

  /** The kept target left the battle, which marked the run lost. */
  public record TargetLeft(int target) implements Event {}

  private final Columns columns;

  /**
   * @param row the row's shared columns
   * @param columns its own columns
   */
  public GoblinHutLifeState(ActionRow row, Columns columns) {
    super(row);
    this.columns = columns;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    Run run = new Run(this, holder.getOwner().goblinHutLife());
    run.begin();
    return run;
  }

  /** One run: its four words. */
  private final class Run extends ActionInstance {

    private final GoblinHutLife host;
    private int timerMs;
    private int target = NO_TARGET;
    private boolean lost;
    private int count;
    private final List<String> calls = new ArrayList<>();

    private Run(BattleAction action, GoblinHutLife host) {
      super(action);
      this.host = host;
    }

    private Memory memory() {
      return new Memory(timerMs, target, lost, count);
    }

    /** The start: a target found is spawned at once; with none the run waits. */
    void begin() {
      calls.clear();
      int found = find();
      if (found != NO_TARGET) {
        target = found;
        schedule(columns.onStartSpawningAction());
        spawn();
      } else {
        schedule(columns.onStartWaitingAction());
        lost = false;
        timerMs = 0;
        target = NO_TARGET;
      }
      host.log(new Stepped(true, null, List.copyOf(calls), memory()));
    }

    @Override
    protected void update(ActionHolder holder) {
      Memory before = memory();
      calls.clear();
      step();
      host.log(new Stepped(false, before, List.copyOf(calls), memory()));
    }

    private void step() {
      if (!host.active()) {
        target = NO_TARGET;
        lost = true;
        calls.add("inactive");
        return;
      }
      if (target == NO_TARGET && !lost) {
        int found = find();
        if (found == NO_TARGET) {
          return;
        }
        target = found;
        schedule(columns.onStartSpawningAction());
        spawn();
        return;
      }
      timerMs += host.spawnSpeed(STEP_MS);
      if (lost) {
        int found = find();
        if (found != NO_TARGET) {
          lost = false;
          target = found;
        } else if (timerMs >= columns.spawnIntervalMs()) {
          schedule(columns.onStartWaitingAction());
          lost = false;
          timerMs = 0;
          target = NO_TARGET;
          return;
        }
      } else if (!keep()) {
        target = NO_TARGET;
        lost = true;
        calls.add("lost");
        return;
      }
      if (timerMs >= columns.spawnIntervalMs()) {
        spawn();
        timerMs -= columns.spawnIntervalMs();
      }
      calls.add("tag " + columns.toggleEffectTag());
    }

    @Override
    protected void objectLeft(int leftId) {
      if (leftId == target) {
        boolean wasLost = lost;
        lost = true;
        if (!wasLost) {
          host.log(new TargetLeft(leftId));
        }
      }
    }

    private void schedule(BattleAction action) {
      calls.add("schedule " + (action == null ? null : action.name()));
      host.schedule(action);
    }

    /**
     * The strictly nearest object the query lists and the validator accepts, by squared centre
     * distance in 32 bits, the earlier of equals; {@link #NO_TARGET} for none.
     */
    private int find() {
      List<Integer> listed = host.query(host.reach(), columns.objectFilter());
      int best = NO_TARGET;
      int bestDistance = Integer.MAX_VALUE;
      for (int id : listed) {
        if (!host.valid(id)) {
          continue;
        }
        int dx = host.ownerX() - host.x(id);
        int dy = host.ownerY() - host.y(id);
        int distance = dx * dx + dy * dy;
        if (distance < bestDistance) {
          best = id;
          bestDistance = distance;
        }
      }
      if (!listed.isEmpty()) {
        host.log(new Found(List.copyOf(listed), best));
      }
      return best;
    }

    /**
     * The kept target at most its radius plus the owner's reach from the owner, by the guarded
     * squared distance, and still accepted by the validator.
     */
    private boolean keep() {
      if (!host.live(target)) {
        throw new IllegalStateException("the kept target " + target + " left unnoticed");
      }
      int reach = host.radius(target) + host.reach();
      int squared =
          FixedMath.guardedSumOfSquares(
              host.x(target) - host.ownerX(), host.y(target) - host.ownerY());
      if (squared > reach * reach) {
        return false;
      }
      return host.valid(target);
    }

    /** As many children as the row's count, the count up after each, then the spawn action. */
    private void spawn() {
      for (int i = 0; i < columns.spawnNumber(); i++) {
        calls.add("spawn " + i + " " + count);
        int[] point = spawnPoint(i);
        int[] at = host.relocated(point[0], point[1]);
        host.log(new SpawnPoint(count, i, target, point[0], point[1], at[0], at[1]));
        host.spawn(columns.spawnData(), at[0], at[1]);
        count++;
      }
      schedule(columns.onSpawnAction());
    }

    /** Child {@code i}'s point toward the target, turned, kept inside the arena. */
    private int[] spawnPoint(int i) {
      int ox = host.ownerX();
      int oy = host.ownerY();
      int[] vec = {host.x(target) - ox, host.y(target) - oy};
      FixedMath.normalize(vec, columns.spawnOffset());
      int n = columns.spawnNumber();
      if (n >= 2) {
        FixedMath.rotate1024(vec, 45 - i * 90 / (n - 1));
      } else {
        FixedMath.rotate1024(vec, 0);
      }
      if (n == 1) {
        // Turned one way for a target to the right of the owner and the other for one to its left.
        int heading = FixedMath.angleOfVector(vec[0], vec[1]);
        int sign = heading < 91 || heading >= 271 ? -1 : 1;
        int angle = columns.singleDeployOffsetAngle() * sign;
        FixedMath.rotate1024(vec, (count & 1) != 0 ? -angle : angle);
      }
      int x = vec[0] + ox;
      int y = vec[1] + oy;
      int maxX = host.arenaWidth() * 500 - EDGE;
      int maxY = host.arenaHeight() * 500 - EDGE;
      x = Math.min(Math.max(x, EDGE), maxX);
      y = Math.min(Math.max(y, EDGE), maxY);
      return new int[] {x, y};
    }
  }
}
