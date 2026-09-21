package org.crforge.core.battle;

import java.util.List;

/**
 * The work the {@link EntityHolder} does once per tick around the entity visits, as opposed to once
 * per entity.
 *
 * <p>The pre-pass is where everything the visits query is brought up to date for the tick: the
 * spatial index and the building footprint overlay are rebuilt from the tick's snapshot before any
 * entity looks at either. The post-pass retires them again. Both see the same snapshot the entity
 * loops run over.
 */
public interface HolderPasses {

  /** A holder with no shared per-tick state. */
  HolderPasses NONE =
      new HolderPasses() {
        @Override
        public void prePass(int tick, List<BattleEntity> snapshot) {}

        @Override
        public void afterPostHooks() {}

        @Override
        public void postPass(int tick) {}
      };

  /** Runs after the tick's snapshot is taken and before any entity hook. */
  void prePass(int tick, List<BattleEntity> snapshot);

  /** Runs after every entity's post-hook and before the phase 3 action pass. */
  void afterPostHooks();

  /** Runs after the phase 3 action pass and before the closing cleanup. */
  void postPass(int tick);
}
