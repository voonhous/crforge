/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.core.battle.unit.UnitData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Card plays worked out before anything is created: the refusal code, or the placed point, the
 * column its units are clamped into, the lane of the point, and each unit's formation offset,
 * position, lane and start.
 *
 * <p>The tests here write the rows they count on or read the shipped card's columns. Whole plays, a
 * formation clamped at the arena's edge among them, are held by the recorded reference battles.
 */
class CardPlacementTest {

  @Test
  @DisplayName(
      "a listed character's offset: both negated for the bottom side, the one across negated for"
          + " the top side from the middle on when the card mirrors it")
  void aListedOffsetTurnsBySideAndHalf() {
    // A character listed at (-1000, 1000), as the Three Musketeers' second is; the map is 36 cells
    // wide, its middle at 9000. The offset alone is read.
    DeployCard.Listed second = new DeployCard.Listed(null, -1000, 1000);
    assertThat(CardPlacement.listOffset(second, true, 0, 3500, 36)).containsExactly(1000, -1000);
    assertThat(CardPlacement.listOffset(second, true, 0, 14500, 36)).containsExactly(1000, -1000);
    assertThat(CardPlacement.listOffset(second, true, 1, 8999, 36)).containsExactly(-1000, 1000);
    assertThat(CardPlacement.listOffset(second, true, 1, 9000, 36)).containsExactly(1000, 1000);
    assertThat(CardPlacement.listOffset(second, false, 1, 14500, 36)).containsExactly(-1000, 1000);
  }

  @Test
  @DisplayName(
      "the top side's Three Musketeers on the left half: the front one level with the point, the"
          + " other two behind it, waiting 100 and 200")
  void theTopSidesListOnTheLeftHalf(@TempDir Path folder) throws IOException {
    // The card written as the scene counts on it: its three characters at (0, -1000), (-1000,
    // 1000) and (1000, 1000), mirrored across the width, 100 ms apart.
    GameTables tables =
        GameData.altered(
            folder,
            "spells_characters",
            rows -> {
              ObjectNode columns = GameData.columns(rows, "ThreeMusketeers");
              columns.putArray("SummonCharactersOffsetsX").add(0).add(-1000).add(1000);
              columns.putArray("SummonCharactersOffsetsY").add(-1000).add(1000).add(1000);
              columns.put("CharactersOffsetsXMirrored", true);
              columns.put("SummonDeployDelay", 100);
              columns.put("CustomDeployTime", 1000);
            });
    Standard1v1Battle match = new Standard1v1Battle(tables, 11);
    CardPlacement.Result result =
        CardPlacement.place(
            match.getWorld().getTileMap(),
            match.getWorld().getRecords().card("ThreeMusketeers"),
            3500,
            20500,
            1,
            List.of(),
            true,
            true);
    assertThat(result.units())
        .extracting(
            u ->
                u.unit().name()
                    + " "
                    + u.dx()
                    + " "
                    + u.dy()
                    + " "
                    + u.start().state()
                    + " "
                    + u.start().waitMs())
        .containsExactly(
            "ThreeMusketeer_Rework_Character_1 0 -1000 4 -1",
            "ThreeMusketeer_Rework_Character_2 -1000 1000 11 100",
            "ThreeMusketeer_Rework_Character_3 1000 1000 11 200");
  }

  @Test
  @DisplayName(
      "the loop reads the card's first unit's radius, angle shift and deploy time for every index:"
          + " a second group's own leave its places and starts as they are")
  void theFirstUnitsColumnsServeEveryIndex() {
    DeployCard rascals = GameData.card("Rascals");
    // One boy of radius 750, no angle shift and a deploy time of 1000, then two girls 100 ms apart
    // whose own radius, angle shift and deploy time all differ from the boy's; with no radius of
    // its own the card's formation takes the boy's 750.
    UnitData boy =
        rascals.unit().toBuilder()
            .collisionRadius(750)
            .spawnAngleShift(0)
            .deployTimeMs(1000)
            .build();
    UnitData girl =
        rascals.secondary().toBuilder()
            .collisionRadius(400)
            .spawnAngleShift(40)
            .deployTimeMs(0)
            .build();
    DeployCard card = withGroups(rascals, boy, 1, girl, 2, 0, 100);
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), 11);
    CardPlacement.Result result =
        CardPlacement.place(
            match.getWorld().getTileMap(), card, 3500, 10000, 0, List.of(), true, true);
    assertThat(result.units()).hasSize(3);
    for (CardPlacement.Unit unit : result.units()) {
      int[] expected =
          Formation.offset(unit.index(), 1, 750, 0, 1, result.originLane(), 0, 2, true, true);
      assertThat(new int[] {unit.dx(), unit.dy()})
          .as("unit %d", unit.index())
          .containsExactly(expected);
    }
    // The girls wait the second stagger in turn, as units with the boy's deploy time.
    assertThat(result.units())
        .extracting(u -> u.start().state() + " " + u.start().waitMs())
        .containsExactly("4 -1", "11 100", "11 200");

    // A first group of more than one turns the ring by the first unit's angle shift, never the
    // second group's own: three goblins of radius 500 and no angle shift, then three spears
    // turned by 40.
    DeployCard gang = GameData.card("GoblinGang");
    UnitData goblin = gang.unit().toBuilder().collisionRadius(500).spawnAngleShift(0).build();
    UnitData spear = gang.secondary().toBuilder().spawnAngleShift(40).build();
    DeployCard turned = withGroups(gang, goblin, 3, spear, 3, 100, 100);
    CardPlacement.Result ring =
        CardPlacement.place(
            match.getWorld().getTileMap(), turned, 3500, 10000, 0, List.of(), true, true);
    for (CardPlacement.Unit unit : ring.units()) {
      int[] expected =
          Formation.offset(unit.index(), 3, 500, 0, 1, ring.originLane(), 0, 3, true, true);
      assertThat(new int[] {unit.dx(), unit.dy()})
          .as("goblin %d", unit.index())
          .containsExactly(expected);
    }
  }

  @Test
  @DisplayName(
      "the formation is handed the second count as the row sets it, with no second unit to place")
  void theRawSecondCount() {
    DeployCard knight = GameData.card("Knight");
    DeployCard card =
        withGroups(
            knight,
            knight.unit(),
            knight.count(),
            null,
            2,
            knight.summonDeployDelayMs(),
            knight.summonDeployDelaySecondMs());
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), 11);
    CardPlacement.Result result =
        CardPlacement.place(
            match.getWorld().getTileMap(), card, 3500, 10000, 0, List.of(), true, true);
    assertThat(result.units()).as("the second group places none").hasSize(1);
    CardPlacement.Unit unit = result.units().get(0);
    int radius = knight.unit().collisionRadius();
    assertThat(new int[] {unit.dx(), unit.dy()})
        .containsExactly(
            Formation.offset(0, 1, radius, 0, 1, result.originLane(), 0, 2, true, true));
    assertThat(new int[] {unit.dx(), unit.dy()})
        .as("a ring place, not the point")
        .isNotEqualTo(new int[] {0, 0});
  }

  /**
   * The card with no radius of its own, the given groups and the given staggers between each
   * group's units.
   */
  private static DeployCard withGroups(
      DeployCard c,
      UnitData unit,
      int count,
      UnitData secondary,
      int secondaryCount,
      int delayMs,
      int delaySecondMs) {
    return new DeployCard(
        c.name(),
        unit,
        count,
        secondary,
        secondaryCount,
        0,
        c.summonWidth(),
        delayMs,
        delaySecondMs,
        c.canDeployOnEnemySide(),
        c.canPlaceOnBuildings(),
        c.canPlaceOnWater(),
        c.fullLaneDeploy(),
        c.touchdownLimitedDeploy(),
        c.deployWTileMargin(),
        c.deployStartY(),
        c.deployEndY(),
        c.projectile(),
        c.areaEffect(),
        c.searchUnit(),
        c.spellAsDeploy(),
        c.radius(),
        c.multipleProjectiles(),
        c.projectileWaves(),
        c.projectileWaveIntervalMs(),
        c.projectileIntervalMs(),
        c.listed(),
        c.listOffsetsXMirrored(),
        c.group());
  }
}
