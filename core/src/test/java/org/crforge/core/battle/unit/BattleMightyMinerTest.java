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
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.projectile.ProjectileLauncher;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.math.FixedMath;
import org.crforge.core.pathfinding.target.TargetView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Mighty Miner's lane switch where its runs do not take it: a spell landing on it as it routes
 * across the arena, a shot launched at it as it starts across, alone or with a projectile aimed at
 * it whose row keeps its target, a switch from the arena's edge, and the rows that would make it do
 * what no reference holds.
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

  /** A homing projectile, the princess tower's arrow, the base of the synthetic keeper rows. */
  private static final String ARROW = "TowerPrincessProjectile";

  /**
   * The synthetic keeper's speed: slow enough to be still in flight from the tower as the Mighty
   * Miner starts across.
   */
  private static final int KEEPER_SPEED = 100;

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
    scene
        .match
        .getWorld()
        .hits()
        .kill(BattleTowers.towerNamed(scene.match.getBattle(), LEFT_TOWER), null);
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
  @DisplayName(
      "a shot launched at the Mighty Miner as it starts across, which the switch's own drop did not"
          + " see, drops it on its first step and flies on to where it stood")
  void aShotAtTheSwitchingMinerFliesOnToItsOldPoint() {
    Scene scene = new Scene(GameData.tables(), 3500, 12000);
    List<ProjectileEntity> shots = new ArrayList<>();
    scene
        .match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void projectileLaunched(int t, ProjectileEntity projectile) {
                shots.add(projectile);
              }
            });
    scene.switchLanes();
    TowerEntity tower = BattleTowers.towerNamed(scene.match.getBattle(), LEFT_TOWER);
    shots.clear();

    // Launched once the switch has run its drop, as a shot fired in the step the Mighty Miner
    // starts across is: it joins the battle after the drop and is not dropped by it.
    ProjectileLauncher.launch(
        tower,
        tower.getTargeting(),
        scene.miner.getTargetView(),
        -1,
        false,
        scene.match.getWorld());
    assertThat(shots).hasSize(1);
    ProjectileEntity shot = shots.get(0);
    assertThat(shot.getData().homing()).as("a shot that re-aims at its target").isTrue();
    assertThat(shot.getData().allowResetTarget()).isTrue();
    assertThat(shot.getTarget()).isSameAs(scene.miner);
    int aimX = shot.getAimX();
    int aimY = shot.getAimY();
    int startX = shot.getX();
    int startY = shot.getY();
    int full = scene.miner.getHitPoints().getHitPoints();

    // Its first flight step finds its target routing across: it lets it go, emptying the damage on
    // its way to it, and flies on to the point it was launched at.
    while (shot.getX() == startX && shot.getY() == startY && !shot.isReleased()) {
      scene.step();
      assertThat(scene.tick).as("the shot flies").isLessThan(TICKS);
    }
    assertThat(shot.getTarget()).isNull();
    assertThat(scene.miner.getView().getPendingDamageAmount()).isZero();
    while (!shot.isReleased()) {
      assertThat(new int[] {shot.getAimX(), shot.getAimY()})
          .as("the aim stays where the Mighty Miner stood")
          .containsExactly(aimX, aimY);
      scene.step();
      assertThat(scene.tick).as("the shot lands").isLessThan(TICKS);
    }
    assertThat(new int[] {shot.getX(), shot.getY()}).containsExactly(aimX, aimY);
    long dx = scene.miner.getView().getX() - aimX;
    long dy = scene.miner.getView().getY() - aimY;
    assertThat(dx * dx + dy * dy).as("the Mighty Miner has moved on").isPositive();
    assertThat(scene.miner.getHitPoints().getHitPoints()).isEqualTo(full);
  }

  @Test
  @DisplayName(
      "a shot launched at the Mighty Miner as it starts across leaves a projectile whose row keeps"
          + " its target aimed at it: with no damage on its way it follows the Mighty Miner across"
          + " and lands on it")
  void aShotAtTheSwitchingMinerLeavesAKeeperAimedAtIt() {
    Scene scene = new Scene(GameData.tables(), 3500, 12000);
    List<ProjectileEntity> shots = new ArrayList<>();
    scene
        .match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void projectileLaunched(int t, ProjectileEntity projectile) {
                shots.add(projectile);
              }
            });
    TowerEntity tower = BattleTowers.towerNamed(scene.match.getBattle(), LEFT_TOWER);
    while (scene.miner.getView().getState() != GridEntityState.MOVING) {
      scene.step();
      assertThat(scene.tick).as("the Mighty Miner walks").isLessThan(TICKS);
    }
    // A slow homing projectile of a row that keeps its target through the reset and deals nothing,
    // as the rows that clear AllowResetTarget do, in flight at the Mighty Miner as it switches.
    ProjectileData keeperRow =
        GameData.records().projectile(ARROW).toBuilder()
            .allowResetTarget(false)
            .damage(0)
            .speed(KEEPER_SPEED)
            .build();
    shots.clear();
    scene.match.getWorld().launchAt(tower, keeperRow, scene.miner);
    assertThat(shots).hasSize(1);
    ProjectileEntity keeper = shots.get(0);
    scene.switchLanes();
    assertThat(keeper.isReleased())
        .as("still in flight as the Mighty Miner starts across")
        .isFalse();
    assertThat(keeper.getTarget()).as("the switch's own drop left it").isSameAs(scene.miner);
    shots.clear();

    // A shot launched in the step the Mighty Miner starts across, after the switch's drop.
    ProjectileLauncher.launch(
        tower,
        tower.getTargeting(),
        scene.miner.getTargetView(),
        -1,
        false,
        scene.match.getWorld());
    assertThat(shots).hasSize(1);
    ProjectileEntity shot = shots.get(0);
    assertThat(shot.getData().allowResetTarget()).isTrue();
    int startX = shot.getX();
    int startY = shot.getY();
    int full = scene.miner.getHitPoints().getHitPoints();

    // Its first flight step lets the Mighty Miner go and runs its reset: the keeper is left alone.
    while (shot.getX() == startX && shot.getY() == startY && !shot.isReleased()) {
      scene.step();
      assertThat(scene.tick).as("the shot flies").isLessThan(TICKS);
    }
    assertThat(shot.getTarget()).isNull();
    assertThat(scene.miner.getView().getPendingDamageAmount()).isZero();
    assertThat(keeper.getTarget()).as("the target the reset keeps").isSameAs(scene.miner);

    // The keeper follows the Mighty Miner across and lands on it, handing back 0 from 0.
    while (!keeper.isReleased()) {
      scene.step();
      assertThat(scene.tick).as("the keeper lands").isLessThan(TICKS * 2);
    }
    assertThat(new int[] {keeper.getX(), keeper.getY()})
        .as("on the Mighty Miner, across")
        .containsExactly(scene.miner.getView().getX(), scene.miner.getView().getY());
    assertThat(scene.miner.getView().getPendingDamageAmount()).isZero();
    assertThat(scene.miner.getHitPoints().getHitPoints()).isEqualTo(full);
  }

  @Test
  @DisplayName(
      "a shot launched at the Mighty Miner as it starts across, with a projectile aimed at it whose"
          + " row keeps its target and has damage on its way, is refused")
  void aShotAtTheSwitchingMinerWithADamagingKeeperIsRefused() {
    Scene scene = new Scene(GameData.tables(), 3500, 12000);
    List<ProjectileEntity> shots = new ArrayList<>();
    scene
        .match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void projectileLaunched(int t, ProjectileEntity projectile) {
                shots.add(projectile);
              }
            });
    TowerEntity tower = BattleTowers.towerNamed(scene.match.getBattle(), LEFT_TOWER);
    while (scene.miner.getView().getState() != GridEntityState.MOVING) {
      scene.step();
      assertThat(scene.tick).as("the Mighty Miner walks").isLessThan(TICKS);
    }
    ProjectileData keeperRow =
        GameData.records().projectile(ARROW).toBuilder()
            .allowResetTarget(false)
            .speed(KEEPER_SPEED)
            .build();
    shots.clear();
    scene.match.getWorld().launchAt(tower, keeperRow, scene.miner);
    ProjectileEntity keeper = shots.get(0);
    scene.switchLanes();
    assertThat(keeper.getTarget()).isSameAs(scene.miner);
    assertThat(keeper.registeredPendingAmount()).as("its damage on its way").isPositive();
    ProjectileLauncher.launch(
        tower,
        tower.getTargeting(),
        scene.miner.getTargetView(),
        -1,
        false,
        scene.match.getWorld());

    assertThatThrownBy(
            () -> {
              for (int tick = 0; tick < TICKS; tick++) {
                scene.step();
              }
            })
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining(keeper.name())
        .hasMessageContaining("keeps its target through the reset with damage on its way");
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
