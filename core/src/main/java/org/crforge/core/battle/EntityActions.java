package org.crforge.core.battle;

/**
 * The scheduled actions of one entity, as the holder tick drives them.
 *
 * <p>The holder calls into every entity's actions five times per tick, always in the same order:
 * the pending pass for phase 1 before any component runs, the run pass after the component passes,
 * the pending pass for phase 2, the pending pass for phase 3 after the entity post-hooks, and the
 * end-of-tick countdown last of all. The interpreter that gives these calls a body is a later
 * slice; the spine already calls them at the right points so that adding it changes no order.
 */
public interface EntityActions {

  /** Phase of actions that start before the component passes. */
  int PHASE_POST_TICK_INIT = 1;

  /** Phase of actions that start after the component passes and the run pass. */
  int PHASE_POST_COMPONENT_TICK = 2;

  /** Phase of actions that start after the entity post-hooks. */
  int PHASE_POST_GAME_OBJECT_TICK = 3;

  /** An entity that schedules nothing. */
  EntityActions NONE =
      new EntityActions() {
        @Override
        public void pendingPass(int phase) {}

        @Override
        public void runPass(int tick) {}

        @Override
        public void endOfTick() {}
      };

  /** Starts every queued action of the given phase whose delay has run out. */
  void pendingPass(int phase);

  /** Steps every action that lasts longer than one tick. */
  void runPass(int tick);

  /** Takes one tick off every queued delay. Runs for the live list, not the tick's snapshot. */
  void endOfTick();
}
