package org.crforge.core.card;

import static org.assertj.core.api.Assertions.assertThat;

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
      assertThat(LevelScaling.scaleCard(690, Rarity.COMMON, 1)).isEqualTo(690);
      assertThat(LevelScaling.scaleCard(100, Rarity.RARE, 1)).isEqualTo(100);
      assertThat(LevelScaling.scaleCard(100, Rarity.EPIC, 1)).isEqualTo(100);
      assertThat(LevelScaling.scaleCard(100, Rarity.LEGENDARY, 1)).isEqualTo(100);
    }

    @Test
    void commonMultipliersMatchTable() {
      // From LEVEL_SCALING.md Common column: multipliers 1.00 → 1.10 → 1.21 → 1.33 → ...
      int base = 100;
      assertThat(LevelScaling.scaleCard(base, Rarity.COMMON, 1)).isEqualTo(100);
      assertThat(LevelScaling.scaleCard(base, Rarity.COMMON, 2)).isEqualTo(110);
      assertThat(LevelScaling.scaleCard(base, Rarity.COMMON, 3)).isEqualTo(121);
      assertThat(LevelScaling.scaleCard(base, Rarity.COMMON, 4)).isEqualTo(133);
      assertThat(LevelScaling.scaleCard(base, Rarity.COMMON, 5)).isEqualTo(146);
      assertThat(LevelScaling.scaleCard(base, Rarity.COMMON, 6)).isEqualTo(160);
      assertThat(LevelScaling.scaleCard(base, Rarity.COMMON, 7)).isEqualTo(176);
      assertThat(LevelScaling.scaleCard(base, Rarity.COMMON, 8)).isEqualTo(193);
      assertThat(LevelScaling.scaleCard(base, Rarity.COMMON, 9)).isEqualTo(212);
      assertThat(LevelScaling.scaleCard(base, Rarity.COMMON, 10)).isEqualTo(233);
      assertThat(LevelScaling.scaleCard(base, Rarity.COMMON, 11)).isEqualTo(256);
      assertThat(LevelScaling.scaleCard(base, Rarity.COMMON, 14)).isEqualTo(339);
    }

    @Test
    void rareMultipliersMatchTable() {
      // All rarities now scale uniformly from level 1
      int base = 100;
      assertThat(LevelScaling.scaleCard(base, Rarity.RARE, 1)).isEqualTo(100);
      assertThat(LevelScaling.scaleCard(base, Rarity.RARE, 3)).isEqualTo(121);
      assertThat(LevelScaling.scaleCard(base, Rarity.RARE, 11)).isEqualTo(256);
      assertThat(LevelScaling.scaleCard(base, Rarity.RARE, 14)).isEqualTo(339);
    }

    @Test
    void epicMultipliersMatchTable() {
      int base = 100;
      assertThat(LevelScaling.scaleCard(base, Rarity.EPIC, 1)).isEqualTo(100);
      assertThat(LevelScaling.scaleCard(base, Rarity.EPIC, 6)).isEqualTo(160);
      assertThat(LevelScaling.scaleCard(base, Rarity.EPIC, 11)).isEqualTo(256);
      assertThat(LevelScaling.scaleCard(base, Rarity.EPIC, 14)).isEqualTo(339);
    }

    @Test
    void legendaryMultipliersMatchTable() {
      int base = 100;
      assertThat(LevelScaling.scaleCard(base, Rarity.LEGENDARY, 1)).isEqualTo(100);
      assertThat(LevelScaling.scaleCard(base, Rarity.LEGENDARY, 9)).isEqualTo(212);
      assertThat(LevelScaling.scaleCard(base, Rarity.LEGENDARY, 11)).isEqualTo(256);
      assertThat(LevelScaling.scaleCard(base, Rarity.LEGENDARY, 14)).isEqualTo(339);
    }

    @Test
    void allRaritiesProduceSameResultAtSameLevel() {
      // Post-unification: rarity no longer affects scaling
      int base = 100;
      int level = 11;
      int expected = 256;
      assertThat(LevelScaling.scaleCard(base, Rarity.COMMON, level)).isEqualTo(expected);
      assertThat(LevelScaling.scaleCard(base, Rarity.RARE, level)).isEqualTo(expected);
      assertThat(LevelScaling.scaleCard(base, Rarity.EPIC, level)).isEqualTo(expected);
      assertThat(LevelScaling.scaleCard(base, Rarity.LEGENDARY, level)).isEqualTo(expected);
      assertThat(LevelScaling.scaleCard(base, Rarity.CHAMPION, level)).isEqualTo(expected);
    }

    @Test
    void levelAboveMaxIsClamped() {
      assertThat(LevelScaling.scaleCard(100, Rarity.COMMON, 99))
          .isEqualTo(LevelScaling.scaleCard(100, Rarity.COMMON, LevelScaling.MAX_CARD_LEVEL));
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
