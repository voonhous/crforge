package org.crforge.core.battle.action;

import lombok.Builder;
import lombok.Getter;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that captures the enemies around the projectile it runs on and carries them, as the
 * evolved Snowball's rolling snowball does: a run on the projectile that claims its captures
 * through the battle's target locks, drags each onto the projectile and holds it there while the
 * projectile moves on.
 *
 * <p>Each step of the run:
 *
 * <ul>
 *   <li>forgets each captured object, from the last, that has left the battle or died;
 *   <li>captures each object it claimed on its last step, from the last claimed, whose lock on
 *       channel 0 the projectile now holds and that still passes its filter: the first capture of
 *       the run schedules its first-capture action on the projectile with the object as the cause,
 *       and every capture schedules its action on the captured object on that object with the
 *       projectile as the cause and gives it the capture buff for 99999 ms, the projectile its
 *       parent and source at its level; the claims are then forgotten, granted or not;
 *   <li>below its count of captures, claims what its query finds within its capture radius around
 *       the projectile under its filter, nearest first, skipping what it holds: each claim asks for
 *       a lock on channel 0 with the priority its row's priority shifted 16 up, less the object's
 *       distance from the projectile, which the post-pass grants;
 *   <li>drags every captured object: its one-step tag word gains the capture's tags, and it moves
 *       toward the projectile by an eased share of its distance at the capture, less the hide
 *       distance, and turns to it; once the drag time has passed, or it is within the hide
 *       distance, it is put on the projectile's point, its route reset, and the hide action is
 *       scheduled on it, the projectile the cause, while its tag word does not hold the hidden tag.
 *       Every step of a capture adds 50 to its time.
 * </ul>
 *
 * <p>Refused as the row is built: a delay before the drag, a pause in it, a pull centre off the
 * projectile, a cooldown, a height change, an action on each completed capture, damage per hit, a
 * row without a capture buff, and the shared columns its run does not read. As it starts: an owner
 * other than a projectile. As it runs: the hit its frequency would deal, and putting down a jumping
 * captured unit.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the forgetting of captures that left, the grant of last step's"
            + " claims by the lock and the filter, the first-capture and captured-object actions,"
            + " the capture buff with the projectile as parent and source, the claims nearest"
            + " first by priority, the eased drag, the put on the point, the route reset and the"
            + " hide action while the unit is not hidden; held by firecracker_snowball_goblins,"
            + " where it captures four Goblins and carries them. Held by no run: a capture that"
            + " leaves or dies, a lock another holds, two units equally near and a unit within the"
            + " hide distance before the drag time has passed. Refused: a drag delay or pause, a pull centre, a cooldown, a height"
            + " change, an action per completed capture, damage per hit, no capture buff, the"
            + " shared columns, and the capture tags' readers it does not model.")
public final class CaptureCharacter extends RowAction {

  /**
   * The row's own columns.
   *
   * @param captureRadius the radius of the query around the projectile
   * @param numberOfUnitsToCapture the most objects it captures and claims at once
   * @param capturePriority the priority of its lock requests, before the shift and the distance
   * @param captureDragTimeMs how long the drag of a capture lasts
   * @param hideDistance how near the projectile a dragged object is put on it, and how much of its
   *     distance at the capture the drag leaves out
   * @param hitFrequencyMs the time between two hits on its captures
   * @param targetFilter the filter its query and its grants ask
   * @param hideAction the action a completed capture schedules on the object, or null
   * @param onFirstCaptureAction the action its first capture schedules on the projectile, or null
   * @param actionOnCapturedObject the action every capture schedules on the object, or null
   * @param buffDuringCapture the buff every capture gives the object
   */
  @Builder
  public record Columns(
      int captureRadius,
      int numberOfUnitsToCapture,
      int capturePriority,
      int captureDragTimeMs,
      int hideDistance,
      int hitFrequencyMs,
      GameObjectFilter targetFilter,
      String hideAction,
      String onFirstCaptureAction,
      String actionOnCapturedObject,
      String buffDuringCapture) {}

  /** The row's own columns. */
  @Getter private final Columns columns;

  /**
   * @param row the row's shared columns
   * @param columns its own columns
   */
  public CaptureCharacter(ActionRow row, Columns columns) {
    super(row);
    this.columns = columns;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return holder.getOwner().captureCharacter(this, holder.passPhase());
  }
}
