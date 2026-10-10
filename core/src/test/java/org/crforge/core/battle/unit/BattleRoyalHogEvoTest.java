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
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The evolved Royal Hog flies until it falls: its first hit, or the step its hit points fall to 99%
 * of its maximum, runs its fall group, gated on a tag the fall's own run carries so it runs once. A
 * fall its hit runs starts in the step of the hit and descends over its transition duration; the
 * step after it the hog lands, takes its grounded row and makes its landing area, which hits the
 * enemies around it a step later, and its route is emptied 100 ms after the landing. It is then
 * held on the ground layer for the rest of the battle.
 *
 * <p>The scene writes both rows' hit speed and the hit-point share the fall waits for (99%); the
 * damages and the fall's length are read from the rows.
 */
class BattleRoyalHogEvoTest {

  private static final String HOG = "RoyalHog_EV1";

  /** Both rows' hit speed, as the scene writes it: a hit every 24 steps. */
  private static final int HIT_SPEED = 1200;

  /** The hit-point share at or below which the hog falls, as the scene writes it. */
  private static final int FALL_AT_PERCENT = 99;

  @TempDir static Path folder;

  /** The configured tables with the scene's columns written. */
  private static GameTables tables;

  @BeforeAll
  static void writeTheScene() throws IOException {
    GameData.altered(
        folder,
        "characters",
        rows -> {
          GameData.columns(rows, HOG).put("HitSpeed", HIT_SPEED);
          GameData.columns(rows, GROUNDED).put("HitSpeed", HIT_SPEED);
        });
    GameData.alterLoaded(
        folder,
        "actions",
        rows ->
            ((ObjectNode) rows.get("RoyalHog_EV1_Health_Threshold").get("fields"))
                .putArray("HealthPercentages")
                .add(FALL_AT_PERCENT));
    tables = GameTables.load(folder);
  }

  /** The card's first level. */
  private static final int LEVEL = 1;

  /** Side 1's right princess tower, at (14500, 25500). */
  private static final String TOWER = "PrincessTower_1_2";

  /** The grounded row the hog takes as it lands. */
  private static final String GROUNDED = "RoyalHog_EV1_Grounded";

  /**
   * What one run saw: the hog's hits and the area hits on side 1's tower, the landing areas made
   * with the step each was made, the hog, the step it took its grounded row and whether it was an
   * air unit after each step.
   */
  private record Fall(
      List<int[]> hits,
      List<int[]> areaHits,
      List<Integer> landings,
      CharacterEntity hog,
      int groundedAt,
      List<Boolean> air) {}

  /**
   * Runs one evolved Royal Hog of side 0 toward side 1's right princess tower, the towers at the
   * level given, holding fire or not.
   */
  private static Fall fall(int towerLevel, boolean towersAttack, int ticks) {
    Standard1v1Battle match = new Standard1v1Battle(tables, towerLevel, towersAttack);
    List<int[]> hits = new ArrayList<>();
    List<int[]> areaHits = new ArrayList<>();
    List<Integer> landings = new ArrayList<>();
    CharacterEntity hog =
        match.deploy(0, match.getWorld().getRecords().unit(HOG), LEVEL, 0, 14500, 21000);
    int[] grounded = {-1};
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void damageDealt(
                  int tick, WorldEntity target, int damage, DamageResult result) {
                if (target.name().equals(TOWER)) {
                  hits.add(new int[] {tick, damage});
                }
              }

              @Override
              public void areaEffectSpawned(
                  int tick,
                  SpawnHost owner,
                  String action,
                  int phase,
                  SpawnHost source,
                  AreaEffectEntity areaEffect) {
                if (action.equals("RoyalHog_EV1_Spawn_Landing_Damage_Area") && owner == hog) {
                  landings.add(tick);
                  // The swap runs just before, in the same step.
                  if (hog.getData().name().equals(GROUNDED) && grounded[0] < 0) {
                    grounded[0] = tick;
                  }
                }
              }

              @Override
              public void areaEffectHit(
                  int tick,
                  AreaEffectEntity areaEffect,
                  WorldEntity victim,
                  int damage,
                  DamageResult result) {
                if (victim.name().equals(TOWER)) {
                  areaHits.add(new int[] {tick, damage});
                }
              }
            });
    List<Boolean> air = new ArrayList<>();
    for (int tick = 0; tick < ticks; tick++) {
      match.getBattle().step();
      air.add(hog.getView().isAir());
    }
    return new Fall(hits, areaHits, landings, hog, grounded[0], air);
  }

  @Test
  @DisplayName(
      "the first hit drops the hog: it lands its fall's steps and one later as its grounded row,"
          + " its landing area hits the tower once, and it stays on the ground layer")
  void theFirstHitDropsTheHog() {
    Fall fall = fall(15, false, 400);
    List<int[]> hits = fall.hits();
    assertThat(hits).hasSizeGreaterThanOrEqualTo(4);
    int firstHit = hits.get(0)[0];
    assertThat(hits.get(0)[1])
        .as("the hog's hit at the first level")
        .isEqualTo(Shipped.number(Shipped.unitRow(HOG), "Damage"));
    assertThat(fall.landings()).as("one landing area, one fall").hasSize(1);
    int landing = fall.landings().get(0);
    // The fall's TransitionDuration in steps, a part step counting as one; it lands on the step
    // after them.
    int fallSteps = (Shipped.number("RoyalHog_EV1_To_Ground", "TransitionDuration") + 49) / 50;
    assertThat(landing).as("the step it lands").isEqualTo(firstHit + fallSteps + 1);
    // The landing area is in the filter form: its hit on the tower is a typed hit, a step later.
    assertThat(fall.areaHits()).isEmpty();
    int landingDamage =
        Shipped.column(
                Shipped.row("area_effect_objects", "RoyalHog_EV1_Landing_Damage_Area"), "Damage")
            .path("BaseDamage")
            .asInt();
    assertThat(hits.get(1))
        .as("the landing area's damage at the first level, a step later")
        .containsExactly(landing + 1, landingDamage);
    // The fall runs once: every later hit is the hog's own, one a hit speed.
    List<int[]> own = new ArrayList<>(hits);
    own.remove(1);
    for (int i = 1; i < own.size(); i++) {
      assertThat(own.get(i)[0] - own.get(i - 1)[0]).as("hit %d", i + 1).isEqualTo(HIT_SPEED / 50);
    }
    assertThat(fall.hog().getData().name()).isEqualTo(GROUNDED);
    assertThat(fall.air().get(fall.air().size() - 1)).as("held on the ground layer").isFalse();
  }

  @Test
  @DisplayName(
      "a tower shot that takes the hog to 99% of its hit points drops it before it attacks")
  void aShotUnderTheThresholdDropsTheHog() {
    Fall fall = fall(15, true, 400);
    assertThat(fall.landings()).as("one landing area, one fall").hasSize(1);
    assertThat(fall.groundedAt()).as("the step it lands").isPositive();
    assertThat(fall.hits()).isNotEmpty();
    assertThat(fall.hits().get(0)[0])
        .as("the hog lands before its first hit")
        .isGreaterThan(fall.groundedAt());
  }
}
