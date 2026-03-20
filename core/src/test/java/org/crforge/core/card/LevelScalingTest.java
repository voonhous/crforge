package org.crforge.core.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Validates LevelScaling against known values from LEVEL_SCALING.md. All expected values are taken
 * directly from the scaling tables in that file.
 */
class LevelScalingTest {

  @Nested
  class CardScaling {

    @Test
    void levelOneReturnsBaseStatUnchanged() {
      assertThat(LevelScaling.scaleCard(690, 1)).isEqualTo(690);
      assertThat(LevelScaling.scaleCard(100, 1)).isEqualTo(100);
    }

    @Test
    void multipliersMatchTable() {
      // From LEVEL_SCALING.md: multipliers 1.00 -> 1.10 -> 1.21 -> 1.33 -> ...
      int base = 100;
      assertThat(LevelScaling.scaleCard(base, 1)).isEqualTo(100);
      assertThat(LevelScaling.scaleCard(base, 2)).isEqualTo(110);
      assertThat(LevelScaling.scaleCard(base, 3)).isEqualTo(121);
      assertThat(LevelScaling.scaleCard(base, 4)).isEqualTo(133);
      assertThat(LevelScaling.scaleCard(base, 5)).isEqualTo(146);
      assertThat(LevelScaling.scaleCard(base, 6)).isEqualTo(160);
      assertThat(LevelScaling.scaleCard(base, 7)).isEqualTo(176);
      assertThat(LevelScaling.scaleCard(base, 8)).isEqualTo(193);
      assertThat(LevelScaling.scaleCard(base, 9)).isEqualTo(212);
      assertThat(LevelScaling.scaleCard(base, 10)).isEqualTo(233);
      assertThat(LevelScaling.scaleCard(base, 11)).isEqualTo(256);
      assertThat(LevelScaling.scaleCard(base, 14)).isEqualTo(339);
    }

    @Test
    void levelAboveMaxIsClamped() {
      assertThat(LevelScaling.scaleCard(100, 99))
          .isEqualTo(LevelScaling.scaleCard(100, LevelScaling.MAX_CARD_LEVEL));
    }

    @Test
    void levelZero_throwsException() {
      assertThatThrownBy(() -> LevelScaling.scaleCard(100, 0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Card level must be >= 1");
    }

    @Test
    void negativeLevel_throwsException() {
      assertThatThrownBy(() -> LevelScaling.scaleCard(100, -5))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Card level must be >= 1");
    }
  }

  @Nested
  class TowerScaling {

    @Test
    void princessHpMatchesTable() {
      assertThat(LevelScaling.scalePrincessHp(1)).isEqualTo(1400);
      assertThat(LevelScaling.scalePrincessHp(2)).isEqualTo(1512);
      assertThat(LevelScaling.scalePrincessHp(3)).isEqualTo(1624);
      assertThat(LevelScaling.scalePrincessHp(9)).isEqualTo(2534);
      assertThat(LevelScaling.scalePrincessHp(10)).isEqualTo(2786);
      assertThat(LevelScaling.scalePrincessHp(11)).isEqualTo(3052);
      assertThat(LevelScaling.scalePrincessHp(16)).isEqualTo(4858);
    }

    @Test
    void princessDamageMatchesTable() {
      assertThat(LevelScaling.scalePrincessDamage(1)).isEqualTo(50);
      assertThat(LevelScaling.scalePrincessDamage(11)).isEqualTo(109);
      assertThat(LevelScaling.scalePrincessDamage(16)).isEqualTo(173);
    }

    @Test
    void kingHpMatchesTable() {
      assertThat(LevelScaling.scaleKingHp(1)).isEqualTo(2400);
      assertThat(LevelScaling.scaleKingHp(2)).isEqualTo(2568);
      assertThat(LevelScaling.scaleKingHp(9)).isEqualTo(4008);
      assertThat(LevelScaling.scaleKingHp(10)).isEqualTo(4392);
      assertThat(LevelScaling.scaleKingHp(11)).isEqualTo(4824);
      assertThat(LevelScaling.scaleKingHp(15)).isEqualTo(7032);
    }

    @Test
    void kingDamageMatchesTable() {
      assertThat(LevelScaling.scaleKingDamage(1)).isEqualTo(50);
      assertThat(LevelScaling.scaleKingDamage(11)).isEqualTo(109);
      assertThat(LevelScaling.scaleKingDamage(15)).isEqualTo(158);
    }
  }
}
