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
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTable;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

  /**
   * Skips a test of the generated cases' fields on a data version whose generated cases no recorded
   * battle has established ({@link ReplayFormat#generated}).
   */
  private static void assumeGeneratedCasesEstablished() {
    assumeTrue(
        ReplayFormat.generated(tables.version()).isPresent(),
        "no recorded battle has established the generated cases' fields of data version "
            + tables.version());
  }

  /** Skips a test of the excluded evolutions on a data version whose replays do not list them. */
  private static void assumeExcludedEvolutionsRead() {
    assumeTrue(
        ReplayFormat.orShared(tables.version()).excludedEvolutions(),
        "the replays of data version " + tables.version() + " list no excluded evolutions");
  }

  /**
   * A card of the fixture's deck with an evolved row of the evolved cards' table, and that row's
   * data id: the deck index, the row's name and its id.
   */
  private static Object[] deckCardWithAnEvolvedRow() {
    GameTable evolvedCards = tables.table("spells_evolved");
    ReplayScenario names = new ReplayScenario(tables);
    for (int index = 0; index < Scenarios.DECK.length; index++) {
      GameRow card = names.find(Scenarios.DECK[index]).orElseThrow();
      JsonNode listed = card.value("EvolvedSpells");
      List<String> evolved =
          listed != null && listed.isArray()
              ? card.strings("EvolvedSpells")
              : List.of(card.string("EvolvedSpells"));
      for (String name : evolved) {
        if (evolvedCards.has(name)) {
          int id =
              Integer.parseInt(evolvedCards.id()) * ReplayScenario.IDS_PER_TABLE
                  + evolvedCards.row(name).index();
          return new Object[] {index, card.name() + " (" + name + ")", id};
        }
      }
    }
    assumeTrue(false, "no card of the fixture's deck has an evolved row in these tables");
    return null;
  }

  /** The scenario with every field, each side's player data listing the excluded evolutions. */
  private static ObjectNode withExcludedEvolutions(int... ids) {
    ObjectNode scenario = fit(Scenarios.knightWithEveryField());
    for (JsonNode data : scenario.path("battle").path("hbd")) {
      ArrayNode listed = ((ObjectNode) data).putArray(ReplayFormat.EXCLUDED_EVOLUTIONS_FIELD);
      for (int id : ids) {
        listed.add(id);
      }
    }
    return scenario;
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

  /**
   * The knight replay as a Path of Legends battle: the Ranked1v1_NewArena game mode with the header
   * its replays write (game mode type 13, level cap 16, trail -1, the ranked header {@code rkd}),
   * each player's data naming the battle's arena ({@code sa}) and each avatar its ranked score
   * ({@code rnk_sc}).
   */
  private static ObjectNode pathOfLegends(ObjectNode scenario) {
    ObjectNode battle = (ObjectNode) scenario.path("battle");
    battle.put("gamemode", 72_000_000 + tables.table("game_modes").row(RANKED).index());
    battle.put("gmt", 13);
    battle.put("lvlcap", 16);
    battle.put("trail", -1);
    battle.putObject("rkd").put("t", 46).put("d", 22);
    for (int side = 0; side < 2; side++) {
      ((ObjectNode) battle.path("hbd").get(side)).put("sa", battle.path("arena").asInt());
      ((ObjectNode) battle.path("avatar" + side)).put("rnk_sc", 3000 + side);
    }
    return scenario;
  }

  /** The Path of Legends game mode. */
  private static final String RANKED = "Ranked1v1_NewArena";

  @Test
  void readsAPathOfLegendsReplayAsTheLadderReplayOfTheSameBattle() {
    ScenarioPlan ladder =
        new ReplayScenario(tables).translate(fit(Scenarios.knightWithEveryField()));
    ReplayScenario mapping = new ReplayScenario(tables);
    ObjectNode scenario = fit(pathOfLegends(Scenarios.knightWithEveryField()));

    assertThat(mapping.survey(scenario)).isEmpty();
    ScenarioPlan plan = mapping.translate(scenario);

    // The same towers, decks, levels and plays as the Ladder replay: the mode's level cap, 16,
    // leaves every level as it is.
    assertThat(plan.towers()).isEqualTo(ladder.towers());
    assertThat(plan.decks()).isEqualTo(ladder.decks());
    for (int side = 0; side < 2; side++) {
      assertThat(plan.deckLevels().get(side)).containsExactly(ladder.deckLevels().get(side));
    }
    assertThat(plan.plays()).isEqualTo(ladder.plays());
    assertThat(mapping.mapping())
        .containsEntry("gmt", "pinned: 13")
        .containsEntry("lvlcap", "pinned: 16")
        .containsEntry("trail", "pinned: -1")
        .containsKeys("battle.rkd", "battle.avatarN.rnk_sc");
    // The battle it builds plays the Ladder battle's steps.
    Standard1v1Battle ranked = ReplayBattle.build(tables, plan);
    Standard1v1Battle played = ReplayBattle.build(tables, ladder);
    for (int tick = 0; tick < 400; tick++) {
      ranked.getBattle().step();
      played.getBattle().step();
    }
    assertThat(ranked.getPlays())
        .extracting(Standard1v1Battle.Play::name)
        .containsExactly("cmd0")
        .isEqualTo(played.getPlays().stream().map(Standard1v1Battle.Play::name).toList());
  }

  @Test
  void refusesAPathOfLegendsReplayWithAnotherLevelCapOrGameModeType() {
    ObjectNode scenario = fit(pathOfLegends(Scenarios.knightWithEveryField()));
    ObjectNode battle = (ObjectNode) scenario.path("battle");
    battle.put("lvlcap", 11);
    battle.put("gmt", 1);

    assertThat(new ReplayScenario(tables).survey(scenario))
        .extracting(ReplayScenario.Refusal::input)
        .containsExactly("gmt=1", "lvlcap=11");
  }

  @Test
  void refusesTheLadderModeWithThePathOfLegendsHeader() {
    ObjectNode scenario = fit(pathOfLegends(Scenarios.knightWithEveryField()));
    ((ObjectNode) scenario.path("battle")).put("gamemode", 72_000_000 + ladderIndex());

    assertThat(new ReplayScenario(tables).survey(scenario))
        .extracting(ReplayScenario.Refusal::input)
        .containsExactly("gmt=13", "lvlcap=16", "trail=-1");
  }

  /** The Ladder game mode's row index. */
  private static int ladderIndex() {
    return tables.table("game_modes").row("Ladder").index();
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
        .containsEntry("battle.avatarN.arena", ReplayScenario.AVATAR_ARENA_CARRIED)
        .doesNotContainKey("arena");
  }

  @Test
  void refusesAnotherValueOfAPinnedFieldOfTheVersion() {
    ObjectNode scenario = fit(Scenarios.knightWithEveryField());
    scenario.putArray("srq").add(1);
    ObjectNode battle = (ObjectNode) scenario.path("battle");
    battle.put("rrb", true);
    ((ObjectNode) battle.path("avatar0")).put("npc", true);

    List<ReplayScenario.Refusal> refusals = new ReplayScenario(tables).survey(scenario);

    assertThat(refusals)
        .extracting(ReplayScenario.Refusal::input)
        .containsExactly("srq=[1]", "rrb=true", "npc=true");
  }

  /** Skips a test of a Ladder battle under an event on a data version whose replays hold none. */
  private static void assumeLadderEventsRead() {
    assumeTrue(
        ReplayFormat.orShared(tables.version()).ladderEvents(),
        "the replays of data version "
            + tables.version()
            + " hold no Ladder battle under an event");
  }

  /** The event id of {@link #underAnEvent}. */
  private static final int EVENT_ID = 4321;

  /**
   * The knight replay as a Ladder battle played under an event, as such replays write it: game mode
   * type 16, the event's trail and id ({@code te}), the event block with empty settings, and each
   * player's progress on the event's trail.
   */
  private static ObjectNode underAnEvent(ObjectNode scenario) {
    ObjectNode battle = (ObjectNode) scenario.path("battle");
    battle.put("gmt", 16);
    battle.put("trail", 170_000_007);
    battle.put("te", EVENT_ID);
    ObjectNode event = battle.putObject("event");
    event.put("id", EVENT_ID);
    event.put("version_id", 1);
    event.put("name", "an event");
    event.put("category", 39);
    event.put("json", "{}");
    event.put("enabled", true);
    for (JsonNode data : battle.path("hbd")) {
      ObjectNode progress = ((ObjectNode) data).putObject(ReplayFormat.TRAIL_PROGRESS_FIELD);
      progress.put("prt", "TrophyRoad");
      progress.put("arena", 168_000_001);
      progress.put("pbl", 10);
    }
    return scenario;
  }

  @Test
  void readsALadderBattleUnderAnEventAsTheLadderBattle() {
    assumeLadderEventsRead();
    ScenarioPlan ladder =
        new ReplayScenario(tables).translate(fit(Scenarios.knightWithEveryField()));
    ReplayScenario mapping = new ReplayScenario(tables);
    ObjectNode scenario = fit(underAnEvent(Scenarios.knightWithEveryField()));

    assertThat(mapping.survey(scenario)).isEmpty();
    ScenarioPlan plan = mapping.translate(scenario);

    // The same towers, decks, levels and plays as the Ladder replay.
    assertThat(plan.towers()).isEqualTo(ladder.towers());
    assertThat(plan.decks()).isEqualTo(ladder.decks());
    for (int side = 0; side < 2; side++) {
      assertThat(plan.deckLevels().get(side)).containsExactly(ladder.deckLevels().get(side));
    }
    assertThat(plan.plays()).isEqualTo(ladder.plays());
    assertThat(mapping.mapping())
        .containsEntry("gmt", "pinned: 16")
        .containsEntry("te", "pinned: " + EVENT_ID)
        .containsEntry("trail", ReplayScenario.TRAIL_UNDER_EVENT)
        .containsEntry("battle.event", ReplayScenario.EVENT_CARRIED);
  }

  @Test
  void refusesAnEventUnderAnotherGameModeTypeOrEventIdOrMode() {
    assumeLadderEventsRead();
    ObjectNode scenario = fit(underAnEvent(Scenarios.knightWithEveryField()));
    ObjectNode battle = (ObjectNode) scenario.path("battle");
    battle.put("gmt", 1);
    battle.put("te", EVENT_ID + 1);

    assertThat(new ReplayScenario(tables).survey(scenario))
        .extracting(ReplayScenario.Refusal::input)
        .containsExactly("gmt=1", "te=" + (EVENT_ID + 1));

    ObjectNode ranked = fit(underAnEvent(pathOfLegends(Scenarios.knightWithEveryField())));
    assertThat(new ReplayScenario(tables).survey(ranked))
        .extracting(ReplayScenario.Refusal::feature)
        .anySatisfy(feature -> assertThat(feature).contains(RANKED + " under an event"));
  }

  @Test
  void refusesAnEventWithNoId() {
    assumeLadderEventsRead();
    ObjectNode scenario = fit(underAnEvent(Scenarios.knightWithEveryField()));
    ((ObjectNode) scenario.path("battle").path("event")).remove("id");

    assertThat(new ReplayScenario(tables).survey(scenario))
        .extracting(ReplayScenario.Refusal::feature)
        .containsExactly(
            "an event with no id",
            "a value of te other than an event id, which has no production input");
  }

  /** The highest level a scenario's deck cards and princess towers stand at, as given. */
  private static int highestLevel(ScenarioPlan plan) {
    int highest = 0;
    for (int side = 0; side < 2; side++) {
      for (int level : plan.deckLevels().get(side)) {
        highest = Math.max(highest, level);
      }
      highest = Math.max(highest, plan.towers().get(side).level());
    }
    return highest;
  }

  /** The scenario with every play's item carrying the given level, its level field less 1. */
  private static ObjectNode playedAt(ObjectNode scenario, int level) {
    for (JsonNode command : scenario.path("cmd")) {
      ObjectNode sel = (ObjectNode) command.path("c").path("sel");
      sel.put("pd", ScenarioItems.withLevelField(sel.path("pd").asInt(), level - 1));
    }
    return scenario;
  }

  /**
   * The lowest top level of the rarities of the deck cards and the tower selections: a rarity's
   * LevelCount plus its RelativeLevel.
   */
  private static int topLevel() {
    int top = Integer.MAX_VALUE;
    for (String table : List.of("rarities", "support_rarities")) {
      for (GameRow rarity : tables.table(table).rows()) {
        top =
            Math.min(
                top,
                ScenarioItems.number(rarity, "LevelCount")
                    + ScenarioItems.number(rarity, "RelativeLevel"));
      }
    }
    return top;
  }

  @Test
  void readsACardLevelMinimumNoDeckCardOrTowerIsBelowAsNone() {
    assumeLadderEventsRead();
    ObjectNode scenario = fit(underAnEvent(Scenarios.knightWithEveryField()));
    ScenarioPlan plan = new ReplayScenario(tables).translate(scenario);
    int lowest = Integer.MAX_VALUE;
    for (int side = 0; side < 2; side++) {
      for (int level : plan.deckLevels().get(side)) {
        lowest = Math.min(lowest, level);
      }
      lowest = Math.min(lowest, plan.towers().get(side).level());
    }
    ((ObjectNode) scenario.path("battle")).put("cardlvlmin", lowest);
    ReplayScenario mapping = new ReplayScenario(tables);

    assertThat(mapping.survey(scenario)).isEmpty();
    ScenarioPlan read = mapping.translate(scenario);
    assertThat(read.cardLevelMinimum()).isEqualTo(lowest);
    assertThat(read.raisedDeckLevels()).usingRecursiveComparison().isEqualTo(plan.deckLevels());
    assertThat(read.raisedTowers()).isEqualTo(plan.towers());
    assertThat(read.plays()).isEqualTo(plan.plays());
    assertThat(mapping.mapping()).containsEntry("cardlvlmin", ReplayScenario.CARD_LEVEL_MIN_READ);
  }

  @Test
  void raisesTheDeckCardsAndThePrincessTowersButNotTheKingToACardLevelMinimum() {
    assumeLadderEventsRead();
    ObjectNode scenario = fit(underAnEvent(Scenarios.knightWithEveryField()));
    // Each king at the first level, below the minimum.
    for (JsonNode data : scenario.path("battle").path("hbd")) {
      ((ObjectNode) data).put(ReplayFormat.KING_LEVEL_FIELD, 1);
    }
    ScenarioPlan given = new ReplayScenario(tables).translate(scenario);
    // A minimum above every deck card and princess tower, the kings standing below it too.
    int minimum = highestLevel(given) + 1;
    for (Standard1v1Battle.Towers towers : given.towers()) {
      assertThat(towers.kingLevel()).isLessThan(minimum);
    }
    ((ObjectNode) scenario.path("battle")).put("cardlvlmin", minimum);
    // The plays' items carry the raised level, as a player's client builds them from its deck.
    playedAt(scenario, minimum);
    ReplayScenario mapping = new ReplayScenario(tables);

    assertThat(mapping.survey(scenario)).isEmpty();
    ScenarioPlan plan = mapping.translate(scenario);
    assertThat(plan.cardLevelMinimum()).isEqualTo(minimum);
    // The levels as given are kept, and the battle's deck and towers stand at the minimum.
    assertThat(plan.deckLevels()).usingRecursiveComparison().isEqualTo(given.deckLevels());
    assertThat(plan.towers()).isEqualTo(given.towers());
    for (int side = 0; side < 2; side++) {
      assertThat(plan.raisedDeckLevels().get(side)).containsOnly(minimum);
      Standard1v1Battle.Towers raised = plan.raisedTowers().get(side);
      assertThat(raised.level()).isEqualTo(minimum);
      assertThat(raised.kingLevel()).isEqualTo(given.towers().get(side).kingLevel());
    }
    assertThat(plan.plays())
        .isNotEmpty()
        .allSatisfy(play -> assertThat(play.level()).isEqualTo(minimum));
    // The battle is built with the raised towers.
    assertThat(ReplayBattle.build(tables, plan).getTowers()).isEqualTo(plan.raisedTowers());

    // A play whose item carries its card's own level runs at that level.
    ScenarioPlan own = new ReplayScenario(tables).translate(fit(scenario));
    for (ScenarioPlan.Play play : own.plays()) {
      int deckIndex = own.decks().get(play.side()).indexOf(play.card());
      assertThat(play.level()).isEqualTo(own.deckLevels().get(play.side())[deckIndex]);
    }
  }

  @Test
  void refusesAPlayUnderACardLevelMinimumAtNeitherItsCardsLevelNorTheRaisedOne() {
    assumeLadderEventsRead();
    ObjectNode scenario = fit(underAnEvent(Scenarios.knightWithEveryField()));
    ScenarioPlan given = new ReplayScenario(tables).translate(scenario);
    ScenarioPlan.Play play = given.plays().get(0);
    // Two levels above the played card: one level between its own and the raised one.
    int minimum = play.level() + 2;
    ((ObjectNode) scenario.path("battle")).put("cardlvlmin", minimum);
    playedAt(scenario, play.level() + 1);

    assertThat(new ReplayScenario(tables).survey(scenario))
        .extracting(ReplayScenario.Refusal::feature)
        .contains("a play whose packed item is not one its deck card can carry");
  }

  @Test
  void refusesACardLevelMinimumThatIsNoLevelOrAboveARaritysTopLevel() {
    assumeLadderEventsRead();
    int top = topLevel();
    for (JsonNode minimum :
        List.<JsonNode>of(
            JsonNodeFactory.instance.textNode("1"),
            JsonNodeFactory.instance.numberNode(-1),
            JsonNodeFactory.instance.numberNode(top + 1))) {
      ObjectNode scenario = fit(underAnEvent(Scenarios.knightWithEveryField()));
      ((ObjectNode) scenario.path("battle")).set("cardlvlmin", minimum);

      assertThat(new ReplayScenario(tables).survey(scenario))
          .singleElement()
          .satisfies(
              refusal -> {
                assertThat(refusal.input()).isEqualTo("cardlvlmin=" + minimum);
                assertThat(refusal.feature()).startsWith("a card level minimum");
              });
    }
    // The top level itself is read, every card and tower raised to it.
    ObjectNode scenario = fit(underAnEvent(Scenarios.knightWithEveryField()));
    ((ObjectNode) scenario.path("battle")).put("cardlvlmin", top);
    playedAt(scenario, top);
    assertThat(new ReplayScenario(tables).survey(scenario)).isEmpty();
  }

  @Test
  void refusesAMirrorOrAVariantCardsPlayTheCardLevelMinimumRaises() {
    assumeLadderEventsRead();
    for (ObjectNode scenario :
        List.of(fit(Scenarios.knightThenMirror()), fit(Scenarios.mergeMaidenMounted()))) {
      ScenarioPlan given = new ReplayScenario(tables).translate(scenario);
      ScenarioPlan.Play special =
          given.plays().stream()
              .filter(play -> play.repeats() != null || play.option() != null)
              .findFirst()
              .orElseThrow();
      int deckIndex = given.decks().get(special.side()).indexOf(special.card());
      int minimum = given.deckLevels().get(special.side())[deckIndex] + 1;
      ((ObjectNode) scenario.path("battle")).put("cardlvlmin", minimum);

      assertThat(new ReplayScenario(tables).survey(scenario))
          .extracting(ReplayScenario.Refusal::feature)
          .anySatisfy(
              feature ->
                  assertThat(feature)
                      .startsWith("a play of " + special.card())
                      .contains("which the card level minimum raises to " + minimum));
    }
  }

  @Test
  void aPlanWithACardLevelMinimumIsWrittenWithItsLevelsAsGivenAndReadsBackTheSame() {
    assumeLadderEventsRead();
    ObjectNode scenario = fit(underAnEvent(Scenarios.knightWithEveryField()));
    ScenarioPlan given = new ReplayScenario(tables).translate(scenario);
    int minimum = highestLevel(given) + 1;
    ((ObjectNode) scenario.path("battle")).put("cardlvlmin", minimum);
    ScenarioPlan plan = new ReplayScenario(tables).translate(scenario);

    ObjectNode written = new ScenarioWriter(tables).write(plan);

    assertThat(written.path("battle").path("cardlvlmin").asInt()).isEqualTo(minimum);
    assertThat(new ReplayScenario(tables).translate(written))
        .usingRecursiveComparison()
        .isEqualTo(plan);
  }

  /** The challenge game mode, played under an event at the Friendly card level adjustment. */
  private static final String CHALLENGE = "Challenge_AllCards_EventDeck_NoSet";

  /**
   * The level a Friendly battle's kings and towers stand at: the SummonerLevel of the exp level the
   * global TOURNAMENT_MAX_EXP_LEVEL names, its row at index the number less 1.
   */
  private static int friendlyLevel() {
    int expLevel = ScenarioItems.global(tables, "TOURNAMENT_MAX_EXP_LEVEL");
    return tables.table("exp_levels").rows().stream()
        .filter(row -> row.index() == expLevel - 1)
        .findFirst()
        .orElseThrow()
        .intValue("SummonerLevel");
  }

  /**
   * A deck card's level under the Friendly adjustment, from its rarity's tournament level index.
   */
  private static int tournamentLevel(GameRow card) {
    GameRow rarity = tables.table("rarities").row(card.string("Rarity"));
    return rarity.intValue("TournamentLevelIndex") + rarity.intValue("RelativeLevel") + 1;
  }

  /**
   * The knight replay as a challenge, as its replays write it: under an event, the challenge game
   * mode, tps 2 and seb true, each deck card at its rarity's tournament level index and the plays'
   * items fitted to it.
   */
  private static ObjectNode challenge(ObjectNode scenario) {
    underAnEvent(scenario);
    ObjectNode battle = (ObjectNode) scenario.path("battle");
    battle.put("gamemode", 72_000_000 + tables.table("game_modes").row(CHALLENGE).index());
    battle.put("tps", 2);
    battle.put("seb", true);
    for (int side = 0; side < 2; side++) {
      for (JsonNode entry : battle.path("deck" + side).path("sp")) {
        GameRow card = ScenarioItems.row(tables, entry.path("d").asInt());
        GameRow rarity = tables.table("rarities").row(card.string("Rarity"));
        ((ObjectNode) entry).put("l", rarity.intValue("TournamentLevelIndex"));
      }
    }
    return fit(scenario);
  }

  @Test
  void readsAChallengeAtTheFriendlyLevelsWhateverLevelsTheReplayWrites() {
    assumeLadderEventsRead();
    ObjectNode scenario = challenge(Scenarios.knightWithEveryField());
    ObjectNode battle = (ObjectNode) scenario.path("battle");
    // Levels the game reads none of under the Friendly adjustment.
    for (int side = 0; side < 2; side++) {
      ((ObjectNode) battle.path("hbd").get(side)).put("kt", 1);
      ((ObjectNode) battle.path("deck" + side).path("sc").get(0)).put("l", 0);
      for (JsonNode entry : battle.path("deck" + side).path("sp")) {
        ((ObjectNode) entry).put("l", 0);
      }
    }
    ReplayScenario mapping = new ReplayScenario(tables);

    assertThat(mapping.survey(scenario)).isEmpty();
    ScenarioPlan plan = mapping.translate(scenario);

    int level = friendlyLevel();
    for (int side = 0; side < 2; side++) {
      Standard1v1Battle.Towers towers = plan.towers().get(side);
      assertThat(towers.kingLevel()).isEqualTo(level);
      assertThat(towers.level()).isEqualTo(level);
      List<String> deck = plan.decks().get(side);
      int[] levels = plan.deckLevels().get(side);
      for (int i = 0; i < deck.size(); i++) {
        assertThat(levels[i]).isEqualTo(tournamentLevel(ScenarioItems.card(tables, deck.get(i))));
      }
    }
    assertThat(mapping.mapping())
        .containsEntry("gmt", "pinned: 16")
        .containsEntry("tps", "pinned: 2")
        .containsEntry("cardlvlmin", "pinned: 0")
        .containsEntry("battle.deckN.sp[i].l", ReplayScenario.DECK_LEVEL_UNDER_FRIENDLY)
        .containsEntry("battle.deckN.sc[0].l", ReplayScenario.TOWER_LEVEL_UNDER_FRIENDLY)
        .containsEntry("battle.hbd[i].kt", ReplayScenario.TOWER_LEVEL_UNDER_FRIENDLY)
        .containsKey("battle.seb");
  }

  @Test
  void refusesAChallengeWithoutAnEventOrAtAnotherTpsOrCardLevelMinimum() {
    assumeLadderEventsRead();
    ObjectNode noEvent = challenge(Scenarios.knightWithEveryField());
    ObjectNode battle = (ObjectNode) noEvent.path("battle");
    battle.remove("event");
    battle.put("gmt", 1);
    battle.put("te", -1);
    battle.put("trail", 170_000_000);
    assertThat(new ReplayScenario(tables).survey(noEvent))
        .extracting(ReplayScenario.Refusal::feature)
        .anySatisfy(feature -> assertThat(feature).contains(CHALLENGE + " without an event"));

    ObjectNode scenario = challenge(Scenarios.knightWithEveryField());
    ((ObjectNode) scenario.path("battle")).put("tps", 1).put("cardlvlmin", 1);
    assertThat(new ReplayScenario(tables).survey(scenario))
        .extracting(ReplayScenario.Refusal::input)
        .containsExactly("tps=1", "cardlvlmin=1");
  }

  @Test
  void refusesAChallengeWhoseRowDiffersFromTheLadderRowInABattleColumn(@TempDir Path folder)
      throws IOException {
    assumeLadderEventsRead();
    GameTables altered =
        GameData.altered(
            folder,
            "game_modes",
            rows -> ((ObjectNode) rows.path(CHALLENGE).path("columns")).put("TripleElixir", true));
    ObjectNode scenario = challenge(Scenarios.knightWithEveryField());

    assertThat(new ReplayScenario(altered).survey(scenario))
        .extracting(ReplayScenario.Refusal::feature)
        .containsExactly(
            "the game mode " + CHALLENGE + ", whose TripleElixir differs from the Ladder row's");
  }

  @Test
  void readsACaseGeneratedForTheVersionByTheFieldsOfItsGeneratedCases() {
    assumeGeneratedCasesEstablished();
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
    assumeGeneratedCasesEstablished();
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
    assumeGeneratedCasesEstablished();
    ObjectNode scenario = fit(Scenarios.generatedKnight());
    ((ObjectNode) scenario.path("cmd").get(0)).put("ct", 124);

    assertThat(new ReplayScenario(tables, ScenarioShape.GENERATED).survey(scenario))
        .containsExactly(new ReplayScenario.Refusal("the command type 124", "cmd[0].ct"));
  }

  @Test
  void refusesAGeneratedCaseOfAVersionWhoseGeneratedCasesAreNotEstablished() {
    assumeTrue(ReplayFormat.generated(tables.version()).isEmpty());

    assertThat(
            new ReplayScenario(tables, ScenarioShape.GENERATED)
                .survey(fit(Scenarios.generatedKnight())))
        .containsExactly(
            new ReplayScenario.Refusal(
                "a generated case of data version "
                    + tables.version()
                    + ", whose generated cases' fields no recorded battle has established",
                "the scenario shape generated"));
  }

  @Test
  void readsTheExcludedEvolutionsOfCardsOutsideTheEvolutionSlotAsNoInputOfTheBattle() {
    assumeExcludedEvolutionsRead();
    Object[] card = deckCardWithAnEvolvedRow();
    ReplayScenario mapping = new ReplayScenario(tables);
    ObjectNode scenario = withExcludedEvolutions((int) card[2]);

    // No deck card is in an evolution slot: no play of the battle is built evolved.
    assertThat(mapping.survey(scenario)).isEmpty();
    assertThat(mapping.translate(scenario).plays()).hasSize(1);
    assertThat(mapping.mapping().get("battle.hbd[i].es")).startsWith("consumed: ");
  }

  @Test
  void refusesAnExcludedEvolutionOfACardInTheEvolutionSlot() {
    assumeExcludedEvolutionsRead();
    Object[] card = deckCardWithAnEvolvedRow();
    ObjectNode scenario = withExcludedEvolutions((int) card[2]);
    ObjectNode entry =
        (ObjectNode) scenario.path("battle").path("deck1").path("sp").get((int) card[0]);
    entry.put("el", 1);

    assertThat(new ReplayScenario(tables).survey(scenario))
        .contains(
            new ReplayScenario.Refusal(
                "an evolution excluded from the battle in an evolution slot, whose plays the"
                    + " battle core would build evolved",
                "battle.deck1.sp: " + card[1]));
  }

  @Test
  void refusesAnExcludedEvolutionOfNoRowAndOneThatIsNoList() {
    assumeExcludedEvolutionsRead();
    GameTable evolvedCards = tables.table("spells_evolved");
    int none =
        Integer.parseInt(evolvedCards.id()) * ReplayScenario.IDS_PER_TABLE
            + evolvedCards.rows().size();
    ObjectNode scenario = withExcludedEvolutions(none);
    ((ObjectNode) scenario.path("battle").path("hbd").get(1))
        .put(ReplayFormat.EXCLUDED_EVOLUTIONS_FIELD, 3);

    assertThat(new ReplayScenario(tables).survey(scenario))
        .containsExactly(
            new ReplayScenario.Refusal(
                "an excluded evolution of no row the tables hold",
                "battle.hbd[0].es=[" + none + "]"),
            new ReplayScenario.Refusal(
                "excluded evolutions that are not a list of data ids", "battle.hbd[1].es=3"));
  }
}
