package org.crforge.core.battle;

/**
 * The rules of the match around the entities: when it is over and what the mode does each step
 * before the entities are ticked.
 *
 * <p>The battle asks the mode two questions per step. {@link #isOver()} is asked first, and a
 * finished match freezes the battle entirely: no clock, no entity tick, no tick counter. {@link
 * #update(Battle)} then runs the mode's own sub-steps and says whether the entities are ticked this
 * step; when they are not, the holder is only cleaned up.
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
}
