/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.crforge.core.battle.unit.PlayerCommands;
import org.junit.jupiter.api.Test;

class SchemaTest {

  @Test
  void theArenaIsEighteenByThirtyTwoTilesOfAThousandUnits() {
    assertThat(Schema.COLS).isEqualTo(18);
    assertThat(Schema.ROWS).isEqualTo(32);
    assertThat(Schema.TILE_UNITS).isEqualTo(1000);
    assertThat(Schema.WIDTH_UNITS).isEqualTo(18_000);
    assertThat(Schema.HEIGHT_UNITS).isEqualTo(32_000);
    assertThat(Schema.ACTIONS).isEqualTo(1 + 4 * 18 * 32);
  }

  @Test
  void everyPlayActionDecodesToTheSlotAndTileThatMadeIt() {
    Set<Integer> seen = new HashSet<>();
    for (int slot = 0; slot < Schema.HAND_SLOTS; slot++) {
      for (int row = 0; row < Schema.ROWS; row++) {
        for (int col = 0; col < Schema.COLS; col++) {
          int action = Schema.play(slot, col, row);
          assertThat(action).isBetween(1, Schema.ACTIONS - 1);
          assertThat(seen.add(action)).as("action %d made twice", action).isTrue();
          assertThat(Schema.slot(action)).isEqualTo(slot);
          assertThat(Schema.col(action)).isEqualTo(col);
          assertThat(Schema.row(action)).isEqualTo(row);
        }
      }
    }
    assertThat(seen).hasSize(Schema.ACTIONS - 1);
    assertThatThrownBy(() -> Schema.slot(Schema.WAIT)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Schema.play(4, 0, 0)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void eachSeatsFrameIsTheGamesViewOfItsSide() {
    // Side 0's view mirrors the width and keeps the length.
    assertThat(Schema.toSeatX(0, 1234)).isEqualTo(Schema.WIDTH_UNITS - 1234);
    assertThat(Schema.toSeatY(0, 5678)).isEqualTo(5678);
    // Side 1's view keeps the width and mirrors the length.
    assertThat(Schema.toSeatX(1, 1234)).isEqualTo(1234);
    assertThat(Schema.toSeatY(1, 5678)).isEqualTo(Schema.HEIGHT_UNITS - 5678);
    // Each is its own inverse, and the two are half a turn apart.
    for (int side = 0; side < 2; side++) {
      assertThat(Schema.toSeatX(side, Schema.toSeatX(side, 1234))).isEqualTo(1234);
      assertThat(Schema.toSeatY(side, Schema.toSeatY(side, 5678))).isEqualTo(5678);
    }
    assertThat(Schema.toSeatX(0, 1234)).isEqualTo(Schema.WIDTH_UNITS - Schema.toSeatX(1, 1234));
    assertThat(Schema.toSeatY(0, 5678)).isEqualTo(Schema.HEIGHT_UNITS - Schema.toSeatY(1, 5678));
    // A tile's centre in either seat's frame is a tile's centre in the game's, row 0 at the
    // seat's own back line.
    int half = Schema.TILE_UNITS / 2;
    assertThat(Schema.gameX(0, 0)).isEqualTo(Schema.WIDTH_UNITS - half);
    assertThat(Schema.gameY(0, 0)).isEqualTo(half);
    assertThat(Schema.gameX(1, 0)).isEqualTo(half);
    assertThat(Schema.gameY(1, 0)).isEqualTo(Schema.HEIGHT_UNITS - half);
    assertThat(Schema.gameX(0, Schema.COLS - 1)).isEqualTo(half);
    assertThat(Schema.gameY(1, Schema.ROWS - 1)).isEqualTo(half);
  }

  @Test
  void theFieldListsMatchTheSizes() {
    assertThat(Schema.headerFields()).hasSize(Schema.HEADER_INTS);
    assertThat(Schema.entityFields()).hasSize(Schema.ENTITY_INTS);
    assertThat(Schema.infoFields()).hasSize(Schema.INFO_INTS);
    assertThat(Schema.headerFields().get(Schema.H_TICK)).isEqualTo("tick");
    assertThat(Schema.headerFields().get(Schema.H_TOWERS + 11)).isEqualTo("enemy_right_max_hp");
    assertThat(Schema.headerFields().get(Schema.H_ENEMY_SEEN + 7)).isEqualTo("enemy_seen");
    assertThat(Schema.headerFields().get(Schema.H_EPISODE_STEP)).isEqualTo("episode_step");
    assertThat(Schema.headerFields().get(Schema.H_ILLEGAL)).isEqualTo("illegal");
    assertThat(
            Schema.headerFields()
                .get(Schema.H_PENDING + Schema.PENDING_ROWS * Schema.PENDING_INTS - 1))
        .isEqualTo("pending_ticks");
    assertThat(Schema.infoFields().get(Schema.I_NOT_MODELLED)).isEqualTo("not_modelled");
    assertThat(Schema.SEAT_INTS)
        .isEqualTo(Schema.HEADER_INTS + Schema.MAX_ENTITIES * Schema.ENTITY_INTS);
    assertThat(Schema.flagNames()).hasSize(Integer.numberOfTrailingZeros(Schema.F_KING) + 1);
    assertThat(Schema.faultNames().get(Schema.FAULT_ENV_BUG)).isEqualTo("env_bug");
  }

  @Test
  void theHeaderFieldsDoNotOverlap() {
    // Every named block ends where the next begins.
    assertThat(Schema.H_HAND_CARD + Schema.HAND_SLOTS).isEqualTo(Schema.H_HAND_COST);
    assertThat(Schema.H_HAND_COST + Schema.HAND_SLOTS).isEqualTo(Schema.H_HAND_PENDING);
    assertThat(Schema.H_HAND_PENDING + Schema.HAND_SLOTS).isEqualTo(Schema.H_NEXT_CARD);
    assertThat(Schema.H_NEXT_CARD + 1).isEqualTo(Schema.H_DECK);
    assertThat(Schema.H_DECK + Schema.DECK_SIZE).isEqualTo(Schema.H_ENEMY_SEEN);
    assertThat(Schema.H_ENEMY_SEEN + Schema.DECK_SIZE).isEqualTo(Schema.H_TOWERS);
    assertThat(Schema.H_TOWERS + 12).isEqualTo(Schema.H_ENTITY_COUNT);
    assertThat(Schema.H_ILLEGAL + 1).isEqualTo(Schema.H_PENDING);
    assertThat(Schema.H_PENDING + Schema.PENDING_ROWS * Schema.PENDING_INTS)
        .isLessThanOrEqualTo(Schema.HEADER_INTS);
  }

  @Test
  void theRefusalCodesAreTheCommandsOwnAndPinnedByTheVersion() {
    // A change of the player's commands' refusals moves the codes, and with them the schema's
    // version: this list is what version 1 means.
    assertThat(Schema.refusalNames().subList(0, 16))
        .containsExactly(
            "NONE",
            "MATCH_OVER",
            "KING_DEAD",
            "NOT_IN_HAND",
            "START_COOLDOWN",
            "CARD_PENDING",
            "NOT_ENOUGH_ELIXIR",
            "NO_TILE",
            "NO_CHAMPION",
            "COOLDOWN",
            "NO_CHARGES",
            "NOT_CHAMPION",
            "ABILITY_PENDING",
            "CLONE",
            "FROZEN",
            "ABILITY_DISABLED");
    assertThat(PlayerCommands.Refusal.values()).hasSize(16);
    assertThat(Schema.refusalNames().get(Schema.REFUSAL_NONE)).isEqualTo("NONE");
    assertThat(Schema.refusalNames().get(Schema.REFUSAL_MASKED)).isEqualTo("MASKED");
    assertThat(Schema.refusalNames().get(Schema.REFUSAL_NOT_MODELLED)).isEqualTo("NOT_MODELLED");
    assertThat(Schema.VERSION).isEqualTo(1);
  }
}
