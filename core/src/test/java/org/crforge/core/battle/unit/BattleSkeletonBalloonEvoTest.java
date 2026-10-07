package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.spawn.SpawnHost;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The evolved Skeleton Balloon: its starting group runs a health trigger and the singleton pop.
 * Falling to 75% re-triggers the pop while the balloon lives, which drops the first container at
 * its offsets; its death action re-triggers it again, dropping the last. Each container's life end
 * spawns seven Skeletons on a ring turned over by the lane, pushed out from its point. A balloon
 * killed with both balloons left is refused.
 */
class BattleSkeletonBalloonEvoTest {

  /** A Common card at its first level, as the evolved play in the reference case stands. */
  private static final int LEVEL = 1;

  private static final String BALLOON = "SkeletonBalloon_EV1";

  /** A battle with the towers passive, and every area effect and every spawn logged. */
  private static final class Scene {
    final Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    final List<String> containers = new ArrayList<>();
    final List<Integer> containerTicks = new ArrayList<>();
    final List<Boolean> balloonAlive = new ArrayList<>();
    final List<String> spawns = new ArrayList<>();
    final List<Integer> spawnTicks = new ArrayList<>();
    CharacterEntity balloon;

    Scene() {
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void areaEffectCreated(
                    int tick, AreaEffectEntity areaEffect, String how, String source) {
                  containers.add(
                      "%s %s %s (%d, %d)"
                          .formatted(
                              how,
                              source,
                              areaEffect.getData().name(),
                              areaEffect.x(),
                              areaEffect.y()));
                  containerTicks.add(tick);
                  balloonAlive.add(balloon.getHitPoints().getHitPoints() > 0);
                }

                @Override
                public void characterSpawned(
                    int tick, SpawnHost source, CharacterEntity child, int x, int y) {
                  spawns.add(child.getData().name());
                  spawnTicks.add(tick);
                }
              });
    }

    /** A unit placed on tick 0 that never moves. */
    CharacterEntity still(int side, String row, int level, int x, int y, String name) {
      CharacterEntity unit = match.deploy(0, GameData.unit(row), level, side, x, y, name);
      unit.setActive(CharacterEntity.MOVEMENT_SLOT, false);
      return unit;
    }

    void step(int ticks) {
      for (int i = 0; i < ticks; i++) {
        match.getBattle().step();
      }
    }
  }

  @Test
  @DisplayName(
      "the bottom side's balloon drops its first container at (-350, +450) once it falls to 75%,"
          + " its last at (+350, 0) as it dies, and each spawns seven Skeletons 13 ticks later")
  void twoContainersDrop() {
    Scene scene = new Scene();
    scene.balloon = scene.still(0, BALLOON, LEVEL, 9000, 14000, "balloon");
    // Out of its reach and the containers', shooting it down in three shots.
    scene.still(1, "Musketeer", LEVEL, 9000, 19500, "musketeer");

    scene.step(200);

    assertThat(scene.balloon.getHitPoints().getHitPoints()).isLessThanOrEqualTo(0);
    assertThat(scene.containers)
        .containsExactly(
            "pop_balloon balloon SkeletonBalloonEvoDummyAeO_EXTRA (8650, 14450)",
            "pop_balloon balloon SkeletonBalloonEvoDummyAeO_DEATH (9350, 14000)");
    assertThat(scene.balloonAlive).containsExactly(true, false);
    assertThat(scene.spawns).hasSize(14).containsOnly("Skeleton");
    // LifeDuration 600: the life-end spawn runs on the update that takes the countdown below 0,
    // the thirteenth after the tick it was made on.
    assertThat(scene.spawnTicks.get(0)).isEqualTo(scene.containerTicks.get(0) + 13);
    assertThat(scene.spawnTicks.get(7)).isEqualTo(scene.containerTicks.get(1) + 13);
  }

  @Test
  @DisplayName(
      "the top side's balloon drops its containers with the offset along the length turned")
  void theTopSideTurnsTheOffset() {
    Scene scene = new Scene();
    scene.balloon = scene.still(1, BALLOON, LEVEL, 9000, 18000, "balloon");
    scene.still(0, "Musketeer", LEVEL, 9000, 12500, "musketeer");

    scene.step(200);

    assertThat(scene.containers)
        .containsExactly(
            "pop_balloon balloon SkeletonBalloonEvoDummyAeO_EXTRA (8650, 17550)",
            "pop_balloon balloon SkeletonBalloonEvoDummyAeO_DEATH (9350, 18000)");
  }

  @Test
  @DisplayName(
      "a balloon killed with both balloons left is refused as its pop is re-triggered: the double"
          + " container or both containers are not modelled")
  void aDeathWithBothBalloonsIsRefused() {
    Scene scene = new Scene();
    scene.balloon = scene.still(0, BALLOON, LEVEL, 9000, 14000, "balloon");
    // A Musketeer at level 15 takes all of its hit points in one shot.
    scene.still(1, "Musketeer", 15, 9000, 19500, "musketeer");

    assertThatThrownBy(() -> scene.step(200))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(
            "skeleton_balloon_evo_pop_balloon is re-triggered with 2 balloons left as its owner"
                + " is dead, which drops every container left; not modelled");
    assertThat(scene.containers).isEmpty();
  }
}
