package org.crforge.core.battle.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Reading a folder of game tables, on small hand-made tables that stand in for the real ones. */
class GameTablesTest {

  private static Path folder(String name) throws URISyntaxException {
    return Paths.get(GameTablesTest.class.getResource("/game_tables/" + name).toURI());
  }

  @Test
  @DisplayName("a folder's tables load with their header and their rows in creation order")
  void tablesLoad() throws Exception {
    GameTables tables = GameTables.load(folder("synthetic"));
    assertThat(tables.version()).isEqualTo("0.0.1");
    assertThat(tables.tableNames()).containsExactlyInAnyOrder("characters", "game_tags");
    GameTable characters = tables.table("characters");
    assertThat(characters.id()).isEqualTo("34");
    assertThat(characters.rows()).extracting(GameRow::name).containsExactly("Alpha", "Beta");
    assertThat(characters.row("Beta").index()).isEqualTo(1);
    assertThat(characters.row("Beta").className()).isEqualTo("LogicCharacterData");
    assertThat(characters.has("Gamma")).isFalse();
    assertThat(tables.table("game_tags").row("SLEEPING").index()).isEqualTo(1);
  }

  @Test
  @DisplayName("a row's values are read by type, an absent or empty one as the client reads it")
  void typedValues() throws Exception {
    GameRow alpha = GameTables.load(folder("synthetic")).table("characters").row("Alpha");
    assertThat(alpha.intValue("Hitpoints")).isEqualTo(700);
    assertThat(alpha.bool("AttacksAir")).isTrue();
    assertThat(alpha.string("Rarity")).isEqualTo("Common");
    assertThat(alpha.strings("Tags")).containsExactly("A", "B");
    assertThat(alpha.has("DeployTime")).isFalse();
    assertThat(alpha.intValue("DeployTime")).as("an empty cell reads as 0").isZero();
    assertThat(alpha.intValue("NoSuchColumn")).isZero();
    assertThat(alpha.bool("NoSuchColumn")).isFalse();
    assertThat(alpha.string("Projectile")).isEmpty();
    assertThat(alpha.strings("NoSuchColumn")).isEmpty();
  }

  @Test
  @DisplayName(
      "a value of a shape the reader does not read is refused, naming the row, column and shape")
  void aValueOfAnotherShapeIsRefused() throws Exception {
    GameRow beta = GameTables.load(folder("synthetic")).table("characters").row("Beta");
    assertThatThrownBy(() -> beta.intValue("Damage"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(
            "the characters row Beta sets Damage to a table of BaseDamage, TowerDamage where a"
                + " number is read, which is not modelled");
    assertThatThrownBy(() -> beta.intValue("DeathDamage"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(
            "the characters row Beta sets DeathDamage to the text \"RageDamage\" where a number is"
                + " read, which is not modelled");
    assertThatThrownBy(() -> beta.intValue("Speed"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(
            "the characters row Beta sets Speed to the fraction 1.5 where a number is read, which"
                + " is not modelled");
    assertThatThrownBy(() -> beta.bool("HitsAir"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(
            "the characters row Beta sets HitsAir to the number 1 where a boolean is read, which is"
                + " not modelled");
    assertThatThrownBy(() -> beta.string("SummonCharacter"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(
            "the characters row Beta sets SummonCharacter to a table of Name where a text is read,"
                + " which is not modelled");
    assertThatThrownBy(() -> beta.strings("IgnoreBuff"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(
            "the characters row Beta sets IgnoreBuff to the text \"Haste\" where a list of texts is"
                + " read, which is not modelled");
    assertThatThrownBy(() -> beta.ints("SpawnCounts"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(
            "the characters row Beta sets SpawnCounts to a list holding the text \"two\" where a"
                + " list of numbers is read, which is not modelled");
    assertThat(beta.intValue("Empty")).as("an empty cell reads as 0").isZero();
    assertThat(beta.bool("Empty")).as("an empty cell reads as false").isFalse();
    assertThat(beta.strings("Empty")).as("an empty cell reads as no list").isEmpty();
    assertThat(beta.ints("Empty")).as("an empty cell reads as no list").isEmpty();
    assertThat(beta.value("Damage").get("BaseDamage").asInt())
        .as("the raw value stays readable")
        .isEqualTo(75);
  }

  @Test
  @DisplayName("a tracking view records every column read through it; a plain row tracks nothing")
  void aTrackingViewRecordsItsReads() throws Exception {
    GameRow alpha = GameTables.load(folder("synthetic")).table("characters").row("Alpha");
    GameRow tracked = alpha.tracking();
    assertThat(tracked.read()).isEmpty();

    assertThat(tracked.intValue("Hitpoints")).isEqualTo(700);
    tracked.bool("AttacksAir");
    tracked.string("NoSuchColumn");
    tracked.strings("Tags");
    tracked.value("DeployTime");

    assertThat(tracked.read())
        .as("an absent or empty column read counts too")
        .containsExactlyInAnyOrder("Hitpoints", "AttacksAir", "NoSuchColumn", "Tags", "DeployTime");
    assertThat(alpha.tracking().read()).as("each view starts from none").isEmpty();
    assertThatThrownBy(alpha::read).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("the action graph loads by name, with its class and its fields")
  void actions() throws Exception {
    GameTables tables = GameTables.load(folder("synthetic"));
    GameAction wait = tables.action("Alpha_Wait");
    assertThat(wait.className()).isEqualTo("LogicActionWaitToActivateData");
    assertThat(wait.classType()).isEqualTo("ActionWaitToActivate");
    assertThat(wait.fields().get("Condition").asText()).isEqualTo("1");
    assertThat(tables.actionNames()).isEqualTo(List.of("Alpha_Wait", "Alpha_Curse", "Alpha_Haste"));
  }

  @Test
  @DisplayName(
      "a buff a spawn row writes inline is a buff row of its Name, outside every table; a buff it"
          + " names is none")
  void inlineBuffs() throws Exception {
    GameTables tables = GameTables.load(folder("synthetic"));
    GameRow curse = tables.inlineBuff("Alpha_Curse_Buff");
    assertThat(curse.name()).isEqualTo("Alpha_Curse_Buff");
    assertThat(curse.className()).isEqualTo("LogicCharacterBuffData");
    assertThat(curse.index()).isEqualTo(-1);
    assertThat(curse.intValue("DamagePerSecond")).isEqualTo(100);
    assertThat(curse.string("Rarity")).isEqualTo("Common");
    assertThat(tables.inlineBuff("Haste")).isNull();
    assertThat(tables.inlineBuff("Alpha_Curse")).isNull();
  }

  @Test
  @DisplayName("tables of two versions in one folder are refused")
  void mixedVersionsAreRefused() {
    assertThatThrownBy(() -> GameTables.load(folder("mixed")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("0.0.2");
  }

  @Test
  @DisplayName("a missing folder or table fails loudly and names the setting")
  void missingFails() throws Exception {
    assertThatThrownBy(() -> GameTables.load(Paths.get("/no/such/game/tables")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(GameTables.PROPERTY)
        .hasMessageContaining(GameTables.ENVIRONMENT);
    GameTables tables = GameTables.load(folder("synthetic"));
    assertThatThrownBy(() -> tables.table("buildings")).isInstanceOf(IllegalStateException.class);
  }
}
