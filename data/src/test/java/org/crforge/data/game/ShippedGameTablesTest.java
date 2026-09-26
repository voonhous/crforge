package org.crforge.data.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The real game tables, when a folder of them is configured; skipped otherwise. They hold the
 * game's own values in its own units.
 */
class ShippedGameTablesTest {

  private static GameTables tables;

  @BeforeAll
  static void load() {
    assumeTrue(GameTables.configuredDirectory().isPresent(), "no game tables configured");
    tables = GameTables.loadConfigured();
  }

  @Test
  @DisplayName("a character's columns are the game's, in milliseconds and game units")
  void aCharacterInGameUnits() {
    GameRow knight = tables.table("characters").row("Knight");
    assertThat(knight.intValue("Hitpoints")).isEqualTo(690);
    assertThat(knight.intValue("HitSpeed")).isEqualTo(1200);
    assertThat(knight.intValue("LoadTime")).isEqualTo(700);
    assertThat(knight.intValue("Range")).isEqualTo(1200);
    assertThat(knight.intValue("CollisionRadius")).isEqualTo(500);
    assertThat(knight.intValue("Speed")).isEqualTo(60);
  }

  @Test
  @DisplayName("every table the battle reads is there, and the game tags sit at their bits")
  void theTablesAreThere() {
    assertThat(tables.tableNames())
        .contains(
            "characters",
            "buildings",
            "projectiles",
            "character_buffs",
            "area_effect_objects",
            "spells_characters",
            "rarities",
            "game_object_filters",
            "variables",
            "damage_types",
            "game_tags");
    assertThat(tables.table("game_tags").row("INACTIVE").index()).isEqualTo(20);
    assertThat(tables.actionNames()).isNotEmpty();
  }
}
