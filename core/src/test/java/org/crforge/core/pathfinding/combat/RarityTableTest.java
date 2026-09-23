package org.crforge.core.pathfinding.combat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The published rarity rows and the multiplier lookup. */
class RarityTableTest {

  @Test
  @DisplayName("every rarity's levels end at 16 and its table starts at a tenth")
  void everyRarityEndsAtSixteen() {
    for (RarityTable rarity : RarityTable.PUBLISHED) {
      assertThat(rarity.relativeLevel() + rarity.levelCount()).as(rarity.name()).isEqualTo(16);
      assertThat(rarity.powerLevelMultiplier().get(0)).as(rarity.name()).isEqualTo(110);
      assertThat(rarity.powerLevelMultiplier().size())
          .as("%s carries an entry for every level above its first", rarity.name())
          .isGreaterThanOrEqualTo(rarity.levelCount() - 1);
    }
    assertThat(RarityTable.COMMON.firstLevel()).isEqualTo(1);
    assertThat(RarityTable.RARE.firstLevel()).isEqualTo(3);
    assertThat(RarityTable.EPIC.firstLevel()).isEqualTo(6);
    assertThat(RarityTable.LEGENDARY.firstLevel()).isEqualTo(9);
    assertThat(RarityTable.CHAMPION.firstLevel()).isEqualTo(11);
    assertThat(RarityTable.EXPERIMENTAL.firstLevel()).isEqualTo(9);
  }

  @Test
  @DisplayName("within the table the multiplier is the table's entry")
  void withinTheTable() {
    assertThat(RarityTable.COMMON.multiplier(0)).isEqualTo(110);
    assertThat(RarityTable.COMMON.multiplier(9)).as("ten steps up").isEqualTo(256);
    assertThat(RarityTable.COMMON.multiplier(18)).as("the last entry").isEqualTo(600);
    assertThat(RarityTable.CHAMPION.multiplier(8)).as("the last entry").isEqualTo(233);
  }

  @Test
  @DisplayName("past the table the multiplier compounds a tenth per step, truncated")
  void pastTheTable() {
    // 100 compounded index + 1 times: 655 for index 19 of a table with 19 entries.
    assertThat(RarityTable.COMMON.multiplier(19)).isEqualTo(655);
    assertThat(RarityTable.COMMON.multiplier(20)).isEqualTo(720);
    assertThat(RarityTable.COMMON.multiplier(25)).isEqualTo(1158);
    // The compounding is not the table's tail: Champion's table ends at 233, the rule continues
    // from 100 rather than from the last entry, and truncation makes it fall behind the table.
    assertThat(RarityTable.CHAMPION.multiplier(9)).isEqualTo(256);
    assertThat(RarityTable.CHAMPION.multiplier(15)).isEqualTo(449);
  }

  @Test
  @DisplayName("a negative index is refused")
  void aNegativeIndexIsRefused() {
    assertThatThrownBy(() -> RarityTable.COMMON.multiplier(-1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
