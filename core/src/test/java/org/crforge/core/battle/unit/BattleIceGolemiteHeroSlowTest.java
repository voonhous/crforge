package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.BattleAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The slow circle the Ice Golemite hero form's ability spawns on itself: a circle of radius 4000
 * that follows the Golemite and, on every update of its 3050 ms, schedules its hit action on every
 * enemy it reaches. The hit action is a choice made on the object it reaches: a crown tower gets
 * the tower slow, a unit of collision radius 500 or less the small one, one below 750 the medium
 * one and any other the large one, each for 2000 ms, refreshed by every later hit rather than
 * stacked. A unit of the Golemite's side is not reached.
 */
class BattleIceGolemiteHeroSlowTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The steps the battle runs before the spawn: every unit has deployed. */
  private static final int SETTLE = 40;

  /** The circle's updates: 3050 ms of 50 ms steps, one hit on each. */
  private static final int UPDATES = 61;

  @Test
  @DisplayName(
      "the slow circle slows an enemy tower, a Knight, a Prince and a Giant with the buff their"
          + " row and radius choose on its first update, refreshes it on every update and slows"
          + " no unit of the Golemite's side")
  void theSlowCircleChoosesABuffPerTarget() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    CharacterEntity golemite =
        match.deploy(0, GameData.unit("IceGolemiteHero"), LEVEL, 0, 3500, 22000, "golemite");
    CharacterEntity knight =
        match.deploy(0, GameData.unit("Knight"), LEVEL, 1, 4500, 22500, "knight");
    CharacterEntity prince =
        match.deploy(0, GameData.unit("Prince"), LEVEL, 1, 2000, 21000, "prince");
    CharacterEntity giant = match.deploy(0, GameData.unit("Giant"), LEVEL, 1, 5500, 21000, "giant");
    CharacterEntity friend =
        match.deploy(0, GameData.unit("Knight"), LEVEL, 0, 2500, 22000, "friend");
    TowerEntity tower = BattleMusketeerRunTest.towerNamed(match.getBattle(), "PrincessTower_1_1");
    for (int k = 0; k < SETTLE; k++) {
      match.getBattle().step();
    }
    assertThat(slows(tower)).isEmpty();
    BattleAction spawn =
        GameData.actions()
            .build("IceGolemiteHero_Spawn_Slow_AEO", match.getWorld().binding(golemite));
    golemite.actionHolder().start(spawn);
    // Spawned between two steps, the circle is first updated in the next step's post-hooks; the
    // buff spawn it schedules runs in the same step.
    match.getBattle().step();
    assertThat(slows(tower)).containsExactly("IceGolemiteHero_Slow_Buff_Tower");
    assertThat(slows(knight)).containsExactly("IceGolemiteHero_Slow_Buff_Small");
    assertThat(slows(prince)).containsExactly("IceGolemiteHero_Slow_Buff_Medium");
    assertThat(slows(giant)).containsExactly("IceGolemiteHero_Slow_Buff_Large");
    assertThat(slows(friend)).isEmpty();
    assertThat(slows(golemite)).isEmpty();
    // Every later update refreshes the one instance instead of listing a second.
    for (int k = 1; k < UPDATES; k++) {
      match.getBattle().step();
      assertThat(slows(tower)).as("tower, update %d", k + 1).hasSize(1);
    }
    assertThat(tower.getBuffs().items().get(0).getRemaining()).isGreaterThan(1900);
  }

  /** The names of the Ice Golemite hero slow buffs an entity carries, in its list's order. */
  private static List<String> slows(WorldEntity entity) {
    return entity.getBuffs().items().stream()
        .map(instance -> instance.getBuff().name())
        .filter(name -> name.startsWith("IceGolemiteHero_Slow"))
        .toList();
  }
}
