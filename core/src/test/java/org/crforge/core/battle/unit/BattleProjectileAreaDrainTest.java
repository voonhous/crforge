/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * When the damage of a projectile's area impact lands within its tick. The game queues each
 * victim's share for the holder's damage drain, as it queues a direct hit, so a direct hit dealt
 * earlier in the tick lands before it. Dealt at once, the impact would land ahead of the queued
 * direct hit.
 *
 * <p>The order shows on a shield, which takes a hit up to what it has left and loses the hit's
 * excess as it breaks. The scene: the bottom side's Knight walks up the left lane and meets the top
 * side's Dark Prince coming down it, and the bottom side's Fireball, cast so that it lands on the
 * Dark Prince on the step the Knight's first hit lands on it, with the shield whole. The Knight's
 * hit takes the shield down to 15 and the Fireball breaks it, its excess lost, so the Dark Prince
 * keeps every hit point. The other order would break the shield with the Fireball and take the
 * Knight's whole hit off the hit points. The scene writes every column its outcome is read from -
 * both units' rows, the Fireball's projectile, the towers' places and the columns of theirs the
 * walk reads - so its ticks, hit points and points are its own and not a version's.
 */
class BattleProjectileAreaDrainTest {

  /** The battle stream's seed of the scene. */
  private static final int SEED = 1131;

  /** The level every card is played at. */
  private static final int LEVEL = 1;

  /** The tick the Fireball is cast on, its flight landing it on the Knight's first hit. */
  private static final int FIREBALL = 271;

  /** Where the Dark Prince stands while it fights the Knight, the Fireball's aim. */
  private static final int AIM_X = 3297;

  private static final int AIM_Y = 19740;

  /** The tick of the step that lands the Knight's first hit and the Fireball's impact. */
  private static final int BOTH_HITS = 299;

  /** The Dark Prince's hit points and shield before the step, as the scene writes them. */
  private static final int HIT_POINTS = 469;

  private static final int SHIELD = 94;

  /** The configured tables with the scene's columns written. */
  private static GameTables written(Path folder) throws IOException {
    GameData.altered(
        folder,
        "characters",
        rows -> {
          GameData.columns(rows, "Knight")
              .put("Hitpoints", 690)
              .put("Damage", 79)
              .put("HitSpeed", 1200)
              .put("LoadTime", 700)
              .put("Speed", 60)
              .put("Mass", 6)
              .put("CollisionRadius", 500)
              .put("Range", 1200)
              .put("SightRange", 5500)
              .put("DeployTime", 1000)
              .put("ProjectileStartRadius", 450)
              .put("ProjectileStartZ", 450);
          GameData.columns(rows, "DarkPrince")
              .put("Hitpoints", HIT_POINTS)
              .put("ShieldHitpoints", SHIELD)
              .put("Damage", 104)
              .put("DamageSpecial", 208)
              .put("AreaDamageRadius", 1100)
              .put("ChargeRange", 300)
              .put("ChargeSpeedMultiplier", 200)
              .put("JumpHeight", 4000)
              .put("JumpSpeed", 160)
              .put("HitSpeed", 1400)
              .put("LoadTime", 1000)
              .put("Speed", 60)
              .put("Mass", 6)
              .put("CollisionRadius", 600)
              .put("Range", 1200)
              .put("SightRange", 5500)
              .put("DeployTime", 1000)
              .put("ProjectileStartRadius", 450)
              .put("ProjectileStartZ", 450);
        });
    GameData.alterLoaded(
        folder,
        "projectiles",
        rows ->
            GameData.columns(rows, "FireballSpell")
                .put("Damage", 269)
                .put("Radius", 2500)
                .put("Speed", 600)
                .put("Gravity", 50)
                .put("Pushback", 1000));
    GameData.writeTowers(folder);
    return GameTables.load(folder);
  }

  /** The towers at the first level, holding fire; the Knight, the Dark Prince and the Fireball. */
  private static Standard1v1Battle scene(GameTables tables) {
    Standard1v1Battle match = new Standard1v1Battle(tables, 1, false);
    match.getWorld().seed(SEED);
    match.play(220, match.getWorld().getRecords().card("Knight"), LEVEL, 0, 3500, 14000, "K");
    match.play(240, match.getWorld().getRecords().card("DarkPrince"), LEVEL, 1, 3500, 21500, "D");
    match.play(
        FIREBALL, match.getWorld().getRecords().card("Fireball"), LEVEL, 0, AIM_X, AIM_Y, "F");
    return match;
  }

  /** Steps the battle until its tick is the given one. */
  private static void stepTo(Standard1v1Battle match, int tick) {
    while (match.getBattle().getTick() < tick) {
      match.getBattle().step();
    }
  }

  /** The Dark Prince, the second play's unit. */
  private static CharacterEntity darkPrince(Standard1v1Battle match) {
    return match.getPlays().get(1).units().get(0);
  }

  /**
   * Runs the scene to the step of both hits, checks the Dark Prince before it, and runs the step.
   */
  private static CharacterEntity throughBothHits(Standard1v1Battle match) {
    stepTo(match, BOTH_HITS);
    CharacterEntity darkPrince = darkPrince(match);
    assertThat(darkPrince.getHitPoints().getHitPoints())
        .as("before the step")
        .isEqualTo(HIT_POINTS);
    assertThat(darkPrince.getHitPoints().getShield()).as("the shield whole").isEqualTo(SHIELD);
    assertThat(darkPrince.getView().getX()).as("at the aim").isEqualTo(AIM_X);
    assertThat(darkPrince.getView().getY()).isEqualTo(AIM_Y);
    match.getBattle().step();
    return darkPrince;
  }

  @Test
  @DisplayName(
      "the Fireball's impact lands at the damage drain after the"
          + " Knight's hit, so the shield takes both and the Dark Prince keeps its hit points")
  void theImpactLandsAfterTheDirectHit(@TempDir Path folder) throws IOException {
    CharacterEntity darkPrince = throughBothHits(scene(written(folder)));
    assertThat(darkPrince.getHitPoints().getShield()).as("broken").isZero();
    assertThat(darkPrince.getHitPoints().getHitPoints())
        .as("the Fireball's excess lost with the shield")
        .isEqualTo(HIT_POINTS);
  }
}
