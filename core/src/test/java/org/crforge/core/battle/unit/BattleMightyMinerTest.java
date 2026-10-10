/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.BattleTowers;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.math.FixedMath;
import org.crforge.core.pathfinding.target.TargetView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Mighty Miner's lane switch where its runs do not take it: a spell landing on it as it routes
 * across the arena, a switch from the arena's edge, and the rows that would make it do what no
 * reference holds.
 */
class BattleMightyMinerTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** Long enough for a placed Mighty Miner to deploy, walk and cast. */
  private static final int TICKS = 200;

  /** The top side's king tower, as the arena names it. */
  private static final String KING_TOWER = "KingTower_1_0";

  /** The top side's princess tower in the left lane, as the arena names it. */
  private static final String LEFT_TOWER = "PrincessTower_1_1";

  /** The top side's princess tower in the right lane, as the arena names it. */
  private static final String RIGHT_TOWER = "PrincessTower_1_2";

  /** A buff that burns, the evolved Firecracker's fireworks. */
  private static final String BURN = "FirecrackerFireworks_EV1";

  /** How long the burn is applied for: longer than any walk across and its landing. */
  private static final int BURN_TIME_MS = 60000;

  /** The milliseconds of one battle step. */
  private static final int STEP_MS = 50;

  /** A Mighty Miner placed for the bottom side, with each lane switch it made. */
  private static final class Scene {
    final Standard1v1Battle match;
    final CharacterEntity miner;
    final List<int[]> switches = new ArrayList<>();
    int tick;

    Scene(GameTables tables, int x, int y) {
      match = new Standard1v1Battle(tables, LEVEL, false);
      miner =
          match.deploy(
              0, match.getWorld().getRecords().unit("MightyMiner"), LEVEL, 0, x, y, "Miner");
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void lanesSwitched(
                    int t,
                    CharacterEntity unit,
                    int mirroredX,
                    int mirroredY,
                    int toX,
                    int toY,
                    TargetView reference) {
                  switches.add(new int[] {unit.getView().getX(), mirroredX, mirroredY, toX, toY});
                }
              });
    }

    void step() {
      match.getBattle().step();
      tick++;
    }

    /**
     * Steps until the Mighty Miner walks, then requests its ability and steps until it switches.
     */
    void switchLanes() {
      while (miner.getView().getState() != GridEntityState.MOVING) {
        step();
        assertThat(tick).as("the Mighty Miner walks").isLessThan(TICKS);
      }
      miner.requestAbility();
      while (switches.isEmpty()) {
        step();
        assertThat(tick).as("the Mighty Miner switches lanes").isLessThan(TICKS);
      }
      assertThat(miner.getView().getState()).isEqualTo(GridEntityState.INGAME_PATHFIND);
    }
  }

  @Test
  @DisplayName("a spell landing on the Mighty Miner as it routes across passes it by")
  void aSpellPassesTheRoutingMinerBy() {
    Scene scene = new Scene(GameData.tables(), 3500, 12000);
    CharacterEntity knight = scene.match.deploy(0, GameData.unit("Knight"), LEVEL, 0, 4500, 12000);
    knight.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    scene.switchLanes();
    int minerFull = scene.miner.getHitPoints().getHitPoints();
    int knightFull = knight.getHitPoints().getHitPoints();
    assertThat(scene.miner.hidden()).isTrue();

    // On the Mighty Miner as it starts across, the Knight in reach too.
    int x = scene.miner.getView().getX();
    int y = scene.miner.getView().getY();
    scene.match.play(scene.tick, GameData.card("Zap"), LEVEL, 1, x, y, "Z");
    scene.step();

    assertThat(scene.miner.getView().getState()).isEqualTo(GridEntityState.INGAME_PATHFIND);
    long dx = scene.miner.getView().getX() - x;
    long dy = scene.miner.getView().getY() - y;
    assertThat(dx * dx + dy * dy)
        .as("moved, and within the Zap's 2500")
        .isBetween(1L, 2500L * 2500);
    assertThat(knight.getHitPoints().getHitPoints())
        .as("the Knight beside it")
        .isLessThan(knightFull);
    assertThat(scene.miner.getHitPoints().getHitPoints()).isEqualTo(minerFull);
  }

  @Test
  @DisplayName(
      "a filter that drops underground objects, as Vines' does, drops the Mighty Miner as it"
          + " routes across and passes it once it lands")
  void anUndergroundFilterDropsTheRoutingMiner() {
    Scene scene = new Scene(GameData.tables(), 3500, 12000);
    GameObjectFilter vines = scene.match.getWorld().getRecords().filter("enemy_troops_for_vines");
    scene.switchLanes();
    assertThat(vines.matches(scene.miner.filterSubject(), 1, "Vines")).isFalse();

    while (scene.miner.getView().getState() == GridEntityState.INGAME_PATHFIND) {
      scene.step();
      assertThat(scene.tick).as("the Mighty Miner lands").isLessThan(TICKS);
    }
    assertThat(vines.matches(scene.miner.filterSubject(), 1, "Vines")).isTrue();
  }

  @Test
  @DisplayName(
      "landed in the other lane with that lane's princess tower gone, the Mighty Miner walks at the"
          + " king, which is the nearer by its true distance")
  void aMinerAcrossWalksAtTheTrulyNearerKing() {
    // Created in the right lane, which it keeps across the switch, so the right princess tower
    // stays a candidate of its own lane.
    Scene scene = new Scene(GameData.tables(), 14500, 10000);
    scene.match.getWorld().kill(BattleTowers.towerNamed(scene.match.getBattle(), LEFT_TOWER), null);
    scene.switchLanes();
    while (scene.miner.getView().getState() != GridEntityState.MOVING) {
      scene.step();
      assertThat(scene.tick).as("the Mighty Miner walks again").isLessThan(TICKS);
    }
    scene.step();

    // From the left lane the king is the nearer of the two by the true distance, the right
    // princess tower by the approximate one: the king's own distance is the true one.
    TowerEntity king = BattleTowers.towerNamed(scene.match.getBattle(), KING_TOWER);
    TowerEntity right = BattleTowers.towerNamed(scene.match.getBattle(), RIGHT_TOWER);
    int x = scene.miner.getView().getX();
    int y = scene.miner.getView().getY();
    int kingDx = king.getView().getX() - x;
    int kingDy = king.getView().getY() - y;
    int rightApprox =
        FixedMath.approxDistance(right.getView().getX() - x, right.getView().getY() - y);
    assertThat(FixedMath.approxDistance(kingDx, kingDy))
        .as("the king is the farther by the approximate distance")
        .isGreaterThan(rightApprox);
    assertThat(FixedMath.guardedSumOfSquares(kingDx, kingDy))
        .as("and the nearer by the true one")
        .isLessThan(rightApprox * rightApprox);
    assertThat(scene.miner.getUnit().targeting().getReference()).isSameAs(king.getTargetView());
  }

  @Test
  @DisplayName(
      "a burn the Mighty Miner carries deals nothing while it routes across hidden, and deals"
          + " again once it lands")
  void aBurnSkipsTheHiddenMiner() {
    Scene scene = new Scene(GameData.tables(), 14500, 10000);
    scene.switchLanes();
    assertThat(scene.miner.hidden()).isTrue();
    // A burn with a short hit frequency, on for longer than the walk across and the landing.
    BuffData burn = scene.match.getWorld().getRecords().buff(BURN);
    assertThat(burn.damagePerSecond()).as("a burn that deals damage").isPositive();
    scene.miner.getBuffs().apply(burn, BURN_TIME_MS, scene.miner.getPackedLevel(), null, 1);
    int full = scene.miner.getHitPoints().getHitPoints();

    int hiddenTicks = 0;
    while (scene.miner.getView().getState() == GridEntityState.INGAME_PATHFIND) {
      scene.step();
      hiddenTicks++;
      assertThat(scene.tick).as("the Mighty Miner lands").isLessThan(TICKS);
    }
    // The walk across outlasts several of the burn's hits, none of which lands.
    assertThat(hiddenTicks * STEP_MS).isGreaterThan(2 * burn.hitFrequency());
    assertThat(scene.miner.getHitPoints().getHitPoints()).isEqualTo(full);

    for (int i = 0; i * STEP_MS <= burn.hitFrequency(); i++) {
      scene.step();
    }
    assertThat(scene.miner.hidden()).isFalse();
    assertThat(scene.miner.getHitPoints().getHitPoints())
        .as("the burn deals again once the Mighty Miner stands visible")
        .isLessThan(full);
  }

  @Test
  @DisplayName("a lane switch from the arena's edge aims 250 inside the other edge")
  void aSwitchFromTheEdgeIsClamped() {
    Scene scene = new Scene(GameData.tables(), 100, 12000);
    scene.switchLanes();

    int[] at = scene.switches.get(0);
    assertThat(at[0]).as("still within 250 of its edge").isLessThan(250);
    assertThat(at[1]).isEqualTo(18000 - at[0]);
    assertThat(at[3]).isEqualTo(17750);
    assertThat(at[4]).isEqualTo(at[2]);
  }

  @Test
  @DisplayName("a deploy time for the character the ability leaves behind is refused")
  void aDeployTimeForTheBombIsRefused(@TempDir Path folder) throws IOException {
    GameTables tables =
        GameData.altered(
            folder,
            "character_abilities",
            rows ->
                GameData.columns(rows, "MightyMinerLaneSwitch")
                    .put("ActivationSpawnDeployTime", 500));
    Scene scene = new Scene(tables, 3500, 12000);

    assertThatThrownBy(scene.miner::requestAbility)
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("[ActivationSpawnDeployTime]");
  }

  @Test
  @DisplayName("a lane switch for a row that stays visible as it routes across is refused")
  void aVisibleSwitchIsRefused(@TempDir Path folder) throws IOException {
    GameTables tables =
        GameData.altered(
            folder,
            "characters",
            rows -> GameData.columns(rows, "MightyMiner").put("IngamePathfindVisible", true));
    Scene scene = new Scene(tables, 3500, 12000);

    assertThatThrownBy(scene.miner::requestAbility)
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("stays visible");
  }
}
