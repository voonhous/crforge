package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An object handed over to the holder already removable never enters the live list: the cleanup
 * removes every removable object waiting to be admitted before it folds the rest in. The Goblin
 * Drill's area object, made as the building surfaces and spent in the update that made it, is told
 * of nothing and listed on no step.
 */
class BattleSpentAreaTest {

  @Test
  @DisplayName(
      "the Goblin Drill's area object, made and spent as the drill surfaces, is never in the live"
          + " list")
  void aSpentAreaIsNeverListed() {
    Standard1v1Battle match =
        new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
    List<AreaEffectEntity> made = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void areaEffectCreated(
                  int tick, AreaEffectEntity areaEffect, String how, String source) {
                made.add(areaEffect);
              }
            });
    match.play(
        0, GameData.card("GoblinDrill"), Standard1v1Battle.DEFAULT_LEVEL, 0, 3000, 14000, "Drill");
    boolean surfaced = false;
    for (int step = 0; step < 120; step++) {
      match.getBattle().step();
      for (BattleEntity entity : match.getBattle().getHolder().entities()) {
        assertThat(entity).as("step %d", step).isNotInstanceOf(AreaEffectEntity.class);
        if (entity instanceof WorldEntity w && w.getData().name().equals("GoblinDrill")) {
          surfaced = true;
        }
      }
    }
    assertThat(surfaced).isTrue();
    assertThat(made).hasSize(1);
    assertThat(made.get(0).getData().name()).isEqualTo("GoblinDrillDamage");
  }
}
