package org.crforge.core.battle.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.junit.jupiter.api.Test;

/** Building a translated scenario's battle and checking the items of the plays it runs. */
class ReplayBattleTest {

  @Test
  void buildsTheBattleAScenarioGivesWithItsCommandsQueuedBeforeItsFirstStep() {
    GameTables tables = GameTables.loadConfigured();
    ScenarioPlan plan = new ReplayScenario(tables).translate(Scenarios.archerQueenAbility());

    Standard1v1Battle battle = ReplayBattle.build(tables, plan);

    assertThat(battle.getBattle().getTick()).isZero();
    assertThat(battle.getMatch()).isNotNull();
    int checked = 0;
    for (int tick = 0; tick < 360; tick++) {
      battle.getBattle().step();
      checked = ReplayBattle.checkItems(battle, plan, checked);
    }
    assertThat(checked).isEqualTo(1);
    assertThat(battle.getPlays()).extracting(Standard1v1Battle.Play::name).containsExactly("cmd0");
    assertThat(battle.getAbilityUses())
        .extracting(Standard1v1Battle.AbilityUse::name)
        .containsExactly("cmd1");
  }

  @Test
  void checkingThePlaysThatRanRefusesAnItemOtherThanTheOneTheBattleBuilt() {
    GameTables tables = GameTables.loadConfigured();
    ObjectNode scenario = Scenarios.knightEvolvedThirdPlay();
    // The third Knight play given without its evolution field, which the battle sets.
    ArrayNode commands = (ArrayNode) scenario.path("cmd");
    ((ObjectNode) commands.get(10).path("c").path("sel")).put("pd", 0x30480180);
    ScenarioPlan plan = new ReplayScenario(tables).translate(scenario);
    Standard1v1Battle battle = ReplayBattle.build(tables, plan);

    assertThatThrownBy(
            () -> {
              int checked = 0;
              for (int tick = 0; tick < 1500; tick++) {
                battle.getBattle().step();
                checked = ReplayBattle.checkItems(battle, plan, checked);
              }
            })
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessageContaining("cmd[10].c.sel.pd=" + 0x30480180);
  }
}
