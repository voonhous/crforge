package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
  @DisplayName("a spell that summons a character is a troop play")
  void rageIsATroopPlay() {
    DeployCard rage = GameData.card("Rage");
    assertThat(rage.spell()).isFalse();
    assertThat(rage.unit().name()).isEqualTo("RageBottle");
  }

  @Test
  @DisplayName(
      "several projectiles and a spell thrown as a projectile are refused as the card is read")
  void theCastsNotModelled() {
    assertThatThrownBy(() -> GameData.card("Arrows"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("MultipleProjectiles");
    assertThatThrownBy(() -> GameData.card("Log"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("SpellAsDeploy");
  }

  @Test
  @DisplayName("a projectile whose impact buffs its target is refused as it is cast")
  void theSnowballIsRefused() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    match.play(0, GameData.card("Snowball"), 11, 0, 3500, 20000, "Snowball");
    assertThatThrownBy(() -> match.getBattle().step())
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("TargetBuff");
  }
}
