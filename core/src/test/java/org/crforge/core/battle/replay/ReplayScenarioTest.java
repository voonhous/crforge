package org.crforge.core.battle.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ReplayScenarioTest {

  private static GameTables tables;

  @BeforeAll
  static void loadTables() {
    tables = GameTables.loadConfigured();
  }

  /** A scenario with its plays' items fitted to the tables: the costs and levels of their rows. */
  private static ObjectNode fit(ObjectNode scenario) {
    return ScenarioItems.fitted(scenario, tables);
  }

  /** The packed item a scenario's command carries. */
  private static int item(ObjectNode scenario, int command) {
    return scenario.path("cmd").get(command).path("c").path("sel").path("pd").asInt();
  }

  /** Puts a command's packed item. */
  private static void putItem(ObjectNode scenario, int command, int item) {
    ((ObjectNode) scenario.path("cmd").get(command).path("c").path("sel")).put("pd", item);
  }

  /** An item with its deck index field replaced. */
  private static int withDeckIndexField(int item, int field) {
    return (item & ~(0x3f << 22)) | (field << 22);
  }

  /** An item with its option field replaced. */
  private static int withOptionField(int item, int field) {
    return (item & ~(0x7 << 4)) | (field << 4);
  }

  /** A card's level counted from 1 at its level index 0: its rarity's RelativeLevel plus 1. */
  private static int firstLevel(String card) {
    return ScenarioItems.levelField(tables, ScenarioItems.card(tables, card), 0) + 1;
  }

  /** A tower selection's rarity row of the support rarities. */
  private static GameRow selectionRarity(String selection) {
    return tables
        .table("support_rarities")
        .row(tables.table("support_cards").row(selection).columns().get("Rarity").asText());
  }

  /**
   * A tower selection's princess level at a level index: plus its rarity's RelativeLevel plus 1.
   */
  private static int towerLevel(String selection, int levelIndex) {
    return levelIndex + ScenarioItems.number(selectionRarity(selection), "RelativeLevel") + 1;
  }

  @Test
  void translatesTheDecksLevelsAccountsAndThePlay() {
    ObjectNode scenario = fit(Scenarios.knight());
    ScenarioPlan plan = new ReplayScenario(tables).translate(scenario);

    assertThat(plan.seed()).isEqualTo(1131);
    // Both sides select the princess towers at level index 0, each king at its kt 1.
    int princessLevel = towerLevel("King_PrincessTowers", 0);
    assertThat(plan.towers())
        .containsExactly(
            new Standard1v1Battle.Towers("King_PrincessTowers", 1, princessLevel),
            new Standard1v1Battle.Towers("King_PrincessTowers", 1, princessLevel));
    List<String> deck =
        List.of(
            "Knight", "Archer", "Goblins", "Giant", "Minions", "Musketeer", "Fireball", "Arrows");
    assertThat(plan.decks().get(0)).containsExactlyElementsOf(deck);
    assertThat(plan.decks().get(1)).isEqualTo(plan.decks().get(0));
    // A level index counts from the rarity's first level: its RelativeLevel plus 1 of all.
    assertThat(plan.deckLevels().get(0))
        .containsExactly(deck.stream().mapToInt(ReplayScenarioTest::firstLevel).toArray());
    assertThat(plan.accounts().get(0)).containsExactly(0, 1);
    assertThat(plan.accounts().get(1)).containsExactly(0, 2);
    assertThat(plan.playerDataChoices()).containsExactly(1, 1);
    // The Knight's plain item: its cost, its level field and deck index field 1.
    int knight = item(scenario, 0);
    assertThat(ScenarioItems.costOf(knight)).isEqualTo(ScenarioItems.cost(tables, "Knight"));
    assertThat(knight & ~(-1 << 28)).isEqualTo((1 << 22) | ((firstLevel("Knight") - 1) << 10));
    assertThat(plan.plays())
        .containsExactly(
            new ScenarioPlan.Play(
                0, 200, 220, 0, "Knight", firstLevel("Knight"), 3500, 14000, knight, null, null));
    // No deck item names slot flags: every card is in neither slot.
    assertThat(plan.slotFlags().get(0)).containsOnly(0);
    assertThat(plan.slotFlags().get(1)).containsOnly(0);
  }

  @Test
  void readsADeckCardWithoutItsLevelAsLevelIndexZero() {
    // A replay leaves a deck card's level index out when it is 0: the game reads the missing
    // level as 0, the rarity's first level, as the written 0 of the same deck.
    ScenarioPlan written = new ReplayScenario(tables).translate(fit(Scenarios.knight()));
    ObjectNode scenario = Scenarios.knight();
    for (int side = 0; side < 2; side++) {
      for (JsonNode entry : scenario.path("battle").path("deck" + side).path("sp")) {
        ((ObjectNode) entry).remove("l");
      }
    }
    ScenarioPlan plan = new ReplayScenario(tables).translate(fit(scenario));

    assertThat(plan.deckLevels().get(0)).containsExactly(written.deckLevels().get(0));
    assertThat(plan.deckLevels().get(1)).containsExactly(written.deckLevels().get(1));
    assertThat(plan.deckLevels().get(0))
        .containsExactly(
            plan.decks().get(0).stream().mapToInt(ReplayScenarioTest::firstLevel).toArray());
    // The Knight's play, whose item's level field is checked against the deck card's level.
    assertThat(plan.plays()).isEqualTo(written.plays());
  }

  @Test
  void refusesADeckCardWithoutItsCard() {
    // The level's absence is read as 0; a deck card without its card row is still refused.
    ObjectNode scenario = fit(Scenarios.knight());
    ((ObjectNode) scenario.path("battle").path("deck0").path("sp").get(1)).remove("d");

    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("the scenario has no d");
  }

  @Test
  void carriesTheArenaOfAnyTrophyArena() {
    // The arena names the players' trophy arena, which sets no battle input; the map is the
    // location's.
    ObjectNode scenario = fit(Scenarios.knight());
    ObjectNode battle = (ObjectNode) scenario.path("battle");
    battle.put("arena", 54000020);
    ((ObjectNode) battle.path("avatar0")).put("arena", 54000020);
    ((ObjectNode) battle.path("avatar1")).put("arena", 54000020);

    assertThat(new ReplayScenario(tables).survey(scenario)).isEmpty();
  }

  @Test
  void readsADeckCardsSlotFlagsAndTheItemsOfItsEvolutionSlotsPlays() {
    ObjectNode scenario = fit(Scenarios.knightEvolvedThirdPlay());
    ScenarioPlan plan = new ReplayScenario(tables).translate(scenario);

    // The Knight's el 1: the evolution slot, bit 0.
    assertThat(plan.slotFlags().get(0)).containsExactly(1, 0, 0, 0, 0, 0, 0, 0);
    assertThat(plan.slotFlags().get(1)).containsOnly(0);
    // The Knight's three items are kept for the run, which checks them against the items the
    // simulator builds as each play runs: the slot flags, the count plus 1 (1, 2, 3), and the
    // evolution field once the count reaches the evolved row's DarkElixirCost.
    assertThat(List.of(item(scenario, 0), item(scenario, 5), item(scenario, 10)))
        .extracting(item -> (item >>> 7) & 0x7, item -> (item >>> 19) & 0x7)
        .containsExactly(tuple(1, 1), tuple(2, 1), tuple(3, 1));
    assertThat(
            plan.plays().stream()
                .filter(play -> play.card().equals("Knight"))
                .map(ScenarioPlan.Play::item))
        .containsExactly(item(scenario, 0), item(scenario, 5), item(scenario, 10));
    assertThat(plan.plays()).hasSize(11);
  }

  @Test
  void refusesSlotFlagsOtherThanTheEvolutionAndHeroSlots() {
    ObjectNode scenario = fit(Scenarios.knight());
    ((ObjectNode) scenario.path("battle").path("deck1").path("sp").get(2)).put("el", 4);

    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessageContaining("battle.deck1.sp[2].el=4");
  }

  @Test
  void refusesAnItemWhoseSlotFlagsAreNotItsDeckCards() {
    ObjectNode scenario = fit(Scenarios.knight());
    ((ObjectNode) scenario.path("battle").path("deck0").path("sp").get(0)).put("el", 1);
    // The plain item, whose flags field (bits 19..21) is 0, for an evolution slot's card.
    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessageContaining("slot flags field 0");
  }

  @Test
  void refusesACountOnTheItemOfACardOutsideTheEvolutionSlot() {
    ObjectNode scenario = fit(Scenarios.knight());
    // The count plus 1, bits 7..9, which only an evolution slot's card carries.
    putItem(scenario, 0, item(scenario, 0) | (1 << 7));

    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessageContaining("count field 1");
  }

  @Test
  void namesTheSideOfAPlayByItsAccount() {
    ObjectNode scenario = fit(Scenarios.knight());
    ((ObjectNode) scenario.path("cmd").get(0).path("c")).put("idLo", 2);

    ScenarioPlan plan = new ReplayScenario(tables).translate(scenario);

    assertThat(plan.plays().get(0).side()).isEqualTo(1);
  }

  @Test
  void buildsTheCannoneerTowersOfASidesTowerSelection() {
    ObjectNode scenario = fit(Scenarios.knight());
    ((ObjectNode) scenario.path("battle").path("deck1").path("sc").get(0)).put("d", 159000001);

    ScenarioPlan plan = new ReplayScenario(tables).translate(scenario);

    // Row 1 of the tower selections, King_CannonTowers, an Epic selection: its Cannoneer rows stand
    // its rarity's RelativeLevel above the first, its king tower at the first. Side 0 keeps the
    // princess towers.
    assertThat(plan.towers())
        .containsExactly(
            new Standard1v1Battle.Towers(
                "King_PrincessTowers", 1, towerLevel("King_PrincessTowers", 0)),
            new Standard1v1Battle.Towers(
                "King_CannonTowers", 1, towerLevel("King_CannonTowers", 0)));
  }

  @Test
  void buildsTheDaggerDuchessTowersOfASidesTowerSelection() {
    ObjectNode scenario = fit(Scenarios.knight());
    ((ObjectNode) scenario.path("battle").path("deck1").path("sc").get(0)).put("d", 159000002);

    ScenarioPlan plan = new ReplayScenario(tables).translate(scenario);

    // Row 2 of the tower selections, King_KnifeTowers, a Legendary selection: its DaggerDuchess
    // rows stand its rarity's RelativeLevel above the first, its king tower at the first.
    assertThat(plan.towers())
        .containsExactly(
            new Standard1v1Battle.Towers(
                "King_PrincessTowers", 1, towerLevel("King_PrincessTowers", 0)),
            new Standard1v1Battle.Towers("King_KnifeTowers", 1, towerLevel("King_KnifeTowers", 0)));
  }

  @Test
  void buildsTheRoyalChefTowersOfASidesTowerSelection() {
    ObjectNode scenario = fit(Scenarios.knight());
    ((ObjectNode) scenario.path("battle").path("deck1").path("sc").get(0)).put("d", 159000004);

    ScenarioPlan plan = new ReplayScenario(tables).translate(scenario);

    // Row 4 of the tower selections, King_ChefTowers, a Legendary selection: its ChefTower rows
    // stand its rarity's RelativeLevel above the first, its king row at the first.
    assertThat(plan.towers())
        .containsExactly(
            new Standard1v1Battle.Towers(
                "King_PrincessTowers", 1, towerLevel("King_PrincessTowers", 0)),
            new Standard1v1Battle.Towers("King_ChefTowers", 1, towerLevel("King_ChefTowers", 0)));
  }

  @Test
  void readsEachSidesTowerLevelFromItsOwnSelection() {
    ObjectNode scenario = fit(Scenarios.knight());
    ((ObjectNode) scenario.path("battle").path("deck0").path("sc").get(0)).put("l", 2);
    ((ObjectNode) scenario.path("battle").path("deck1").path("sc").get(0))
        .put("d", 159000001)
        .put("l", 10);

    ScenarioPlan plan = new ReplayScenario(tables).translate(scenario);

    // The level index plus the selection rarity's RelativeLevel plus 1.
    assertThat(plan.towers().get(0).level()).isEqualTo(towerLevel("King_PrincessTowers", 2));
    assertThat(plan.towers().get(1).level()).isEqualTo(towerLevel("King_CannonTowers", 10));
  }

  @Test
  void buildsEachKingTowerAtItsPlayerDatasKingLevelWhateverTheSelectionLevel() {
    ObjectNode scenario = fit(Scenarios.knight());
    ((ObjectNode) scenario.path("battle").path("deck0").path("sc").get(0)).put("l", 2);
    ((ObjectNode) scenario.path("battle").path("deck1").path("sc").get(0))
        .put("d", 159000001)
        .put("l", 10);

    ScenarioPlan plan = new ReplayScenario(tables).translate(scenario);

    // The king row's level comes from the side's player data, its kt 1, not from the tower
    // selection.
    assertThat(plan.towers())
        .containsExactly(
            new Standard1v1Battle.Towers(
                "King_PrincessTowers", 1, towerLevel("King_PrincessTowers", 2)),
            new Standard1v1Battle.Towers(
                "King_CannonTowers", 1, towerLevel("King_CannonTowers", 10)));
  }

  @Test
  void refusesATowerLevelOutsideItsSelectionsLevels() {
    // An Epic selection has its rarity's LevelCount levels, index 0 to the count less 1.
    int levelCount = ScenarioItems.number(selectionRarity("King_CannonTowers"), "LevelCount");
    for (int level : new int[] {levelCount, -1}) {
      ObjectNode scenario = fit(Scenarios.knight());
      ((ObjectNode) scenario.path("battle").path("deck1").path("sc").get(0))
          .put("d", 159000001)
          .put("l", level);

      assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
          .isInstanceOf(UnsupportedScenarioException.class)
          .hasMessageContaining("battle.deck1.sc[0].l=" + level);
    }
  }

  @Test
  void refusesATowerSelectionTheSimulatorDoesNotBuild() {
    // The Goblin Queen's towers, the one selection left unbuilt.
    ObjectNode scenario = fit(Scenarios.knight());
    ((ObjectNode) scenario.path("battle").path("deck1").path("sc").get(0)).put("d", 159000003);

    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessageContaining("battle.deck1.sc[0].d=159000003")
        .hasMessageNotContaining("hold no table");
  }

  @Test
  void refusesACommandOfAnotherType() {
    ObjectNode scenario = fit(Scenarios.knight());
    ((ObjectNode) scenario.path("cmd").get(0)).put("ct", 124);

    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessageContaining("the command type 124");
  }

  @Test
  void readsAnAbilityCommandThatNamesItsUnitByGameObjectId() {
    ObjectNode scenario = fit(Scenarios.archerQueenAbility());
    ScenarioPlan plan = new ReplayScenario(tables).translate(scenario);

    // A Champion at level index 0, its item her cost, her level field and deck index field 1.
    int queen = item(scenario, 0);
    assertThat(ScenarioItems.costOf(queen)).isEqualTo(ScenarioItems.cost(tables, "ArcherQueen"));
    assertThat(ScenarioItems.levelFieldOf(queen)).isEqualTo(firstLevel("ArcherQueen") - 1);
    assertThat(plan.plays())
        .containsExactly(
            new ScenarioPlan.Play(
                0,
                200,
                220,
                0,
                "ArcherQueen",
                firstLevel("ArcherQueen"),
                3500,
                14000,
                queen,
                null,
                null));
    assertThat(plan.abilities()).containsExactly(new ScenarioPlan.Ability(1, 330, 350, 0, 5000006));
  }

  @Test
  void namesTheSideOfAnAbilityCommandByItsAccount() {
    ObjectNode scenario = fit(Scenarios.archerQueenAbility());
    ((ObjectNode) scenario.path("cmd").get(1).path("c")).put("idLo", 2);

    ScenarioPlan plan = new ReplayScenario(tables).translate(scenario);

    assertThat(plan.abilities().get(0).side()).isEqualTo(1);
  }

  @Test
  void refusesAnAbilityCommandFieldItHasNoMappingFor() {
    ObjectNode scenario = fit(Scenarios.archerQueenAbility());
    // A unit's row and play are not read from an ability command's fields.
    ((ObjectNode) scenario.path("cmd").get(1).path("c")).put("px", 3500);

    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessageContaining("cmd[1].c.px");
  }

  @Test
  void refusesAnotherValueOfAFieldWithNoProductionInput() {
    ObjectNode scenario = fit(Scenarios.knight());
    ((ObjectNode) scenario.path("battle")).put("hm", true);

    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessageContaining("hm=true");
  }

  @Test
  void refusesAFieldItHasNoMappingFor() {
    ObjectNode scenario = fit(Scenarios.knight());
    ((ObjectNode) scenario.path("battle")).put("event", 1);

    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessageContaining("battle.event");
  }

  @Test
  void refusesAPlayWhoseItemIsNotItsDeckCardsPlainItem() {
    ObjectNode scenario = fit(Scenarios.knight());
    // The level field, bits 10..16, one above the deck card's at its level index.
    int plain = item(scenario, 0);
    putItem(
        scenario, 0, ScenarioItems.withLevelField(plain, ScenarioItems.levelFieldOf(plain) + 1));

    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessageContaining("packed item");
  }

  @Test
  void refusesAPlayWhoseItemNamesAnotherDeckIndex() {
    ObjectNode scenario = fit(Scenarios.knight());
    // The deck index field, bits 22..27, is the index plus 1: 2 names index 1, not the Knight's 0.
    putItem(scenario, 0, withDeckIndexField(item(scenario, 0), 2));

    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessageContaining("deck index field 2");
  }

  @Test
  void refusesAPlayWhoseItemSetsAnEvolutionBit() {
    ObjectNode scenario = fit(Scenarios.knight());
    // The evolution field 1 on a card in neither slot, which is never evolved.
    putItem(scenario, 0, item(scenario, 0) | 1);

    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessageContaining("evolution field 1");
  }

  @Test
  void refusesAPlayWhoseItemSetsAnOptionBit() {
    // The option field (bits 4..5) 1 or 2 on a card that is no variant. The cosmetic field (bits
    // 17..18) is carried by the configured version's replays.
    for (int option : new int[] {1, 2}) {
      int bits = option << 4;
      ObjectNode scenario = fit(Scenarios.knight());
      putItem(scenario, 0, item(scenario, 0) | bits);

      assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
          .isInstanceOf(UnsupportedScenarioException.class)
          .hasMessageContaining("packed item");
    }
  }

  @Test
  void readsTheCardAMirrorPlayRepeatsFromItsItem() {
    ObjectNode scenario = fit(Scenarios.knightThenMirror());
    ScenarioPlan plan = new ReplayScenario(tables).translate(scenario);

    ScenarioPlan.Play mirror = plan.plays().get(4);
    assertThat(mirror.card()).isEqualTo("Mirror");
    assertThat(mirror.repeats()).isEqualTo(new ScenarioPlan.Repeated(Scenarios.KNIGHT, "Knight"));
    // An Epic at level index 0: its rarity's RelativeLevel plus 1 of all. Its item is kept whole
    // for the run to check.
    assertThat(mirror.level()).isEqualTo(firstLevel("Mirror"));
    assertThat(mirror.item()).isEqualTo(item(scenario, 4));
    // A play of any other card repeats nothing.
    assertThat(plan.plays().subList(0, 4)).allMatch(play -> play.repeats() == null);
  }

  @Test
  void refusesARepeatedCardOnAPlayOfACardOtherThanTheMirror() {
    ObjectNode scenario = fit(Scenarios.knightThenMirror());
    ((ObjectNode) scenario.path("cmd").get(3).path("c").path("sel")).put("fs", Scenarios.ARCHER);

    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessageContaining("cmd[3].c.sel.fs=" + Scenarios.ARCHER);
  }

  @Test
  void refusesAMirrorPlayThatNamesNoRepeatedCard() {
    ObjectNode scenario = fit(Scenarios.knightThenMirror());
    ((ObjectNode) scenario.path("cmd").get(4).path("c").path("sel")).remove("fs");

    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessage(
            "a Mirror play whose item names no repeated card, which no reference holds:"
                + " cmd[4].c.sel");
  }

  @Test
  void refusesAMirrorPlayWhoseItemNamesAnotherDeckIndexOrSetsAnOptionBit() {
    // The deck index field 5 names index 4, the Archer's; the option field 1 is a variant's.
    int fitted = item(fit(Scenarios.knightThenMirror()), 4);
    for (int item : new int[] {withDeckIndexField(fitted, 5), withOptionField(fitted, 1)}) {
      ObjectNode scenario = fit(Scenarios.knightThenMirror());
      ((ObjectNode) scenario.path("cmd").get(4).path("c").path("sel")).put("pd", item);

      assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
          .isInstanceOf(UnsupportedScenarioException.class)
          .hasMessageContaining("cmd[4].c.sel.pd=" + item);
    }
  }

  @Test
  void readsTheOptionAVariantPlayIsPlayedAsFromItsItem() {
    ObjectNode mountedScenario = fit(Scenarios.mergeMaidenMounted());
    ScenarioPlan mounted = new ReplayScenario(tables).translate(mountedScenario);
    ScenarioPlan onFoot = new ReplayScenario(tables).translate(fit(Scenarios.mergeMaidenOnFoot()));

    ScenarioPlan.Play maiden = mounted.plays().get(0);
    assertThat(maiden.card()).isEqualTo("MergeMaiden");
    // The option field 1 is the first option, the mounted maiden, for its cost.
    assertThat(maiden.option())
        .isEqualTo(
            new ScenarioPlan.Option(
                0, "MergeMaiden_Mounted", ScenarioItems.cost(tables, "MergeMaiden_Mounted")));
    // A Legendary at level index 0: its rarity's RelativeLevel plus 1 of all. Its item is kept
    // whole for the run to check.
    assertThat(maiden.level()).isEqualTo(firstLevel("MergeMaiden"));
    assertThat(maiden.item()).isEqualTo(item(mountedScenario, 0));
    assertThat(maiden.repeats()).isNull();
    // The option field 2 is the second, the maiden on foot; a play of any other card has none.
    assertThat(onFoot.plays().get(1).option())
        .isEqualTo(
            new ScenarioPlan.Option(
                1, "MergeMaiden_Normal", ScenarioItems.cost(tables, "MergeMaiden_Normal")));
    assertThat(onFoot.plays().get(0).option()).isNull();
  }

  @Test
  void refusesAVariantPlayWhoseOptionFieldNamesNoneOfItsOptions() {
    // The option field 0 names no option, and the one past its options one it does not have.
    int options = ScenarioItems.card(tables, "MergeMaiden").columns().get("Options").size();
    int fitted = item(fit(Scenarios.mergeMaidenMounted()), 0);
    for (int item : new int[] {withOptionField(fitted, 0), withOptionField(fitted, options + 1)}) {
      ObjectNode scenario = fit(Scenarios.mergeMaidenMounted());
      ((ObjectNode) scenario.path("cmd").get(0).path("c").path("sel")).put("pd", item);

      assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
          .isInstanceOf(UnsupportedScenarioException.class)
          .hasMessageStartingWith(
              "a variant play whose option field names none of its card's options:"
                  + " cmd[0].c.sel.pd="
                  + item)
          .hasMessageEndingWith("for MergeMaiden, which has " + options + " options");
    }
  }

  @Test
  void refusesAVariantPlayWhoseItemIsNotOneItsDeckCardCanCarry() {
    // The mounted maiden at the on-foot cost, the deck index field 2, the level field one above
    // its own and the slot flags field 1.
    int fitted = item(fit(Scenarios.mergeMaidenMounted()), 0);
    assertThat(ScenarioItems.cost(tables, "MergeMaiden_Normal"))
        .isNotEqualTo(ScenarioItems.costOf(fitted));
    for (int item :
        new int[] {
          ScenarioItems.withCost(fitted, ScenarioItems.cost(tables, "MergeMaiden_Normal")),
          withDeckIndexField(fitted, 2),
          ScenarioItems.withLevelField(fitted, ScenarioItems.levelFieldOf(fitted) + 1),
          fitted | (1 << 19)
        }) {
      ObjectNode scenario = fit(Scenarios.mergeMaidenMounted());
      ((ObjectNode) scenario.path("cmd").get(0).path("c").path("sel")).put("pd", item);

      assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
          .isInstanceOf(UnsupportedScenarioException.class)
          .hasMessageStartingWith(
              "a play whose packed item is not one its deck card can carry: cmd[0].c.sel.pd="
                  + item);
    }
  }

  @Test
  void refusesAVariantCardInADecksEvolutionOrHeroSlot() {
    for (int flags : new int[] {1, 2}) {
      ObjectNode scenario = fit(Scenarios.mergeMaidenMounted());
      ((ObjectNode) scenario.path("battle").path("deck0").path("sp").get(0)).put("el", flags);

      assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
          .isInstanceOf(UnsupportedScenarioException.class)
          .hasMessage(
              "a variant card in a deck's evolution or hero slot, whose item no reference holds:"
                  + " battle.deck0.sp[0].el="
                  + flags);
    }
  }

  @Test
  void readsARareCardsItemAtItsLevelField() {
    ObjectNode scenario = Scenarios.knight();
    ObjectNode body = (ObjectNode) scenario.path("cmd").get(0).path("c");
    // The Giant: deck index 3 (field 4), a Rare at level index 0: its cost and its level field,
    // its rarity's RelativeLevel, from its rows.
    ((ObjectNode) body.path("sel")).put("os", 26000003).put("pd", 4 << 22);
    fit(scenario);
    assertThat(ScenarioItems.levelFieldOf(item(scenario, 0))).isEqualTo(firstLevel("Giant") - 1);

    ScenarioPlan plan = new ReplayScenario(tables).translate(scenario);

    assertThat(plan.plays().get(0).card()).isEqualTo("Giant");
    assertThat(plan.plays().get(0).level()).isEqualTo(firstLevel("Giant"));
  }

  @Test
  void refusesAGameModeOtherThanLadder() {
    ObjectNode scenario = fit(Scenarios.knight());
    ((ObjectNode) scenario.path("battle")).put("gamemode", 72000000);

    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessageContaining("battle.gamemode=72000000");
  }

  @Test
  void refusesEveryCommandOfADataVersionWhoseCommandTypesAreNotEstablished() {
    // The tables' own version has its command types; none given is a version without them.
    assertThatThrownBy(
            () ->
                new ReplayScenario(tables, (CommandTypes) null).translate(fit(Scenarios.knight())))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessage(
            "the command type 153 of data version "
                + tables.version()
                + ", whose command types are not established: cmd[0].ct");
  }

  @Test
  void carriesTheReplaysLastTick() {
    ObjectNode scenario = fit(Scenarios.knight());
    scenario.put("endTick", 3681);

    ScenarioPlan plan = new ReplayScenario(tables).translate(scenario);

    assertThat(plan.plays()).hasSize(1);
    assertThat(new ReplayScenario(tables).survey(scenario)).isEmpty();
  }

  @Test
  void surveysAScenarioTheMappingReadsWithNoRefusal() {
    assertThat(new ReplayScenario(tables).survey(fit(Scenarios.archerQueenAbility()))).isEmpty();
  }

  @Test
  void surveyListsEveryRefusalWhereTranslateStopsAtTheFirst() {
    ObjectNode scenario = fit(Scenarios.knight());
    scenario.putArray("srq").add(1);
    ((ObjectNode) scenario.path("battle")).put("rrb", true);
    ((ObjectNode) scenario.path("battle").path("avatar1")).put("npc", true);
    ArrayNode commands = (ArrayNode) scenario.path("cmd");
    commands.add(commands.get(0).deepCopy());
    ((ObjectNode) commands.get(0)).put("ct", 124);
    ((ObjectNode) commands.get(1)).put("ct", 124);

    ReplayScenario surveyed = new ReplayScenario(tables);
    List<ReplayScenario.Refusal> refusals = surveyed.survey(scenario);

    assertThat(refusals)
        .extracting(ReplayScenario.Refusal::input)
        .containsExactly("srq=[1]", "rrb=true", "npc=true", "cmd[0].ct", "cmd[1].ct");
    assertThat(refusals.get(3).feature()).isEqualTo("the command type 124");
    // Translating the same scenario stops at the first refusal the survey lists.
    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessage(refusals.get(0).feature() + ": " + refusals.get(0).input());
  }

  @Test
  void surveyListsAPlayItCannotReadAndGoesOnToTheNextCommand() {
    ObjectNode scenario = fit(Scenarios.archerQueenAbility());
    // The Knight is not in the decks: the play names a card its side cannot play.
    ((ObjectNode) scenario.path("cmd").get(0).path("c").path("sel")).put("os", 26000000);
    ((ObjectNode) scenario.path("cmd").get(1).path("c")).put("px", 3500);

    List<ReplayScenario.Refusal> refusals = new ReplayScenario(tables).survey(scenario);

    assertThat(refusals).hasSize(2);
    assertThat(refusals.get(0).feature()).contains("Knight, which is not in side 0's deck");
    assertThat(refusals.get(1).input()).isEqualTo("cmd[1].c.px");
  }

  @Test
  void findsTheRowADataIdNamesOrNothing() {
    ReplayScenario mapping = new ReplayScenario(tables);

    assertThat(mapping.find(26000000)).map(row -> row.name()).contains("Knight");
    assertThat(mapping.find(72000006)).map(row -> row.name()).contains("Ladder");
    // No table 99, and no row 999999 of the characters' card table.
    assertThat(mapping.find(99000000)).isEmpty();
    assertThat(mapping.find(26999999)).isEmpty();
  }
}
