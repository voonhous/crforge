package org.crforge.core.battle.expression;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The battle's function names: ids, argument counts and matching. */
class BattleFunctionsTest {

  @Test
  @DisplayName(
      "49 functions with the ids 0 to 48, each once: the 47 of 14.593.1 and a newer version's two")
  void fortyNineIds() {
    assertThat(BattleFunctions.ALL).hasSize(49);
    assertThat(BattleFunctions.byId(47).name()).isEqualTo("ability_charges_left");
    assertThat(BattleFunctions.byId(48).name()).isEqualTo("is_valid_position");
    for (int id = 0; id < 49; id++) {
      assertThat(BattleFunctions.byId(id).id()).isEqualTo(id);
    }
  }

  @Test
  @DisplayName(
      "seven take exactly one argument, two an optional one, one exactly two, the rest none")
  void argumentCounts() {
    List<String> exactlyOne =
        BattleFunctions.ALL.stream()
            .filter(e -> e.minArguments() == 1 && e.maxArguments() == 1)
            .map(BattleFunctions.Entry::name)
            .toList();
    assertThat(exactlyOne)
        .containsExactlyInAnyOrder(
            "has_crown_tower_in_range",
            "has_data",
            "rand",
            "random_chance",
            "target_in_range",
            "team_y_direction",
            "timer");
    assertThat(BattleFunctions.byName("max_hp").maxArguments()).isEqualTo(1);
    assertThat(BattleFunctions.byName("max_hp").minArguments()).isZero();
    assertThat(BattleFunctions.byName("target_max_hp").maxArguments()).isEqualTo(1);
    assertThat(BattleFunctions.byName("king_tower_damaged").maxArguments()).isZero();
    assertThat(BattleFunctions.byName("is_valid_position").minArguments()).isEqualTo(2);
    assertThat(BattleFunctions.byName("is_valid_position").maxArguments()).isEqualTo(2);
  }

  @Test
  @DisplayName("names are matched without regard to case, and others are not names")
  void matching() {
    assertThat(BattleFunctions.byName("HAS_DATA").id()).isEqualTo(29);
    assertThat(BattleFunctions.byName("Rand").id()).isEqualTo(25);
    assertThat(BattleFunctions.byName("min")).isNull();
    assertThat(BattleFunctions.byName("king_tower")).isNull();
  }
}
