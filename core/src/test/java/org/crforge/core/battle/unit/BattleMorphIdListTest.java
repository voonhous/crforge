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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A morph and the id lists that hold the old object. In the game a morph lists the new object in
 * every projectile's hit list and every area effect's hit-once list that holds the old one, so a
 * body that has hit the old object passes the new one by. The battle core does not hand those lists
 * over, so a morph of an object some list holds is refused. No shipped row reaches it: the only
 * morph of a Ladder battle is a Goblin Drill dig surfacing, and the dig has no collision radius, so
 * nothing finds it to list it.
 *
 * <p>The scene: the top side's Log rolls down a lane while the bottom side's Goblin Drill tunnels
 * up to a point in that lane, the dig surfacing under the rolling body. With the dig's collision
 * radius at 0 the body never meets it and hits the building the dig becomes; written with a radius,
 * the body lists the tunnelling dig as it rolls over it, and its surfacing is refused.
 */
class BattleMorphIdListTest {

  /** The tick the Log is played on. */
  private static final int LOG_TICK = 320;

  /** The tick the Goblin Drill is played on, so that its dig surfaces under the rolling Log. */
  private static final int DRILL_TICK = 336;

  /** Ticks the scene runs: past the surfacing and the Log's hit on the building. */
  private static final int TICKS = 420;

  /** The tables with the dig's collision radius written. */
  private static GameTables withDigRadius(Path folder, int radius) throws IOException {
    return GameData.altered(
        folder,
        "characters",
        rows -> GameData.columns(rows, "GoblinDrillDig").put("CollisionRadius", radius));
  }

  /** The scene on the tables, the towers passive. */
  private static final class Scene {
    final Standard1v1Battle match;
    final List<CharacterEntity> buildings = new ArrayList<>();

    /** Every projectile the battle has held, in the order first seen: the Log's among them. */
    final Set<ProjectileEntity> projectiles = new LinkedHashSet<>();

    Scene(GameTables tables) {
      match = new Standard1v1Battle(tables, Standard1v1Battle.DEFAULT_LEVEL, false);
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void morphed(int tick, CharacterEntity old, CharacterEntity building) {
                  buildings.add(building);
                }
              });
      match.play(
          LOG_TICK,
          match.getWorld().getRecords().card("Log"),
          Standard1v1Battle.DEFAULT_LEVEL,
          1,
          9500,
          18500,
          "Log");
      match.play(
          DRILL_TICK,
          match.getWorld().getRecords().card("GoblinDrill"),
          Standard1v1Battle.DEFAULT_LEVEL,
          0,
          9000,
          12000,
          "Drill");
    }

    /** The Goblin Drill's dig, once its play has run. */
    CharacterEntity dig() {
      return match.getPlays().stream()
          .filter(play -> play.name().equals("Drill"))
          .findFirst()
          .orElseThrow()
          .units()
          .get(0);
    }

    /** True when a projectile the scene launched has listed the id in its hit list. */
    boolean listed(int id) {
      return projectiles.stream().anyMatch(projectile -> projectile.getHitIds().contains(id));
    }

    void run() {
      for (int i = 0; i < TICKS; i++) {
        match.getBattle().step();
        for (BattleEntity entity : match.getBattle().getHolder().entities()) {
          if (entity instanceof ProjectileEntity projectile) {
            projectiles.add(projectile);
          }
        }
      }
    }
  }

  @Test
  @DisplayName(
      "a dig without a collision radius surfaces under a rolling Log unlisted, and the Log hits"
          + " the building it becomes")
  void aDigWithoutARadiusIsNeverListed(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(withDigRadius(folder, 0));
    scene.run();

    assertThat(scene.buildings).as("the dig surfaced").hasSize(1);
    assertThat(scene.listed(scene.dig().getId())).as("the Log never met the dig").isFalse();
    assertThat(scene.listed(scene.buildings.get(0).getId()))
        .as("the Log hit the building")
        .isTrue();
  }

  @Test
  @DisplayName(
      "a dig with a collision radius that a rolling Log has listed is refused as it surfaces: the"
          + " game would list the new building in the Log's hit list too")
  void aListedDigIsRefusedAsItMorphs(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(withDigRadius(folder, 500));

    assertThatThrownBy(scene::run)
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("morphs")
        .hasMessageContaining("hit list");
    assertThat(scene.listed(scene.dig().getId())).as("the Log listed the dig").isTrue();
    assertThat(scene.buildings).as("refused before the building is made").isEmpty();
  }
}
