/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.expression;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The battle's function names: ids, argument counts and matching. */
class BattleFunctionsTest {

  @Test
  @DisplayName(
      "59 functions with the ids 0 to 58, each once: the 47 of 14.593.1, a newer version's five and"
          + " the seven 16.635.1 adds")
  void fiftyNineIds() {
    assertThat(BattleFunctions.ALL).hasSize(59);
    assertThat(BattleFunctions.byId(47).name()).isEqualTo("ability_charges_left");
    assertThat(BattleFunctions.byId(48).name()).isEqualTo("is_valid_position");
    assertThat(BattleFunctions.byId(49).name()).isEqualTo("is_dodging_damage");
    assertThat(BattleFunctions.byId(50).name()).isEqualTo("is_crown_tower");
    assertThat(BattleFunctions.byId(51).name()).isEqualTo("character_level");
    assertThat(BattleFunctions.byId(52).name()).isEqualTo("is_enough_to_kill_target");
    assertThat(BattleFunctions.byId(53).name()).isEqualTo("is_contained_by_circle");
    assertThat(BattleFunctions.byId(54).name()).isEqualTo("collision_radius");
    assertThat(BattleFunctions.byId(55).name()).isEqualTo("direction_offset_x");
    assertThat(BattleFunctions.byId(56).name()).isEqualTo("direction_offset_y");
    assertThat(BattleFunctions.byId(57).name()).isEqualTo("damage");
    assertThat(BattleFunctions.byId(58).name()).isEqualTo("crown_tower_damage");
    for (int id = 0; id < 59; id++) {
      assertThat(BattleFunctions.byId(id).id()).isEqualTo(id);
    }
  }

  @Test
  @DisplayName(
      "ten take exactly one argument, two an optional one, one exactly two, one exactly three,"
          + " the rest none")
  void argumentCounts() {
    List<String> exactlyOne =
        BattleFunctions.ALL.stream()
            .filter(e -> e.minArguments() == 1 && e.maxArguments() == 1)
            .map(BattleFunctions.Entry::name)
            .toList();
    assertThat(exactlyOne)
        .containsExactlyInAnyOrder(
            "direction_offset_x",
            "direction_offset_y",
            "has_crown_tower_in_range",
            "has_data",
            "is_enough_to_kill_target",
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
    assertThat(BattleFunctions.byName("is_contained_by_circle").minArguments()).isEqualTo(3);
    assertThat(BattleFunctions.byName("is_contained_by_circle").maxArguments()).isEqualTo(3);
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
