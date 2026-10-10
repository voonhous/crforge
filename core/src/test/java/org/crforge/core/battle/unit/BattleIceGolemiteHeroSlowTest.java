/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.crforge.core.battle.BattleTowers;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.data.GameRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The slow circle the Ice Golemite hero form's ability spawns on itself: a circle that follows the
 * Golemite and, on every update of its life, schedules its hit action on every enemy it reaches.
 * The hit action is a choice made on the object it reaches: a crown tower gets the tower slow, a
 * unit of collision radius up to the choice's first bound the small one, one below its second the
 * medium one and any other the large one, each for its spawn's time, refreshed by every later hit
 * rather than stacked. A unit of the Golemite's side is not reached.
 */
class BattleIceGolemiteHeroSlowTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The steps the battle runs before the spawn: every unit has deployed. */
  private static final int SETTLE = 40;

  /** The action that spawns the circle. */
  private static final String SPAWN = "IceGolemiteHero_Spawn_Slow_AEO";

  /** The circle. */
  private static final GameRow CIRCLE =
      Shipped.row("area_effect_objects", Shipped.text(SPAWN, "SpawnData"));

  /** The choice the circle's hit runs on each object it reaches. */
  private static final String CHOICE = Shipped.text(CIRCLE, "OnHitAction");

  /** The circle's updates: one on each 50 ms step of its life and one as it starts, a hit each. */
  private static final int UPDATES = Shipped.ticks(Shipped.number(CIRCLE, "LifeDuration")) + 1;

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
    TowerEntity tower = BattleTowers.towerNamed(match.getBattle(), "PrincessTower_1_1");
    for (int k = 0; k < SETTLE; k++) {
      match.getBattle().step();
    }
    assertThat(slows(tower)).isEmpty();
    BattleAction spawn = GameData.actions().build(SPAWN, match.getWorld().binding(golemite));
    golemite.actionHolder().start(spawn);
    // Spawned between two steps, the circle is first updated in the next step's post-hooks; the
    // buff spawn it schedules runs in the same step.
    match.getBattle().step();
    assertThat(slows(tower)).containsExactly(buff(0));
    assertThat(slows(knight)).containsExactly(chosen("Knight"));
    assertThat(slows(prince)).containsExactly(chosen("Prince"));
    assertThat(slows(giant)).containsExactly(chosen("Giant"));
    assertThat(slows(friend)).isEmpty();
    assertThat(slows(golemite)).isEmpty();
    // Every later update refreshes the one instance instead of listing a second.
    for (int k = 1; k < UPDATES; k++) {
      match.getBattle().step();
      assertThat(slows(tower)).as("tower, update %d", k + 1).hasSize(1);
    }
    // The last update refreshed it to its spawn's whole time; the circle's life is then out, so
    // the next step only counts it down.
    int time = Shipped.number(Shipped.actionNames(CHOICE, "SubActions").get(0), "SpawnTime");
    assertThat(tower.getBuffs().items().get(0).getRemaining()).isEqualTo(time);
    match.getBattle().step();
    assertThat(tower.getBuffs().items().get(0).getRemaining()).isEqualTo(time - 50);
  }

  /**
   * The buff the choice gives a unit that is no crown tower, worked out from the unit row's
   * collision radius and the two bounds the choice's conditions write.
   */
  private static String chosen(String unit) {
    int radius = Shipped.number(Shipped.unitRow(unit), "CollisionRadius");
    List<String> conditions = Shipped.texts(CHOICE, "PerActionConditions");
    if (radius <= bound(conditions.get(1), "<=")) {
      return buff(1);
    }
    return radius < bound(conditions.get(2), "<") ? buff(2) : buff(3);
  }

  /** The bound a condition of the form {@code get_radius() <op> <bound>} writes. */
  private static int bound(String condition, String op) {
    Matcher matcher =
        Pattern.compile("get_radius\\(\\) " + Pattern.quote(op) + " (\\d+)").matcher(condition);
    assertThat(matcher.matches()).as(condition).isTrue();
    return Integer.parseInt(matcher.group(1));
  }

  /** The buff a sub-action of the choice spawns. */
  private static String buff(int index) {
    return Shipped.text(Shipped.actionNames(CHOICE, "SubActions").get(index), "SpawnData");
  }

  /** The names of the Ice Golemite hero slow buffs an entity carries, in its list's order. */
  private static List<String> slows(WorldEntity entity) {
    return entity.getBuffs().items().stream()
        .map(instance -> instance.getBuff().name())
        .filter(name -> name.startsWith("IceGolemiteHero_Slow"))
        .toList();
  }
}
