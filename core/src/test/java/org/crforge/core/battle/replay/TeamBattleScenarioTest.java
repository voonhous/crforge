/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.LadderMatch;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Reading a team battle's scenario: four avatars, four decks and four players' data under a game
 * mode row of two players a team, each command named by its avatar's account id, and the battle
 * then refused, since no battle mode models a team match.
 *
 * <p>The scenarios are synthetic: {@link Scenarios#knight()} with two more players of the same deck
 * and made-up accounts, the game mode and the location read from the tables by name.
 */
class TeamBattleScenarioTest {

  private static GameTables tables;

  @BeforeAll
  static void loadTables() {
    tables = GameTables.loadConfigured();
  }

  /** The data id of a table's row: the table's id times a million plus the row's index. */
  private static int dataId(String table, String row) {
    return Integer.parseInt(tables.table(table).id()) * ReplayScenario.IDS_PER_TABLE
        + tables.table(table).row(row).index();
  }

  /** The team location of the arena a test's battle names, read from the arenas table. */
  private static String teamLocation() {
    GameRow arena = tables.table(ScenarioWriter.ARENAS).row(Scenarios.arena(tables));
    return arena.string("TeamVsTeamLocation");
  }

  /**
   * The {@link Scenarios#knight()} battle as a team battle: under the team Ladder row, on the
   * arena's team location, with avatars 2 and 3 of the same deck (accounts 0/3 and 0/4) and player
   * data for all four; its one command is still side 0's Knight.
   */
  private static ObjectNode teamKnight() {
    ObjectNode scenario = Scenarios.knight();
    ObjectNode battle = (ObjectNode) scenario.path("battle");
    battle.put("gamemode", dataId(ReplayScenario.GAME_MODES, ReplayScenario.LADDER_TEAM_VS_TEAM));
    battle.put("location", dataId("locations", teamLocation()));
    for (int side = 2; side < 4; side++) {
      battle.set("deck" + side, battle.path("deck0").deepCopy());
      ObjectNode avatar = battle.path("avatar0").deepCopy();
      avatar.put("accountID.lo", side + 1);
      avatar.put("name", "Offline " + side);
      battle.set("avatar" + side, avatar);
      ((ArrayNode) battle.path("hbd")).add(battle.path("hbd").get(0).deepCopy());
    }
    return scenario;
  }

  /** Adds a Knight play by the avatar of an account's low word, copied from command 0. */
  private static void addKnight(ObjectNode scenario, int accountLo, int runTick) {
    ObjectNode command = scenario.path("cmd").get(0).deepCopy();
    ObjectNode body = (ObjectNode) command.path("c");
    body.put("idLo", accountLo);
    body.put("t", runTick - 20);
    body.put("t2", runTick);
    ((ArrayNode) scenario.path("cmd")).add(command);
  }

  @Test
  void readsTheFourAvatarsDecksAndPlayersData() {
    ObjectNode scenario = ScenarioItems.fitted(teamKnight(), tables);
    ScenarioPlan plan = new ReplayScenario(tables).read(scenario);

    assertThat(plan.players()).isEqualTo(4);
    assertThat(plan.decks()).hasSize(4);
    for (List<String> deck : plan.decks()) {
      assertThat(deck).isEqualTo(plan.decks().get(0));
    }
    assertThat(plan.deckLevels()).hasSize(4);
    assertThat(plan.slotFlags()).hasSize(4);
    assertThat(plan.towers()).hasSize(4);
    assertThat(plan.arenas()).hasSize(4);
    assertThat(plan.accounts())
        .containsExactly(new int[] {0, 1}, new int[] {0, 2}, new int[] {0, 3}, new int[] {0, 4});
    assertThat(plan.playerDataChoices()).hasSize(4);
    assertThat(plan.excludedEvolutions()).hasSize(4);
    // Team of a side: its parity, sides 0 and 2 against sides 1 and 3; partners 0-2 and 1-3.
    assertThat(List.of(0, 1, 2, 3)).map(plan::team).containsExactly(0, 1, 0, 1);
    assertThat(List.of(0, 1, 2, 3)).map(plan::partner).containsExactly(2, 3, 0, 1);
  }

  @Test
  void aBattleOfOnePlayerASideHasTwoPlayersAndNoPartners() {
    ScenarioPlan plan =
        new ReplayScenario(tables).translate(ScenarioItems.fitted(Scenarios.knight(), tables));

    assertThat(plan.players()).isEqualTo(2);
    assertThat(List.of(0, 1)).map(plan::team).containsExactly(0, 1);
    assertThat(List.of(0, 1)).map(plan::partner).containsExactly(-1, -1);
  }

  @Test
  void mapsEachCommandToTheAvatarOfItsAccount() {
    ObjectNode scenario = teamKnight();
    addKnight(scenario, 4, 240);
    addKnight(scenario, 3, 260);
    addKnight(scenario, 2, 280);
    ScenarioPlan plan = new ReplayScenario(tables).read(ScenarioItems.fitted(scenario, tables));

    assertThat(plan.plays()).extracting(ScenarioPlan.Play::side).containsExactly(0, 3, 2, 1);
  }

  @Test
  void refusesFourAvatarsUnderARowOfOnePlayerASide() {
    ObjectNode scenario = ScenarioItems.fitted(teamKnight(), tables);
    ((ObjectNode) scenario.path("battle"))
        .put("gamemode", dataId(ReplayScenario.GAME_MODES, LadderMatch.GAME_MODE));

    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOfSatisfying(
            UnsupportedScenarioException.class,
            e -> {
              assertThat(e.feature())
                  .startsWith("a battle of 4 avatars and 4 decks, where the game mode ")
                  .contains(LadderMatch.GAME_MODE)
                  .endsWith("2 players");
              assertThat(e.input())
                  .isEqualTo("battle.gamemode=" + scenario.path("battle").path("gamemode"));
            });
  }

  @Test
  void refusesTwoAvatarsUnderTheTeamRow() {
    ObjectNode scenario = ScenarioItems.fitted(Scenarios.knight(), tables);
    ((ObjectNode) scenario.path("battle"))
        .put("gamemode", dataId(ReplayScenario.GAME_MODES, ReplayScenario.LADDER_TEAM_VS_TEAM));

    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOfSatisfying(
            UnsupportedScenarioException.class,
            e ->
                assertThat(e.feature())
                    .startsWith("a battle of 2 avatars and 2 decks, where the game mode ")
                    .contains(ReplayScenario.LADDER_TEAM_VS_TEAM)
                    .endsWith("4 players"));
  }

  @Test
  void refusesPlayersDataOfAnotherCountThanThePlayers() {
    ObjectNode scenario = ScenarioItems.fitted(teamKnight(), tables);
    ((ArrayNode) scenario.path("battle").path("hbd")).remove(3);

    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOfSatisfying(
            UnsupportedScenarioException.class,
            e -> {
              assertThat(e.feature()).isEqualTo("players' data of 3 entries, not one per side");
              assertThat(e.input()).isEqualTo("battle.hbd");
            });
  }

  @Test
  void refusesTwoAvatarsOfOneAccount() {
    ObjectNode scenario = ScenarioItems.fitted(teamKnight(), tables);
    ((ObjectNode) scenario.path("battle").path("avatar3")).put("accountID.lo", 2);

    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOfSatisfying(
            UnsupportedScenarioException.class,
            e ->
                assertThat(e.feature())
                    .isEqualTo("two sides of one account, whose commands name no side"));
  }

  @Test
  void refusesTheTeamBattleOnceReadNamingTheMissingTeamMatch() {
    ObjectNode scenario = teamKnight();
    addKnight(scenario, 4, 240);
    ScenarioItems.fitted(scenario, tables);
    JsonNode gameMode = scenario.path("battle").path("gamemode");

    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOfSatisfying(
            UnsupportedScenarioException.class,
            e -> {
              assertThat(e.feature())
                  .contains(ReplayScenario.LADDER_TEAM_VS_TEAM)
                  .contains(teamLocation())
                  .endsWith(ReplayScenario.NO_TEAM_MATCH);
              assertThat(e.input()).isEqualTo("battle.gamemode=" + gameMode);
            });
    // A survey reads the whole battle with nothing else to refuse: the team match is the one
    // refusal, listed last.
    assertThat(new ReplayScenario(tables).survey(scenario))
        .singleElement()
        .satisfies(refusal -> assertThat(refusal.feature()).endsWith(ReplayScenario.NO_TEAM_MATCH));
  }

  @Test
  void surveysATeamBattleOfAnUnregisteredTeamRowWithItsModeAndTheTeamMatchRefused() {
    // A row of two players a team that no registry entry admits: the first such row of the
    // tables, read by its Players column.
    String unregistered =
        tables.table(ReplayScenario.GAME_MODES).rows().stream()
            .filter(row -> ReplayScenario.TEAM_PLAYERS.equals(row.string(ReplayScenario.PLAYERS)))
            .map(GameRow::name)
            .filter(name -> !ReplayScenario.TEAM_MODES.containsKey(name))
            .findFirst()
            .orElseThrow();
    ObjectNode scenario = ScenarioItems.fitted(teamKnight(), tables);
    ((ObjectNode) scenario.path("battle"))
        .put("gamemode", dataId(ReplayScenario.GAME_MODES, unregistered));

    List<ReplayScenario.Refusal> refusals = new ReplayScenario(tables).survey(scenario);

    assertThat(refusals).isNotEmpty();
    assertThat(refusals.get(0).feature()).startsWith("the game mode " + unregistered);
    assertThat(refusals.get(refusals.size() - 1).feature())
        .startsWith("a team battle of game mode " + unregistered + ", 4 players on location ")
        .endsWith(ReplayScenario.NO_TEAM_MATCH);
  }

  @Test
  void theReplayBattleRefusesAPlanOfFourPlayers() {
    ScenarioPlan plan = new ReplayScenario(tables).read(ScenarioItems.fitted(teamKnight(), tables));

    assertThatThrownBy(() -> ReplayBattle.build(tables, plan))
        .isInstanceOfSatisfying(
            UnsupportedScenarioException.class,
            e -> assertThat(e.feature()).isEqualTo(ReplayScenario.NO_TEAM_MATCH));
  }
}
