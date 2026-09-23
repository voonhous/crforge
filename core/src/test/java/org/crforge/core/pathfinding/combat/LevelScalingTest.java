package org.crforge.core.pathfinding.combat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A base stat at a level, under both rules. */
class LevelScalingTest {

  private static final ScalingGlobals GLOBALS = ScalingGlobals.standard();

  /** A Common entity at level 11: ten steps up. */
  private static final int LEVEL_11 = PackedLevel.fromLevel(11, RarityTable.COMMON);

  @Test
  @DisplayName("a Knight at level 11 hits for 202 and has 1766 hit points")
  void theKnightAtLevelEleven() {
    assertThat(
            LevelScaling.scale(GLOBALS, 79, LEVEL_11, ScalingMode.CARD_DAMAGE, RarityTable.COMMON))
        .isEqualTo(202);
    assertThat(
            LevelScaling.scale(
                GLOBALS, 690, LEVEL_11, ScalingMode.CARD_HITPOINTS, RarityTable.COMMON))
        .isEqualTo(1766);
  }

  @Test
  @DisplayName("the towers at level 11: princess 3052, king 4824, and 109 damage from 50")
  void theTowersAtLevelEleven() {
    assertThat(
            LevelScaling.scale(
                GLOBALS, 1400, LEVEL_11, ScalingMode.TOWER_HITPOINTS, RarityTable.COMMON))
        .isEqualTo(3052);
    assertThat(
            LevelScaling.scale(
                GLOBALS, 2400, LEVEL_11, ScalingMode.KING_HITPOINTS, RarityTable.COMMON))
        .isEqualTo(4824);
    assertThat(
            LevelScaling.scale(GLOBALS, 50, LEVEL_11, ScalingMode.TOWER_DAMAGE, RarityTable.COMMON))
        .isEqualTo(109);
    assertThat(
            LevelScaling.scale(GLOBALS, 50, LEVEL_11, ScalingMode.KING_DAMAGE, RarityTable.COMMON))
        .isEqualTo(109);
  }

  @Test
  @DisplayName("for every level a card can have, the card rule is the iterated floor of a tenth")
  void theCardRuleIsTheIteratedFloor() {
    for (RarityTable rarity : RarityTable.PUBLISHED) {
      int multiplier = 100;
      for (int steps = 1; steps < rarity.levelCount(); steps++) {
        multiplier += multiplier / 10;
        int packed = (rarity.relativeLevel() << 8) | steps;
        assertThat(LevelScaling.scale(GLOBALS, 1000, packed, ScalingMode.CARD_HITPOINTS, rarity))
            .as("%s, %d steps", rarity.name(), steps)
            .isEqualTo(1000 * multiplier / 100);
      }
    }
  }

  @Test
  @DisplayName("past a card's last level the published table is read, not the iterated floor")
  void pastTheLastLevelTheTableIsRead() {
    // Common's sixteenth entry is 450 where the iterated floor gives 449, so a Common card one
    // level past its last would scale by the table's entry; the rule reads the table wherever the
    // table reaches.
    assertThat(RarityTable.COMMON.multiplier(15)).isEqualTo(450);
    assertThat(
            LevelScaling.scale(GLOBALS, 1000, 16, ScalingMode.CARD_HITPOINTS, RarityTable.COMMON))
        .isEqualTo(4500);
  }

  @Test
  @DisplayName("at the published values the tower rule is 1.07 or 1.08 per level, then 1.10")
  void theTowerRuleAtThePublishedValues() {
    for (int level = 1; level <= 16; level++) {
      int packed = PackedLevel.fromLevel(level, RarityTable.COMMON);
      assertThat(
              LevelScaling.scale(
                  GLOBALS, 2400, packed, ScalingMode.KING_HITPOINTS, RarityTable.COMMON))
          .as("king hit points at level %d", level)
          .isEqualTo(iteratedTower(2400, level, 1.07));
      assertThat(
              LevelScaling.scale(
                  GLOBALS, 1400, packed, ScalingMode.TOWER_HITPOINTS, RarityTable.COMMON))
          .as("princess hit points at level %d", level)
          .isEqualTo(iteratedTower(1400, level, 1.08));
      assertThat(
              LevelScaling.scale(GLOBALS, 50, packed, ScalingMode.KING_DAMAGE, RarityTable.COMMON))
          .as("king damage at level %d", level)
          .isEqualTo(iteratedTower(50, level, 1.08));
      assertThat(
              LevelScaling.scale(GLOBALS, 50, packed, ScalingMode.TOWER_DAMAGE, RarityTable.COMMON))
          .as("princess damage at level %d", level)
          .isEqualTo(iteratedTower(50, level, 1.08));
    }
  }

  /** The tower rule as a floating-point iteration: the early growth up to level 9, then 1.10. */
  private static int iteratedTower(int base, int level, double early) {
    int multiplier = 100;
    for (int i = 1; i < level; i++) {
      multiplier = (int) (multiplier * (i < 9 ? early : 1.10));
    }
    return (int) (base * multiplier / 100.0);
  }

  @Test
  @DisplayName("step 0, no mode, and a card mode without a rarity all return the base")
  void theBaseComesBackUnscaled() {
    assertThat(LevelScaling.scale(GLOBALS, 123, 0x200, ScalingMode.CARD_DAMAGE, RarityTable.RARE))
        .as("step 0")
        .isEqualTo(123);
    assertThat(LevelScaling.scale(GLOBALS, 123, LEVEL_11, ScalingMode.NONE, RarityTable.COMMON))
        .as("no mode")
        .isEqualTo(123);
    assertThat(LevelScaling.scale(GLOBALS, 123, LEVEL_11, ScalingMode.CARD_DAMAGE, null))
        .as("card mode without a rarity compounds by nothing")
        .isEqualTo(123);
  }

  @Test
  @DisplayName("the tournament cap is the start level less the rarity's relative level")
  void theCapMovesWithTheRarity() {
    // Without a rarity the cap sits at the start level itself, as for Common.
    assertThat(LevelScaling.scale(GLOBALS, 2400, LEVEL_11, ScalingMode.KING_HITPOINTS, null))
        .isEqualTo(4824);
    // A Rare tower reaches the cap two steps sooner and compounds by 1.10 from there.
    assertThat(
            LevelScaling.scale(GLOBALS, 1400, 0x20a, ScalingMode.TOWER_HITPOINTS, RarityTable.RARE))
        .isEqualTo(3164);
  }

  @Test
  @DisplayName(
      "a large multiplier divides before it multiplies, and negative steps compound nothing")
  void theEdgesOfTheCompounding() {
    assertThat(LevelScaling.scale(GLOBALS, 1, 100, ScalingMode.TOWER_HITPOINTS, RarityTable.COMMON))
        .isEqualTo(11247);
    assertThat(LevelScaling.scale(GLOBALS, 7, 100, ScalingMode.KING_HITPOINTS, RarityTable.COMMON))
        .isEqualTo(72711);
    assertThat(LevelScaling.scale(GLOBALS, 1, 127, ScalingMode.TOWER_HITPOINTS, RarityTable.COMMON))
        .as("the multiplier passes 100000 on the way")
        .isEqualTo(147394);
    assertThat(LevelScaling.scale(GLOBALS, 7, 127, ScalingMode.KING_HITPOINTS, RarityTable.COMMON))
        .isEqualTo(952751);
    assertThat(
            LevelScaling.scale(
                GLOBALS, 1400, 0x80, ScalingMode.TOWER_HITPOINTS, RarityTable.COMMON))
        .as("a signed low byte below zero takes no step")
        .isEqualTo(1400);
  }

  @Test
  @DisplayName("the hit-points getter names the mode from the tower columns")
  void theHitPointsGetter() {
    assertThat(LevelScaling.hitpoints(GLOBALS, 2400, LEVEL_11, RarityTable.COMMON, true, false))
        .as("king tower")
        .isEqualTo(4824);
    assertThat(LevelScaling.hitpoints(GLOBALS, 1400, LEVEL_11, RarityTable.COMMON, false, true))
        .as("princess tower")
        .isEqualTo(3052);
    assertThat(LevelScaling.hitpoints(GLOBALS, 690, LEVEL_11, RarityTable.COMMON, false, false))
        .as("a card")
        .isEqualTo(1766);
  }

  @Test
  @DisplayName("the damage getter names the mode and falls back to the projectile's damage")
  void theDamageGetter() {
    assertThat(LevelScaling.damage(GLOBALS, 50, LEVEL_11, RarityTable.COMMON, true, false, null))
        .as("king tower")
        .isEqualTo(109);
    assertThat(LevelScaling.damage(GLOBALS, 50, LEVEL_11, RarityTable.COMMON, false, true, null))
        .as("princess tower")
        .isEqualTo(109);
    assertThat(LevelScaling.damage(GLOBALS, 79, LEVEL_11, RarityTable.COMMON, false, false, null))
        .as("a card")
        .isEqualTo(202);
    assertThat(
            LevelScaling.damage(GLOBALS, 79, LEVEL_11, RarityTable.COMMON, false, false, () -> 45))
        .as("a card with damage of its own ignores its projectile")
        .isEqualTo(202);
    assertThat(
            LevelScaling.damage(GLOBALS, 0, LEVEL_11, RarityTable.COMMON, false, false, () -> 45))
        .as("no damage of its own: the projectile's")
        .isEqualTo(45);
    assertThat(LevelScaling.damage(GLOBALS, 0, LEVEL_11, RarityTable.COMMON, false, false, () -> 0))
        .as("a projectile without damage leaves the card's zero")
        .isZero();
    assertThat(LevelScaling.damage(GLOBALS, 0, LEVEL_11, RarityTable.COMMON, false, false, null))
        .as("no projectile")
        .isZero();
  }
}
