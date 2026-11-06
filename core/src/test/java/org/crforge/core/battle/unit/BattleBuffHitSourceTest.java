/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.spawn.SpawnHost;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a buff's damage over time does with the buff's source at the damage drain. The game deals
 * each hit of it as it deals any queued hit: a character source scales it by the damage percents of
 * the buffs it carries and applies its BuffOnDamage to the carrier after it; a projectile source
 * credits its character shooter with the hit and applies its TargetBuff after it, unless the row
 * applies that before the damage. None of these is modelled, and no shipped row reaches one, so
 * each is refused; a source that does none of them - none at all, a character with no percents and
 * no BuffOnDamage, a projectile with no shooter and no TargetBuff - deals the hit as before.
 *
 * <p>The scene: two Knights of the two sides stand still far from each other and from the towers,
 * which stay passive. The burn, a written buff of damage over time, is put on the top side's Knight
 * with the source under test, and the battle runs on until its hits have landed.
 */
class BattleBuffHitSourceTest {

  /** The level of the towers and of the Knights. */
  private static final int LEVEL = 1;

  /** The written buff of damage over time. */
  private static final String BURN = "Test_Burn";

  /** The burn's damage a second, one hit a second. */
  private static final int BURN_DAMAGE_PER_SECOND = 20;

  /** How long the burn lasts, longer than the scene. */
  private static final int BURN_TIME_MS = 10_000;

  /** A written buff on the source that raises the hits it deals by half. */
  private static final String BOOST = "Test_Damage_Boost";

  /** A princess tower's arrow, given the burn as its target buff where a test needs one. */
  private static final String ARROW = "TowerPrincessProjectile";

  /** Ticks the scene runs after the burn is put on: two of its hits. */
  private static final int TICKS = 50;

  /** The tables with the burn and the boost written in. */
  private static GameTables withBurn(Path folder) throws IOException {
    return GameData.altered(
        folder,
        "character_buffs",
        rows -> {
          ObjectNode burn = rows.putObject(BURN);
          burn.put("index", rows.size() - 1);
          burn.put("class", "LogicCharacterBuffData");
          burn.putObject("columns")
              .put("DamagePerSecond", BURN_DAMAGE_PER_SECOND)
              .put("HitFrequency", 1000)
              .put("Rarity", "Common");
          ObjectNode boost = rows.putObject(BOOST);
          boost.put("index", rows.size() - 1);
          boost.put("class", "LogicCharacterBuffData");
          boost.putObject("columns").put("DamageMultiplier", 150).put("Rarity", "Common");
        });
  }

  /** The two Knights' scene on the tables. */
  private static final class Scene {
    final Standard1v1Battle match;
    final CharacterEntity bottom;
    final CharacterEntity top;

    Scene(GameTables tables) {
      match = new Standard1v1Battle(tables, LEVEL, false);
      UnitData knight = match.getWorld().getRecords().unit("Knight");
      bottom = match.deploy(0, knight, LEVEL, 0, 3500, 4000, "bottom");
      top = match.deploy(0, knight, LEVEL, 1, 14500, 28000, "top");
      bottom.setActive(CharacterEntity.MOVEMENT_SLOT, false);
      top.setActive(CharacterEntity.MOVEMENT_SLOT, false);
      // Past both deploys, so the burn's hits are not refused as the Knights deploy.
      step(40);
    }

    void step(int ticks) {
      for (int i = 0; i < ticks; i++) {
        match.getBattle().step();
      }
    }

    /** Puts the burn on the top Knight with the source, for the bottom side. */
    void burn(SpawnHost source) {
      BuffData burn = match.getWorld().buffData(BURN);
      top.getBuffs().apply(burn, BURN_TIME_MS, bottom.getPackedLevel(), source, 0);
    }

    /** A projectile of the row the bottom Knight fires at the top one, never registered. */
    ProjectileEntity shot(String row, boolean owned) {
      ProjectileData data = match.getWorld().getRecords().projectile(row);
      ProjectileEntity projectile = new ProjectileEntity(match.getWorld(), data, 0);
      if (owned) {
        projectile.launchFromUnit(bottom, -1, bottom.x(), bottom.y(), 0, top.x(), top.y());
      }
      return projectile;
    }

    /**
     * A projectile of the row the bottom side's king tower fires at the top Knight, never
     * registered.
     */
    ProjectileEntity towerShot(String row) {
      ProjectileData data = match.getWorld().getRecords().projectile(row);
      ProjectileEntity projectile = new ProjectileEntity(match.getWorld(), data, 0);
      TowerEntity king = match.getWorld().kingTower(0);
      projectile.launchFromUnit(king, -1, king.x(), king.y(), 0, top.x(), top.y());
      return projectile;
    }

    /** The hit points the top Knight has lost to the burn over the scene's ticks. */
    int burnt(SpawnHost source) {
      int before = top.getHitPoints().getHitPoints();
      burn(source);
      step(TICKS);
      return before - top.getHitPoints().getHitPoints();
    }
  }

  @Test
  @DisplayName(
      "a buff's damage over time from no source, a plain character or a projectile with no shooter"
          + " and no target buff is dealt as before, the same amount from each")
  void theReachableSourcesDealTheHit(@TempDir Path folder) throws IOException {
    GameTables tables = withBurn(folder);

    int none = new Scene(tables).burnt(null);
    Scene character = new Scene(tables);
    int fromCharacter = character.burnt(character.bottom);
    Scene projectile = new Scene(tables);
    int fromProjectile = projectile.burnt(projectile.shot(ARROW, false));

    assertThat(none).as("the burn's hits landed").isPositive();
    assertThat(fromCharacter).as("from a plain character").isEqualTo(none);
    assertThat(fromProjectile).as("from a projectile with no shooter").isEqualTo(none);
  }

  @Test
  @DisplayName(
      "a buff's damage over time from a character whose buffs scale its hits is refused: the game"
          + " scales it by them")
  void aScalingCharacterSourceIsRefused(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(withBurn(folder));
    scene
        .bottom
        .getBuffs()
        .apply(
            scene.match.getWorld().buffData(BOOST),
            BURN_TIME_MS,
            scene.bottom.getPackedLevel(),
            scene.bottom,
            0);
    scene.burn(scene.bottom);

    assertThatThrownBy(() -> scene.step(TICKS))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("damage percents");
  }

  @Test
  @DisplayName(
      "a buff's damage over time from a character with a BuffOnDamage is refused: the game applies"
          + " it to the carrier at the drain")
  void aCharacterSourceWithABuffOnDamageIsRefused(@TempDir Path folder) throws IOException {
    withBurn(folder);
    GameData.alterLoaded(
        folder,
        "characters",
        rows ->
            GameData.columns(rows, "Knight")
                .put("BuffOnDamage", BOOST)
                .put("BuffOnDamageTime", 1000));
    Scene scene = new Scene(GameTables.load(folder));
    scene.burn(scene.bottom);

    assertThatThrownBy(() -> scene.step(TICKS))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("BuffOnDamage");
  }

  @Test
  @DisplayName(
      "a buff's damage over time from a projectile a character shot is refused: the game credits"
          + " the shooter with the hit")
  void aProjectileSourceWithAShooterIsRefused(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(withBurn(folder));
    scene.burn(scene.shot(ARROW, true));

    assertThatThrownBy(() -> scene.step(TICKS))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("shooter");
  }

  @Test
  @DisplayName(
      "a buff's damage over time from a projectile a tower shot is refused: whether the game"
          + " credits a tower shooter with the hit is not established")
  void aProjectileSourceWithATowerShooterIsRefused(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(withBurn(folder));
    scene.burn(scene.towerShot(ARROW));

    assertThatThrownBy(() -> scene.step(TICKS))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("shooter")
        .hasMessageContaining("tower");
  }

  @Test
  @DisplayName(
      "a buff's damage over time from a projectile with an after-damage target buff is refused:"
          + " the game applies that buff to the carrier at the drain")
  void aProjectileSourceWithAnAfterDamageTargetBuffIsRefused(@TempDir Path folder)
      throws IOException {
    withBurn(folder);
    GameData.alterLoaded(
        folder,
        "projectiles",
        rows -> GameData.columns(rows, ARROW).put("TargetBuff", BOOST).put("BuffTime", 1000));
    Scene scene = new Scene(GameTables.load(folder));
    scene.burn(scene.shot(ARROW, false));

    assertThatThrownBy(() -> scene.step(TICKS))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("TargetBuff");
  }
}
