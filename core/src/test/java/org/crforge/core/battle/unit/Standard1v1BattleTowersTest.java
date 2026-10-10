/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.groups.Tuple;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.action.BurstAttack;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.pathfinding.combat.LevelScaling;
import org.crforge.core.pathfinding.combat.PackedLevel;
import org.crforge.core.pathfinding.combat.RarityTable;
import org.crforge.core.pathfinding.combat.ScalingGlobals;
import org.junit.jupiter.api.Test;

/**
 * The towers a battle is built with: each side's from the spawn group of its tower selection, the
 * king row at the king's level and every other row at the selection's level.
 */
class Standard1v1BattleTowersTest {

  /**
   * A side's towers as its spawn group's objects place them, in creation order: each object's row,
   * at its cell (500 units each; the top side mirrored along the 64 cells of the arena's length),
   * the king's level for the king row and the selection's for the rest, and the row's Hitpoints at
   * that level.
   */
  private static List<Tuple> spawnGroup(String group, int side, int kingLevel, int level) {
    List<Tuple> towers = new ArrayList<>();
    for (JsonNode object : Shipped.column(Shipped.row("spawn_groups", group), "Objects")) {
      GameRow row = Shipped.unitRow(object.path("Data").asText());
      boolean king = Shipped.flag(row, "IsSummoner");
      int rowLevel = king ? kingLevel : level;
      int cell = object.path("y").asInt();
      towers.add(
          tuple(
              row.name(),
              side,
              object.path("x").asInt() * 500,
              (side == 1 ? 64 - cell : cell) * 500,
              rowLevel,
              hitpointsAt(row, rowLevel)));
    }
    return towers;
  }

  /**
   * A row's Hitpoints at a level, scaled by the level scaling's published rule for its mode: the
   * king tower's for a summoner, a princess tower's for a summoner tower.
   */
  private static int hitpointsAt(GameRow row, int level) {
    RarityTable rarity =
        RarityTable.PUBLISHED.stream()
            .filter(table -> table.name().equals(Shipped.text(row, "Rarity")))
            .findFirst()
            .orElseThrow();
    return LevelScaling.hitpoints(
        ScalingGlobals.standard(),
        Shipped.number(row, "Hitpoints"),
        PackedLevel.fromLevel(level, rarity),
        rarity,
        Shipped.flag(row, "IsSummoner"),
        Shipped.flag(row, "IsSummonerTower"));
  }

  @Test
  void buildsEachSidesTowersFromItsOwnSpawnGroup() {
    // Side 0 the princess towers at level 1, side 1 the cannoneer towers: its king at level 1 and
    // its Cannoneer rows at level 6, five steps above the first.
    List<Tuple> expected = new ArrayList<>(spawnGroup("King_PrincessTowers", 0, 1, 1));
    expected.addAll(spawnGroup("King_CannonTowers", 1, 1, 6));
    Standard1v1Battle battle =
        new Standard1v1Battle(
            GameData.tables(),
            List.of(
                new Standard1v1Battle.Towers("King_PrincessTowers", 1, 1),
                new Standard1v1Battle.Towers("King_CannonTowers", 1, 6)),
            true);

    // Created side by side, king first, then the low and the high princess slot; the top side is
    // the bottom side mirrored along the arena's length.
    assertThat(towers(battle))
        .extracting(
            tower -> tower.getData().name(),
            WorldEntity::side,
            WorldEntity::x,
            WorldEntity::y,
            WorldEntity::level,
            tower -> tower.getHitPoints().getHitPoints())
        .containsExactlyElementsOf(expected);
  }

  @Test
  void theDefaultTowersAreThePrincessTowersOfBothSidesAtOneLevel() {
    Standard1v1Battle byLevel = new Standard1v1Battle(GameData.tables(), 3);
    Standard1v1Battle bySpawnGroup =
        new Standard1v1Battle(
            GameData.tables(),
            List.of(
                new Standard1v1Battle.Towers("King_PrincessTowers", 3, 3),
                new Standard1v1Battle.Towers("King_PrincessTowers", 3, 3)),
            true);

    assertThat(describe(byLevel)).isEqualTo(describe(bySpawnGroup));
  }

  @Test
  void aDaggerDuchessStartsItsChargeCounterFullOnItsFirstStep() {
    Standard1v1Battle battle =
        new Standard1v1Battle(
            GameData.tables(),
            List.of(
                new Standard1v1Battle.Towers("King_PrincessTowers", 1, 1),
                new Standard1v1Battle.Towers("King_KnifeTowers", 1, 9)),
            true);

    battle.getBattle().step();

    // Each Duchess's placement queued its row's charge counter, which the first step starts with
    // all its MaxChargeCount charges; full, the counter sets the index the list gives last.
    JsonNode counter = Shipped.column(Shipped.unitRow("DaggerDuchess"), "OnStartingAction");
    int charges = counter.path("MaxChargeCount").asInt();
    JsonNode indices = counter.path("AttackSequenceIndices");
    List<WorldEntity> duchesses =
        towers(battle).stream()
            .filter(tower -> tower.getData().name().equals("DaggerDuchess"))
            .toList();
    assertThat(duchesses).hasSize(2);
    for (WorldEntity duchess : duchesses) {
      List<BurstAttack.Run> runs =
          duchess.actionHolder().running().stream()
              .filter(BurstAttack.Run.class::isInstance)
              .map(BurstAttack.Run.class::cast)
              .toList();
      assertThat(runs).hasSize(1);
      assertThat(runs.get(0).charges()).isEqualTo(charges);
      assertThat(runs.get(0).depleted()).isFalse();
      assertThat(duchess.attackSequenceIndex()).isEqualTo(indices.get(indices.size() - 1).asInt());
    }
  }

  private static List<String> describe(Standard1v1Battle battle) {
    List<String> lines = new ArrayList<>();
    for (WorldEntity tower : towers(battle)) {
      lines.add(
          tower.getId()
              + " "
              + tower.getData().name()
              + " "
              + tower.side()
              + " "
              + tower.x()
              + ","
              + tower.y()
              + " level "
              + tower.level()
              + " hp "
              + tower.getHitPoints().getHitPoints());
    }
    return lines;
  }

  private static List<WorldEntity> towers(Standard1v1Battle battle) {
    List<WorldEntity> towers = new ArrayList<>();
    for (BattleEntity entity : battle.getBattle().getHolder().entities()) {
      towers.add((WorldEntity) entity);
    }
    return towers;
  }
}
