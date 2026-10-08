package org.crforge.core.battle.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The real game tables, which must be configured: without them this test fails, naming the setting.
 * They hold the game's own values in its own units.
 */
class ShippedGameTablesTest {

  private static GameTables tables;

  @BeforeAll
  static void load() {
    tables = GameTables.loadConfigured();
  }

  @Test
  @DisplayName("a character's columns are the game's, in milliseconds and game units, unconverted")
  void aCharacterInGameUnits() {
    GameRow knight = tables.table("characters").row("Knight");
    for (String column :
        List.of("Hitpoints", "HitSpeed", "LoadTime", "Range", "CollisionRadius", "Speed")) {
      // Each is a whole number as the table writes it, read as it is.
      assertThat(knight.columns().get(column).isIntegralNumber()).as(column).isTrue();
      assertThat(knight.intValue(column))
          .as(column)
          .isEqualTo(knight.columns().get(column).asInt())
          .isPositive();
    }
    // A time is in milliseconds, a distance in game units of 1000 a tile: the Knight hits more
    // slowly than one tick and reaches beyond a tenth of a tile.
    assertThat(knight.intValue("HitSpeed")).isGreaterThan(50);
    assertThat(knight.intValue("Range")).isGreaterThan(100);
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
    // Each tag's index is its place in the table, from 0: the bit it sits at.
    List<Integer> indices = new ArrayList<>();
    for (GameRow tag : tables.table("game_tags").rows()) {
      indices.add(tag.index());
    }
    assertThat(indices).isEqualTo(IntStream.range(0, indices.size()).boxed().toList());
    assertThat(tables.actionNames()).isNotEmpty();
  }
}
