package org.crforge.core.battle.projectile;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.TargetLocks;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.CaptureCharacter;
import org.crforge.core.battle.data.ActionBinding;
import org.crforge.core.battle.unit.BattleWorld;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * One run of a capture on the object it runs on, a projectile or a character: the ids it has
 * captured, with the time and the distance of each capture, the ids whose drag is complete, the ids
 * it claimed on its last step, its cooldown and its hit timer. See {@link CaptureCharacter}.
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
            + " within the hide distance; held by the reference battle evo_snowball_on_musketeer."
            + " On a character, as the evolved Goblin Cage: the claims only while its targeting"
            + " component is on, the grants and the capture distances from the pull centre, the"
            + " drag delay and the pause before the drag, the cooldown after a capture leaves,"
            + " the action on each completed capture, the hit per hit frequency at the owner's"
            + " level with its hit-speed scaled timer, and the hold tags without a capture buff;"
            + " not held by a recorded battle (no 16.402.18 reference plays the evolved Goblin"
            + " Cage). Held by no run: a capture that leaves the battle or dies, and the"
            + " distances it leaves behind, a claim whose lock another holds, two units equally"
            + " near, and a unit within the hide distance before the drag time has passed.")
public final class CaptureRun extends ActionInstance {

  /** The step every timer takes, in milliseconds. */
  private static final int STEP_MS = 50;

  /** The channel its locks are asked on. */
  private static final int CHANNEL = 0;

  /** How long a capture's buff lasts, in milliseconds. */
  static final int CAPTURE_BUFF_MS = 99999;

  private final CaptureCharacter.Columns columns;
  private final CaptureHost host;

  private final List<Integer> captured = new ArrayList<>();
  private final List<Integer> timesMs = new ArrayList<>();
  private final List<Integer> lengths = new ArrayList<>();
  private final List<Integer> complete = new ArrayList<>();
  private final List<Integer> claimed = new ArrayList<>();

  /** True once the run has made its first capture. */
  private boolean firstCaptured;

  /** The time toward the next hit on its captures. */
  private int hitTimerMs;

  /** The time left before it claims again after a capture left its list. */
  private int cooldownMs;

  /**
   * @param row the capture
   * @param host what the capture runs on
   */
  public CaptureRun(CaptureCharacter row, CaptureHost host) {
    super(row);
    this.columns = row.getColumns();
    this.host = host;
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
    BattleWorld world = host.world();
    // The captures that have left the battle or died, from the last: each starts the cooldown.
    for (int k = captured.size() - 1; k >= 0; k--) {
      if (!world.listedAndAlive(captured.get(k))) {
        int id = captured.remove(k);
        timesMs.remove(k);
        cooldownMs = columns.captureCooldownMs();
        complete.remove(Integer.valueOf(id));
      }
    }
    if (host.claims()) {
      claims(world);
    }
    drags();
    if (cooldownMs >= 1) {
      cooldownMs -= STEP_MS;
    }
    world.captureStepped(host.owner(), captured, complete, timesMs);
  }

  /**
   * Last step's claims granted at the pull centre, then new claims around the owner's point while
   * the run holds fewer captures than its count and its cooldown has run out.
   */
  private void claims(BattleWorld world) {
    int x = host.x();
    int y = host.y();
    TargetLocks locks = world.locks();
    // Last step's claims, from the last: each whose lock the owner holds and that passes the
    // filter is captured at the pull centre; then every claim is forgotten.
    int centreX = x + columns.pullCenterOffsetX();
    int centreY = y + columns.pullCenterOffsetY();
    for (int k = claimed.size() - 1; k >= 0; k--) {
      int id = claimed.get(k);
      if (!locks.claim(host.id(), id, CHANNEL)) {
        continue;
      }
      WorldEntity unit = world.liveEntity(id);
      if (unit != null && host.passes(unit, columns.targetFilter())) {
        capture(unit, centreX, centreY);
      }
    }
    claimed.clear();
    if (captured.size() < columns.numberOfUnitsToCapture() && cooldownMs <= 0) {
      List<WorldEntity> found =
          new ArrayList<>(host.query(columns.captureRadius(), columns.targetFilter()));
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
        boolean answer = locks.request(host.id(), pick.getId(), CHANNEL, priority, 0);
        world.captureRequested(host.owner(), pick, priority, answer);
        claimed.add(pick.getId());
      }
    }
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
    BattleWorld world = host.world();
    boolean reached = false;
    for (int k = 0; k < captured.size(); k++) {
      WorldEntity unit = world.liveEntity(captured.get(k));
      int t = timesMs.get(k);
      boolean delayed = t >= columns.dragDelayMs();
      if (delayed
          && drag(unit, t - columns.dragDelayMs(), lengths.get(k))
          && columns.hideAction() != null
          && !world.taggedHidden(unit)) {
        scheduleOnUnit(unit, columns.hideAction());
      }
      timesMs.set(k, t + STEP_MS);
      if (timer >= columns.hitFrequencyMs()) {
        host.hit(unit, columns.damagePerHit());
      }
      reached |= delayed;
    }
    // The timer runs while a capture has reached its drag, at the owner's hit speed.
    hitTimerMs = reached ? hitTimerMs + host.hitStep(STEP_MS) : 0;
  }

  /**
   * A capture: listed with time 0 and its distance from the pull centre; the first of the run
   * schedules the first-capture action on the owner, the unit its cause; the action on the captured
   * object is scheduled on the unit, the owner its cause; and the unit takes the capture buff, when
   * the row has one, the owner its parent and source.
   */
  private void capture(WorldEntity unit, int x, int y) {
    GridEntity at = unit.getView();
    captured.add(unit.getId());
    timesMs.add(0);
    lengths.add(FixedMath.guardedDistance(x - at.getX(), y - at.getY()));
    if (!firstCaptured) {
      firstCaptured = true;
      if (columns.onFirstCaptureAction() != null) {
        scheduleOnOwner(unit, columns.onFirstCaptureAction());
      }
    }
    if (columns.actionOnCapturedObject() != null) {
      scheduleOnUnit(unit, columns.actionOnCapturedObject());
    }
    if (columns.buffDuringCapture() != null) {
      host.buff(unit, columns.buffDuringCapture(), CAPTURE_BUFF_MS);
    }
  }

  /**
   * One step of a capture's drag toward the owner's point, its time counted from the drag delay;
   * true once it is complete: the drag time has passed since the pause, or the unit stands within
   * the hide distance, and it has been put on the point. During the pause it is only turned to the
   * point.
   */
  private boolean drag(WorldEntity unit, int t, int length) {
    BattleWorld world = host.world();
    world.captureTagged(unit, columns.buffDuringCapture() == null);
    int x = host.x();
    int y = host.y();
    GridEntity at = unit.getView();
    int distance = FixedMath.guardedDistance(x - at.getX(), y - at.getY());
    int paused = t - columns.timePausedWhenGrabbingMs();
    if (paused < 0) {
      world.captureFaced(host.name(), unit, x, y);
      return false;
    }
    int dragTime = columns.captureDragTimeMs();
    if (paused >= dragTime || distance < columns.hideDistance()) {
      host.hasCapture();
      world.capturePutOn(
          host.name(), unit, x, y, columns.heightModifier(), columns.heightModifierCap());
      if (!complete.contains(unit.getId())) {
        complete.add(unit.getId());
        if (columns.onCaptureAction() != null) {
          scheduleOnOwner(unit, columns.onCaptureAction());
        }
      }
      return true;
    }
    // An eased share: the sine of the drag's share of a quarter turn, in whole degrees.
    int angle = paused * 100000 / dragTime * 90 / 100000;
    int sine = FixedMath.sine1024(angle);
    int step = (length - columns.hideDistance()) * sine * 50 / 1048 / dragTime;
    int[] vec = {x - at.getX(), y - at.getY()};
    FixedMath.normalize(vec, step);
    world.captureDragged(host.name(), unit, at.getX() + vec[0], at.getY() + vec[1], x, y);
    return false;
  }

  /**
   * Schedules an action on the owner with a unit as its cause: from the run pass, so it waits for
   * the next pending pass.
   */
  private void scheduleOnOwner(WorldEntity cause, String action) {
    host.world().captureScheduled(host.owner(), cause, action);
    host.scheduleOnOwner(cause, action);
  }

  /**
   * Schedules an action, built on a unit, on the unit with the owner as its cause: from the run
   * pass, so it waits for the next pending pass.
   */
  private void scheduleOnUnit(WorldEntity unit, String action) {
    BattleWorld world = host.world();
    world.captureScheduled(unit, host.owner(), action);
    ActionBinding binding = world.binding(unit);
    unit.actionHolder()
        .schedule(
            world.getActions().build(action, binding),
            ActionHolder.OWN_DELAY,
            false,
            host.actionHolder());
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
