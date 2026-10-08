package org.crforge.core.battle.action;

import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that lasts on its owner and holds it on the ground for a time, as Vines does: a ground
 * unit is held where it is, and an air unit is pulled down, held at height 0 and let climb back.
 * While it holds, it raises FORCE_IS_GROUND on its owner, under which the owner is a ground unit to
 * every reader of its layer from its next pre-hook. Its height changes are pushed to the owner,
 * which folds them into its live height at its next pre-hook. A second start of a singleton row
 * while its run lasts starts the run's phase over.
 *
 * <p>An air unit that lands at the end of its descent has the row's action once on the ground
 * scheduled on it, with itself as the cause, as the evolved Royal Hog's fall does.
 *
 * <p>Refused as the row is built: the landing and landing end actions, a path reset at landing and
 * a next action. As it starts: an owner that is a clone, hovers, rides another or carries riders,
 * and an action once on the ground for a run that starts on the ground. As it ends: the path reset
 * of an air unit that lived through its climb, none of which a reference holds.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the start's phase from the owner's height and layer, the four"
            + " phases, the pushes, FORCE_IS_GROUND raised, the re-trigger and the finish; held by"
            + " vines_group, a Giant and a Knight held and a Minion pulled down until a Knight"
            + " kills it, and vines_tower, a princess tower held. Held by the tests alone: the"
            + " re-trigger of a hold on the ground (BattleShapeSelectorTest, by a selector written"
            + " in Vines' form) and the climb's one"
            + " step (BattleAirToGroundTest); the re-trigger of a hold in the air or of a climb is"
            + " translated but held by nothing. The action once on the ground at the end of a"
            + " descent, and the row's tags, held by evo_royalhogs_vs_musketeer. Refused: the"
            + " landing and landing end actions, a path reset at landing or at the end, a next"
            + " action, the action once on the ground at the start, and a clone, hovering, riding"
            + " or carrying owner.")
public final class AirToGround extends RowAction {

  /** How long the descent and the climb each take, in milliseconds. */
  @Getter private final int transitionDurationMs;

  /** How long the whole run takes, in milliseconds. */
  @Getter private final int totalDurationMs;

  /** True when a ground unit held in place is forced onto the ground layer too. */
  @Getter private final boolean allowIsGroundTagOnIdle;

  /** True when an air unit's path is reset as the run ends. */
  @Getter private final boolean resetPathAtEnd;

  /** The action scheduled on the owner once it is on the ground, or null for none. */
  @Getter private final BattleAction onGround;

  /**
   * @param row the row's shared columns
   * @param transitionDurationMs how long the descent and the climb each take
   * @param totalDurationMs how long the whole run takes
   * @param allowIsGroundTagOnIdle true when a ground unit is forced onto the ground layer too
   * @param resetPathAtEnd true when an air unit's path is reset as the run ends
   * @param onGround the action scheduled on the owner once it is on the ground, or null
   */
  public AirToGround(
      ActionRow row,
      int transitionDurationMs,
      int totalDurationMs,
      boolean allowIsGroundTagOnIdle,
      boolean resetPathAtEnd,
      BattleAction onGround) {
    super(row);
    this.transitionDurationMs = transitionDurationMs;
    this.totalDurationMs = totalDurationMs;
    this.allowIsGroundTagOnIdle = allowIsGroundTagOnIdle;
    this.resetPathAtEnd = resetPathAtEnd;
    this.onGround = onGround;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return holder.getOwner().airToGround(this, holder.passPhase());
  }
}
