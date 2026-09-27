package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.crforge.core.battle.GameData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An area effect's row and its buff's as the battle reads them, and the area effects it refuses.
 */
class BattleAreaEffectTest {

  @Test
  @DisplayName("an area effect is its row's columns, in the row's own units")
  void theRow() {
    AreaEffectData drill = GameData.records().areaEffect("GoblinDrillDamage");
    assertThat(drill.lifeDurationMs()).isEqualTo(1);
    assertThat(drill.radius()).isEqualTo(2000);
    assertThat(drill.damage()).isEqualTo(33);
    assertThat(drill.crownTowerDamagePercent()).isEqualTo(-70);
    assertThat(drill.pushback()).isEqualTo(1000);
    assertThat(drill.onlyEnemies()).isTrue();
    assertThat(drill.hitsGround()).isTrue();
    assertThat(drill.hitsAir()).isFalse();
    assertThat(drill.unmodelledColumns()).isEmpty();
    assertThat(drill.buff()).isNull();
    assertThat(drill.spawnAreaEffectObject()).isNull();

    AreaEffectData zap = GameData.records().areaEffect("Zap");
    assertThat(zap.buff()).isEqualTo("ZapFreeze");
    assertThat(zap.buffTimeMs()).isEqualTo(500);
    assertThat(zap.capBuffTimeToAreaEffectTime()).isFalse();
    assertThat(zap.unmodelledColumns()).isEmpty();

    AreaEffectData rage = GameData.records().areaEffect("Rage");
    assertThat(rage.buff()).isEqualTo("Rage");
    assertThat(rage.buffTimeMs()).isEqualTo(1000);
    assertThat(rage.capBuffTimeToAreaEffectTime()).isTrue();
    assertThat(rage.onlyOwnTroops()).isTrue();
    assertThat(rage.spawnAreaEffectObject()).isEqualTo("RageDamage");
    assertThat(rage.unmodelledColumns()).isEmpty();
  }

  @Test
  @DisplayName(
      "a buff is its row's columns, and every column it sets that neither is read nor only shows"
          + " something is listed")
  void theBuffRow() {
    BuffData poison = GameData.records().buff("Poison");
    assertThat(poison.speedMultiplier()).isEqualTo(-15);
    assertThat(poison.damagePerSecond()).isEqualTo(36);
    assertThat(poison.hitFrequency()).isEqualTo(1000);
    assertThat(poison.crownTowerDamagePercent()).isEqualTo(-75);
    assertThat(poison.enableStacking()).isTrue();
    assertThat(poison.unmodelledColumns()).isEmpty();

    BuffData rage = GameData.records().buff("Rage");
    assertThat(rage.speedMultiplier()).isEqualTo(130);
    assertThat(rage.hitSpeedMultiplier()).isEqualTo(130);
    assertThat(rage.spawnSpeedMultiplier()).isEqualTo(130);
    assertThat(rage.unmodelledColumns()).isEmpty();

    assertThat(GameData.records().buff("ZapFreeze").hitSpeedMultiplier()).isEqualTo(-100);
    assertThat(GameData.records().buff("Earthquake").unmodelledColumns())
        .containsExactly("HitTickFromSource");
    assertThat(GameData.records().buff("Tornado").unmodelledColumns())
        .containsExactly("AttractPercentage", "ControlledByParent", "PushSpeedFactor");
  }

  @Test
  @DisplayName("an area effect whose buff sets a column not modelled is refused as it is created")
  void aBuffNotModelledIsRefused() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    match.placeAreaEffect(1, "Earthquake", 11, 0, 3500, 20000, "Earthquake");
    match.getBattle().step();
    assertThatThrownBy(() -> match.getBattle().step())
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("HitTickFromSource");
  }
}
