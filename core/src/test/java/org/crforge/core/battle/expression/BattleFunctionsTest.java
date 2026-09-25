package org.crforge.core.battle.expression;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The battle's function names: ids, argument counts and matching. */
class BattleFunctionsTest {

  @Test
  @DisplayName("47 functions with the ids 0 to 46, each once")
  void fortySevenIds() {
    assertThat(BattleFunctions.ALL).hasSize(47);
    for (int id = 0; id < 47; id++) {
      assertThat(BattleFunctions.byId(id).id()).isEqualTo(id);
    }
  }

  @Test
  @DisplayName("seven take exactly one argument, two an optional one, the rest none")
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
