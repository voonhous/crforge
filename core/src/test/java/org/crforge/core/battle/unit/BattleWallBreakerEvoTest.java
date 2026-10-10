/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.spawn.SpawnHost;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The evolved Wall Breaker: killed by an enemy, it runs WallBreaker_EV1_SpawnMini, which makes one
 * Wallbreaker_mini where it stood and then, as its next action, launches the barrel explosion from
 * the dying unit's own point and height, aimed at that point, at its current target, which its
 * combat gate has already dropped. A Wall Breaker that dies by its own attack runs no killed
 * action. An owner that still holds a target launches the barrel the same way: an area projectile
 * that does not home reads no target.
 */
class BattleWallBreakerEvoTest {

  /** An Epic card at the level the evolved play in the reference case stands at. */
  private static final int LEVEL = 6;

  private static final String WALL_BREAKER = "Wallbreaker_EV1";

  private static final String BARREL = "WallbreakerBarrelExplosion_EV1";

  /** A battle with the towers passive, every spawn and every action projectile recorded. */
  private static final class Scene {
    final Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    final List<String> spawns = new ArrayList<>();
    final List<ProjectileEntity> barrels = new ArrayList<>();
    final List<String> launches = new ArrayList<>();
    final List<Integer> deathTicks = new ArrayList<>();
    final List<Integer> launchTicks = new ArrayList<>();
    final List<String> deathHooks = new ArrayList<>();
    final List<WorldEntity> launchOwners = new ArrayList<>();
    final List<WorldEntity> launchTargets = new ArrayList<>();
    WorldEntity breaker;
    int deathX;
    int deathY;

    Scene() {
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void characterSpawned(
                    int tick, SpawnHost source, CharacterEntity child, int x, int y) {
                  spawns.add("%s %s".formatted(source.name(), child.getData().name()));
                }

                @Override
                public void actionProjectileLaunched(
                    int tick,
                    WorldEntity owner,
                    String action,
                    int phase,
                    ProjectileEntity projectile) {
                  barrels.add(projectile);
                  launchTicks.add(tick);
                  launchOwners.add(projectile.getOwner());
                  launchTargets.add(projectile.getTarget());
                  launches.add(
                      "%s %s %s".formatted(owner.name(), action, projectile.getData().name()));
                }

                @Override
                public void deathHooksScheduled(
                    int tick,
                    WorldEntity dying,
                    BattleEntity attacker,
                    int side,
                    List<String> hooks,
                    boolean inPendingPass) {
                  if (dying == breaker) {
                    deathTicks.add(tick);
                    deathHooks.addAll(hooks);
                    deathX = dying.getView().getX();
                    deathY = dying.getView().getY();
                  }
                }
              });
    }

    void step(int ticks) {
      for (int i = 0; i < ticks; i++) {
        match.getBattle().step();
      }
    }
  }

  @Test
  @DisplayName(
      "killed by an enemy, it leaves a mini Wall Breaker and launches the barrel from its own point,"
          + " aimed at that point, at ground height, at no target")
  void killedItLeavesAMiniAndTheBarrel() {
    Scene scene = new Scene();
    scene.breaker = scene.match.deploy(0, GameData.unit(WALL_BREAKER), LEVEL, 0, 14500, 19000);
    scene.match.deploy(0, GameData.unit("Musketeer"), LEVEL, 1, 14500, 22000, "musketeer");

    for (int i = 0; i < 400 && scene.barrels.isEmpty(); i++) {
      scene.step(1);
    }

    assertThat(scene.deathTicks).hasSize(1);
    assertThat(scene.deathHooks).containsExactly("WallBreaker_EV1_SpawnMini");
    assertThat(scene.launchTicks).containsExactly(scene.deathTicks.get(0));
    assertThat(scene.spawns).contains(WALL_BREAKER + " Wallbreaker_mini");
    assertThat(scene.launches)
        .containsExactly(WALL_BREAKER + " WallBreaker_EV1_SpawnMini_NextAction " + BARREL);
    ProjectileEntity barrel = scene.barrels.get(0);
    assertThat(scene.launchOwners.get(0)).isSameAs(scene.breaker);
    assertThat(barrel.getStartX()).isEqualTo(scene.deathX);
    assertThat(barrel.getStartY()).isEqualTo(scene.deathY);
    assertThat(barrel.getStartZ()).isZero();
    assertThat(barrel.getAimX()).isEqualTo(scene.deathX);
    assertThat(barrel.getAimY()).isEqualTo(scene.deathY);
    assertThat(barrel.getSide()).isZero();
    // It would be launched at the dying unit's current target, but the unit's combat gate has
    // switched its targeting off before the killed action runs: no target.
    assertThat(scene.launchTargets.get(0)).isNull();
  }

  @Test
  @DisplayName(
      "dying by its own attack on a tower, it runs no killed action and launches no barrel")
  void itsOwnAttackLaunchesNoBarrel() {
    Scene scene = new Scene();
    scene.breaker = scene.match.deploy(0, GameData.unit(WALL_BREAKER), LEVEL, 0, 14500, 22000);

    for (int i = 0; i < 400 && scene.breaker.getHitPoints().getHitPoints() > 0; i++) {
      scene.step(1);
    }
    scene.step(20);

    assertThat(scene.breaker.getHitPoints().getHitPoints()).isZero();
    assertThat(scene.deathHooks).isEmpty();
    assertThat(scene.spawns).isEmpty();
    assertThat(scene.launches).isEmpty();
  }

  @Test
  @DisplayName(
      "the barrel row run on a Wall Breaker that still holds a target launches from its own point,"
          + " aimed at that point, at no target: the barrel, an area projectile that does not home,"
          + " reads no target")
  void aHeldTargetIsNotRead() {
    Scene scene = new Scene();
    CharacterEntity breaker =
        scene.match.deploy(0, GameData.unit(WALL_BREAKER), LEVEL, 0, 14500, 19000);
    for (int i = 0; i < 100 && !breaker.referenceHeld(); i++) {
      scene.step(1);
    }
    assertThat(breaker.referenceHeld()).isTrue();
    BattleWorld world = scene.match.getWorld();

    breaker
        .actionHolder()
        .start(
            world
                .getActions()
                .build("WallBreaker_EV1_SpawnMini_NextAction", world.binding(breaker)));

    assertThat(scene.launches)
        .containsExactly(WALL_BREAKER + " WallBreaker_EV1_SpawnMini_NextAction " + BARREL);
    ProjectileEntity barrel = scene.barrels.get(0);
    assertThat(barrel.getStartX()).isEqualTo(breaker.getView().getX());
    assertThat(barrel.getStartY()).isEqualTo(breaker.getView().getY());
    assertThat(barrel.getAimX()).isEqualTo(breaker.getView().getX());
    assertThat(barrel.getAimY()).isEqualTo(breaker.getView().getY());
    assertThat(scene.launchTargets.get(0)).isNull();
  }
}
