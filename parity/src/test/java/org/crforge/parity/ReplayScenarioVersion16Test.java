package org.crforge.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Replays of the game client whose data version is 16.402.18, read against that version's tables:
 * its command types, the fields its replays write beyond 14.593.1's, and each side's king level
 * read from its player data. Skipped without tables of 16.402.18 ({@link Version16Tables}).
 */
class ReplayScenarioVersion16Test {

  private static GameTables tables;

  @BeforeAll
  static void loadTables() {
    tables = Version16Tables.load();
  }

  @Test
  void readsEveryFieldOfTheVersionsReplay() {
    assertThat(new ReplayScenario(tables).survey(Scenarios.knightOfVersion16())).isEmpty();
  }

  @Test
  void translatesThePlayAndBuildsEachKingAtItsPlayerDatasKingLevel() {
    ReplayScenario mapping = new ReplayScenario(tables);
    ScenarioPlan plan = mapping.translate(Scenarios.knightOfVersion16());

    // Side 0's kt 15, side 1's kt 16; the princess towers at level index 0 stand at level 1.
    assertThat(plan.towers())
        .containsExactly(
            new Standard1v1Battle.Towers("King_PrincessTowers", 15, 1),
            new Standard1v1Battle.Towers("King_PrincessTowers", 16, 1));
    // The Knight's item carries its cosmetic field, 2, which is no battle input.
    assertThat(plan.plays())
        .containsExactly(
            new ScenarioPlan.Play(
                0, 200, 220, 0, "Knight", 1, 3500, 14000, 0x30440000, null, null));
    // Side 1's avatar leaves out the high word of its account id: 0, as its commands give it.
    assertThat(plan.accounts().get(1)).containsExactly(0, 2);
    assertThat(plan.playerDataChoices()).containsExactly(1, 1);
    assertThat(mapping.mapping())
        .containsEntry("cmd[i].c.sid", "pinned: -1")
        .containsEntry("srq", "pinned: []")
        .containsEntry("rrb", "pinned: false")
        .containsKeys("evt", "battle.hbd[i].kt", "battle.avatarN.clan_name", "battle.deckN.hdr");
    assertThat(mapping.mapping().get("cmd[i].ct")).startsWith("consumed: 153, a card play, or 189");
  }

  @Test
  void readsAnAbilityCommandOfType189() {
    ObjectNode scenario = Scenarios.knightOfVersion16();
    Scenarios.addAbility((ArrayNode) scenario.path("cmd"), 350, 1, 5000006);
    ((ObjectNode) scenario.path("cmd").get(1)).put("ct", 189);

    ScenarioPlan plan = new ReplayScenario(tables).translate(scenario);

    assertThat(plan.abilities()).containsExactly(new ScenarioPlan.Ability(1, 330, 350, 0, 5000006));
  }

  @Test
  void refusesTheCommandTypesOfVersion14_593_1() {
    ObjectNode scenario = Scenarios.knightOfVersion16();
    Scenarios.addAbility((ArrayNode) scenario.path("cmd"), 350, 1, 5000006);
    ((ObjectNode) scenario.path("cmd").get(0)).put("ct", 124);

    List<ReplayScenario.Refusal> refusals = new ReplayScenario(tables).survey(scenario);

    // 124 and 178, the play and the ability command of 14.593.1, are neither in this version.
    assertThat(refusals)
        .containsExactly(
            new ReplayScenario.Refusal("the command type 124", "cmd[0].ct"),
            new ReplayScenario.Refusal("the command type 178", "cmd[1].ct"));
  }

  @Test
  void refusesAKingLevelOutsideTheKingsLevels() {
    ObjectNode scenario = Scenarios.knightOfVersion16();
    ((ObjectNode) scenario.path("battle").path("hbd").get(1)).put("kt", 17);

    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessageContaining("battle.hbd[1].kt=17");
  }

  @Test
  void refusesPlayerDataWithoutAKingLevelOrWithAnotherField() {
    ObjectNode scenario = Scenarios.knightOfVersion16();
    ((ObjectNode) scenario.path("battle").path("hbd").get(0)).put("xyz", 1);
    ((ObjectNode) scenario.path("battle").path("hbd").get(1)).remove("kt");

    List<ReplayScenario.Refusal> refusals = new ReplayScenario(tables).survey(scenario);

    assertThat(refusals)
        .containsExactly(
            new ReplayScenario.Refusal("the field xyz, which has no mapping", "battle.hbd[0].xyz"),
            new ReplayScenario.Refusal("the scenario has no kt", "the reading stops here"));
  }

  @Test
  void refusesAnEventOfAnotherTypeOrWithAnotherField() {
    ObjectNode scenario = Scenarios.knightOfVersion16();
    ArrayNode events = (ArrayNode) scenario.path("evt");
    ((ObjectNode) events.get(0)).put("type", 2);
    ((ObjectNode) events.get(1)).put("xy", 0);

    List<ReplayScenario.Refusal> refusals = new ReplayScenario(tables).survey(scenario);

    assertThat(refusals)
        .extracting(ReplayScenario.Refusal::input)
        .containsExactly("evt[0].type=2", "evt[1].xy");
  }

  @Test
  void refusesAnotherValueOfAPinnedFieldOfTheVersion() {
    ObjectNode scenario = Scenarios.knightOfVersion16();
    scenario.putArray("srq").add(1);
    ObjectNode battle = (ObjectNode) scenario.path("battle");
    battle.put("seb", true);
    battle.put("cardlvlmin", 1);
    battle.put("arena", 54000001);
    ((ObjectNode) battle.path("avatar0")).put("npc", true);

    List<ReplayScenario.Refusal> refusals = new ReplayScenario(tables).survey(scenario);

    assertThat(refusals)
        .extracting(ReplayScenario.Refusal::input)
        .containsExactly("srq=[1]", "arena=54000001", "cardlvlmin=1", "seb=true", "npc=true");
  }

  @Test
  void refusesAFieldOnlyVersion16_402_18sReplaysWriteInAReplayOfVersion14_593_1() {
    // The 14.593.1 tables read a replay by 14.593.1's fields: kt is a field with no mapping there.
    GameTables shared = GameTables.loadConfigured();
    ObjectNode scenario = Scenarios.knight();
    ((ObjectNode) scenario.path("battle").path("hbd").get(0)).put("kt", 15);
    ((ObjectNode) scenario.path("battle").path("deck0").path("sp").get(0)).put("pr", 2);

    assertThat(new ReplayScenario(shared).survey(scenario))
        .extracting(ReplayScenario.Refusal::input)
        .containsExactly(
            "battle.hbd={\"em\":{\"oe\":[],\"de\":[]},\"kt\":15}", "battle.deck0.sp.pr");
  }
}
