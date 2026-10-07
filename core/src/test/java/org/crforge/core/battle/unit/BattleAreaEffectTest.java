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
    AreaEffectData explosion = GameData.records().areaEffect("MightyMinerExplosion");
    assertThat(explosion.radius()).isEqualTo(3000);
    assertThat(explosion.typedDamage().baseDamage()).isEqualTo(130);
    assertThat(explosion.typedDamage().towerDamage()).isEqualTo(AreaDamageType.NO_TOWER_DAMAGE);
    assertThat(explosion.pushback()).isEqualTo(1800);
    assertThat(explosion.filter()).isEqualTo("CommonAreaDamageFilter");
    assertThat(explosion.filterHits()).isTrue();
    assertThat(explosion.unmodelledColumns()).isEmpty();
    assertThat(explosion.buff()).isNull();
    assertThat(explosion.spawnAreaEffectObject()).isNull();

    AreaEffectData zap = GameData.records().areaEffect("Zap");
    assertThat(zap.lifeDurationMs()).isEqualTo(1);
    assertThat(zap.typedDamage().baseDamage()).isEqualTo(75);
    assertThat(zap.typedDamage().towerDamage()).isEqualTo(19);
    assertThat(zap.buff()).isEqualTo("ZapFreeze");
    assertThat(zap.buffTimeMs()).isEqualTo(500);
    assertThat(zap.capBuffTimeToAreaEffectTime()).isFalse();
    assertThat(zap.unmodelledColumns()).isEmpty();

    AreaEffectData rage = GameData.records().areaEffect("Rage");
    assertThat(rage.buff()).isEqualTo("Rage");
    assertThat(rage.buffTimeMs()).isEqualTo(1000);
    assertThat(rage.capBuffTimeToAreaEffectTime()).isTrue();
    assertThat(rage.filter()).isEqualTo("all_friendly_troops");
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
    assertThat(poison.crownTowerDamagePercent()).isEqualTo(-78);
    assertThat(poison.enableStacking()).isTrue();
    assertThat(poison.unmodelledColumns()).isEmpty();

    BuffData rage = GameData.records().buff("Rage");
    assertThat(rage.speedMultiplier()).isEqualTo(130);
    assertThat(rage.hitSpeedMultiplier()).isEqualTo(130);
    assertThat(rage.spawnSpeedMultiplier()).isEqualTo(130);
    assertThat(rage.unmodelledColumns()).isEmpty();

    assertThat(GameData.records().buff("ZapFreeze").hitSpeedMultiplier()).isEqualTo(-100);
    BuffData earthquake = GameData.records().buff("Earthquake");
    assertThat(earthquake.hitTickFromSource()).isTrue();
    assertThat(earthquake.buildingDamagePercent()).isEqualTo(350);
    assertThat(earthquake.unmodelledColumns()).isEmpty();
    assertThat(poison.hitTickFromSource()).isFalse();
    BuffData tornado = GameData.records().buff("Tornado");
    assertThat(tornado.attractPercentage()).isEqualTo(360);
    assertThat(tornado.pushSpeedFactor()).isEqualTo(100);
    assertThat(tornado.pushMassFactor()).isZero();
    assertThat(tornado.lateralPushPercentage()).isZero();
    assertThat(tornado.controlledByParent()).isTrue();
    assertThat(tornado.enableStacking()).isTrue();
    assertThat(tornado.unmodelledColumns()).isEmpty();
    assertThat(GameData.records().buff("SuperArcherTornado").attractMaxAngle()).isEqualTo(90);
    assertThat(GameData.records().areaEffect("Tornado").controlsBuff()).isTrue();
    assertThat(GameData.records().areaEffect("Tornado").unmodelledColumns()).isEmpty();
    assertThat(tornado.attracts()).isTrue();
    assertThat(poison.attracts()).isFalse();
    assertThat(BuffData.builder().name("Sideways").lateralPushPercentage(200).build().attracts())
        .as("a lateral share alone pulls")
        .isTrue();
  }

  @Test
  @DisplayName("an area effect whose buff sets a column not modelled is refused as it is created")
  void aBuffNotModelledIsRefused() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    match.placeAreaEffect(1, "ShieldArea", 11, 0, 3500, 20000, "ShieldArea");
    match.getBattle().step();
    assertThatThrownBy(() -> match.getBattle().step())
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("[Shield]");
  }
}
