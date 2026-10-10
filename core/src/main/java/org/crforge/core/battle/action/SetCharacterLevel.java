/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import java.util.Objects;
import java.util.function.IntSupplier;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that changes the level of the entity that caused it, not its owner's: a buff's starting
 * action or an ability's activation passes the entity itself as the cause. A row that sets
 * ExecuteOnParent changes instead the level of its parent, the entity whose holder runs it, and
 * reads no cause, so it runs without one: the Tombstone's hero monster runs it on itself, sent by
 * the building that caused it. The entity changed - a cause or a parent - that is gone or dead is
 * left alone.
 *
 * <p>The level is the packed one: the rarity's relative level in the high bits and the steps above
 * the rarity's first level in the low byte. A relative adjustment moves the low byte by that many
 * steps, wrapping within the byte; without one, the low byte is set so the level counted from 1
 * becomes the absolute level, 1 when the row leaves it out. The entity then takes the new level as
 * its level change does. The action does not last and schedules nothing.
 *
 * <p>A row may write the adjustment as an expression, RelativeLevelAdjustmentExpression, evaluated
 * when the action runs. That form acts only on a character (a troop, a building or a tower), and
 * moves the low byte, read as a signed number, by the value, held between 0 and 99 in place of the
 * wrap.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled and held by the level-up run: the cause as the entity changed, a dead cause"
            + " left alone, the relative adjustment on the low byte. Settled, not held by a run:"
            + " the absolute level, which no shipped row uses. The expression form is read from the"
            + " build's perform: the cause, a character alone,"
            + " the value evaluated at the run, the signed low byte moved and held to 0..99."
            + " ExecuteOnParent is read from the build's perform, which picks the holder's owner"
            + " in place of the cause before either form, and is held by the hero Tombstone's"
            + " monster taking its building's level.")
public final class SetCharacterLevel extends RowAction {

  /** Bits of the packed level that hold the steps above the rarity's first level. */
  private static final int STEPS_MASK = 0xff;

  /** The highest number of steps the expression form gives a level. */
  private static final int MAX_STEPS = 99;

  private final int relativeAdjustment;
  private final int absoluteLevel;
  private final IntSupplier relativeExpression;

  /** True to change the entity whose holder runs the action, false to change its cause. */
  private final boolean onParent;

  /**
   * @param row the row's shared columns
   * @param relativeAdjustment steps to add to the level, or 0 to set the absolute level
   * @param absoluteLevel the level counted from 1 to set when there is no relative adjustment
   */
  public SetCharacterLevel(ActionRow row, int relativeAdjustment, int absoluteLevel) {
    this(row, relativeAdjustment, absoluteLevel, false);
  }

  /**
   * @param row the row's shared columns
   * @param relativeAdjustment steps to add to the level, or 0 to set the absolute level
   * @param absoluteLevel the level counted from 1 to set when there is no relative adjustment
   * @param onParent true to change the entity whose holder runs the action (ExecuteOnParent), false
   *     to change its cause
   */
  public SetCharacterLevel(
      ActionRow row, int relativeAdjustment, int absoluteLevel, boolean onParent) {
    this(row, relativeAdjustment, absoluteLevel, null, onParent);
  }

  private SetCharacterLevel(
      ActionRow row,
      int relativeAdjustment,
      int absoluteLevel,
      IntSupplier relativeExpression,
      boolean onParent) {
    super(row);
    this.relativeAdjustment = relativeAdjustment;
    this.absoluteLevel = absoluteLevel;
    this.relativeExpression = relativeExpression;
    this.onParent = onParent;
  }

  /**
   * The form that writes the adjustment as an expression.
   *
   * @param row the row's shared columns
   * @param relativeExpression the steps to add to the level, evaluated when the action runs
   */
  public static SetCharacterLevel ofExpression(ActionRow row, IntSupplier relativeExpression) {
    return ofExpression(row, relativeExpression, false);
  }

  /**
   * The form that writes the adjustment as an expression.
   *
   * @param row the row's shared columns
   * @param relativeExpression the steps to add to the level, evaluated when the action runs
   * @param onParent true to change the entity whose holder runs the action (ExecuteOnParent), false
   *     to change its cause
   */
  public static SetCharacterLevel ofExpression(
      ActionRow row, IntSupplier relativeExpression, boolean onParent) {
    return new SetCharacterLevel(row, 0, 0, Objects.requireNonNull(relativeExpression), onParent);
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    // The entity changed: the holder's owner on the parent, else the cause. The expression is
    // evaluated for the holder's owner either way, as the action is built for it.
    ActionOwner cause;
    if (onParent) {
      cause = holder.getOwner();
    } else {
      cause = instigator == null ? null : instigator.getOwner();
    }
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
