package org.crforge.core.battle.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Replays with every field the game client writes, read against the configured tables: its command
 * types, the fields its replays write beyond the shape every version's replays share, and each
 * side's king level read from its player data.
 */
class ReplayScenarioEveryFieldTest {

  private static GameTables tables;

  @BeforeAll
  static void loadTables() {
    tables = GameData.tables();
  }

  /** A scenario with its plays' items fitted to the tables: the costs and levels of their rows. */
  private static ObjectNode fit(ObjectNode scenario) {
    return ScenarioItems.fitted(scenario, tables);
  }

  /** The princess towers' level at level index 0: their rarity's RelativeLevel plus 1. */
  private static int princessLevel() {
    String rarity =
        tables.table("support_cards").row("King_PrincessTowers").columns().get("Rarity").asText();
    return ScenarioItems.relativeLevel(tables, "support_rarities", rarity) + 1;
  }

  /** The Knight's level at level index 0: its rarity's RelativeLevel plus 1. */
  private static int knightLevel() {
    return ScenarioItems.levelField(tables, ScenarioItems.card(tables, "Knight"), 0) + 1;
  }

  @Test
  void readsEveryFieldOfTheVersionsReplay() {
    assertThat(new ReplayScenario(tables).survey(fit(Scenarios.knightWithEveryField()))).isEmpty();
  }

  @Test
  void translatesThePlayAndBuildsEachKingAtItsPlayerDatasKingLevel() {
    ReplayScenario mapping = new ReplayScenario(tables);
    ObjectNode scenario = fit(Scenarios.knightWithEveryField());
    ScenarioPlan plan = mapping.translate(scenario);

    // Side 0's kt 15, side 1's kt 16; the princess towers at level index 0 stand at their first
    // level.
    assertThat(plan.towers())
        .containsExactly(
            new Standard1v1Battle.Towers("King_PrincessTowers", 15, princessLevel()),
            new Standard1v1Battle.Towers("King_PrincessTowers", 16, princessLevel()));
    // The Knight's item carries its cosmetic field, 2, which is no battle input.
    int item = scenario.path("cmd").get(0).path("c").path("sel").path("pd").asInt();
    assertThat((item >>> 17) & 0x3).isEqualTo(2);
    assertThat(plan.plays())
        .containsExactly(
            new ScenarioPlan.Play(
                0, 200, 220, 0, "Knight", knightLevel(), 3500, 14000, item, null, null));
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
    ObjectNode scenario = fit(Scenarios.knightWithEveryField());
    Scenarios.addAbility((ArrayNode) scenario.path("cmd"), 350, 1, 5000006);
    ((ObjectNode) scenario.path("cmd").get(1)).put("ct", 189);

    ScenarioPlan plan = new ReplayScenario(tables).translate(scenario);

    assertThat(plan.abilities()).containsExactly(new ScenarioPlan.Ability(1, 330, 350, 0, 5000006));
  }

  @Test
  void refusesTheCommandTypesOfAnOlderClient() {
    ObjectNode scenario = fit(Scenarios.knightWithEveryField());
    Scenarios.addAbility((ArrayNode) scenario.path("cmd"), 350, 1, 5000006);
    ((ObjectNode) scenario.path("cmd").get(0)).put("ct", 124);
    ((ObjectNode) scenario.path("cmd").get(1)).put("ct", 178);

    List<ReplayScenario.Refusal> refusals = new ReplayScenario(tables).survey(scenario);

    // 124 and 178, the play and the ability command of 14.593.1, are neither in this version.
    assertThat(refusals)
        .containsExactly(
            new ReplayScenario.Refusal("the command type 124", "cmd[0].ct"),
            new ReplayScenario.Refusal("the command type 178", "cmd[1].ct"));
  }

  @Test
  void refusesAKingLevelOutsideTheKingsLevels() {
    // The king's levels are 1 to its Common rarity's LevelCount: one past them is refused.
    int pastTheLast =
        ScenarioItems.number(tables.table("rarities").row("Common"), "LevelCount") + 1;
    ObjectNode scenario = fit(Scenarios.knightWithEveryField());
    ((ObjectNode) scenario.path("battle").path("hbd").get(1)).put("kt", pastTheLast);

    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessageContaining("battle.hbd[1].kt=" + pastTheLast);
  }

  @Test
  void refusesPlayerDataWithoutAKingLevelOrWithAnotherField() {
    ObjectNode scenario = fit(Scenarios.knightWithEveryField());
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
    ObjectNode scenario = fit(Scenarios.knightWithEveryField());
    ArrayNode events = (ArrayNode) scenario.path("evt");
    ((ObjectNode) events.get(0)).put("type", 2);
    ((ObjectNode) events.get(1)).put("xy", 0);

    List<ReplayScenario.Refusal> refusals = new ReplayScenario(tables).survey(scenario);

    assertThat(refusals)
        .extracting(ReplayScenario.Refusal::input)
        .containsExactly("evt[0].type=2", "evt[1].xy");
  }

  @Test
  void readsAStickerEventAsNoBattleInput() {
    ObjectNode scenario = fit(Scenarios.knightWithEveryField());
    ObjectNode withoutStickers = scenario.deepCopy();
    ArrayNode kept = withoutStickers.putArray("evt");
    for (JsonNode event : scenario.path("evt")) {
      if (event.path("type").asInt() != 10) {
        kept.add(event.deepCopy());
      }
    }
    assertThat(kept.size()).isLessThan(scenario.path("evt").size());
    ReplayScenario mapping = new ReplayScenario(tables);

    // A sticker (type 10) is read like the other carried events: the battle plays the same as
    // without it.
    assertThat(mapping.survey(scenario)).isEmpty();
    ScenarioPlan plan = mapping.translate(scenario);
    assertThat(plan)
        .usingRecursiveComparison()
        .isEqualTo(new ReplayScenario(tables).translate(withoutStickers));
    assertThat(mapping.mapping().get("evt")).contains("[1, 3, 5, 10]");
  }

  @Test
  void refusesTheEventTypesBesideTheCarriedOnes() {
    ObjectNode scenario = fit(Scenarios.knightWithEveryField());
    ArrayNode events = (ArrayNode) scenario.path("evt");
    ((ObjectNode) events.get(0)).put("type", 9);
    ((ObjectNode) events.get(1)).put("type", 11);

    List<ReplayScenario.Refusal> refusals = new ReplayScenario(tables).survey(scenario);

    assertThat(refusals)
        .extracting(ReplayScenario.Refusal::input)
        .containsExactly("evt[0].type=9", "evt[1].type=11");
  }

  @Test
  void carriesTheArenaOfAnyTrophyArena() {
    // A replay from another trophy arena's TV channel: the arena names the players' trophy arena,
    // which sets no battle input; the map is the location's.
    ObjectNode scenario = fit(Scenarios.knightWithEveryField());
    ObjectNode battle = (ObjectNode) scenario.path("battle");
    battle.put("arena", 54000020);
    ((ObjectNode) battle.path("avatar0")).put("arena", 54000020);
    ((ObjectNode) battle.path("avatar1")).put("arena", 54000020);
    ReplayScenario mapping = new ReplayScenario(tables);

    assertThat(mapping.survey(scenario)).isEmpty();
    mapping.translate(scenario);
    assertThat(mapping.mapping())
        .containsEntry("battle.arena", ReplayScenario.ARENA_CARRIED)
        .containsEntry("battle.avatarN.arena", ReplayScenario.ARENA_CARRIED)
        .doesNotContainKey("arena");
  }

  @Test
  void refusesAnotherValueOfAPinnedFieldOfTheVersion() {
    ObjectNode scenario = fit(Scenarios.knightWithEveryField());
    scenario.putArray("srq").add(1);
    ObjectNode battle = (ObjectNode) scenario.path("battle");
    battle.put("seb", true);
    battle.put("cardlvlmin", 1);
    ((ObjectNode) battle.path("avatar0")).put("npc", true);

    List<ReplayScenario.Refusal> refusals = new ReplayScenario(tables).survey(scenario);

    assertThat(refusals)
        .extracting(ReplayScenario.Refusal::input)
        .containsExactly("srq=[1]", "cardlvlmin=1", "seb=true", "npc=true");
  }

  @Test
  void readsACaseGeneratedForTheVersionByTheFieldsOfItsGeneratedCases() {
    ReplayScenario mapping = new ReplayScenario(tables, ScenarioShape.GENERATED);
    ObjectNode scenario = fit(Scenarios.generatedKnight());

    assertThat(mapping.survey(scenario)).isEmpty();
    ScenarioPlan plan = mapping.translate(scenario);

    // No player data gives a king level: each king stands at level 1, the princess towers at
    // level index 0 at their first level.
    assertThat(plan.towers())
        .containsExactly(
            new Standard1v1Battle.Towers("King_PrincessTowers", 1, princessLevel()),
            new Standard1v1Battle.Towers("King_PrincessTowers", 1, princessLevel()));
    int item = scenario.path("cmd").get(0).path("c").path("sel").path("pd").asInt();
    assertThat(plan.plays())
        .containsExactly(
            new ScenarioPlan.Play(
                0, 200, 220, 0, "Knight", knightLevel(), 3500, 14000, item, null, null));
    assertThat(plan.accounts()).containsExactly(new int[] {0, 1}, new int[] {0, 2});
    assertThat(plan.playerDataChoices()).containsExactly(1, 1);
    assertThat(mapping.mapping())
        .containsEntry("battle.arena", ReplayScenario.ARENA_CARRIED)
        .containsEntry("expLevel", "pinned: 1")
        .containsEntry("evt", "pinned: []")
        .doesNotContainKeys("srq", "srs", "cardlvlmin", "battle.hbd[i].kt");
    assertThat(mapping.mapping().get("cmd[i].ct")).startsWith("consumed: 153, a card play, or 189");
  }

  @Test
  void refusesACaseGeneratedForTheVersionReadAsAReplay() {
    // A replay of the version must hold the request lists: a generated case is read as one only
    // when the caller names its shape.
    assertThat(new ReplayScenario(tables).survey(fit(Scenarios.generatedKnight())))
        .containsExactly(
            new ReplayScenario.Refusal("the scenario has no srq", "the reading stops here"));
  }

  @Test
  void refusesInAGeneratedCaseTheFieldsOnlyTheVersionsReplaysWrite() {
    ObjectNode scenario = fit(Scenarios.generatedKnight());
    scenario.putArray("srq");
    ObjectNode battle = (ObjectNode) scenario.path("battle");
    battle.put("seb", false);
    ((ObjectNode) battle.path("hbd").get(0)).put("kt", 1);

    List<ReplayScenario.Refusal> refusals =
        new ReplayScenario(tables, ScenarioShape.GENERATED).survey(scenario);

    assertThat(refusals)
        .extracting(ReplayScenario.Refusal::input)
        .containsExactly(
            "$.srq", "battle.seb", "battle.hbd={\"em\":{\"oe\":[],\"de\":[]},\"kt\":1}");
  }

  @Test
  void refusesInAGeneratedCaseTheCommandTypesOfAnOlderClient() {
    ObjectNode scenario = fit(Scenarios.generatedKnight());
    ((ObjectNode) scenario.path("cmd").get(0)).put("ct", 124);

    assertThat(new ReplayScenario(tables, ScenarioShape.GENERATED).survey(scenario))
        .containsExactly(new ReplayScenario.Refusal("the command type 124", "cmd[0].ct"));
  }
}
