/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.grid.TileMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The pull of an attracting buff asks the unit's layer as it stands, not its row, whether to keep
 * it off the water: a unit whose row flies but which a fall holds on the ground, as the evolved
 * Royal Hog's grounded row, is a ground unit to the pull, and the water clamp holds it on the
 * bridge's edge while a Tornado beside the bridge pulls it toward the river. A unit in the air is
 * pulled over the water.
 *
 * <p>The scene: the bottom side's evolved Royal Hog falls as soon as it has deployed (the scene
 * writes the hit-point share the fall waits for as 100%) and walks up the left bridge, the towers
 * passive; a Tornado of the top side is placed in the river beside the bridge, so each hit pulls
 * the hog sideways off the bridge. The scene writes the flying height both of the hog's rows share,
 * the Tornado's circle and timings and its buff's pull; the control is a Minion under the same
 * Tornado.
 */
class BattleTornadoLayerTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String HOG = "RoyalHog_EV1";

  /** The grounded row the hog takes as it lands. */
  private static final String GROUNDED = "RoyalHog_EV1_Grounded";

  /** The flying height the scene writes into both of the hog's rows. */
  private static final int FLYING_HEIGHT = 4000;

  /** The left bridge's lane, where the units are deployed. */
  private static final int LANE_X = 3500;

  private static final int DEPLOY_Y = 13000;

  /** The Tornado's point: in the river, beside the left bridge. */
  private static final int TORNADO_X = 6500;

  private static final int TORNADO_Y = 16000;

  /** How long the Tornado lives, as the scene writes it. */
  private static final int TORNADO_LIFE = 1050;

  @TempDir static Path folder;

  /** The configured tables with the scene's columns written. */
  private static GameTables tables;

  @BeforeAll
  static void writeTheScene() throws IOException {
    GameData.altered(
        folder,
        "area_effect_objects",
        rows -> {
          ObjectNode columns = GameData.columns(rows, "Tornado");
          columns.put("Radius", 5500);
          columns.put("HitSpeed", 50);
          columns.put("HitSpeedOffset", 50);
          columns.put("LifeDuration", TORNADO_LIFE);
          columns.put("BuffTime", 500);
        });
    GameData.alterLoaded(
        folder,
        "character_buffs",
        rows -> {
          ObjectNode buff = GameData.columns(rows, "Tornado");
          buff.put("PushSpeedFactor", 100);
          buff.put("AttractPercentage", 360);
          buff.put("DamagePerSecond", 0);
          buff.remove(List.of("PushMassFactor", "LateralPushPercentage", "AttractMaxAngle"));
        });
    GameData.alterLoaded(
        folder,
        "characters",
        rows -> {
          GameData.columns(rows, HOG).put("FlyingHeight", FLYING_HEIGHT);
          GameData.columns(rows, GROUNDED).put("FlyingHeight", FLYING_HEIGHT);
          GameData.columns(rows, "Minion").put("FlyingHeight", FLYING_HEIGHT);
        });
    GameData.alterLoaded(
        folder,
        "actions",
        rows ->
            GameData.fields(rows, "RoyalHog_EV1_Health_Threshold")
                .putArray("HealthPercentages")
                .add(100));
    tables = GameTables.load(folder);
  }

  /** A battle with the towers holding fire and every pull of a Tornado recorded. */
  private static final class Scene {
    final Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    final List<AreaEffectEntity.Pull> pulls = new ArrayList<>();

    Scene() {
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void areaPulled(
                    int tick, AreaEffectEntity areaEffect, List<AreaEffectEntity.Pull> pulled) {
                  pulls.addAll(pulled);
                }
              });
    }

    /** Places the Tornado now, on the next tick's commands. */
    void tornado() {
      match.placeAreaEffect(
          match.getBattle().getTick(), "Tornado", LEVEL, 1, TORNADO_X, TORNADO_Y, "Tornado");
    }

    /** Whether the unit stands on a water cell. */
    boolean onWater(CharacterEntity unit) {
      GridEntity view = unit.getView();
      return (match
                  .getWorld()
                  .getGrid()
                  .water(view.getX() / TileMap.CELL_UNITS, view.getY() / TileMap.CELL_UNITS)
              & 1)
          != 0;
    }
  }

  @Test
  @DisplayName(
      "a Tornado beside the bridge pulls a grounded evolved Royal Hog, whose row flies, as a"
          + " ground unit: each pull asks for the water clamp, and the hog stays off the river")
  void theGroundedHogIsKeptOffTheWater() {
    Scene scene = new Scene();
    CharacterEntity hog =
        scene.match.deploy(
            0, scene.match.getWorld().getRecords().unit(HOG), LEVEL, 0, LANE_X, DEPLOY_Y);
    for (int tick = 0; tick < 200 && !GROUNDED.equals(hog.getData().name()); tick++) {
      scene.match.getBattle().step();
    }
    assertThat(hog.getData().name()).as("the hog has fallen").isEqualTo(GROUNDED);
    // Its layer is read again from its tag word on the pre-hooks that follow the landing.
    for (int tick = 0; tick < 5 && hog.getView().isAir(); tick++) {
      scene.match.getBattle().step();
    }
    assertThat(hog.getData().air()).as("its row flies").isTrue();
    assertThat(hog.getView().isAir()).as("its fall holds it on the ground").isFalse();
    assertThat(scene.onWater(hog)).as("it has not reached the river yet").isFalse();

    scene.tornado();
    int maxX = hog.getView().getX();
    for (int step = 0; step < TORNADO_LIFE / 50 + 2; step++) {
      scene.match.getBattle().step();
      maxX = Math.max(maxX, hog.getView().getX());
      if (hog.getView().getState() != GridEntityState.JUMPING) {
        assertThat(scene.onWater(hog))
            .as(
                "off the water at (%d, %d), tick %d",
                hog.getView().getX(), hog.getView().getY(), scene.match.getBattle().getTick())
            .isFalse();
      }
    }
    List<AreaEffectEntity.Pull> hogPulls =
        scene.pulls.stream().filter(pull -> pull.target() == hog).toList();
    assertThat(hogPulls).as("the Tornado pulled the hog").isNotEmpty();
    // The fourth accumulator is the water clamp the pull asks for.
    assertThat(hogPulls).allSatisfy(pull -> assertThat(pull.after()[3]).isEqualTo(1));
    assertThat(maxX).as("pulled sideways, toward the river").isGreaterThan(LANE_X + 500);
  }

  @Test
  @DisplayName(
      "the control: the same Tornado pulls a Minion in the air over the river, without the water"
          + " clamp")
  void anAirUnitIsPulledOverTheWater() {
    Scene scene = new Scene();
    CharacterEntity minion =
        scene.match.deploy(
            0, scene.match.getWorld().getRecords().unit("Minion"), LEVEL, 0, LANE_X, 14000);
    for (int tick = 0;
        tick < 200 && minion.getView().getState() == GridEntityState.DEPLOYING;
        tick++) {
      scene.match.getBattle().step();
    }
    assertThat(minion.getView().isAir()).isTrue();
    scene.tornado();
    boolean overWater = false;
    for (int step = 0; step < TORNADO_LIFE / 50 + 2; step++) {
      scene.match.getBattle().step();
      overWater |= scene.onWater(minion);
    }
    List<AreaEffectEntity.Pull> minionPulls =
        scene.pulls.stream().filter(pull -> pull.target() == minion).toList();
    assertThat(minionPulls).as("the Tornado pulled the Minion").isNotEmpty();
    assertThat(minionPulls).allSatisfy(pull -> assertThat(pull.after()[3]).isZero());
    assertThat(overWater).as("over the river").isTrue();
  }
}
