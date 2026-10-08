package org.crforge.core.battle;

import org.crforge.core.battle.unit.TowerEntity;

/** The battle's towers found by the names the arena gives them, for the tests that watch one. */
public final class BattleTowers {

  private BattleTowers() {
    // Utility class
  }

  /**
   * The tower of the given name, as the battle holds it.
   *
   * @throws IllegalStateException when the holder has no tower of that name, for instance after it
   *     was destroyed and removed
   */
  public static TowerEntity towerNamed(Battle battle, String name) {
    for (BattleEntity entity : battle.getHolder().entities()) {
      if (entity instanceof TowerEntity tower && tower.name().equals(name)) {
        return tower;
      }
    }
    throw new IllegalStateException("No tower named " + name);
  }
}
