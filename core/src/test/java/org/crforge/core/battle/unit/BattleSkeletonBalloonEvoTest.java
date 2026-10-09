package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.spawn.SpawnHost;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The evolved Skeleton Balloon: its starting group runs a health trigger and the singleton pop.
 * Falling to 75% re-triggers the pop while the balloon lives, which drops the first container at
 * its offsets; its death action re-triggers it again, dropping the last, or both in turn when it
 * dies with both left. Each container's life end spawns seven Skeletons on a ring turned over by
 * the lane, pushed out from its point. A row that names a double container for a death with both
 * left is refused.
 *
 * <p>The scene writes the pop's two balloons and the hit-point share that drops the first; the
 * offsets, the containers' life and the Skeletons' count are read from the rows.
 */
class BattleSkeletonBalloonEvoTest {

  private static final String POP = "skeleton_balloon_evo_pop_balloon";

  /** The balloons the pop holds, and the hit-point share that drops the first, as written. */
  private static final int BALLOONS = 2;

  private static final int DROP_AT_PERCENT = 75;

  @TempDir static Path folder;

  /** The configured tables with the scene's columns written. */
  private static GameTables tables;

  @BeforeAll
  static void writeTheScene() throws IOException {
    tables =
        GameData.altered(
            folder,
            "actions",
            rows -> {
              ObjectNode pop = (ObjectNode) rows.get(POP).get("fields");
              pop.put("TotalBalloons", BALLOONS);
              pop.putArray("DropBalloonAtHpList").add(DROP_AT_PERCENT);
              ((ObjectNode) rows.get("SkeletonBalloon_trigger_at_health").get("fields"))
                  .putArray("HealthPercentages")
                  .add(DROP_AT_PERCENT);
            });
  }

  /**
   * A container's point: the balloon's moved by the container's offset, along the length turned for
   * the top side.
   */
  private static String point(int container, int side, int x, int y) {
    int dx = Shipped.numbers(POP, "OffsetXList").get(container);
    int dy = Shipped.numbers(POP, "OffsetYList").get(container);
    return "(%d, %d)".formatted(x + dx, side == 0 ? y + dy : y - dy);
  }

  /** The steps from a container's making to its life-end spawn: its life, and one past it. */
  private static int lifeSteps(String container) {
    return Shipped.number(Shipped.row("area_effect_objects", container), "LifeDuration") / 50 + 1;
  }

  /** The Skeletons one container spawns. */
  private static final int SKELETONS = Shipped.number("SkeletonBalloonDeathSpawn", "Count");

  /** A Common card at its first level, as the evolved play in the reference case stands. */
  private static final int LEVEL = 1;

  private static final String BALLOON = "SkeletonBalloon_EV1";

  /** A battle with the towers passive, and every area effect and every spawn logged. */
  private static final class Scene {
    final Standard1v1Battle match;
    final List<String> containers = new ArrayList<>();
    final List<Integer> containerTicks = new ArrayList<>();
    final List<Boolean> balloonAlive = new ArrayList<>();
    final List<String> spawns = new ArrayList<>();
    final List<Integer> spawnTicks = new ArrayList<>();
    CharacterEntity balloon;

    Scene() {
      this(tables);
    }

    /** A scene on other tables. */
    Scene(GameTables sceneTables) {
      match = new Standard1v1Battle(sceneTables, LEVEL, false);
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
      CharacterEntity unit =
          match.deploy(0, match.getWorld().getRecords().unit(row), level, side, x, y, name);
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
      "the bottom side's balloon drops its first container at its first offset once it falls to"
          + " 75%, its last at its second as it dies, and each spawns its Skeletons a life later")
  void twoContainersDrop() {
    Scene scene = new Scene();
    scene.balloon = scene.still(0, BALLOON, LEVEL, 9000, 14000, "balloon");
    // Out of its reach and the containers', shooting it down in three shots.
    scene.still(1, "Musketeer", LEVEL, 9000, 19500, "musketeer");

    scene.step(200);

    assertThat(scene.balloon.getHitPoints().getHitPoints()).isLessThanOrEqualTo(0);
    assertThat(scene.containers)
        .containsExactly(
            "pop_balloon balloon SkeletonBalloonEvoDummyAeO_EXTRA " + point(0, 0, 9000, 14000),
            "pop_balloon balloon SkeletonBalloonEvoDummyAeO_DEATH " + point(1, 0, 9000, 14000));
    assertThat(scene.balloonAlive).containsExactly(true, false);
    assertThat(scene.spawns).hasSize(2 * SKELETONS).containsOnly("Skeleton");
    // The life-end spawn runs on the update that takes the countdown below 0, the one after its
    // LifeDuration's steps, counted from the tick it was made on.
    assertThat(scene.spawnTicks.get(0))
        .isEqualTo(scene.containerTicks.get(0) + lifeSteps("SkeletonBalloonEvoDummyAeO_EXTRA"));
    assertThat(scene.spawnTicks.get(SKELETONS))
        .isEqualTo(scene.containerTicks.get(1) + lifeSteps("SkeletonBalloonEvoDummyAeO_DEATH"));
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
            "pop_balloon balloon SkeletonBalloonEvoDummyAeO_EXTRA " + point(0, 1, 9000, 18000),
            "pop_balloon balloon SkeletonBalloonEvoDummyAeO_DEATH " + point(1, 1, 9000, 18000));
  }

  @Test
  @DisplayName(
      "a balloon killed with both balloons left drops both containers as it dies, the first at its"
          + " first offset, then the last at its second")
  void aDeathWithBothBalloonsDropsBoth() {
    Scene scene = new Scene();
    scene.balloon = scene.still(0, BALLOON, LEVEL, 9000, 14000, "balloon");
    // A Musketeer at level 15 takes all of its hit points in one shot.
    scene.still(1, "Musketeer", 15, 9000, 19500, "musketeer");

    scene.step(200);

    assertThat(scene.balloon.getHitPoints().getHitPoints()).isLessThanOrEqualTo(0);
    assertThat(scene.containers)
        .containsExactly(
            "pop_balloon balloon SkeletonBalloonEvoDummyAeO_EXTRA " + point(0, 0, 9000, 14000),
            "pop_balloon balloon SkeletonBalloonEvoDummyAeO_DEATH " + point(1, 0, 9000, 14000));
    // Both drop in the one re-trigger of its death action, on the tick it dies.
    assertThat(scene.balloonAlive).containsExactly(false, false);
    assertThat(scene.containerTicks.get(1)).isEqualTo(scene.containerTicks.get(0));
    assertThat(scene.spawns).hasSize(2 * SKELETONS).containsOnly("Skeleton");
  }

  @Test
  @DisplayName(
      "a balloon killed with both balloons left is refused when its row names a double container")
  void aDoubleContainerIsRefused(@TempDir Path doubled) throws IOException {
    GameTables doubleTables =
        GameData.altered(
            doubled,
            "actions",
            rows -> {
              ObjectNode pop = (ObjectNode) rows.get(POP).get("fields");
              pop.put("TotalBalloons", BALLOONS);
              pop.putArray("DropBalloonAtHpList").add(DROP_AT_PERCENT);
              pop.put("OverrideKamikazeDoubleContainer", "SkeletonBalloonEvoDummyAeO_DEATH");
              ((ObjectNode) rows.get("SkeletonBalloon_trigger_at_health").get("fields"))
                  .putArray("HealthPercentages")
                  .add(DROP_AT_PERCENT);
            });
    Scene scene = new Scene(doubleTables);
    scene.balloon = scene.still(0, BALLOON, LEVEL, 9000, 14000, "balloon");
    scene.still(1, "Musketeer", 15, 9000, 19500, "musketeer");

    assertThatThrownBy(() -> scene.step(200))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(
            POP
                + " is re-triggered with %d balloons left as its owner".formatted(BALLOONS)
                + " is dead, which drops the double container SkeletonBalloonEvoDummyAeO_DEATH;"
                + " not modelled");
    assertThat(scene.containers).isEmpty();
  }
}
