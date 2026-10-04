package org.crforge.core.battle.action;

import java.util.List;
import lombok.Builder;
import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The evolved Dart Goblin's poison controller: a run on the object its darts hit that counts the
 * darts, keeps a stack, and drops a poison area effect on the object every SpawnInterval while it
 * lasts. One run per object and thrower: a dart's hit, or the dart choice, re-triggers the run the
 * same thrower caused.
 *
 * <p>Its start takes the thrower, side and level from what caused it, its duration from Duration,
 * or CrownTowerDuration on a crown tower, and counts one dart. A start caused by a dart's hit also
 * activates it and drops the first area at once. A re-trigger by a dart's hit activates a run the
 * dart choice started, dropping its first area, and puts the duration back. While active, each step
 * takes 50 ms off the duration and, once it runs out, finishes the run when none of its areas is
 * left; until then it drops an area every SpawnInterval. The area effect is the stack's entry of
 * AeoList, at the object's point, for the thrower's side and level, with no parent; a copy of the
 * run listed on the area keeps the stack and thrower for the poison damage its hits run.
 *
 * <p>A dart counted brings the stack up by one once the darts reach the count StackAmountChecks
 * lists for it; the stack that reaches MaxStacks stays one below it and marks the last stack.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the start from a dart choice or a dart's hit, the thrower match, the dart count"
            + " and stack, the crown tower duration, the area dropped on activation and every"
            + " SpawnInterval, the stack's area row at the object's point and the thrower's side"
            + " and level, the copy on the area, the areas listed until they leave and the finish;"
            + " held by evo_blowdartgoblin_vs_musketeer. Refused as the row is built:"
            + " OnStackIncrementAction, which no shipped row sets.")
public final class BlowdartController extends RowAction {

  /**
   * The row's own columns.
   *
   * @param durationMs how long the run lasts after a dart's hit
   * @param crownTowerDurationMs the same on a crown tower, or -1 to keep Duration
   * @param stackAmountChecks the darts each stack is reached at
   * @param maxStacks the stack count
   * @param spawnIntervalMs the time between two areas
   * @param aeoList the area effect of each stack
   */
  @Builder
  public record Columns(
      int durationMs,
      int crownTowerDurationMs,
      List<Integer> stackAmountChecks,
      int maxStacks,
      int spawnIntervalMs,
      List<String> aeoList) {}

  /** The row's own columns. */
  @Getter private final Columns columns;

  /**
   * @param row the row's shared columns
   * @param columns its own columns
   */
  public BlowdartController(ActionRow row, Columns columns) {
    super(row);
    this.columns = columns;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    return holder.getOwner().blowdartController(this, instigator);
  }
}
