package org.crforge.core.battle;

/**
 * One behaviour slot of a {@link BattleEntity}, visited once per holder tick.
 *
 * <p>An entity carries up to {@link BattleEntity#COMPONENT_SLOTS} components, each at a fixed
 * index. The holder runs one whole-list pass per index, so every entity's index-0 component is
 * visited before any entity's index-1 component. Targeting sits at index 0 and movement at index 1,
 * which is why every unit has chosen its target for the tick before any unit moves.
 */
public interface BattleComponent {

  /** The slot this component occupies on its entity, from 0 to the slot count minus one. */
  int index();

  /**
   * Runs on every pass that reaches this component, whether or not the component is active. A
   * component uses it for bookkeeping that must not stop while it is switched off.
   */
  default void refresh() {}

  /** The component's own work for this tick. Runs only while the component is active. */
  void visit();
}
