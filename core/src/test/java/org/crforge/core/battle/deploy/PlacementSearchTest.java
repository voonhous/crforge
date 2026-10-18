/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.core.battle.unit.UnitData;
import org.crforge.core.pathfinding.grid.TileMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The tiles a card lands on unmoved ({@link PlacementSearch#landingTiles}): exactly the tiles from
 * whose centre the placement search does not move the card. The tests read the tables' own rows or
 * alter a column of one; no tile, count or position is written here.
 */
class PlacementSearchTest {

  /** The deck tables, whose rows are the cards a player plays. */
  private static final List<String> DECK_TABLES =
      List.of("spells_characters", "spells_other", "spells_buildings");

  private static final List<String> KNIGHTS = Collections.nCopies(8, "Knight");

  /** The towers of a Ladder battle as the deploy mask reads them, before any play. */
  private static List<MaskEntity> towers(GameTables tables) {
    Standard1v1Battle battle = new Standard1v1Battle(tables);
    battle.startLadderMatch(KNIGHTS, KNIGHTS, 0, 0);
    List<MaskEntity> towers = battle.placementEntities(w -> true);
    assertThat(towers).anyMatch(MaskEntity::summonerTower);
    return towers;
  }

  /** The towers with each princess tower of a side dead. */
  private static List<MaskEntity> princessesDown(List<MaskEntity> towers, int side) {
    List<MaskEntity> out = new ArrayList<>();
    for (MaskEntity e : towers) {
      boolean down = e.summonerTower() && e.side() == side;
      out.add(
          new MaskEntity(
              e.alive() && !down,
              e.side(),
              e.x(),
              e.y(),
              e.summonerTower(),
              e.summoner(),
              e.building(),
              e.noDeploySizeW(),
              e.noDeploySizeH(),
              e.footprint()));
    }
    return out;
  }

  /** The tile of the grid a found point lies on, row after row; -1 for none found. */
  private static int tileOf(int[] point, int stride) {
    if (point == null) {
      return -1;
    }
    return Math.floorDiv(point[1], 1000) * stride + Math.floorDiv(point[0], 1000);
  }

  /** Every card of the deck tables the records build. */
  private static List<DeployCard> deckCards(GameTables tables, BattleRecords records) {
    List<DeployCard> cards = new ArrayList<>();
    for (String table : DECK_TABLES) {
      for (GameRow row : tables.table(table).rows()) {
        try {
          cards.add(records.card(row.name()));
        } catch (RuntimeException e) {
          // A card the battle core does not build is never offered to a player's hand.
        }
      }
    }
    return cards;
  }

  /** Asserts the landing tiles of a card for a side are the tiles the search leaves it on. */
  private static int assertLandingIsTheSearch(
      TileMap map, DeployCard card, int side, List<MaskEntity> entities) {
    boolean[] mask = PlacementSearch.mask(map, card, side, entities);
    boolean[] lands = PlacementSearch.landingTiles(map, card, mask);
    int stride = map.width() >> 1;
    int rows = map.height() >> 1;
    assertThat(lands).hasSize(stride * rows);
    int open = 0;
    for (int row = 0; row < rows; row++) {
      for (int col = 0; col < stride; col++) {
        int tile = row * stride + col;
        int[] found =
            PlacementSearch.find(
                map,
                card,
                col * 1000 + 500,
                row * 1000 + 500,
                side,
                mask,
                Standard1v1Battle.SYMMETRICAL_DEPLOY_SNAP);
        assertThat(lands[tile])
            .as("%s of side %d at tile (%d, %d)", card.name(), side, col, row)
            .isEqualTo(tileOf(found, stride) == tile);
        open += lands[tile] ? 1 : 0;
      }
    }
    return open;
  }

  @Test
  @DisplayName(
      "every card of the tables lands unmoved exactly on the tiles the search leaves it on, for"
          + " both sides, with every tower up, a side's princess towers down and a building placed")
  void theLandingTilesAreTheTilesTheSearchEndsOn() {
    GameTables tables = GameData.tables();
    BattleRecords records = GameData.records();
    TileMap map = new Standard1v1Battle(tables).getWorld().getTileMap();
    List<MaskEntity> towers = towers(tables);
    List<DeployCard> cards = deckCards(tables, records);
    assertThat(cards).isNotEmpty();
    // A building of the tables, placed in side 0's half, writes its footprint into every mask.
    DeployCard building =
        cards.stream()
            .filter(c -> c.unit() != null && c.unit().building())
            .findFirst()
            .orElseThrow();
    UnitData unit = building.unit();
    List<MaskEntity> withBuilding = new ArrayList<>(towers);
    withBuilding.add(PlacementSearch.maskEntity(unit, 0, map.widthUnits() / 3, 9000, true));
    int checked = 0;
    for (DeployCard card : cards) {
      for (int side = 0; side < 2; side++) {
        assertLandingIsTheSearch(map, card, side, towers);
        assertLandingIsTheSearch(map, card, side, princessesDown(towers, 1 - side));
        assertLandingIsTheSearch(map, card, side, withBuilding);
        checked++;
      }
    }
    assertThat(checked).isEqualTo(2 * cards.size());
  }

  @Test
  @DisplayName(
      "a troop lands only in its own half while the enemy's princess towers stand, and in their"
          + " pockets once they fall")
  void aTroopTakesTheEnemyPocketsOnlyOnceTheirTowersFall() {
    GameTables tables = GameData.tables();
    TileMap map = new Standard1v1Battle(tables).getWorld().getTileMap();
    DeployCard knight = GameData.card("Knight");
    List<MaskEntity> towers = towers(tables);
    for (int side = 0; side < 2; side++) {
      boolean[] up =
          PlacementSearch.landingTiles(
              map, knight, PlacementSearch.mask(map, knight, side, towers));
      boolean[] down =
          PlacementSearch.landingTiles(
              map,
              knight,
              PlacementSearch.mask(map, knight, side, princessesDown(towers, 1 - side)));
      assertThat(enemyHalf(up, map, side)).as("side %d, towers up", side).isZero();
      assertThat(enemyHalf(down, map, side)).as("side %d, towers down", side).isPositive();
      // A fallen tower only opens tiles: its box no longer closes them.
      for (int t = 0; t < up.length; t++) {
        assertThat(!up[t] || down[t]).as("side %d, tile %d", side, t).isTrue();
      }
    }
  }

  @Test
  @DisplayName(
      "a troop whose row lets it deploy on the enemy side lands in the enemy half with every tower"
          + " up, but never on a tower")
  void aCardTheRowLetsOnTheEnemySideLandsThere(@TempDir Path folder) throws IOException {
    GameTables altered =
        GameData.altered(
            folder,
            "spells_characters",
            rows -> GameData.columns(rows, "Knight").put("CanDeployOnEnemySide", true));
    Standard1v1Battle battle = new Standard1v1Battle(altered);
    TileMap map = battle.getWorld().getTileMap();
    DeployCard anywhere = battle.getWorld().getRecords().card("Knight");
    assertThat(anywhere.canDeployOnEnemySide()).isTrue();
    List<MaskEntity> towers = towers(altered);
    boolean[] lands =
        PlacementSearch.landingTiles(map, anywhere, PlacementSearch.mask(map, anywhere, 0, towers));
    assertThat(enemyHalf(lands, map, 0)).isPositive();
    int stride = map.width() >> 1;
    for (MaskEntity tower : towers) {
      int tile = Math.floorDiv(tower.y(), 1000) * stride + Math.floorDiv(tower.x(), 1000);
      assertThat(lands[tile])
          .as("the tile under a tower at (%d, %d)", tower.x(), tower.y())
          .isFalse();
    }
  }

  /** The open tiles of the half of the side's enemy. */
  private static int enemyHalf(boolean[] lands, TileMap map, int side) {
    return half(lands, map, side == 0);
  }

  /** The open tiles of the far half (rows from the middle on), or of the near one. */
  private static int half(boolean[] lands, TileMap map, boolean far) {
    int stride = map.width() >> 1;
    int rows = map.height() >> 1;
    int open = 0;
    for (int row = far ? rows / 2 : 0; row < (far ? rows : rows / 2); row++) {
      for (int col = 0; col < stride; col++) {
        open += lands[row * stride + col] ? 1 : 0;
      }
    }
    return open;
  }
}
