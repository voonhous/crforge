package org.crforge.core.battle.action;

import lombok.Builder;
import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * A push of the owner away from the object that caused the action, as the Giant hero form's slap
 * pushes the enemy it picked: after its delay, the owner is asked for a pushback through the same
 * request an attack's pushback takes, and the row's actions follow its answer.
 *
 * <p>Its run starts with the action and is due the given delay later, in whole steps: the battle
 * tick it starts on plus the delay over 50 ms, rounded toward zero. The step on exactly that tick
 * pushes and finishes the run. The push fails for an owner without a movement component, one whose
 * tag word carries one of the row's tags that disallow it, and one whose request is refused. In the
 * mode toward the horizontal centre from the cause, the point it is pushed away from lies on the
 * owner's own line along the length, the row's directional offset to the side of the arena's
 * vertical centre line the cause stands on: toward the left for a cause left of the centre, so the
 * owner goes right, and toward the right otherwise. The request lifts the row's gates when it is
 * forced, counts as an attack's when it says so, takes the separation off when the push is
 * proportional, keeps a longer pushback in flight when it resets only for a stronger one, and
 * pushes a hidden owner when it may.
 *
 * <p>On success the row's action on the cause is scheduled on the cause, the owner its cause; then
 * the row's success action on the owner, the cause its cause; and, when the row resets avoidance,
 * the owner's push blend is cleared. On failure the row's failure action on the cause is scheduled
 * on the cause, the owner its cause.
 *
 * <p>Refused as the row is built: a push with no delay, which the action's perform makes at once; a
 * mode other than toward the horizontal centre from the cause; the push that skips the request's
 * checks; and a failure action on the owner. Refused as it runs: a cause that has left the battle
 * by the push, and a run let go before its push, whose failure is not modelled.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled from the listing: the columns and their defaults, the due tick and its exact"
            + " test, the three failures, the point for the mode toward the horizontal centre"
            + " from the cause, the request's five switches and the order of the success's"
            + " schedules and the blend reset; held by ability_hero_giant_slap, a Knight pushed"
            + " along the width by the Giant hero form's slap. Refused: no delay, the other three"
            + " modes, the unchecked push, a failure action on the owner, a cause gone by the"
            + " push and a run let go before it.")
public final class DoPushbackFromInstigator extends RowAction {

  /**
   * The row's own columns.
   *
   * @param delayMs how long after the start the push comes
   * @param strength how far the owner is pushed
   * @param directionalOffset how far beside the owner the point it is pushed from lies
   * @param disallowTags the tags that, in the owner's tag word, make the push fail
   * @param forced true to lift the request's gates
   * @param attack true to count the push as the owner's attack's
   * @param proportional true to take the separation off the distance
   * @param resetIfStronger true to keep a longer pushback in flight instead of refusing
   * @param invisible true to push a hidden owner too
   * @param resetAvoidance true to clear the owner's push blend on success
   * @param successOnInstigator the row run on the cause on success, or null
   * @param failureOnInstigator the row run on the cause on failure, or null
   * @param successAction the row run on the owner on success, or null
   */
  @Builder
  public record Columns(
      int delayMs,
      int strength,
      int directionalOffset,
      long disallowTags,
      boolean forced,
      boolean attack,
      boolean proportional,
      boolean resetIfStronger,
      boolean invisible,
      boolean resetAvoidance,
      String successOnInstigator,
      String failureOnInstigator,
      String successAction) {}

  /** The step the delay is counted in, in milliseconds. */
  private static final int STEP_MS = 50;

  /** The row's own columns. */
  @Getter private final Columns columns;

  /**
   * @param row the row's shared columns
   * @param columns its own columns
   */
  public DoPushbackFromInstigator(ActionRow row, Columns columns) {
    super(row);
    this.columns = columns;
  }

  /** The battle tick the push is due on, for a run started on the given tick. */
  public int dueTick(int startTick) {
    return startTick + columns.delayMs() / STEP_MS;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    if (instigator == null) {
      throw new UnsupportedOperationException(name() + " runs with no cause, not modelled");
    }
    return holder
        .getOwner()
        .pushbackFromInstigator(this, holder.passPhase(), instigator.getOwner());
  }
}
