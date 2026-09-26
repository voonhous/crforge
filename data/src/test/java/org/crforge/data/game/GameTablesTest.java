package org.crforge.data.game;

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
  @DisplayName("the action graph loads by name, with its class and its fields")
  void actions() throws Exception {
    GameTables tables = GameTables.load(folder("synthetic"));
    GameAction wait = tables.action("Alpha_Wait");
    assertThat(wait.className()).isEqualTo("LogicActionWaitToActivateData");
    assertThat(wait.classType()).isEqualTo("ActionWaitToActivate");
    assertThat(wait.fields().get("Condition").asText()).isEqualTo("1");
    assertThat(tables.actionNames()).isEqualTo(List.of("Alpha_Wait"));
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
