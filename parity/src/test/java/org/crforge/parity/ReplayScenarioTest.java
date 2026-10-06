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

class ReplayScenarioTest {

  private static GameTables tables;

  @BeforeAll
  static void loadTables() {
    tables = GameTables.loadConfigured();
  }

  @Test
  void translatesTheDecksLevelsAccountsAndThePlay() {
    ScenarioPlan plan = new ReplayScenario(tables).translate(Scenarios.knight());

    assertThat(plan.seed()).isEqualTo(1131);
    // Both sides select the princess towers at level index 0: every tower at level 1.
    assertThat(plan.towers())
        .containsExactly(
            new Standard1v1Battle.Towers("King_PrincessTowers", 1, 1),
            new Standard1v1Battle.Towers("King_PrincessTowers", 1, 1));
    assertThat(plan.decks().get(0))
        .containsExactly(
            "Knight", "Archer", "Goblins", "Giant", "Minions", "Musketeer", "Fireball", "Arrows");
    assertThat(plan.decks().get(1)).isEqualTo(plan.decks().get(0));
    // A level index counts from the rarity's first level: a Rare's first is level 3 of all.
    assertThat(plan.deckLevels().get(0)).containsExactly(1, 1, 1, 3, 1, 3, 3, 1);
    assertThat(plan.accounts().get(0)).containsExactly(0, 1);
    assertThat(plan.accounts().get(1)).containsExactly(0, 2);
    assertThat(plan.playerDataChoices()).containsExactly(1, 1);
    assertThat(plan.plays())
        .containsExactly(
            new ScenarioPlan.Play(
                0, 200, 220, 0, "Knight", 1, 3500, 14000, 0x30400000, null, null));
    // No deck item names slot flags: every card is in neither slot.
    assertThat(plan.slotFlags().get(0)).containsOnly(0);
    assertThat(plan.slotFlags().get(1)).containsOnly(0);
  }

  @Test
  void carriesTheArenaOfAnyTrophyArena() {
    // The arena names the players' trophy arena, which sets no battle input; the map is the
    // location's.
    ObjectNode scenario = Scenarios.knight();
    ObjectNode battle = (ObjectNode) scenario.path("battle");
    battle.put("arena", 54000020);
    ((ObjectNode) battle.path("avatar0")).put("arena", 54000020);
    ((ObjectNode) battle.path("avatar1")).put("arena", 54000020);

    assertThat(new ReplayScenario(tables).survey(scenario)).isEmpty();
  }

  @Test
  void readsADeckCardsSlotFlagsAndTheItemsOfItsEvolutionSlotsPlays() {
    ScenarioPlan plan = new ReplayScenario(tables).translate(Scenarios.knightEvolvedThirdPlay());

    // The Knight's el 1: the evolution slot, bit 0.
    assertThat(plan.slotFlags().get(0)).containsExactly(1, 0, 0, 0, 0, 0, 0, 0);
    assertThat(plan.slotFlags().get(1)).containsOnly(0);
    // The Knight's three items are kept for the run, which checks them against the items the
    // simulator builds as each play runs: the count plus 1, then the evolution field.
    assertThat(
            plan.plays().stream()
                .filter(play -> play.card().equals("Knight"))
                .map(ScenarioPlan.Play::item))
        .containsExactly(0x30480080, 0x30480100, 0x30480181);
    assertThat(plan.plays()).hasSize(11);
  }

  @Test
  void refusesSlotFlagsOtherThanTheEvolutionAndHeroSlots() {
    ObjectNode scenario = Scenarios.knight();
    ((ObjectNode) scenario.path("battle").path("deck1").path("sp").get(2)).put("el", 4);

    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessageContaining("battle.deck1.sp[2].el=4");
  }

  @Test
  void refusesAnItemWhoseSlotFlagsAreNotItsDeckCards() {
    ObjectNode scenario = Scenarios.knight();
    ((ObjectNode) scenario.path("battle").path("deck0").path("sp").get(0)).put("el", 1);
    // The plain item, whose flags field (bits 19..21) is 0, for an evolution slot's card.
    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessageContaining("slot flags field 0");
  }

  @Test
  void refusesACountOnTheItemOfACardOutsideTheEvolutionSlot() {
    ObjectNode scenario = Scenarios.knight();
    // The count plus 1, bits 7..9, which only an evolution slot's card carries.
    ((ObjectNode) scenario.path("cmd").get(0).path("c").path("sel")).put("pd", 0x30400080);

    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessageContaining("count field 1");
  }

  @Test
  void refusesAPlayersDataWhoseChoicesAreNotEstablished() {
    ObjectNode scenario = Scenarios.knight();
    ((ObjectNode) scenario.path("battle").path("hbd").get(1).path("em")).putArray("de").add(1);

    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessageContaining("battle.hbd=");
  }

  @Test
  void namesTheSideOfAPlayByItsAccount() {
    ObjectNode scenario = Scenarios.knight();
    ((ObjectNode) scenario.path("cmd").get(0).path("c")).put("idLo", 2);

    ScenarioPlan plan = new ReplayScenario(tables).translate(scenario);

    assertThat(plan.plays().get(0).side()).isEqualTo(1);
  }

  @Test
  void buildsTheCannoneerTowersOfASidesTowerSelection() {
    ObjectNode scenario = Scenarios.knight();
    ((ObjectNode) scenario.path("battle").path("deck1").path("sc").get(0)).put("d", 159000001);

    ScenarioPlan plan = new ReplayScenario(tables).translate(scenario);

    // Row 1 of the tower selections, King_CannonTowers, an Epic selection: its Cannoneer rows stand
    // five levels above the first, its king tower at the first. Side 0 keeps the princess towers.
    assertThat(plan.towers())
        .containsExactly(
            new Standard1v1Battle.Towers("King_PrincessTowers", 1, 1),
            new Standard1v1Battle.Towers("King_CannonTowers", 1, 6));
  }

  @Test
  void buildsTheDaggerDuchessTowersOfASidesTowerSelection() {
    ObjectNode scenario = Scenarios.knight();
    ((ObjectNode) scenario.path("battle").path("deck1").path("sc").get(0)).put("d", 159000002);

    ScenarioPlan plan = new ReplayScenario(tables).translate(scenario);

    // Row 2 of the tower selections, King_KnifeTowers, a Legendary selection: its DaggerDuchess
    // rows stand eight levels above the first, its king tower at the first.
    assertThat(plan.towers())
        .containsExactly(
            new Standard1v1Battle.Towers("King_PrincessTowers", 1, 1),
            new Standard1v1Battle.Towers("King_KnifeTowers", 1, 9));
  }

  @Test
  void buildsTheRoyalChefTowersOfASidesTowerSelection() {
    ObjectNode scenario = Scenarios.knight();
    ((ObjectNode) scenario.path("battle").path("deck1").path("sc").get(0)).put("d", 159000004);

    ScenarioPlan plan = new ReplayScenario(tables).translate(scenario);

    // Row 4 of the tower selections, King_ChefTowers, a Legendary selection: its ChefTower rows
    // stand eight levels above the first, its king row at the first.
    assertThat(plan.towers())
        .containsExactly(
            new Standard1v1Battle.Towers("King_PrincessTowers", 1, 1),
            new Standard1v1Battle.Towers("King_ChefTowers", 1, 9));
  }

  @Test
  void readsEachSidesTowerLevelFromItsOwnSelection() {
    ObjectNode scenario = Scenarios.knight();
    ((ObjectNode) scenario.path("battle").path("deck0").path("sc").get(0)).put("l", 2);
    ((ObjectNode) scenario.path("battle").path("deck1").path("sc").get(0))
        .put("d", 159000001)
        .put("l", 10);

    ScenarioPlan plan = new ReplayScenario(tables).translate(scenario);

    // The level index plus the selection rarity's RelativeLevel plus 1: Common 0, Epic 5.
    assertThat(plan.towers().get(0).level()).isEqualTo(3);
    assertThat(plan.towers().get(1).level()).isEqualTo(16);
  }

  @Test
  void buildsEachKingTowerAtTheAvatarsLevelWhateverTheSelectionLevel() {
    ObjectNode scenario = Scenarios.knight();
    ((ObjectNode) scenario.path("battle").path("deck0").path("sc").get(0)).put("l", 2);
    ((ObjectNode) scenario.path("battle").path("deck1").path("sc").get(0))
        .put("d", 159000001)
        .put("l", 10);

    ScenarioPlan plan = new ReplayScenario(tables).translate(scenario);

    // The king row's level comes from the avatar's exp level, not from the tower selection: the
    // first level at exp level 1, the only exp level the adapter accepts.
    assertThat(plan.towers())
        .containsExactly(
            new Standard1v1Battle.Towers("King_PrincessTowers", 1, 3),
            new Standard1v1Battle.Towers("King_CannonTowers", 1, 16));
  }

  @Test
  void refusesATowerLevelOutsideItsSelectionsLevels() {
    for (int level : new int[] {11, -1}) {
      ObjectNode scenario = Scenarios.knight();
      // An Epic selection has 11 levels, index 0 to 10.
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
    ObjectNode scenario = Scenarios.knight();
    ((ObjectNode) scenario.path("battle").path("deck1").path("sc").get(0)).put("d", 159000003);

    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessageContaining("battle.deck1.sc[0].d=159000003")
        .hasMessageNotContaining("hold no table");
  }

  @Test
  void refusesACommandOfAnotherType() {
    ObjectNode scenario = Scenarios.knight();
    ((ObjectNode) scenario.path("cmd").get(0)).put("ct", 153);

    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessageContaining("the command type 153");
  }

  @Test
  void readsAnAbilityCommandThatNamesItsUnitByGameObjectId() {
    ScenarioPlan plan = new ReplayScenario(tables).translate(Scenarios.archerQueenAbility());

    assertThat(plan.plays())
        .containsExactly(
            new ScenarioPlan.Play(
                0, 200, 220, 0, "ArcherQueen", 11, 3500, 14000, 0x50402800, null, null));
    assertThat(plan.abilities()).containsExactly(new ScenarioPlan.Ability(1, 330, 350, 0, 5000006));
  }

  @Test
  void namesTheSideOfAnAbilityCommandByItsAccount() {
    ObjectNode scenario = Scenarios.archerQueenAbility();
    ((ObjectNode) scenario.path("cmd").get(1).path("c")).put("idLo", 2);

    ScenarioPlan plan = new ReplayScenario(tables).translate(scenario);

    assertThat(plan.abilities().get(0).side()).isEqualTo(1);
  }

  @Test
  void refusesAnAbilityCommandFieldItHasNoMappingFor() {
    ObjectNode scenario = Scenarios.archerQueenAbility();
    // A unit's row and play are not read from an ability command's fields.
    ((ObjectNode) scenario.path("cmd").get(1).path("c")).put("px", 3500);

    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessageContaining("cmd[1].c.px");
  }

  @Test
  void refusesAnotherValueOfAFieldWithNoProductionInput() {
    ObjectNode scenario = Scenarios.knight();
    ((ObjectNode) scenario.path("battle")).put("hm", true);

    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessageContaining("hm=true");
  }

  @Test
  void refusesAFieldItHasNoMappingFor() {
    ObjectNode scenario = Scenarios.knight();
    ((ObjectNode) scenario.path("battle")).put("event", 1);

    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessageContaining("battle.event");
  }

  @Test
  void refusesAPlayWhoseItemIsNotItsDeckCardsPlainItem() {
    ObjectNode scenario = Scenarios.knight();
    // The level field, bits 10..16, set to 1 for a deck card of level index 0.
    ((ObjectNode) scenario.path("cmd").get(0).path("c").path("sel")).put("pd", 0x30400400);

    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessageContaining("packed item");
  }

  @Test
  void refusesAPlayWhoseItemNamesAnotherDeckIndex() {
    ObjectNode scenario = Scenarios.knight();
    // The deck index field, bits 22..27, is the index plus 1: 2 names index 1, not the Knight's 0.
    ((ObjectNode) scenario.path("cmd").get(0).path("c").path("sel")).put("pd", 0x30800000);

    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessageContaining("deck index field 2");
  }

  @Test
  void refusesAPlayWhoseItemSetsAnEvolutionBit() {
    ObjectNode scenario = Scenarios.knight();
    // The evolution field 1 on a card in neither slot, which is never evolved.
    ((ObjectNode) scenario.path("cmd").get(0).path("c").path("sel")).put("pd", 0x30400001);

    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessageContaining("evolution field 1");
  }

  @Test
  void refusesAPlayWhoseItemSetsAnOptionOrCosmeticBit() {
    for (int bits : new int[] {0x10, 0x20000}) {
      ObjectNode scenario = Scenarios.knight();
      ((ObjectNode) scenario.path("cmd").get(0).path("c").path("sel")).put("pd", 0x30400000 | bits);

      assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
          .isInstanceOf(UnsupportedScenarioException.class)
          .hasMessageContaining("packed item");
    }
  }

  @Test
  void readsTheCardAMirrorPlayRepeatsFromItsItem() {
    ScenarioPlan plan = new ReplayScenario(tables).translate(Scenarios.knightThenMirror());

    ScenarioPlan.Play mirror = plan.plays().get(4);
    assertThat(mirror.card()).isEqualTo("Mirror");
    assertThat(mirror.repeats()).isEqualTo(new ScenarioPlan.Repeated(Scenarios.KNIGHT, "Knight"));
    // An Epic at level index 0: level 6 of all. Its item is kept whole for the run to check.
    assertThat(mirror.level()).isEqualTo(6);
    assertThat(mirror.item()).isEqualTo(0x41801800);
    // A play of any other card repeats nothing.
    assertThat(plan.plays().subList(0, 4)).allMatch(play -> play.repeats() == null);
  }

  @Test
  void refusesARepeatedCardOnAPlayOfACardOtherThanTheMirror() {
    ObjectNode scenario = Scenarios.knightThenMirror();
    ((ObjectNode) scenario.path("cmd").get(3).path("c").path("sel")).put("fs", Scenarios.ARCHER);

    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessageContaining("cmd[3].c.sel.fs=" + Scenarios.ARCHER);
  }

  @Test
  void refusesAMirrorPlayThatNamesNoRepeatedCard() {
    ObjectNode scenario = Scenarios.knightThenMirror();
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
    for (int item : new int[] {0x41401800, 0x41801810}) {
      ObjectNode scenario = Scenarios.knightThenMirror();
      ((ObjectNode) scenario.path("cmd").get(4).path("c").path("sel")).put("pd", item);

      assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
          .isInstanceOf(UnsupportedScenarioException.class)
          .hasMessageContaining("cmd[4].c.sel.pd=" + item);
    }
  }

  @Test
  void readsTheOptionAVariantPlayIsPlayedAsFromItsItem() {
    ScenarioPlan mounted = new ReplayScenario(tables).translate(Scenarios.mergeMaidenMounted());
    ScenarioPlan onFoot = new ReplayScenario(tables).translate(Scenarios.mergeMaidenOnFoot());

    ScenarioPlan.Play maiden = mounted.plays().get(0);
    assertThat(maiden.card()).isEqualTo("MergeMaiden");
    // The option field 1 is the first option, the mounted maiden, for its cost.
    assertThat(maiden.option()).isEqualTo(new ScenarioPlan.Option(0, "MergeMaiden_Mounted", 6));
    // A Legendary at level index 0: level 9 of all. Its item is kept whole for the run to check.
    assertThat(maiden.level()).isEqualTo(9);
    assertThat(maiden.item()).isEqualTo(Scenarios.MOUNTED_MAIDEN_ITEM);
    assertThat(maiden.repeats()).isNull();
    // The option field 2 is the second, the maiden on foot; a play of any other card has none.
    assertThat(onFoot.plays().get(1).option())
        .isEqualTo(new ScenarioPlan.Option(1, "MergeMaiden_Normal", 3));
    assertThat(onFoot.plays().get(0).option()).isNull();
  }

  @Test
  void refusesAVariantPlayWhoseOptionFieldNamesNoneOfItsOptions() {
    // The option field 0 names no option, and 3 a third one the Merge Maiden does not have.
    for (int item : new int[] {0x60402000, 0x60402030}) {
      ObjectNode scenario = Scenarios.mergeMaidenMounted();
      ((ObjectNode) scenario.path("cmd").get(0).path("c").path("sel")).put("pd", item);

      assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
          .isInstanceOf(UnsupportedScenarioException.class)
          .hasMessageStartingWith(
              "a variant play whose option field names none of its card's options:"
                  + " cmd[0].c.sel.pd="
                  + item)
          .hasMessageEndingWith("for MergeMaiden, which has 2 options");
    }
  }

  @Test
  void refusesAVariantPlayWhoseItemIsNotOneItsDeckCardCanCarry() {
    // The mounted maiden at the on-foot cost, the deck index field 2, the level field 9 and the
    // slot flags field 1.
    for (int item : new int[] {0x30402010, 0x60802010, 0x60402410, 0x60482010}) {
      ObjectNode scenario = Scenarios.mergeMaidenMounted();
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
      ObjectNode scenario = Scenarios.mergeMaidenMounted();
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
    // The Giant: deck index 3, cost 5, a Rare at level index 0, so level field 2.
    ((ObjectNode) body.path("sel")).put("os", 26000003).put("pd", 0x51000800);

    ScenarioPlan plan = new ReplayScenario(tables).translate(scenario);

    assertThat(plan.plays().get(0).card()).isEqualTo("Giant");
    assertThat(plan.plays().get(0).level()).isEqualTo(3);
  }

  @Test
  void refusesAGameModeOtherThanLadder() {
    ObjectNode scenario = Scenarios.knight();
    ((ObjectNode) scenario.path("battle")).put("gamemode", 72000000);

    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessageContaining("battle.gamemode=72000000");
  }

  @Test
  void refusesEveryCommandOfADataVersionWhoseCommandTypesAreNotEstablished() {
    // The tables' own version has its command types; none given is a version without them.
    assertThatThrownBy(
            () -> new ReplayScenario(tables, (CommandTypes) null).translate(Scenarios.knight()))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessage(
            "the command type 124 of data version "
                + tables.version()
                + ", whose command types are not established: cmd[0].ct");
  }

  @Test
  void carriesTheReplaysLastTick() {
    ObjectNode scenario = Scenarios.knight();
    scenario.put("endTick", 3681);

    ScenarioPlan plan = new ReplayScenario(tables).translate(scenario);

    assertThat(plan.plays()).hasSize(1);
    assertThat(new ReplayScenario(tables).survey(scenario)).isEmpty();
  }

  @Test
  void surveysAScenarioTheMappingReadsWithNoRefusal() {
    assertThat(new ReplayScenario(tables).survey(Scenarios.archerQueenAbility())).isEmpty();
  }

  @Test
  void surveyListsEveryRefusalWhereTranslateStopsAtTheFirst() {
    ObjectNode scenario = Scenarios.knight();
    scenario.putArray("srq");
    ((ObjectNode) scenario.path("battle")).put("rrb", false);
    ((ObjectNode) scenario.path("battle").path("avatar1")).put("expLevel", 14);
    ArrayNode commands = (ArrayNode) scenario.path("cmd");
    commands.add(commands.get(0).deepCopy());
    ((ObjectNode) commands.get(0)).put("ct", 153);
    ((ObjectNode) commands.get(1)).put("ct", 153);

    ReplayScenario surveyed = new ReplayScenario(tables);
    List<ReplayScenario.Refusal> refusals = surveyed.survey(scenario);

    assertThat(refusals)
        .extracting(ReplayScenario.Refusal::input)
        .containsExactly("$.srq", "battle.rrb", "expLevel=14", "cmd[0].ct", "cmd[1].ct");
    assertThat(refusals.get(3).feature()).isEqualTo("the command type 153");
    // Translating the same scenario stops at the first refusal the survey lists.
    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessage(refusals.get(0).feature() + ": " + refusals.get(0).input());
  }

  @Test
  void surveyListsAPlayItCannotReadAndGoesOnToTheNextCommand() {
    ObjectNode scenario = Scenarios.archerQueenAbility();
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
