package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.GameData;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The swap in {@code golemite_convert}, looked at on the tick it happens: a crazy-arena baby
 * golemite placed directly stores its hit points' share of its maximum 70 ticks later, then turns
 * into an Elixir Golem and is healed back to that share of the new maximum, all in one phase-1
 * pass. {@link BattleActionSpawnRunTest} holds the whole run tick for tick; this holds what the
 * reference records of the swap itself.
 */
class BattleChangeDataRunTest {

  private static final String REFERENCE = "/pathfinding/golden/golemite_convert.json";

  /** The tick the swap runs on. */
  private static final int SWAP_TICK = 70;

  @Test
  @Disabled("golden recorded on 14.593.1; awaiting decision")
  @DisplayName(
      "the golemite takes the Elixir Golem's row, keeps its hit points and is healed to its share"
          + " of the new maximum")
  void theSwap() {
    JsonNode reference = BattleMusketeerRunTest.load(REFERENCE);
    Standard1v1Battle match =
        new Standard1v1Battle(GameData.tables(), reference.get("tower_level").asInt());
    Battle battle = match.getBattle();
    CharacterEntity unit = BattleTowerRunTest.deployAll(match, reference).get(0);
    for (int tick = 0; tick < SWAP_TICK; tick++) {
      battle.step();
    }
    assertThat(unit.getData().name()).isEqualTo("ElixirGolem4_crazy_babyGolemite");
    assertThat(unit.getHitPoints().getHitPoints()).isEqualTo(142);

    battle.step();

    JsonNode swap = null;
    for (JsonNode a : reference.get("actions")) {
      if (a.get("event").asText().equals("change_data")) {
        swap = a;
      }
    }
    assertThat(swap).isNotNull();
    assertThat(unit.getData().name()).isEqualTo(swap.get("row").get(1).asText());
    assertThat(unit.getHitPoints().getMaximum()).isEqualTo(swap.get("max").get(1).asInt());
    assertThat(unit.getView().getCollisionRadius()).isEqualTo(swap.get("radius").get(1).asInt());
    assertThat(unit.getSpeedBudget()).isEqualTo(swap.get("speed").asInt());
    assertThat(unit.variable(match.getWorld().declaredVariable("ElixirGolem2_crazy_maxHP")))
        .isEqualTo(39);
    assertThat(unit.getHitPoints().getHitPoints()).as("healed to its share").isEqualTo(297);
  }
}
