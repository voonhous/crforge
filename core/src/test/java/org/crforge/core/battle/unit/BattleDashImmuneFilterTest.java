/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A filter's DashImmune exclusion: a character in the dashing state whose row has a dash immunity
 * time is left out of every object the filter is asked about. An area effect whose filter carries
 * it neither pushes nor damages a Bandit in its dash, while the same area reaches the Bandit as it
 * walks.
 *
 * <p>The scene: the top side's Inferno Tower stands in the left lane, and the bottom side's Bandit
 * walks up the lane at it and dashes. A top side area, whose filter leaves out hidden and dash
 * immune characters and which pushes and damages what it reaches, is placed on the Bandit either
 * while it walks or on its first dashing step. The scene writes every column its outcome is read
 * from: both units' ranges, radii, pace and dash, the area's radius, push and damage, and its
 * filter.
 */
class BattleDashImmuneFilterTest {

  /** The level every object is placed at: the first, whose stats are the rows' own. */
  private static final int LEVEL = 1;

  /** Long enough for the Bandit to walk into its dash range, wind up and dash. */
  private static final int TICKS = 400;

  /** The Bandit's hit points, more than the scene's hits take. */
  private static final int BANDIT_HIT_POINTS = 1_000_000;

  /** The area effect the scene places, its row taken over by the scene's columns. */
  private static final String AREA = "GolemDeathExplosion";

  /** The area's filter, its row taken over by the scene's columns. */
  private static final String FILTER = "CommonAreaDamageFilter";

  /** The configured tables with the scene's columns written. */
  private static GameTables written(Path folder) throws IOException {
    Files.createDirectories(folder);
    GameData.altered(
        folder,
        "characters",
        rows ->
            GameData.columns(rows, "Assassin")
                .put("Hitpoints", BANDIT_HIT_POINTS)
                .put("Damage", 76)
                .put("HitSpeed", 1000)
                .put("LoadTime", 600)
                .put("Speed", 90)
                .put("Mass", 3)
                .put("CollisionRadius", 600)
                .put("Range", 750)
                .put("SightRange", 6000)
                .put("DeployTime", 1000)
                .put("DashDamage", 152)
                .put("DashMinRange", 3500)
                .put("DashMaxRange", 6000)
                .put("DashCooldown", 800)
                .put("DashImmuneToDamageTime", 150)
                .put("JumpSpeed", 500));
    GameData.alterLoaded(
        folder,
        "area_effect_objects",
        rows -> {
          ObjectNode columns = GameData.columns(rows, AREA);
          columns.removeAll();
          columns.put("Radius", 2000);
          columns.put("Pushback", 1800);
          columns.put("Filter", FILTER);
          columns.putObject("Damage").put("BaseDamage", 100);
        });
    GameData.alterLoaded(
        folder,
        "game_object_filters",
        rows -> {
          ObjectNode columns = GameData.columns(rows, FILTER);
          columns.removeAll();
          columns.put("MatchTeamEnemy", true);
          columns.put("MatchTypeCharacters", true);
          ArrayNode filters = columns.putArray("Filters");
          filters.add("Hidden");
          filters.add("DashImmune");
        });
    GameData.alterLoaded(
        folder,
        "buildings",
        rows -> {
          GameData.writeTowerColumns(rows);
          GameData.columns(rows, "InfernoTower")
              .put("Hitpoints", 1_000_000)
              .put("Damage", 20)
              .put("VariableDamage2", 0)
              .put("VariableDamage3", 0)
              .put("VariableDamageTime1", 0)
              .put("VariableDamageTime2", 0)
              .put("HitSpeed", 400)
              .put("LoadTime", 1200)
              .put("CollisionRadius", 1000)
              .put("Range", 6000)
              .put("SightRange", 6000)
              .put("DeployTime", 1000);
        });
    GameData.alterLoaded(folder, "projectiles", GameData::writeTowerShots);
    GameData.alterLoaded(folder, "spawn_groups", GameData::placeTowers);
    return GameTables.load(folder);
  }

  /**
   * What the area did to the Bandit: whether it was in its dash on the step the area was placed,
   * whether a pushback was asked of it, and the hit points it lost to the area.
   */
  private record Seen(boolean dashing, boolean pushed, int lost) {}

  /**
   * Plays the scene, the towers passive: the area is placed on the Bandit's point once the Bandit
   * has walked the given number of steps, or on its first dashing step when that is negative.
   */
  private static Seen play(GameTables tables, int walkedSteps) {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    match.deploy(
        0, match.getWorld().getRecords().unit("InfernoTower"), LEVEL, 1, 3500, 18000, "Tower");
    CharacterEntity bandit =
        match.deploy(
            0, match.getWorld().getRecords().unit("Assassin"), LEVEL, 0, 3500, 9000, "Bandit");
    for (int step = 0; step < TICKS; step++) {
      match.getBattle().step();
      boolean dashing = bandit.getView().getState() == GridEntityState.DASHING;
      boolean place =
          walkedSteps < 0
              ? dashing
              : step >= walkedSteps && bandit.getView().getState() == GridEntityState.MOVING;
      if (!place) {
        continue;
      }
      int before = bandit.getHitPoints().getHitPoints();
      // On the Bandit's point, a little toward the tower, on the step that follows.
      match.placeAreaEffect(
          match.getWorld().tick() + 1,
          AREA,
          LEVEL,
          1,
          bandit.getView().getX(),
          bandit.getView().getY() + 100,
          "Area");
      match.getBattle().step();
      boolean pushed = bandit.getUnit().movement().getPushbackInFlight() != 0;
      return new Seen(dashing, pushed, before - bandit.getHitPoints().getHitPoints());
    }
    return new Seen(false, false, 0);
  }

  @Test
  @DisplayName("an area whose filter leaves out dash immune characters reaches a walking Bandit")
  void theAreaReachesAWalkingBandit(@TempDir Path folder) throws IOException {
    Seen scene = play(written(folder), 40);
    assertThat(scene.dashing()).as("the Bandit walked").isFalse();
    assertThat(scene.pushed()).as("pushed").isTrue();
    assertThat(scene.lost()).as("damaged").isPositive();
  }

  @Test
  @DisplayName(
      "an area whose filter leaves out dash immune characters neither pushes nor damages a Bandit"
          + " in its dash")
  void theAreaLeavesOutADashingBandit(@TempDir Path folder) throws IOException {
    Seen scene = play(written(folder), -1);
    assertThat(scene.dashing()).as("the Bandit dashed").isTrue();
    assertThat(scene.pushed()).as("pushed").isFalse();
    assertThat(scene.lost()).as("damaged").isZero();
  }
}
