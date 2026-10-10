/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.battle.Shipped.number;
import static org.crforge.core.battle.Shipped.numbers;
import static org.crforge.core.battle.Shipped.row;
import static org.crforge.core.battle.Shipped.text;
import static org.crforge.core.battle.Shipped.texts;

import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.deploy.DeployCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A spell card as the battle reads it, and the spells its cast refuses. */
class SpellCardTest {

  @Test
  @DisplayName(
      "a spell summons no unit and casts its projectile or its area effect; the Goblin Barrel is"
          + " searched for the character its projectile spawns")
  void theCard() {
    DeployCard fireball = GameData.card("Fireball");
    assertThat(fireball.spell()).isTrue();
    assertThat(fireball.total()).isZero();
    assertThat(fireball.projectile())
        .isEqualTo(text(row("spells_other", "Fireball"), "Projectile"));
    assertThat(fireball.areaEffect()).isNull();
    assertThat(fireball.placementUnit()).isNull();
    assertThat(fireball.canDeployOnEnemySide()).isTrue();

    DeployCard zap = GameData.card("Zap");
    assertThat(zap.spell()).isTrue();
    assertThat(zap.areaEffect()).isEqualTo(text(row("spells_other", "Zap"), "AreaEffectObject"));
    assertThat(zap.projectile()).isNull();

    DeployCard barrel = GameData.card("GoblinBarrel");
    String projectile = text(row("spells_other", "GoblinBarrel"), "Projectile");
    assertThat(barrel.projectile()).isEqualTo(projectile);
    // Placed as the character its projectile spawns.
    assertThat(barrel.placementUnit().name())
        .isEqualTo(text(row("projectiles", projectile), "SpawnCharacter"));
  }

  @Test
  @DisplayName(
      "a card that lists its unit and casts deploys as a spell: the wizards list the wizard, 50 ms"
          + " late, and make their area effect")
  void theWizardsDeployAsSpells() {
    DeployCard electro = GameData.card("ElectroWizard");
    GameRow electroRow = row("spells_characters", "ElectroWizard");
    assertThat(electro.spellAsDeploy()).isTrue();
    assertThat(electro.areaEffect()).isEqualTo(text(electroRow, "AreaEffectObject"));
    assertThat(electro.listed())
        .extracting(listed -> listed.unit().name() + " " + listed.delayMs())
        .containsExactly(
            texts(electroRow, "SummonCharactersList").get(0)
                + " "
                + numbers(electroRow, "SummonCharactersDelayList").get(0));
    assertThat(GameData.card("IceWizard").areaEffect())
        .isEqualTo(text(row("spells_characters", "IceWizard"), "AreaEffectObject"));
    // Deploying as a spell changes nothing for a card with a unit and no cast.
    DeployCard heal = GameData.card("Heal");
    assertThat(heal.spell()).isFalse();
    assertThat(heal.unit().name()).isEqualTo(text(row("spells_other", "Heal"), "SummonCharacter"));
  }

  @Test
  @DisplayName("a spell that summons a character is a troop play")
  void rageIsATroopPlay() {
    DeployCard rage = GameData.card("Rage");
    assertThat(rage.spell()).isFalse();
    assertThat(rage.unit().name()).isEqualTo(text(row("spells_other", "Rage"), "SummonCharacter"));
  }

  @Test
  @DisplayName("Arrows casts its waves of projectiles in the circle of its radius")
  void arrows() {
    DeployCard arrows = GameData.card("Arrows");
    GameRow row = row("spells_other", "Arrows");
    assertThat(arrows.multipleProjectiles()).isEqualTo(number(row, "MultipleProjectiles"));
    assertThat(arrows.projectileWaves()).isEqualTo(number(row, "ProjectileWaves"));
    assertThat(arrows.projectileWaveIntervalMs()).isEqualTo(number(row, "ProjectileWaveInterval"));
    assertThat(arrows.projectileIntervalMs()).isEqualTo(number(row, "ProjectileInterval"));
    assertThat(arrows.radius()).isEqualTo(number(row, "Radius"));
  }

  @Test
  @DisplayName(
      "The Log is thrown: it snaps to the tile centre and casts a projectile that spawns a rolling"
          + " one")
  void theLog() {
    DeployCard log = GameData.card("Log");
    String projectile = text(row("spells_other", "Log"), "Projectile");
    GameRow projectileRow = row("projectiles", projectile);
    String rolling = text(projectileRow, "SpawnProjectile");
    assertThat(log.spellAsDeploy()).isTrue();
    assertThat(log.projectile()).isEqualTo(projectile);
    assertThat(GameData.records().projectile(projectile).spawnProjectile()).isEqualTo(rolling);
    assertThat(GameData.records().projectile(projectile).spawnChain())
        .isEqualTo(Math.max(1, number(projectileRow, "SpawnChain")));
    // The Barbarian Barrel's row names no chain; the loader stores one link.
    assertThat(number(row("projectiles", "BarbLogProjectile"), "SpawnChain")).isZero();
    assertThat(GameData.records().projectile("BarbLogProjectile").spawnChain()).isEqualTo(1);
    assertThat(GameData.records().projectile(rolling).homingLike()).isTrue();
  }
}
