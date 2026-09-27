package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.crforge.core.battle.GameData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** An area effect's row as the battle reads it, and the area effects it refuses. */
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
    assertThat(GameData.records().areaEffect("Zap").unmodelledColumns()).contains("Buff");
  }

  @Test
  @DisplayName("an area effect whose row carries a buff is refused as it is created")
  void aBuffIsRefused() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    match.placeAreaEffect(1, "Zap", 11, 0, 3500, 20000, "Zap");
    match.getBattle().step();
    assertThatThrownBy(() -> match.getBattle().step())
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("Buff");
  }
}
