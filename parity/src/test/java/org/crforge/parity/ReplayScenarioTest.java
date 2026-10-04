package org.crforge.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.crforge.core.battle.data.GameTables;
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
    assertThat(plan.towerLevel()).isEqualTo(1);
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
            new ScenarioPlan.Play(0, 200, 220, 0, "Knight", 1, 3500, 14000, 0x30400000));
    // No deck item names slot flags: every card is in neither slot.
    assertThat(plan.slotFlags().get(0)).containsOnly(0);
    assertThat(plan.slotFlags().get(1)).containsOnly(0);
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
  void refusesATowerSelectionTheSimulatorDoesNotBuild() {
    ObjectNode scenario = Scenarios.knight();
    ((ObjectNode) scenario.path("battle").path("deck1").path("sc").get(0)).put("d", 159000001);

    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessageContaining("battle.deck1.sc[0].d=159000001");
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
            new ScenarioPlan.Play(0, 200, 220, 0, "ArcherQueen", 11, 3500, 14000, 0x50402800));
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
}
