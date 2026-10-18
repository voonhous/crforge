/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.battle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.deploy.DeployMask;
import org.crforge.core.battle.deploy.MaskEntity;
import org.crforge.core.battle.deploy.PlacementSearch;
import org.crforge.core.battle.unit.PlayerCommands;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.core.battle.unit.TowerEntity;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.util.GameUnits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The deploy overlay's model: of a selected card, the tiles it lands on without being moved, as the
 * environment's landing mask opens them, and where a drop on any other tile is moved. The decks
 * name rows of the tables; which tiles a card takes is read from the battle core's search, never
 * written here.
 */
class LandingTilesTest {

  /** Troops, a troop that may go anywhere, buildings, spells and a spell that spawns a troop. */
  private static final List<String> BLUE =
      List.of("Miner", "Cannon", "Tesla", "GoblinBarrel", "Fireball", "Knight", "Xbow", "Mortar");

  /** Buildings that hide, buildings that do not, troops and spells. */
  private static final List<String> RED =
      List.of(
          "Tesla", "Cannon", "InfernoTower", "BombTower", "Knight", "Archer", "Fireball", "Log");

  @Test
  @DisplayName(
      "a selected card's open tiles are the tiles the placement search lands it on unmoved, on"
          + " the field its side sees, and a hovered tile shows the tile the search sends it to")
  void theOpenTilesAreTheTilesTheSearchLandsOnUnmoved() {
    BattleSession session = open(BattleSession.ladder(GameData.tables(), BLUE, RED));
    Standard1v1Battle battle = session.getBattle();
    TileMap map = battle.getWorld().getTileMap();
    int cols = map.widthUnits() / GameUnits.UNITS_PER_TILE;
    int rows = map.heightUnits() / GameUnits.UNITS_PER_TILE;
    SplittableRandom random = new SplittableRandom(7);
    int checked = 0;
    int moved = 0;
    int plays = 0;
    int hiddenFields = 0;
    for (int step = 0; step < 2400 && session.getHalted() == null && !session.isOver(); step++) {
      if (step % 60 == 0) {
        for (int side = 0; side < 2; side++) {
          int s = side;
          List<MaskEntity> seen = battle.placementEntities(w -> LandingTiles.seenBy(s, w));
          List<MaskEntity> all = battle.placementEntities(w -> true);
          hiddenFields +=
              all.stream()
                      .filter(DeployMask::reads)
                      .toList()
                      .equals(seen.stream().filter(DeployMask::reads).toList())
                  ? 0
                  : 1;
          for (int slot = 0; slot < 4; slot++) {
            LandingTiles.Tiles tiles = session.landingTiles(side, slot);
            List<DeployCard> cards = searchCards(session, side, slot);
            assertThat(tiles == null).as("side %d slot %d", side, slot).isEqualTo(cards.isEmpty());
            if (tiles == null) {
              continue;
            }
            assertThat(tiles.width()).isEqualTo(cols);
            assertThat(tiles.height()).isEqualTo(rows);
            for (int row = 0; row < rows; row++) {
              for (int col = 0; col < cols; col++) {
                int x = GameUnits.tileCenter(col);
                int y = GameUnits.tileCenter(row);
                boolean lands = true;
                for (DeployCard card : cards) {
                  lands &= onTile(found(map, card, side, x, y, seen), col, row);
                }
                assertThat(tiles.open(col, row))
                    .as("step %d side %d slot %d tile (%d, %d)", step, side, slot, col, row)
                    .isEqualTo(lands);
                LandingTiles.Landing landing = session.landingAt(side, slot, col, row);
                assertThat(landing.open()).isEqualTo(lands);
                assertThat(landing.point())
                    .isEqualTo(battle.searchPlacement(cards.get(0), side, x, y));
                moved += landing.moved() ? 1 : 0;
                checked++;
              }
            }
          }
        }
      }
      // Each side now and then plays a card it may play somewhere in its own half.
      for (int side = 0; side < 2; side++) {
        int slot = random.nextInt(4);
        if (random.nextInt(40) == 0 && session.cardUnavailableReason(side, slot) == null) {
          int x = 500 + 1000 * random.nextInt(cols);
          int y = side == 0 ? 500 + 1000 * random.nextInt(15) : 17500 + 1000 * random.nextInt(15);
          plays += session.play(side, slot, x, y) ? 1 : 0;
        }
      }
      assertThat(session.step()).as("step %d: %s", step, session.messages()).isTrue();
    }
    assertThat(checked).isGreaterThan(10_000);
    assertThat(moved).as("hovered tiles a drop is moved off").isPositive();
    assertThat(plays).isGreaterThan(10);
    assertThat(hiddenFields).as("checks with an enemy building out of view").isPositive();
  }

  @Test
  @DisplayName(
      "a troop's tiles are kept while the field stands, and the enemy's pocket opens once the"
          + " princess tower in front of it falls")
  void aFallenPrincessTowerOpensItsPocket() {
    BattleSession session = only("Knight");
    TileMap map = session.getBattle().getWorld().getTileMap();
    DeployCard knight = session.deployCard(session.handCard(0, 0));
    assertThat(knight.canDeployOnEnemySide()).isFalse();
    LandingTiles.Tiles before = session.landingTiles(0, 0);
    assertThat(session.step()).isTrue();
    assertThat(session.landingTiles(0, 0)).as("kept: nothing changed").isSameAs(before);
    assertThat(enemyHalf(before, map)).isZero();

    for (BattleEntity entity : session.getBattle().getBattle().getHolder().entities()) {
      if (entity instanceof TowerEntity tower
          && tower.side() == 1
          && tower.getData().summonerTower()) {
        tower.getHitPoints().setHitPoints(0);
      }
    }
    for (int i = 0; i < 5; i++) {
      assertThat(session.step()).isTrue();
    }

    LandingTiles.Tiles after = session.landingTiles(0, 0);
    assertThat(after).isNotSameAs(before);
    assertThat(enemyHalf(after, map)).isPositive();
    // A tile of the pocket now lands the Knight unmoved, where it was moved before.
    int[] pocket = firstEnemyTile(after, map);
    LandingTiles.Landing there = session.landingAt(0, 0, pocket[0], pocket[1]);
    assertThat(there.open()).isTrue();
    assertThat(there.moved()).isFalse();
    assertThat(there.status()).contains("lands here");
  }

  @Test
  @DisplayName(
      "a drop on a closed tile is moved to the nearest tile the search finds, which is an open"
          + " one, and the status line names it")
  void aClosedTileShowsWhereTheDropGoes() {
    BattleSession session = only("Knight");
    LandingTiles.Tiles tiles = session.landingTiles(0, 0);
    TileMap map = session.getBattle().getWorld().getTileMap();
    int[] closed = firstEnemyTile(invert(tiles), map);

    LandingTiles.Landing landing = session.landingAt(0, 0, closed[0], closed[1]);

    assertThat(landing.open()).isFalse();
    assertThat(landing.moved()).isTrue();
    assertThat(landing.point()).isNotNull();
    int tileX = Math.floorDiv(landing.point()[0], GameUnits.UNITS_PER_TILE);
    int tileY = Math.floorDiv(landing.point()[1], GameUnits.UNITS_PER_TILE);
    assertThat(landing.targetTile()).containsExactly(tileX, tileY);
    assertThat(tiles.open(tileX, tileY)).isTrue();
    assertThat(landing.status()).contains("moved to tile (" + tileX + ", " + tileY + ")");
  }

  @Test
  @DisplayName("a card the client's try refuses, or a battle without a match, shows no tiles")
  void noTilesWithoutAPlay() {
    BattleSession golem = only("Golem");
    assertThat(golem.cardUnavailableReason(0, 0)).isNotNull();
    assertThat(golem.landingTiles(0, 0)).isNull();
    assertThat(golem.landingAt(0, 0, 3, 3)).isNull();

    BattleSession bare = BattleSession.of(new Standard1v1Battle(GameData.tables()));
    assertThat(bare.landingTiles(0, 0)).isNull();
    assertThat(bare.landingAt(0, 0, 3, 3)).isNull();
  }

  /** The cards the screen's search is handed for a play of a slot now, as the session asks. */
  private static List<DeployCard> searchCards(BattleSession session, int side, int slot) {
    if (session.cardUnavailableReason(side, slot) != null) {
      return List.of();
    }
    PlayerCommands commands = new PlayerCommands(session.getBattle());
    return commands.searchCards(side, slot, session.handCardLevel(side, slot));
  }

  /** The point the search sends a drop to on a field. */
  private static int[] found(
      TileMap map, DeployCard card, int side, int x, int y, List<MaskEntity> field) {
    return PlacementSearch.find(
        map,
        card,
        x,
        y,
        side,
        PlacementSearch.mask(map, card, side, field),
        Standard1v1Battle.SYMMETRICAL_DEPLOY_SNAP);
  }

  private static boolean onTile(int[] point, int col, int row) {
    return point != null
        && Math.floorDiv(point[0], GameUnits.UNITS_PER_TILE) == col
        && Math.floorDiv(point[1], GameUnits.UNITS_PER_TILE) == row;
  }

  /** How many open tiles lie in side 1's half. */
  private static int enemyHalf(LandingTiles.Tiles tiles, TileMap map) {
    int count = 0;
    for (int row = tiles.height() / 2; row < tiles.height(); row++) {
      for (int col = 0; col < tiles.width(); col++) {
        count += tiles.open(col, row) ? 1 : 0;
      }
    }
    return count;
  }

  /** The first open tile of side 1's half, as {column, row}. */
  private static int[] firstEnemyTile(LandingTiles.Tiles tiles, TileMap map) {
    for (int row = tiles.height() / 2; row < tiles.height(); row++) {
      for (int col = 0; col < tiles.width(); col++) {
        if (tiles.open(col, row)) {
          return new int[] {col, row};
        }
      }
    }
    throw new AssertionError("no open tile in the enemy half");
  }

  /** The tiles with every flag turned. */
  private static LandingTiles.Tiles invert(LandingTiles.Tiles tiles) {
    boolean[] flags = new boolean[tiles.width() * tiles.height()];
    for (int row = 0; row < tiles.height(); row++) {
      for (int col = 0; col < tiles.width(); col++) {
        flags[row * tiles.width() + col] = !tiles.open(col, row);
      }
    }
    return new LandingTiles.Tiles(tiles.width(), tiles.height(), flags);
  }

  /** A Ladder battle in which both decks are eight copies of one card, at its start cooldown. */
  private static BattleSession only(String card) {
    List<String> deck = Collections.nCopies(8, card);
    return open(BattleSession.ladder(GameData.tables(), deck, deck));
  }

  /** Steps a session to the start cooldown, the first tick counter a card play may be given at. */
  private static BattleSession open(BattleSession session) {
    while (session.tick() < session.match().getStartCooldownTicks()) {
      assertThat(session.step()).isTrue();
    }
    return session;
  }
}
