/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import org.crforge.core.battle.HeroFormTables;
import org.crforge.core.battle.SpawnedChampionTables;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.MatchSide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The hero slot of a Ladder deck, on rows the tests write ({@link HeroFormTables}): a card in it
 * whose hero form links a champion makes the king's first champion slot follow that champion from
 * the start, before any play, with the card's deck index; a spell or building card with no hero
 * form in it is refused.
 */
class BattleHeroSlotTest {

  @Test
  @DisplayName(
      "a hero slot's troop card or spell card makes the first champion slot follow the champion its"
          + " hero form links, with the card's deck index, and leaves the second slot empty")
  void theSlotFollowsTheLinkedChampion(@TempDir Path folder) throws IOException {
    GameTables tables = HeroFormTables.write(folder);
    for (String[] linked :
        new String[][] {
          {HeroFormTables.SQUAD_CARD, HeroFormTables.BANNER},
          {HeroFormTables.ROLL_CARD, HeroFormTables.ROLL_HERO}
        }) {
      Standard1v1Battle battle = new Standard1v1Battle(tables);
      battle.startLadderMatch(
          SpawnedChampionTables.deck(linked[0]),
          SpawnedChampionTables.fillers(),
          0,
          0,
          heroFirst(),
          new int[8]);
      ChampionController slot = battle.getWorld().kingTower(0).championSlot(1);
      assertThat(slot.getChampion().name()).as(linked[0]).isEqualTo(linked[1]);
      assertThat(slot.getDeckIndex()).isZero();
      assertThat(battle.getWorld().kingTower(0).championSlot(2).getChampion()).isNull();
    }
  }

  @Test
  @DisplayName(
      "a hero slot on a spell or building card with no hero form is refused, as no reference"
          + " holds it")
  void theHeroSlotsNoReferenceHoldsAreRefused(@TempDir Path folder) throws IOException {
    GameTables tables = HeroFormTables.write(folder);
    assertThatThrownBy(
            () ->
                new Standard1v1Battle(tables)
                    .startLadderMatch(
                        SpawnedChampionTables.deck(HeroFormTables.PLAIN_SPELL),
                        SpawnedChampionTables.fillers(),
                        0,
                        0,
                        heroFirst(),
                        new int[8]))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining(
            HeroFormTables.PLAIN_SPELL + " in a hero slot, a spell card with no hero form");
    assertThatThrownBy(
            () ->
                new Standard1v1Battle(tables)
                    .startLadderMatch(
                        SpawnedChampionTables.deck(HeroFormTables.PLAIN_BUILDING),
                        SpawnedChampionTables.fillers(),
                        0,
                        0,
                        heroFirst(),
                        new int[8]))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining(
            HeroFormTables.PLAIN_BUILDING + " in a hero slot, a building card with no hero form");
  }

  /** Slot flags with the first card in the hero slot. */
  private static int[] heroFirst() {
    int[] slots = new int[8];
    slots[0] = MatchSide.HERO_SLOT;
    return slots;
  }
}
