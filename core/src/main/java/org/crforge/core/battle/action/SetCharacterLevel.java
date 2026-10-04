package org.crforge.core.battle.action;

import java.util.Objects;
import java.util.function.IntSupplier;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that changes the level of the entity that caused it, not its owner's: every shipped
 * trigger, a buff's starting action or an ability's activation, passes the entity itself as the
 * cause. A cause that is gone or dead is left alone.
 *
 * <p>The level is the packed one: the rarity's relative level in the high bits and the steps above
 * the rarity's first level in the low byte. A relative adjustment moves the low byte by that many
 * steps, wrapping within the byte; without one, the low byte is set so the level counted from 1
 * becomes the absolute level, 1 when the row leaves it out. The entity then takes the new level as
 * its level change does. The action does not last and schedules nothing.
 *
 * <p>A newer data version writes the adjustment as an expression,
 * RelativeLevelAdjustmentExpression, evaluated when the action runs. That form acts only on a
 * character (a troop, a building or a tower), and moves the low byte, read as a signed number, by
 * the value, held between 0 and 99 in place of the wrap.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled and held by the level-up run: the cause as the entity changed, a dead cause"
            + " left alone, the relative adjustment on the low byte. Settled, not held by a run:"
            + " the absolute level, which no shipped row uses. The expression form (a newer data"
            + " version) is read from the newer build's perform: the cause, a character alone,"
            + " the value evaluated at the run, the signed low byte moved and held to 0..99.")
public final class SetCharacterLevel extends RowAction {

  /** Bits of the packed level that hold the steps above the rarity's first level. */
  private static final int STEPS_MASK = 0xff;

  /** The highest number of steps the expression form gives a level. */
  private static final int MAX_STEPS = 99;

  private final int relativeAdjustment;
  private final int absoluteLevel;
  private final IntSupplier relativeExpression;

  /**
   * @param row the row's shared columns
   * @param relativeAdjustment steps to add to the level, or 0 to set the absolute level
   * @param absoluteLevel the level counted from 1 to set when there is no relative adjustment
   */
  public SetCharacterLevel(ActionRow row, int relativeAdjustment, int absoluteLevel) {
    this(row, relativeAdjustment, absoluteLevel, null);
  }

  private SetCharacterLevel(
      ActionRow row, int relativeAdjustment, int absoluteLevel, IntSupplier relativeExpression) {
    super(row);
    this.relativeAdjustment = relativeAdjustment;
    this.absoluteLevel = absoluteLevel;
    this.relativeExpression = relativeExpression;
  }

  /**
   * The newer data version's form, which writes the adjustment as an expression.
   *
   * @param row the row's shared columns
   * @param relativeExpression the steps to add to the level, evaluated when the action runs
   */
  public static SetCharacterLevel ofExpression(ActionRow row, IntSupplier relativeExpression) {
    return new SetCharacterLevel(row, 0, 0, Objects.requireNonNull(relativeExpression));
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    ActionOwner cause = instigator == null ? null : instigator.getOwner();
    if (cause == null || !cause.actionAlive()) {
      return null;
    }
    if (relativeExpression != null) {
      if (!cause.actionCharacter()) {
        return null;
      }
      int current = cause.actionPackedLevel();
      int steps = Math.max(0, Math.min((byte) current + relativeExpression.getAsInt(), MAX_STEPS));
      cause.changeLevel((current & ~STEPS_MASK) | steps);
      return null;
    }
    int current = cause.actionPackedLevel();
    int steps;
    if (relativeAdjustment != 0) {
      steps = (current + relativeAdjustment) & STEPS_MASK;
    } else {
      // The entity's level is packed on its own rarity, so its high bits are that rarity's
      // relative level: the steps that make the level counted from 1 the absolute level.
      steps = (absoluteLevel + ~(current >> 8)) & STEPS_MASK;
    }
    cause.changeLevel((current & ~STEPS_MASK) | steps);
    return null;
  }
}
