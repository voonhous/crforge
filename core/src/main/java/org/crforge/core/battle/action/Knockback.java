package org.crforge.core.battle.action;

import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * A knock into the air: a run on its owner that lifts it along an arc to its height and back over
 * its duration, a step of 50 ms at a time. Each step raises DISABLE_PHYSICAL_INTERACTIONS_WITH_
 * OBJECTS on the owner, and FORCE_IS_AIR while more than 149 ms are left, under which the owner is
 * an air unit to every reader of its layer from its next pre-hook; each step pushes the arc's
 * height, which the owner folds into its live height at its next pre-hook. Once the duration has
 * run out the owner lands: its route is reset and the run finishes.
 *
 * <p>Refused as the row is built: a landing action, passing the cause on to it, the no-collision
 * tag and the shared columns its run does not read.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the start's tags and duration, each step's tags, the arc's"
            + " height pushed, the landing's route reset and the finish; held by"
            + " mega_knight_ev1_uppercut, a Knight knocked up by the evolved Mega Knight. Refused:"
            + " a landing action, passing the cause on to it, the no-collision tag, an owner that"
            + " is jumping, dashing, charging or dragged, and one with an ability, whose"
            + " postponing no run holds.")
public final class Knockback extends RowAction {

  /** The top of the arc, in game units. */
  @Getter private final int height;

  /** How long the owner is in the air, in milliseconds. */
  @Getter private final int durationMs;

  /**
   * @param row the row's shared columns
   * @param height the top of the arc
   * @param durationMs how long the owner is in the air
   */
  public Knockback(ActionRow row, int height, int durationMs) {
    super(row);
    this.height = height;
    this.durationMs = durationMs;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    return holder
        .getOwner()
        .knockback(this, holder.passPhase(), instigator == null ? null : instigator.getOwner());
  }
}
