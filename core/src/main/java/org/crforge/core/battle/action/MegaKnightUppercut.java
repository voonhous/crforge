package org.crforge.core.battle.action;

import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The evolved Mega Knight's uppercut, run as the unit attacks: a run on the unit that keeps the
 * target it attacks, pushes that target on its first update toward the nearest tower of the
 * target's own side, past every gate of the pushback request, schedules its action on the target
 * with the unit as the cause, and then holds the unit for its follow-up delay, neither moving nor
 * attacking. A push that went through also clears the target's avoidance blend unless the row turns
 * that off, so the target flies straight. A target without a movement component ends the run at
 * once. A target carrying NO_PUSHBACK, as a counter's parry leaves it, is not pushed: the run ends
 * without the hold, and the action on the target is scheduled only when the row asks for it on a
 * refused push too.
 *
 * <p>Refused as the row is built: a push through the request's gates, the follow-up dash, and the
 * shared columns its run does not read.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the start's hold and target, the push point by the nearest tower"
            + " of the target's side and then the opposite king, the push through the entry past"
            + " the gates, the action on the target, the blend cleared after a push that went"
            + " through, the hold for the follow-up delay, the end with another target in range"
            + " or none, the mark of the target in the targeting queue and the target forgotten"
            + " as it leaves; held by the reference battles cg_megaknight_evo_uppercut_giant and"
            + " evo_megaknight_vs_musketeer. The push refused on a NO_PUSHBACK target, with no"
            + " action on it while OnlyRunActionOnPushback is set; held by"
            + " BattleUppercutWindTest. Refused: the push through the request's gates, the"
            + " follow-up dash, the start without a current target and the facing's push point"
            + " with no tower or king.")
public final class MegaKnightUppercut extends RowAction {

  /** How far the target is pushed. */
  @Getter private final int pushBackStrength;

  /** How far behind the target the push point stands, along the line from its tower. */
  @Getter private final int pushRadiusDirectionalOffset;

  /** True when the separation is taken off the push's distance first. */
  @Getter private final boolean distanceProportionalPush;

  /** True when a longer pushback already in flight is kept. */
  @Getter private final boolean resetPushbackIfStronger;

  /** How long the unit is held after the push. */
  @Getter private final int dashFollowUpDelayMs;

  /** True when a push that went through clears the target's avoidance blend. */
  @Getter private final boolean resetAvoidanceAtPushback;

  /**
   * True when the action on the target is scheduled only after a push the entry took; false when a
   * refused push schedules it too.
   */
  @Getter private final boolean onlyRunActionOnPushback;

  /** The action scheduled on the target after the push, or null for none. */
  @Getter private final BattleAction actionOnTargets;

  /**
   * @param row the row's shared columns
   * @param pushBackStrength how far the target is pushed
   * @param pushRadiusDirectionalOffset how far behind the target the push point stands
   * @param distanceProportionalPush true to take the separation off the push's distance first
   * @param resetPushbackIfStronger true to keep a longer pushback already in flight
   * @param dashFollowUpDelayMs how long the unit is held after the push
   * @param resetAvoidanceAtPushback true to clear the target's avoidance blend after a push that
   *     went through
   * @param onlyRunActionOnPushback true to schedule the action on the target only after a push the
   *     entry took
   * @param actionOnTargets the action scheduled on the target after the push, or null
   */
  public MegaKnightUppercut(
      ActionRow row,
      int pushBackStrength,
      int pushRadiusDirectionalOffset,
      boolean distanceProportionalPush,
      boolean resetPushbackIfStronger,
      int dashFollowUpDelayMs,
      boolean resetAvoidanceAtPushback,
      boolean onlyRunActionOnPushback,
      BattleAction actionOnTargets) {
    super(row);
    this.pushBackStrength = pushBackStrength;
    this.pushRadiusDirectionalOffset = pushRadiusDirectionalOffset;
    this.distanceProportionalPush = distanceProportionalPush;
    this.resetPushbackIfStronger = resetPushbackIfStronger;
    this.dashFollowUpDelayMs = dashFollowUpDelayMs;
    this.resetAvoidanceAtPushback = resetAvoidanceAtPushback;
    this.onlyRunActionOnPushback = onlyRunActionOnPushback;
    this.actionOnTargets = actionOnTargets;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    return holder
        .getOwner()
        .uppercut(this, holder.passPhase(), instigator == null ? null : instigator.getOwner());
  }
}
