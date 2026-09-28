package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.battle.GameData;
import org.crforge.core.battle.deploy.DeployCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A spell card as the battle reads it, and the spells its cast refuses. */
class SpellCardTest {

  @Test
  @DisplayName(
      "a spell summons no unit and casts its projectile or its area effect; the Goblin Barrel is"
          + " searched for its projectile's Goblin")
  void theCard() {
    DeployCard fireball = GameData.card("Fireball");
    assertThat(fireball.spell()).isTrue();
    assertThat(fireball.total()).isZero();
    assertThat(fireball.projectile()).isEqualTo("FireballSpell");
    assertThat(fireball.areaEffect()).isNull();
    assertThat(fireball.placementUnit()).isNull();
    assertThat(fireball.canDeployOnEnemySide()).isTrue();

    DeployCard zap = GameData.card("Zap");
    assertThat(zap.spell()).isTrue();
    assertThat(zap.areaEffect()).isEqualTo("Zap");
    assertThat(zap.projectile()).isNull();

    DeployCard barrel = GameData.card("GoblinBarrel");
    assertThat(barrel.projectile()).isEqualTo("GoblinBarrelSpell");
    assertThat(barrel.placementUnit().name()).isEqualTo("Goblin");
  }

  @Test
  @DisplayName(
      "a card that names no unit but casts is a spell from the characters card table too: the"
          + " wizards cast their area effect, which makes the wizard")
  void theWizardsAreSpells() {
    DeployCard electro = GameData.card("ElectroWizard");
    assertThat(electro.spell()).isTrue();
    assertThat(electro.areaEffect()).isEqualTo("ElectroWizardZap");
    assertThat(electro.placementUnit()).isNull();
    assertThat(GameData.card("IceWizard").areaEffect()).isEqualTo("IceWizardCold");
    // Deploying as a spell changes nothing for a card with a unit and no cast.
    DeployCard heal = GameData.card("Heal");
    assertThat(heal.spell()).isFalse();
    assertThat(heal.unit().name()).isEqualTo("HealSpirit");
  }

  @Test
  @DisplayName("a spell that summons a character is a troop play")
  void rageIsATroopPlay() {
    DeployCard rage = GameData.card("Rage");
    assertThat(rage.spell()).isFalse();
    assertThat(rage.unit().name()).isEqualTo("RageBottle");
  }

  @Test
  @DisplayName("Arrows casts three waves of ten projectiles in the circle of its radius")
  void arrows() {
    DeployCard arrows = GameData.card("Arrows");
    assertThat(arrows.multipleProjectiles()).isEqualTo(10);
    assertThat(arrows.projectileWaves()).isEqualTo(3);
    assertThat(arrows.projectileWaveIntervalMs()).isEqualTo(200);
    assertThat(arrows.projectileIntervalMs()).isZero();
    assertThat(arrows.radius()).isEqualTo(3500);
  }

  @Test
  @DisplayName(
      "The Log is thrown: it snaps to the tile centre and casts a projectile that spawns a rolling"
          + " one")
  void theLog() {
    DeployCard log = GameData.card("Log");
    assertThat(log.spellAsDeploy()).isTrue();
    assertThat(log.projectile()).isEqualTo("LogProjectile");
    assertThat(GameData.records().projectile("LogProjectile").spawnProjectile())
        .isEqualTo("LogProjectileRolling");
    assertThat(GameData.records().projectile("LogProjectile").spawnChain()).isEqualTo(1);
    // The Barbarian Barrel's row names no chain; the loader stores one link.
    assertThat(GameData.records().projectile("BarbLogProjectile").spawnChain()).isEqualTo(1);
    assertThat(GameData.records().projectile("LogProjectileRolling").homingLike()).isTrue();
  }
}
