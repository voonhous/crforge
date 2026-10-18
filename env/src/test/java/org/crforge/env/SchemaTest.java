/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.crforge.core.battle.unit.BuffInstance;
import org.crforge.core.battle.unit.PlayerCommands;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.Test;

class SchemaTest {

  @Test
  void theArenaIsEighteenByThirtyTwoTilesOfAThousandUnits() {
    assertThat(Schema.COLS).isEqualTo(18);
    assertThat(Schema.ROWS).isEqualTo(32);
    assertThat(Schema.TILE_UNITS).isEqualTo(1000);
    assertThat(Schema.WIDTH_UNITS).isEqualTo(18_000);
    assertThat(Schema.HEIGHT_UNITS).isEqualTo(32_000);
    assertThat(Schema.ACTIONS).isEqualTo(1 + 4 * 18 * 32 + 2);
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
    assertThat(seen).hasSize(Schema.FIRST_ABILITY - 1);
    assertThatThrownBy(() -> Schema.slot(Schema.WAIT)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Schema.play(4, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Schema.slot(Schema.FIRST_ABILITY))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theAbilityActionsComeAfterEveryPlayOnePerChampionSlot() {
    assertThat(Schema.FIRST_ABILITY).isEqualTo(Schema.play(3, 17, 31) + 1);
    for (int slot = 0; slot < Schema.ABILITY_SLOTS; slot++) {
      int action = Schema.ability(slot);
      assertThat(action).isEqualTo(Schema.FIRST_ABILITY + slot);
      assertThat(Schema.isAbility(action)).isTrue();
      assertThat(Schema.abilitySlot(action)).isEqualTo(slot);
    }
    assertThat(Schema.ability(Schema.ABILITY_SLOTS - 1)).isEqualTo(Schema.ACTIONS - 1);
    assertThat(Schema.isAbility(Schema.WAIT)).isFalse();
    assertThat(Schema.isAbility(Schema.FIRST_ABILITY - 1)).isFalse();
    assertThat(Schema.isAbility(Schema.ACTIONS)).isFalse();
    assertThatThrownBy(() -> Schema.ability(2)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Schema.abilitySlot(1)).isInstanceOf(IllegalArgumentException.class);
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
    assertThat(Schema.headerFields().get(Schema.H_DECK_SLOTS + 7)).isEqualTo("deck_slot");
    assertThat(Schema.headerFields().get(Schema.H_DECK_EVOLUTION)).isEqualTo("deck_evolution");
    assertThat(Schema.headerFields().get(Schema.H_HAND_FORM + 3)).isEqualTo("hand_form");
    assertThat(Schema.headerFields().get(Schema.H_ABILITY)).isEqualTo("ability_champion");
    assertThat(
            Schema.headerFields()
                .get(Schema.H_ABILITY + Schema.ABILITY_SLOTS * Schema.ABILITY_INTS - 1))
        .isEqualTo("ability_command_ticks");
    assertThat(Schema.headerFields().get(Schema.H_LAST_ABILITY_RUN_REFUSAL))
        .isEqualTo("last_ability_run_refusal");
    assertThat(Schema.infoFields().get(Schema.I_NOT_MODELLED)).isEqualTo("not_modelled");
    assertThat(Schema.SEAT_INTS)
        .isEqualTo(Schema.HEADER_INTS + Schema.MAX_ENTITIES * Schema.ENTITY_INTS);
    assertThat(Schema.flagNames()).hasSize(Integer.numberOfTrailingZeros(Schema.F_CHARGED) + 1);
    assertThat(Schema.flagNames().get(Integer.numberOfTrailingZeros(Schema.F_AWAKE)))
        .isEqualTo("awake");
    assertThat(Schema.headerFields().get(Schema.H_ENEMY_ELIXIR)).isEqualTo("enemy_elixir");
    assertThat(Schema.headerFields().get(Schema.H_ENEMY_PLAYS)).isEqualTo("enemy_play_card");
    assertThat(
            Schema.headerFields()
                .get(Schema.H_ENEMY_PLAYS + Schema.ENEMY_PLAY_ROWS * Schema.ENEMY_PLAY_INTS - 1))
        .isEqualTo("enemy_play_point_ticks");
    assertThat(Schema.headerFields().get(Schema.H_QUEUE + 3)).isEqualTo("queue");
    assertThat(Schema.headerFields().get(Schema.H_DECK_LEVEL + 7)).isEqualTo("deck_level");
    assertThat(Schema.entityFields().get(Schema.E_LEVEL)).isEqualTo("level");
    assertThat(Schema.entityFields().get(Schema.E_BUFFS + 2 * Schema.BUFF_SLOTS - 1))
        .isEqualTo("buff_ms_2");
    assertThat(Schema.entityFields().get(Schema.ENTITY_INTS - 1)).isEqualTo("ability_ticks");
    assertThat(Schema.headerFields().get(Schema.H_ENEMY_SEEN_EVOLUTION + 7))
        .isEqualTo("enemy_seen_evolution");
    assertThat(Schema.headerFields().get(Schema.H_ENEMY_SEEN_EVOLUTION_NEED + 7))
        .isEqualTo("enemy_seen_evolution_need");
    assertThat(Schema.playKindNames()).hasSize(Schema.PLAY_TUNNEL + 1);
    assertThat(Schema.playKindNames().get(Schema.PLAY_LAUNCHED)).isEqualTo("launched");
    assertThat(Schema.playFlagNames())
        .hasSize(Integer.numberOfTrailingZeros(Schema.PLAY_EVOLUTION_DIAMOND) + 1);
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
        .isEqualTo(Schema.H_DECK_SLOTS);
    assertThat(Schema.H_DECK_SLOTS + Schema.DECK_SIZE).isEqualTo(Schema.H_DECK_EVOLUTION);
    assertThat(Schema.H_DECK_EVOLUTION + Schema.DECK_SIZE).isEqualTo(Schema.H_HAND_FORM);
    assertThat(Schema.H_HAND_FORM + Schema.HAND_SLOTS).isEqualTo(Schema.H_ABILITY);
    assertThat(Schema.H_ABILITY + Schema.ABILITY_SLOTS * Schema.ABILITY_INTS)
        .isEqualTo(Schema.H_LAST_ABILITY_RUN_REFUSAL);
    assertThat(Schema.H_LAST_ABILITY_RUN_REFUSAL + 1).isEqualTo(Schema.H_ENEMY_PLAY_COUNT);
    assertThat(Schema.H_ENEMY_PLAY_COUNT + 1).isEqualTo(Schema.H_ENEMY_ELIXIR);
    assertThat(Schema.H_ENEMY_ELIXIR + 1).isEqualTo(Schema.H_ENEMY_PLAYS);
    assertThat(Schema.H_ENEMY_PLAYS + Schema.ENEMY_PLAY_ROWS * Schema.ENEMY_PLAY_INTS)
        .isEqualTo(Schema.H_QUEUE);
    assertThat(Schema.H_QUEUE + Schema.QUEUE_SLOTS).isEqualTo(Schema.H_DECK_LEVEL);
    assertThat(Schema.H_DECK_LEVEL + Schema.DECK_SIZE).isEqualTo(Schema.H_ENEMY_SEEN_EVOLUTION);
    assertThat(Schema.H_ENEMY_SEEN_EVOLUTION + Schema.DECK_SIZE)
        .isEqualTo(Schema.H_ENEMY_SEEN_EVOLUTION_NEED);
    assertThat(Schema.H_ENEMY_SEEN_EVOLUTION_NEED + Schema.DECK_SIZE)
        .isLessThanOrEqualTo(Schema.HEADER_INTS);
  }

  @Test
  void theEntityFieldsDoNotOverlapAndEndTheRow() {
    List<String> fields = Schema.entityFields();
    assertThat(fields).doesNotContainNull().doesNotContain("reserved").doesNotHaveDuplicates();
    assertThat(Schema.E_BUFF_COUNT + 1).isEqualTo(Schema.E_BUFFS);
    assertThat(Schema.E_BUFFS + 2 * Schema.BUFF_SLOTS).isEqualTo(Schema.E_AIM_X);
    assertThat(Schema.E_RADIUS + 1).isEqualTo(Schema.E_ABILITY_TICKS);
    assertThat(Schema.E_ABILITY_TICKS + 1).isEqualTo(Schema.ENTITY_INTS);
  }

  @Test
  void theStateNamesAreTheBattleCoresStatesByNumber() {
    // The state is a category: its names are exported, one per number the battle core gives.
    assertThat(Schema.stateNames()).hasSize(GridEntityState.MAX_STATE + 1).doesNotHaveDuplicates();
    assertThat(Schema.stateNames().get(GridEntityState.STANDING)).isEqualTo("standing");
    assertThat(Schema.stateNames().get(GridEntityState.MOVING)).isEqualTo("moving");
    assertThat(Schema.stateNames().get(GridEntityState.ATTACKING)).isEqualTo("attacking");
    assertThat(Schema.stateNames().get(GridEntityState.DEPLOYING)).isEqualTo("deploying");
    assertThat(Schema.stateNames().get(GridEntityState.WAITING_TO_DEPLOY))
        .isEqualTo("waiting_to_deploy");
    assertThat(Schema.stateNames().get(GridEntityState.ABILITY_FOLLOW_UP))
        .isEqualTo("ability_follow_up");
    assertThat(Schema.scales()).containsEntry("max_state", GridEntityState.MAX_STATE);
    assertThat(Schema.NO_STATE).isNegative();
  }

  @Test
  void theRefusalCodesAreTheCommandsOwnAndPinnedByTheVersion() {
    // A change of the player's commands' refusals moves the codes, and with them the schema's
    // version: this list is what version 4 means.
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
    assertThat(Schema.VERSION).isEqualTo(4);
  }

  @Test
  void aBuffThatStopsOrSlowsRanksAheadOfOnesThatLastForever() {
    // Synthetic buffs, by their movement and attack pace changes and their time left.
    int forever = BuffInstance.FOREVER;
    assertThat(BuffSlots.rank(-100, -100, 500)).isEqualTo(BuffSlots.STOPS);
    assertThat(BuffSlots.rank(0, -100, 500)).isEqualTo(BuffSlots.STOPS);
    assertThat(BuffSlots.rank(-30, 0, forever)).isEqualTo(BuffSlots.SLOWS);
    assertThat(BuffSlots.rank(0, -30, 800)).isEqualTo(BuffSlots.SLOWS);
    assertThat(BuffSlots.rank(35, 35, 800)).isEqualTo(BuffSlots.TIMED);
    assertThat(BuffSlots.rank(0, 0, forever)).isEqualTo(BuffSlots.FOREVER);
    assertThat(BuffSlots.STOPS).isLessThan(BuffSlots.SLOWS);
    assertThat(BuffSlots.SLOWS).isLessThan(BuffSlots.TIMED);
    assertThat(BuffSlots.TIMED).isLessThan(BuffSlots.FOREVER);
    // Four permanent buffs, then a slow, then a stun, oldest first: the slots list the stun, the
    // slow and the oldest permanent one.
    List<Integer> ranks =
        List.of(
            BuffSlots.FOREVER,
            BuffSlots.FOREVER,
            BuffSlots.FOREVER,
            BuffSlots.FOREVER,
            BuffSlots.SLOWS,
            BuffSlots.STOPS);
    int[] slots = new int[Schema.BUFF_SLOTS];
    assertThat(BuffSlots.fill(ranks, Integer::intValue, slots)).isEqualTo(3);
    assertThat(slots).containsExactly(5, 4, 0);
    assertThat(BuffSlots.fill(List.of(BuffSlots.TIMED), Integer::intValue, slots)).isEqualTo(1);
  }
}
