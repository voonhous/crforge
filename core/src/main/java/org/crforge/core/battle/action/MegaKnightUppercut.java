package org.crforge.core.battle.action;

import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The evolved Mega Knight's uppercut, run as the unit attacks: a run on the unit that keeps the
 * target it attacks, pushes that target on its first update toward the nearest tower of the
 * target's own side, past every gate of the pushback request, schedules its action on the target
 * with the unit as the cause, and then holds the unit for its follow-up delay, neither moving nor
 * attacking. A target without a movement component ends the run at once.
 *
 * <p>Refused as the row is built: a push through the request's gates, the follow-up dash, and the
 * shared columns its run does not read.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the start's hold and target, the push point by the nearest tower"
            + " of the target's side and then the opposite king, the push through the entry past"
            + " the gates, the action on the target, the hold for the follow-up delay, the end"
            + " with another target in range or none, the mark of the target in the targeting"
            + " queue and the target forgotten as it leaves; held by mega_knight_ev1_uppercut."
            + " Refused: the push through the request's gates, the follow-up dash, the start"
            + " without a current target and the facing's push point with no tower or king.")
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

  /** The action scheduled on the target after the push, or null for none. */
  @Getter private final BattleAction actionOnTargets;

  /**
   * @param row the row's shared columns
   * @param pushBackStrength how far the target is pushed
   * @param pushRadiusDirectionalOffset how far behind the target the push point stands
   * @param distanceProportionalPush true to take the separation off the push's distance first
   * @param resetPushbackIfStronger true to keep a longer pushback already in flight
   * @param dashFollowUpDelayMs how long the unit is held after the push
   * @param actionOnTargets the action scheduled on the target after the push, or null
   */
  public MegaKnightUppercut(
      ActionRow row,
      int pushBackStrength,
      int pushRadiusDirectionalOffset,
      boolean distanceProportionalPush,
      boolean resetPushbackIfStronger,
      int dashFollowUpDelayMs,
      BattleAction actionOnTargets) {
    super(row);
    this.pushBackStrength = pushBackStrength;
    this.pushRadiusDirectionalOffset = pushRadiusDirectionalOffset;
    this.distanceProportionalPush = distanceProportionalPush;
    this.resetPushbackIfStronger = resetPushbackIfStronger;
    this.dashFollowUpDelayMs = dashFollowUpDelayMs;
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
