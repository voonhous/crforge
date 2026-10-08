package org.crforge.core.battle.action;

import lombok.Builder;
import lombok.Getter;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that captures the enemies around the object it runs on and holds them: the evolved
 * Snowball's rolling snowball carries them while it moves on, the evolved Goblin Cage holds one and
 * hits it. A run on its owner claims its captures through the battle's target locks, drags each
 * onto the owner and holds it there.
 *
 * <p>Each step of the run:
 *
 * <ul>
 *   <li>forgets each captured object, from the last, that has left the battle or died, each
 *       starting the cooldown;
 *   <li>unless the owner is a character whose targeting component is off (a deploy or a stun):
 *       captures each object it claimed on its last step, from the last claimed, whose lock on
 *       channel 0 the owner now holds and that still passes its filter, measuring its distance from
 *       the pull centre (the owner's point moved by the pull centre offsets): the first capture of
 *       the run schedules its first-capture action on the owner with the object as the cause, and
 *       every capture schedules its action on the captured object on that object with the owner as
 *       the cause and gives it the capture buff, when the row has one, for 99999 ms, the owner its
 *       parent and source at its level; the claims are then forgotten, granted or not; then, below
 *       its count of captures and with the cooldown run out, claims what its query finds within its
 *       capture radius around the owner under its filter, nearest first, skipping what it holds:
 *       each claim asks for a lock on channel 0 with the priority its row's priority shifted 16 up,
 *       less the object's distance from the owner, which the post-pass grants;
 *   <li>drags every captured object whose time has reached the drag delay: its one-step tag word
 *       gains the capture's tags (and NO_MOVE, NO_SUMMON and NO_ATTACK without a capture buff);
 *       during the pause it is only turned to the owner; then it moves toward the owner by an eased
 *       share of its distance at the capture, less the hide distance, and turns to it; once the
 *       drag time has passed since the pause, or it is within the hide distance, the owner's word
 *       gains HAS_CAPTURE, the object is put on the owner's point, its route reset and, with a
 *       height change, the change pushed with its floor, the action on a completed capture is
 *       scheduled on the owner the first time, the object its cause, and the hide action is
 *       scheduled on it, the owner the cause, while its tag word does not hold the hidden tag.
 *       Every step of a capture adds 50 to its time, and while the hit timer has reached the hit
 *       frequency each capture takes the damage per hit at the owner's level. The timer then grows
 *       by 50 at the owner's hit speed while a capture has reached the drag delay, else it is 0; a
 *       positive cooldown loses 50.
 * </ul>
 *
 * <p>The pull clips and frames, the grab point, the effects and the capture and idle animation
 * labels and priority are read only by the capture's view. Refused as the row is built: a row
 * without a filter, and the shared columns its run does not read. As it starts: an owner other than
 * a projectile or a character. As it runs: a hit on a projectile, a capture buff on a character,
 * and putting down a jumping captured unit.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the forgetting of captures that left, the grant of last step's"
            + " claims by the lock and the filter, the first-capture and captured-object actions,"
            + " the capture buff with the projectile as parent and source, the claims nearest"
            + " first by priority, the eased drag, the put on the point, the route reset and the"
            + " hide action while the unit is not hidden; held by the reference battle"
            + " evo_snowball_on_musketeer, where the evolved Snowball captures. Held by no run: a"
            + " capture that leaves or dies, a lock another holds, two units equally near and a"
            + " unit within the hide distance before the drag time has passed. On a character, as"
            + " the evolved Goblin Cage, with the drag delay and pause, the pull centre, the"
            + " cooldown, the action per completed capture, the damage per hit and no capture"
            + " buff: not held by a recorded battle (no 16.402.18 reference plays the evolved"
            + " Goblin Cage). The height change of a completed capture is pushed each step (the"
            + " loader's -15000 with floor 0 on both rows, which folds to no change on a unit"
            + " standing at height 0). Refused: the shared columns, and the capture tags' readers"
            + " it does not model.")
public final class CaptureCharacter extends RowAction {

  /**
   * The row's own columns.
   *
   * @param captureRadius the radius of the query around the owner
   * @param numberOfUnitsToCapture the most objects it captures and claims at once
   * @param capturePriority the priority of its lock requests, before the shift and the distance
   * @param captureDragTimeMs how long the drag of a capture lasts
   * @param hideDistance how near the owner a dragged object is put on it, and how much of its
   *     distance at the capture the drag leaves out
   * @param hitFrequencyMs the time between two hits on its captures
   * @param damagePerHit the damage of one hit on a capture, before the owner's level scales it
   * @param dragDelayMs how long a capture waits before its drag starts
   * @param timePausedWhenGrabbingMs how long a capture is held, turned to the owner, after the drag
   *     delay before it moves
   * @param pullCenterOffsetX the pull centre's offset from the owner along the width
   * @param pullCenterOffsetY the pull centre's offset from the owner along the length
   * @param captureCooldownMs how long it waits to claim again once a capture leaves its list
   * @param heightModifier the height change pushed on a completed capture each step, 0 for none
   * @param heightModifierCap the floor of that push
   * @param targetFilter the filter its query and its grants ask
   * @param hideAction the action a completed capture schedules on the object, or null
   * @param onFirstCaptureAction the action its first capture schedules on the owner, or null
   * @param onCaptureAction the action each completed capture schedules on the owner once, or null
   * @param actionOnCapturedObject the action every capture schedules on the object, or null
   * @param buffDuringCapture the buff every capture gives the object, or null for none
   */
  @Builder
  public record Columns(
      int captureRadius,
      int numberOfUnitsToCapture,
      int capturePriority,
      int captureDragTimeMs,
      int hideDistance,
      int hitFrequencyMs,
      int damagePerHit,
      int dragDelayMs,
      int timePausedWhenGrabbingMs,
      int pullCenterOffsetX,
      int pullCenterOffsetY,
      int captureCooldownMs,
      int heightModifier,
      int heightModifierCap,
      GameObjectFilter targetFilter,
      String hideAction,
      String onFirstCaptureAction,
      String onCaptureAction,
      String actionOnCapturedObject,
      String buffDuringCapture) {}

  /** The loader's HeightModifier for a row that leaves it unset. */
  public static final int DEFAULT_HEIGHT_MODIFIER = -15000;

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
