package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * When a direct hit's damage lands within its tick. The game queues it for the holder's damage
 * drain, which runs after every post-hook, so a unit the hit kills still takes its movement visit
 * of that tick.
 *
 * <p>The scene: the bottom side's Mini P.E.K.K.A. walks up the left lane and meets the top side's
 * Giant coming down it. The Giant, which targets buildings only, walks on while the Mini P.E.K.K.A.
 * hits it, and the hit that kills it lands while it walks. The scene writes every column its
 * outcome is read from - both units' rows, the towers' places, the columns of theirs the walk reads
 * and their shots - and plays both cards at the first level, where their stats are their rows' own,
 * so its ticks, hit points and points are its own and not a version's. The same scene with the
 * Giant written with hit points no hit takes is the control: it shows where the Giant's walking
 * step of each tick takes it.
 */
class BattleDirectHitDrainTest {

  /** The battle stream's seed of the scene. */
  private static final int SEED = 1131;

  /** The level both cards are played at: the first, whose stats are the rows' own. */
  private static final int LEVEL = 1;

  /** The Giant's hit points and the Mini P.E.K.K.A.'s damage, as the scene writes them. */
  private static final int GIANT_HIT_POINTS = 1550;

  private static final int MINI_PEKKA_DAMAGE = 600;

  /**
   * The tick of the killing hit, the Mini P.E.K.K.A.'s third: the scene's own, as every column it
   * follows from is written.
   */
  private static final int DEATH = 358;

  /** The Giant's hit points before the killing hit: two of the Mini P.E.K.K.A.'s hits taken. */
  private static final int ALIVE = GIANT_HIT_POINTS - 2 * MINI_PEKKA_DAMAGE;

  /** Where the Giant stands after its last walking step before the killing hit's tick. */
  private static final int LAST_X = 3733;

  private static final int LAST_Y = 17251;

  /** Where its walking step of the killing hit's tick takes it. */
  private static final int STEP_X = 3733;

  private static final int STEP_Y = 17199;

  /** The configured tables with the scene's columns written, the Giant's hit points given. */
  private static GameTables written(Path folder, int giantHitPoints) throws IOException {
    Files.createDirectories(folder);
    GameData.altered(
        folder,
        "characters",
        rows -> {
          GameData.columns(rows, "MiniPekka")
              .put("Hitpoints", 543)
              .put("Damage", MINI_PEKKA_DAMAGE)
              .put("HitSpeed", 1600)
              .put("LoadTime", 1100)
              .put("Speed", 90)
              .put("Mass", 4)
              .put("CollisionRadius", 450)
              .put("Range", 800)
              .put("SightRange", 5500)
              .put("DeployTime", 1000)
              .put("ProjectileStartRadius", 450)
              .put("ProjectileStartZ", 450);
          GameData.columns(rows, "Giant")
              .put("Hitpoints", giantHitPoints)
              .put("Damage", 99)
              .put("HitSpeed", 1500)
              .put("LoadTime", 1000)
              .put("Speed", 45)
              .put("StopMovementAfterMS", 640)
              .put("WaitMS", 100)
              .put("Mass", 18)
              .put("CollisionRadius", 750)
              .put("Range", 1200)
              .put("SightRange", 7500)
              .put("SightClip", 2000)
              .put("SightClipSide", 2000)
              .put("DeployTime", 1000)
              .put("ProjectileStartRadius", 450)
              .put("ProjectileStartZ", 450);
        });
    writeTowers(folder);
    return GameTables.load(folder);
  }

  /** Writes the towers' columns, their shots and their places into an altered copy. */
  private static void writeTowers(Path folder) throws IOException {
    GameData.alterLoaded(
        folder,
        "buildings",
        rows -> {
          GameData.columns(rows, "PrincessTower")
              .put("CollisionRadius", 1000)
              .put("Range", 7500)
              .put("SightRange", 7500)
              .put("HitSpeed", 800)
              .put("Hitpoints", 1400)
              .put("ProjectileStartRadius", 300)
              .put("ProjectileStartZ", 3000)
              .put("NoDeploySizeW", 11)
              .put("NoDeploySizeH", 21);
          GameData.columns(rows, "KingTower")
              .put("CollisionRadius", 1400)
              .put("Range", 7000)
              .put("SightRange", 7000)
              .put("HitSpeed", 1000)
              .put("LoadTime", 500)
              .put("Hitpoints", 2400)
              .put("ProjectileStartRadius", 750)
              .put("ProjectileStartZ", 3500)
              .put("NoDeploySizeW", 18)
              .put("NoDeploySizeH", 16);
        });
    GameData.alterLoaded(
        folder,
        "projectiles",
        rows -> {
          GameData.columns(rows, "TowerPrincessProjectile")
              .put("Damage", 50)
              .put("Speed", 600)
              .put("Gravity", 60);
          GameData.columns(rows, "KingProjectile")
              .put("Damage", 50)
              .put("Speed", 1000)
              .put("Gravity", 50);
        });
    GameData.alterLoaded(
        folder,
        "spawn_groups",
        rows -> {
          ArrayNode towers = GameData.columns(rows, "King_PrincessTowers").putArray("Objects");
          towers.addObject().put("Data", "KingTower").put("x", 18).put("y", 6);
          towers.addObject().put("Data", "PrincessTower").put("x", 7).put("y", 13);
          towers.addObject().put("Data", "PrincessTower").put("x", 29).put("y", 13);
        });
  }

  /** The towers at the first level, fighting; the Mini P.E.K.K.A. and the Giant played. */
  private static Standard1v1Battle scene(GameTables tables) {
    Standard1v1Battle match = new Standard1v1Battle(tables, 1, true);
    match.getWorld().seed(SEED);
    match.play(220, match.getWorld().getRecords().card("MiniPekka"), LEVEL, 0, 3500, 14000, "P");
    match.play(240, match.getWorld().getRecords().card("Giant"), LEVEL, 1, 3500, 21500, "G");
    return match;
  }

  /** Steps the battle until its tick is the given one. */
  private static void stepTo(Standard1v1Battle match, int tick) {
    while (match.getBattle().getTick() < tick) {
      match.getBattle().step();
    }
  }

  /** The Giant, the second play's unit. */
  private static CharacterEntity giant(Standard1v1Battle match) {
    return match.getPlays().get(1).units().get(0);
  }

  @Test
  @DisplayName(
      "a direct hit lands at the damage drain, so the Giant it kills still takes its walking step"
          + " of that tick")
  void theKilledGiantStillWalksItsStep(@TempDir Path folder) throws IOException {
    Standard1v1Battle match = scene(written(folder.resolve("scene"), GIANT_HIT_POINTS));
    stepTo(match, DEATH - 1);
    CharacterEntity giant = giant(match);
    assertThat(giant.getHitPoints().getHitPoints()).as("alive before the hit").isEqualTo(ALIVE);
    assertThat(giant.getView().getX()).isEqualTo(LAST_X);
    assertThat(giant.getView().getY()).isEqualTo(LAST_Y);

    stepTo(match, DEATH);
    assertThat(giant.getHitPoints().getHitPoints()).as("killed").isZero();
    assertThat(giant.getView().getX()).as("one walking step on").isEqualTo(STEP_X);
    assertThat(giant.getView().getY()).isEqualTo(STEP_Y);

    // The control's Giant takes every hit and lives: its step of that tick takes it to the same
    // point.
    Standard1v1Battle control = scene(written(folder.resolve("control"), 1_000_000));
    stepTo(control, DEATH);
    assertThat(new int[] {giant(control).getView().getX(), giant(control).getView().getY()})
        .containsExactly(STEP_X, STEP_Y);
  }
}
