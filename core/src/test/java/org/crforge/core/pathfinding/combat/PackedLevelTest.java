package org.crforge.core.pathfinding.combat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How a level is packed against a rarity and read back. */
class PackedLevelTest {

  @Test
  @DisplayName("a level counted from 1 is re-based on the rarity's first level")
  void aLevelIsRebasedOnTheRarity() {
    assertThat(PackedLevel.fromLevel(11, RarityTable.COMMON)).isEqualTo(10);
    assertThat(PackedLevel.fromLevel(11, RarityTable.RARE)).isEqualTo(0x208);
    assertThat(PackedLevel.fromLevel(11, RarityTable.EPIC)).isEqualTo(0x505);
    assertThat(PackedLevel.fromLevel(11, RarityTable.LEGENDARY)).isEqualTo(0x802);
    assertThat(PackedLevel.fromLevel(11, RarityTable.CHAMPION)).isEqualTo(0xa00);
    assertThat(PackedLevel.fromLevel(1, RarityTable.COMMON)).isEqualTo(0);
  }

  @Test
  @DisplayName("a level below the rarity's first level is floored at zero steps")
  void aLevelBelowTheFirstIsFloored() {
    assertThat(PackedLevel.fromLevel(2, RarityTable.LEGENDARY)).isEqualTo(0x800);
    assertThat(PackedLevel.steps(0x800)).isZero();
  }

  @Test
  @DisplayName("a packed level is re-based on another rarity and left alone on its own")
  void aPackedLevelIsRebased() {
    assertThat(PackedLevel.pack(0x208, RarityTable.RARE)).as("same rarity").isEqualTo(0x208);
    assertThat(PackedLevel.pack(0x208, RarityTable.COMMON)).as("to Common").isEqualTo(10);
    assertThat(PackedLevel.pack(0x503, RarityTable.RARE)).as("Epic to Rare").isEqualTo(0x206);
  }

  @Test
  @DisplayName("the steps are the sign-extended low byte and the level counts from 1 again")
  void theFieldsReadBack() {
    assertThat(PackedLevel.steps(0x208)).isEqualTo(8);
    assertThat(PackedLevel.relativeLevel(0x208)).isEqualTo(2);
    assertThat(PackedLevel.level(0x208)).isEqualTo(11);
    assertThat(PackedLevel.level(10)).isEqualTo(11);
    assertThat(PackedLevel.steps(0x80)).as("the low byte is signed").isEqualTo(-128);
  }

  @Test
  @DisplayName("a level below 1 is refused")
  void aLevelBelowOneIsRefused() {
    assertThatThrownBy(() -> PackedLevel.fromLevel(0, RarityTable.COMMON))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
