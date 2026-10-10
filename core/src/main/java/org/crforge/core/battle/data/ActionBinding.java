/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.data;

import java.util.function.IntSupplier;
import java.util.function.IntUnaryOperator;
import java.util.function.LongSupplier;

/**
 * What an action row needs from the entity it is built for: its expressions compiled for that
 * entity, the keys of the variables it writes, the entity's tag word, its spawn rate and what its
 * buffs make of a hit speed step.
 */
public interface ActionBinding {

  /**
   * An expression of the row, compiled for the entity and evaluated each time it is asked.
   *
   * @param text the expression as the row writes it
   */
  IntSupplier expression(String text);

  /**
   * The key of a variable the battle declares.
   *
   * @param name the variable's name
   */
  int variableKey(String name);

  /** The entity's tag word as it stands. */
  LongSupplier tags();

  /**
   * The entity's spawn rate as its buffs make it, in percent: 100 for an entity without buffs,
   * which is every entity that is not a character or a tower.
   */
  default IntSupplier spawnRate() {
    return () -> 100;
  }

  /**
   * What the entity's buffs as they stand make of a hit speed step: the largest boost times what
   * the largest slow leaves of the step, each division truncating. The step itself for an entity
   * without buffs, which is every entity that is not a character or a tower.
   */
  default IntUnaryOperator hitSpeed() {
    return base -> base;
  }
}
