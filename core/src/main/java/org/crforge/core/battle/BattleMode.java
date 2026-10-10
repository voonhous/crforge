/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle;

/**
 * The rules of the match around the entities: when it is over and what the mode does each step
 * before the entities are ticked.
 *
 * <p>The battle asks the mode two questions per step. {@link #isOver()} is asked first, and a
 * finished match freezes the battle entirely: no clock, no entity tick, no tick counter. {@link
 * #update(Battle)} then runs the mode's own sub-steps and says whether the entities are ticked this
 * step; when they are not, the holder is only cleaned up. A mode may also take a whole step over
 * ({@link #replacesStep(Battle)}), as a Ladder match's tiebreaker does.
 */
public interface BattleMode {

  /** A match that never ends and ticks its entities on every step. */
  BattleMode ENDLESS =
      new BattleMode() {
        @Override
        public boolean isOver() {
          return false;
        }

        @Override
        public boolean update(Battle battle) {
          return true;
        }
      };

  /** Whether the match has been decided. */
  boolean isOver();

  /**
   * Runs the mode's own per-step work.
   *
   * @return true when the entity tick runs this step, false when the holder is only cleaned up
   */
  boolean update(Battle battle);

  /**
   * Runs at the head of a step that runs, before its commands: where a match that has just been
   * decided is ended. A mode without an end does nothing.
   */
  default void beforeCommands(Battle battle) {
    // No end to reach.
  }

  /**
   * Runs the rest of a step in the battle's place, when the mode's rules replace the step: the mode
   * then asks the battle for the commands ({@link Battle#runCommands()}) and the update with its
   * entity tick ({@link Battle#runUpdate()}) when and if its rules run them, and the battle only
   * advances the tick counter afterwards. Asked after {@link #beforeCommands(Battle)}. A mode whose
   * rules never replace the step answers false.
   *
   * @return true when the mode ran the step
   */
  default boolean replacesStep(Battle battle) {
    return false;
  }

  /**
   * Runs at the tail of a step, after the entity tick or the cleanup that stood for it: where a
   * match decided by the tick is ended. A mode without an end does nothing.
   *
   * @param ticked true when the entities were ticked this step
   */
  default void afterTick(Battle battle, boolean ticked) {
    // No end to reach.
  }
}
