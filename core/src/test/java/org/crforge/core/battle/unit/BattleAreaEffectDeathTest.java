package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.GameData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plays {@code area_effect_death}: a Rage Barbarian whose death leaves an area effect, created at
 * its point inside the killing hit and admitted at the tick's closing cleanup, whose starting
 * action spawns the Rage Barbarian's bottle on the next tick. The bottle has no hit points, which a
 * spawn does not take yet, so the run is held through the death and the spawn is refused.
 */
class BattleAreaEffectDeathTest {

  private static final String REFERENCE = "/pathfinding/golden/area_effect_death.json";

  /** The tick the Rage Barbarian dies and its area effect is created and admitted. */
  private static final int DEATH_TICK = 220;

  @Test
  @DisplayName(
      "a death leaves its area effect at its point, admitted at the cleanup, whose starting action"
          + " runs on the next tick")
  void theDeathLeavesItsAreaEffect() {
    JsonNode reference = BattleMusketeerRunTest.load(REFERENCE);
    Standard1v1Battle match =
        new Standard1v1Battle(GameData.tables(), reference.get("tower_level").asInt());
    Battle battle = match.getBattle();
    CharacterEntity unit = BattleTowerRunTest.deployAll(match, reference).get(0);
    int[] currentTick = {-1};
    List<String> areaEffects = new ArrayList<>();
    match.getWorld().addObserver(BattleActionSpawnRunTest.areaEffectLog(currentTick, areaEffects));
    List<JsonNode> records = BattleMusketeerRunTest.records(reference);

    for (int i = 0; i < records.size(); i++) {
      JsonNode record = records.get(i);
      int tick = record.get("tick").asInt();
      currentTick[0] = tick;
      battle.step();
      String where = "reference tick " + tick;
      assertThat(unit.getView().getX()).as("%s x", where).isEqualTo(record.get("x").asInt());
      assertThat(unit.getView().getY()).as("%s y", where).isEqualTo(record.get("y").asInt());
      assertThat(unit.getHitPoints().getHitPoints())
          .as("%s the unit's own hit points", where)
          .isEqualTo(record.get("own_hp").asInt());
    }
    assertThat(currentTick[0]).isEqualTo(DEATH_TICK);
    List<String> expected =
        BattleActionSpawnRunTest.expectedAreaEffects(reference).stream()
            .filter(line -> Integer.parseInt(line.split(" ")[0]) <= DEATH_TICK)
            .toList();
    assertThat(areaEffects)
        .as("created and admitted at the death")
        .containsExactlyElementsOf(expected);

    currentTick[0] = DEATH_TICK + 1;
    assertThatThrownBy(battle::step)
        .as("its starting action spawns the bottle, which has no hit points")
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("without hit points");
  }
}
