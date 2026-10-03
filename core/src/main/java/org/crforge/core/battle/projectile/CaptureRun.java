package org.crforge.core.battle.projectile;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.TargetLocks;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.CaptureCharacter;
import org.crforge.core.battle.unit.BattleWorld;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * One run of a capture on its projectile: the ids it has captured, with the time and the distance
 * of each capture, the ids whose drag is complete, and the ids it claimed on its last step. See
 * {@link CaptureCharacter}.
 *
 * <p>A capture that leaves its list takes its time with it but leaves its distance behind, so the
 * distances of the captures after it pair with the wrong ones from then on, as the standard game
 * keeps them.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the step's order, the claims granted from the last, the nearest"
            + " pick by the wrapped squared distance with the first of equals kept, the priority"
            + " by the guarded distance, the drag's eased step from the angle of its share of the"
            + " drag time, and the put on the point once the drag time has passed or the unit is"
            + " within the hide distance; held by firecracker_snowball_goblins and"
            + " snowball_ev1_goblins. Held by no run: a capture that leaves the battle or dies, and"
            + " the distances it leaves behind, a claim whose lock another holds, two units equally"
            + " near, and a unit within the hide distance before the drag time has passed.")
final class CaptureRun extends ActionInstance {

  /** The step every timer takes, in milliseconds. */
  private static final int STEP_MS = 50;

  /** The channel its locks are asked on. */
  private static final int CHANNEL = 0;

  /** How long a capture's buff lasts, in milliseconds. */
  static final int CAPTURE_BUFF_MS = 99999;

  private final CaptureCharacter.Columns columns;
  private final ProjectileEntity projectile;

  private final List<Integer> captured = new ArrayList<>();
  private final List<Integer> timesMs = new ArrayList<>();
  private final List<Integer> lengths = new ArrayList<>();
  private final List<Integer> complete = new ArrayList<>();
  private final List<Integer> claimed = new ArrayList<>();

  /** True once the run has made its first capture. */
  private boolean firstCaptured;

  /** The time toward the next hit on its captures. */
  private int hitTimerMs;

  /**
   * @param row the capture
   * @param projectile the projectile it runs on
   */
  CaptureRun(CaptureCharacter row, ProjectileEntity projectile) {
    super(row);
    this.columns = row.getColumns();
    this.projectile = projectile;
  }

  /** The ids it has captured, in its list's order. */
  List<Integer> captured() {
    return List.copyOf(captured);
  }

  /** The ids whose drag is complete, in its list's order. */
  List<Integer> complete() {
    return List.copyOf(complete);
  }

  /** The time of each capture, in the captured list's order. */
  List<Integer> timesMs() {
    return List.copyOf(timesMs);
  }

  @Override
  protected void update(ActionHolder holder) {
    BattleWorld world = projectile.world();
    // The captures that have left the battle or died, from the last.
    for (int k = captured.size() - 1; k >= 0; k--) {
      if (!world.listedAndAlive(captured.get(k))) {
        int id = captured.remove(k);
        timesMs.remove(k);
        complete.remove(Integer.valueOf(id));
      }
    }
    int x = projectile.getX();
    int y = projectile.getY();
    TargetLocks locks = world.locks();
    // Last step's claims, from the last: each whose lock the projectile holds and that passes the
    // filter is captured; then every claim is forgotten.
    for (int k = claimed.size() - 1; k >= 0; k--) {
      int id = claimed.get(k);
      if (!locks.claim(projectile.getId(), id, CHANNEL)) {
        continue;
      }
      WorldEntity unit = world.liveEntity(id);
      if (unit != null && world.capturePasses(projectile, unit, columns.targetFilter())) {
        capture(unit, x, y);
      }
    }
    claimed.clear();
    if (captured.size() < columns.numberOfUnitsToCapture()) {
      List<WorldEntity> found =
          new ArrayList<>(
              world.captureQuery(projectile, columns.captureRadius(), columns.targetFilter()));
      while (!found.isEmpty()
          && captured.size() + claimed.size() < columns.numberOfUnitsToCapture()) {
        WorldEntity pick = nearest(found, x, y);
        found.remove(pick);
        if (captured.contains(pick.getId())) {
          continue;
        }
        GridEntity at = pick.getView();
        int priority =
            (columns.capturePriority() << 16)
                - FixedMath.guardedDistance(x - at.getX(), y - at.getY());
        boolean answer = locks.request(projectile.getId(), pick.getId(), CHANNEL, priority, 0);
        world.captureRequested(projectile, pick, priority, answer);
        claimed.add(pick.getId());
      }
    }
    drags();
    world.captureStepped(projectile, captured, complete, timesMs);
  }

  /** Every capture's drag, with the hit timer around it. */
  private void drags() {
    if (captured.isEmpty()) {
      hitTimerMs = 0;
      return;
    }
    int timer = hitTimerMs;
    if (timer >= columns.hitFrequencyMs()) {
      hitTimerMs = 0;
    }
    BattleWorld world = projectile.world();
    for (int k = 0; k < captured.size(); k++) {
      WorldEntity unit = world.liveEntity(captured.get(k));
      int t = timesMs.get(k);
      if (drag(unit, t, lengths.get(k))
          && columns.hideAction() != null
          && !world.taggedHidden(unit)) {
        scheduleOnUnit(unit, columns.hideAction());
      }
      timesMs.set(k, t + STEP_MS);
      if (timer >= columns.hitFrequencyMs()) {
        throw new UnsupportedOperationException(
            projectile.name() + " hits its capture " + unit.name() + ", not modelled");
      }
    }
    // Without a drag delay every capture has reached its drag, and a projectile has no buffs to
    // scale the step.
    hitTimerMs += STEP_MS;
  }

  /**
   * A capture: listed with time 0 and its distance from the point; the first of the run schedules
   * the first-capture action on the projectile, the unit its cause; the action on the captured
   * object is scheduled on the unit, the projectile its cause; and the unit takes the capture buff,
   * the projectile its parent and source.
   */
  private void capture(WorldEntity unit, int x, int y) {
    GridEntity at = unit.getView();
    captured.add(unit.getId());
    timesMs.add(0);
    lengths.add(FixedMath.guardedDistance(x - at.getX(), y - at.getY()));
    BattleWorld world = projectile.world();
    if (!firstCaptured) {
      firstCaptured = true;
      if (columns.onFirstCaptureAction() != null) {
        scheduleOnProjectile(unit, columns.onFirstCaptureAction());
      }
    }
    if (columns.actionOnCapturedObject() != null) {
      scheduleOnUnit(unit, columns.actionOnCapturedObject());
    }
    world.captureBuff(projectile, unit, columns.buffDuringCapture(), CAPTURE_BUFF_MS);
  }

  /**
   * One step of a capture's drag toward the projectile's point; true once it is complete: the drag
   * time has passed, or the unit stands within the hide distance, and it has been put on the point.
   */
  private boolean drag(WorldEntity unit, int t, int length) {
    BattleWorld world = projectile.world();
    world.captureTagged(unit);
    int x = projectile.getX();
    int y = projectile.getY();
    GridEntity at = unit.getView();
    int distance = FixedMath.guardedDistance(x - at.getX(), y - at.getY());
    int dragTime = columns.captureDragTimeMs();
    if (t >= dragTime || distance < columns.hideDistance()) {
      world.capturePutOn(projectile, unit, x, y);
      if (!complete.contains(unit.getId())) {
        complete.add(unit.getId());
      }
      return true;
    }
    // An eased share: the sine of the drag's share of a quarter turn, in whole degrees.
    int angle = t * 100000 / dragTime * 90 / 100000;
    int sine = FixedMath.sine1024(angle);
    int step = (length - columns.hideDistance()) * sine * 50 / 1048 / dragTime;
    int[] vec = {x - at.getX(), y - at.getY()};
    FixedMath.normalize(vec, step);
    world.captureDragged(projectile, unit, at.getX() + vec[0], at.getY() + vec[1], x, y);
    return false;
  }

  /**
   * Schedules an action, built on the projectile, on the projectile with a unit as its cause: from
   * the run pass, so it waits for the next pending pass.
   */
  private void scheduleOnProjectile(WorldEntity cause, String action) {
    BattleWorld world = projectile.world();
    world.captureScheduled(projectile, cause, action);
    projectile
        .actionHolder()
        .schedule(
            world.getActions().build(action, new ProjectileBinding(world, projectile)),
            ActionHolder.OWN_DELAY,
            false,
            cause.actionHolder());
  }

  /**
   * Schedules an action, built on a unit, on the unit with the projectile as its cause: from the
   * run pass, so it waits for the next pending pass.
   */
  private void scheduleOnUnit(WorldEntity unit, String action) {
    BattleWorld world = projectile.world();
    world.captureScheduled(unit, projectile, action);
    unit.actionHolder()
        .schedule(
            world.getActions().build(action, world.binding(unit)),
            ActionHolder.OWN_DELAY,
            false,
            projectile.actionHolder());
  }

  /**
   * The object of a list nearest a point by the wrapped squared distance, the first of equals; the
   * list is not empty.
   */
  private static WorldEntity nearest(List<WorldEntity> objects, int x, int y) {
    int best = Integer.MAX_VALUE;
    WorldEntity pick = null;
    for (WorldEntity object : objects) {
      int dx = x - object.getView().getX();
      int dy = y - object.getView().getY();
      int squared = dx * dx + dy * dy;
      if (squared < best) {
        best = squared;
        pick = object;
      }
    }
    return pick;
  }
}
