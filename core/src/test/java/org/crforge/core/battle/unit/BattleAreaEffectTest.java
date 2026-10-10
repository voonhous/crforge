/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.crforge.core.battle.Shipped.column;
import static org.crforge.core.battle.Shipped.flag;
import static org.crforge.core.battle.Shipped.number;
import static org.crforge.core.battle.Shipped.row;
import static org.crforge.core.battle.Shipped.text;

import com.fasterxml.jackson.databind.JsonNode;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An area effect's row and its buff's as the battle reads them, and the area effects it refuses.
 */
class BattleAreaEffectTest {

  /** The area effects' table. */
  private static final String AREAS = "area_effect_objects";

  /** The buffs' table. */
  private static final String BUFFS = "character_buffs";

  @Test
  @DisplayName("an area effect is its row's columns, in the row's own units")
  void theRow() {
    GameRow explosionRow = row(AREAS, "MightyMinerExplosion");
    AreaEffectData explosion = GameData.records().areaEffect("MightyMinerExplosion");
    assertThat(explosion.radius()).isEqualTo(number(explosionRow, "Radius"));
    assertThat(explosion.typedDamage().baseDamage())
        .isEqualTo(column(explosionRow, "Damage").get("BaseDamage").asInt());
    // A damage that names no tower damage leaves the tower's to the area's base damage.
    JsonNode explosionDamage = column(explosionRow, "Damage");
    assertThat(explosion.typedDamage().towerDamage())
        .isEqualTo(
            explosionDamage.has("TowerDamage")
                ? explosionDamage.get("TowerDamage").asInt()
                : AreaDamageType.NO_TOWER_DAMAGE);
    assertThat(explosion.pushback()).isEqualTo(number(explosionRow, "Pushback"));
    assertThat(explosion.filter()).isEqualTo(text(explosionRow, "Filter"));
    assertThat(explosion.filterHits()).isTrue();
    assertThat(explosion.unmodelledColumns()).isEmpty();
    assertThat(explosion.buff()).isNull();
    assertThat(explosion.spawnAreaEffectObject()).isNull();

    GameRow zapRow = row(AREAS, "Zap");
    AreaEffectData zap = GameData.records().areaEffect("Zap");
    assertThat(zap.lifeDurationMs()).isEqualTo(number(zapRow, "LifeDuration"));
    assertThat(zap.typedDamage().baseDamage())
        .isEqualTo(column(zapRow, "Damage").get("BaseDamage").asInt());
    assertThat(zap.typedDamage().towerDamage())
        .isEqualTo(column(zapRow, "Damage").get("TowerDamage").asInt());
    assertThat(zap.buff()).isEqualTo(text(zapRow, "Buff")).isNotNull();
    assertThat(zap.buffTimeMs()).isEqualTo(number(zapRow, "BuffTime"));
    assertThat(zap.capBuffTimeToAreaEffectTime())
        .isEqualTo(flag(zapRow, "CapBuffTimeToAreaEffectTime"));
    assertThat(zap.unmodelledColumns()).isEmpty();

    GameRow rageRow = row(AREAS, "Rage");
    AreaEffectData rage = GameData.records().areaEffect("Rage");
    assertThat(rage.buff()).isEqualTo(text(rageRow, "Buff")).isNotNull();
    assertThat(rage.buffTimeMs()).isEqualTo(number(rageRow, "BuffTime"));
    assertThat(rage.capBuffTimeToAreaEffectTime())
        .isEqualTo(flag(rageRow, "CapBuffTimeToAreaEffectTime"));
    assertThat(rage.filter()).isEqualTo(text(rageRow, "Filter")).isNotNull();
    assertThat(rage.spawnAreaEffectObject())
        .isEqualTo(text(rageRow, "SpawnAreaEffectObject"))
        .isNotNull();
    assertThat(rage.unmodelledColumns()).isEmpty();
  }

  @Test
  @DisplayName(
      "a buff is its row's columns, and every column it sets that neither is read nor only shows"
          + " something is listed")
  void theBuffRow() {
    GameRow poisonRow = row(BUFFS, "Poison");
    BuffData poison = GameData.records().buff("Poison");
    assertThat(poison.speedMultiplier()).isEqualTo(number(poisonRow, "SpeedMultiplier"));
    assertThat(poison.damagePerSecond()).isEqualTo(number(poisonRow, "DamagePerSecond"));
    assertThat(poison.hitFrequency()).isEqualTo(number(poisonRow, "HitFrequency"));
    assertThat(poison.crownTowerDamagePercent())
        .isEqualTo(number(poisonRow, "CrownTowerDamagePercent"));
    assertThat(poison.enableStacking()).isEqualTo(flag(poisonRow, "EnableStacking"));
    assertThat(poison.unmodelledColumns()).isEmpty();
    GameRow rageRow = row(BUFFS, "Rage");
    BuffData rage = GameData.records().buff("Rage");
    assertThat(rage.speedMultiplier()).isEqualTo(number(rageRow, "SpeedMultiplier"));
    assertThat(rage.hitSpeedMultiplier()).isEqualTo(number(rageRow, "HitSpeedMultiplier"));
    assertThat(rage.spawnSpeedMultiplier()).isEqualTo(number(rageRow, "SpawnSpeedMultiplier"));
    assertThat(rage.unmodelledColumns()).isEmpty();
    assertThat(GameData.records().buff("ZapFreeze").hitSpeedMultiplier())
        .isEqualTo(number(row(BUFFS, "ZapFreeze"), "HitSpeedMultiplier"));
    GameRow earthquakeRow = row(BUFFS, "Earthquake");
    BuffData earthquake = GameData.records().buff("Earthquake");
    assertThat(earthquake.hitTickFromSource()).isEqualTo(flag(earthquakeRow, "HitTickFromSource"));
    assertThat(earthquake.buildingDamagePercent())
        .isEqualTo(number(earthquakeRow, "BuildingDamagePercent"));
    assertThat(earthquake.unmodelledColumns()).isEmpty();
    assertThat(poison.hitTickFromSource()).isEqualTo(flag(poisonRow, "HitTickFromSource"));
    GameRow tornadoRow = row(BUFFS, "Tornado");
    BuffData tornado = GameData.records().buff("Tornado");
    assertThat(tornado.attractPercentage()).isEqualTo(number(tornadoRow, "AttractPercentage"));
    assertThat(tornado.pushSpeedFactor()).isEqualTo(number(tornadoRow, "PushSpeedFactor"));
    assertThat(tornado.pushMassFactor()).isEqualTo(number(tornadoRow, "PushMassFactor"));
    assertThat(tornado.lateralPushPercentage())
        .isEqualTo(number(tornadoRow, "LateralPushPercentage"));
    assertThat(tornado.controlledByParent()).isEqualTo(flag(tornadoRow, "ControlledByParent"));
    assertThat(tornado.enableStacking()).isEqualTo(flag(tornadoRow, "EnableStacking"));
    assertThat(tornado.unmodelledColumns()).isEmpty();
    assertThat(GameData.records().buff("SuperArcherTornado").attractMaxAngle())
        .isEqualTo(number(row(BUFFS, "SuperArcherTornado"), "AttractMaxAngle"));
    assertThat(GameData.records().areaEffect("Tornado").controlsBuff())
        .isEqualTo(flag(row(AREAS, "Tornado"), "ControlsBuff"));
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
