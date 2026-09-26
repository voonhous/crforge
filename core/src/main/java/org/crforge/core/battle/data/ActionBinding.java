package org.crforge.core.battle.data;

import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * What an action row needs from the entity it is built for: its expressions compiled for that
 * entity, the keys of the variables it writes, and the entity's tag word.
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
}
