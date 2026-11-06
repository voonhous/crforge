/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTable;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.EvolutionItem;
import org.crforge.core.battle.unit.CharacterEntity;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.junit.jupiter.api.Test;

/** Building a translated scenario's battle and checking the items of the plays it runs. */
class ReplayBattleTest {

  @Test
  void buildsTheBattleAScenarioGivesWithItsCommandsQueuedBeforeItsFirstStep() {
    GameTables tables = GameTables.loadConfigured();
    ScenarioPlan plan =
        new ReplayScenario(tables)
            .translate(ScenarioItems.fitted(Scenarios.archerQueenAbility(), tables));

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
  void aReplayWhoseMirrorRunsAtAnotherDelayThanTheGamesIsRefusedBeforeItsBattleIsBuilt() {
    GameTables tables = GameTables.loadConfigured();
    ObjectNode scenario = ScenarioItems.fitted(Scenarios.knightThenMirror(), tables);
    // The Mirror stated as given one tick before the tick the game would have given it: its item,
    // which the battle builds as the player's client built it at the giving, would be one no
    // client of the game builds.
    ObjectNode mirror = (ObjectNode) scenario.path("cmd").get(4).path("c");
    int run = mirror.path("t2").asInt();
    mirror.put("t", run - Standard1v1Battle.PLAY_DELAY_TICKS - 1);

    assertThatThrownBy(
            () -> ReplayBattle.build(tables, new ReplayScenario(tables).translate(scenario)))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessageContaining(
            "a command run "
                + (Standard1v1Battle.PLAY_DELAY_TICKS + 1)
                + " ticks after it was given")
        .hasMessageContaining("cmd[4].c.t2=" + run);
  }

  @Test
  void checkingThePlaysThatRanRefusesAnItemOtherThanTheOneTheBattleBuilt() {
    GameTables tables = GameTables.loadConfigured();
    ObjectNode scenario = ScenarioItems.fitted(Scenarios.knightEvolvedThirdPlay(), tables);
    // The third Knight play given with the other evolution field than the one the battle sets.
    ArrayNode commands = (ArrayNode) scenario.path("cmd");
    ObjectNode third = (ObjectNode) commands.get(10).path("c").path("sel");
    int item = third.path("pd").asInt() ^ 1;
    third.put("pd", item);
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
        .hasMessageContaining("cmd[10].c.sel.pd=" + item);
  }

  @Test
  void anEvolutionSlotsCardWhoseEvolvedRowTheExcludedEvolutionsListIsStillPlayedEvolved() {
    GameTables tables = GameTables.loadConfigured();
    assumeTrue(
        ReplayFormat.orShared(tables.version()).excludedEvolutions(),
        "the replays of data version " + tables.version() + " list no excluded evolutions");
    // The Knight's evolved row, read from the tables: its first EvolvedSpells entry in the evolved
    // cards' table.
    GameRow knight = ScenarioItems.card(tables, "Knight");
    GameTable evolvedCards = tables.table("spells_evolved");
    GameRow evolved =
        knight.strings("EvolvedSpells").stream()
            .filter(evolvedCards::has)
            .map(evolvedCards::row)
            .findFirst()
            .orElseThrow();
    int evolvedId =
        Integer.parseInt(evolvedCards.id()) * ReplayScenario.IDS_PER_TABLE + evolved.index();
    // The Knight in side 0's evolution slot, played three times, with its evolved row listed in
    // both sides' excluded evolutions. The game's client builds the third play evolved all the
    // same: the list is read only when an evolved Mirror repeats a card.
    ObjectNode scenario = ScenarioItems.fitted(Scenarios.knightEvolvedThirdPlay(), tables);
    for (JsonNode data : scenario.path("battle").path("hbd")) {
      ((ObjectNode) data).putArray(ReplayFormat.EXCLUDED_EVOLUTIONS_FIELD).add(evolvedId);
    }
    ScenarioPlan plan = new ReplayScenario(tables).translate(scenario);
    Standard1v1Battle battle = ReplayBattle.build(tables, plan);

    int checked = 0;
    for (int tick = 0; tick < 1500; tick++) {
      battle.getBattle().step();
      checked = ReplayBattle.checkItems(battle, plan, checked);
    }
    assertThat(checked).isEqualTo(plan.plays().size());
    // The third Knight play, command 10.
    List<Standard1v1Battle.Play> third =
        battle.getPlays().stream().filter(play -> play.name().equals("cmd10")).toList();
    assertThat(third).hasSize(1);
    assertThat(third.get(0).evolution().field()).isEqualTo(EvolutionItem.EVOLVED);
    assertThat(third.get(0).units())
        .extracting(CharacterEntity::getData)
        .extracting(data -> data.name())
        .containsOnly(evolved.string("SummonCharacter"));
  }
}
