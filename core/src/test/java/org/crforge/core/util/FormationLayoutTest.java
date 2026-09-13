package org.crforge.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.crforge.core.util.GameUnits.tiles;

import org.junit.jupiter.api.Test;

class FormationLayoutTest {

  // Offsets are rounded to whole game units, so geometric checks allow one unit of quantization
  private static final int EPSILON = 1;

  @Test
  void singleUnit_shouldReturnZeroOffset() {
    FormationLayout.Offset offset = FormationLayout.calculateOffset(0, 1, tiles(2.0), tiles(0.5));
    assertThat(offset.x()).isZero();
    assertThat(offset.y()).isZero();
  }

  @Test
  void twoUnits_even_shouldProduceSymmetricHorizontalPair() {
    // N=2 (even): startAngle = 0
    // spawnRadius=1.0 tiles -> units placed at distance 1.0 tiles from center
    // Unit 0: angle=0 -> (1, 0)
    // Unit 1: angle=pi -> (-1, 0)
    int spawnRadius = tiles(1.0);
    int collisionRadius = tiles(0.5);

    FormationLayout.Offset offset0 =
        FormationLayout.calculateOffset(0, 2, spawnRadius, collisionRadius);
    FormationLayout.Offset offset1 =
        FormationLayout.calculateOffset(1, 2, spawnRadius, collisionRadius);

    assertThat(offset0.x()).isEqualTo(spawnRadius);
    assertThat(offset0.y()).isZero();

    assertThat(offset1.x()).isEqualTo(-spawnRadius);
    assertThat(offset1.y()).isZero();
  }

  @Test
  void threeUnits_odd_shouldProduceTriangleWithFirstAtTop() {
    // N=3 (odd): startAngle = pi/2
    // spawnRadius=1.0 tiles -> r = 1000 game units
    int spawnRadius = tiles(1.0);
    int collisionRadius = 0;

    FormationLayout.Offset offset0 =
        FormationLayout.calculateOffset(0, 3, spawnRadius, collisionRadius);

    // First unit should be at the top (0, r)
    assertThat(offset0.x()).isZero();
    assertThat(offset0.y()).isEqualTo(spawnRadius);

    // All three offsets should be at distance r from origin (within one unit of rounding)
    for (int i = 0; i < 3; i++) {
      FormationLayout.Offset offset =
          FormationLayout.calculateOffset(i, 3, spawnRadius, collisionRadius);
      double dist = Math.hypot(offset.x(), offset.y());
      assertThat(dist).isCloseTo(spawnRadius, within((double) EPSILON));
    }
  }

  @Test
  void fourUnits_even_shouldProduceCrossPattern() {
    // N=4 (even): startAngle = 0
    // spawnRadius=1.5 tiles -> units at distance 1.5 tiles
    int spawnRadius = tiles(1.5);
    int collisionRadius = tiles(0.5);

    FormationLayout.Offset offset0 =
        FormationLayout.calculateOffset(0, 4, spawnRadius, collisionRadius);
    FormationLayout.Offset offset1 =
        FormationLayout.calculateOffset(1, 4, spawnRadius, collisionRadius);
    FormationLayout.Offset offset2 =
        FormationLayout.calculateOffset(2, 4, spawnRadius, collisionRadius);
    FormationLayout.Offset offset3 =
        FormationLayout.calculateOffset(3, 4, spawnRadius, collisionRadius);

    assertThat(offset0.x()).isEqualTo(spawnRadius);
    assertThat(offset0.y()).isZero();

    assertThat(offset1.x()).isZero();
    assertThat(offset1.y()).isEqualTo(spawnRadius);

    assertThat(offset2.x()).isEqualTo(-spawnRadius);
    assertThat(offset2.y()).isZero();

    assertThat(offset3.x()).isZero();
    assertThat(offset3.y()).isEqualTo(-spawnRadius);
  }

  @Test
  void fiveUnits_odd_shouldProducePentagonWithFirstAtTop() {
    // N=5 (odd): startAngle = pi/2
    // spawnRadius=2.0 tiles -> units at distance 2.0 tiles
    int spawnRadius = tiles(2.0);
    int collisionRadius = tiles(0.3);

    FormationLayout.Offset offset0 =
        FormationLayout.calculateOffset(0, 5, spawnRadius, collisionRadius);

    // First unit should be at the top
    assertThat(offset0.x()).isZero();
    assertThat(offset0.y()).isEqualTo(spawnRadius);

    // All five offsets should be at distance r from origin (within one unit of rounding)
    for (int i = 0; i < 5; i++) {
      FormationLayout.Offset offset =
          FormationLayout.calculateOffset(i, 5, spawnRadius, collisionRadius);
      double dist = Math.hypot(offset.x(), offset.y());
      assertThat(dist).isCloseTo(spawnRadius, within((double) EPSILON));
    }
  }

  @Test
  void offsets_shouldRoundToNearestGameUnit() {
    // N=3, index 1: angle = pi/2 + 2pi/3 -> (-866.025..., -500.0) for a 1000-unit radius.
    // One game unit is one thousandth of a tile, so this reproduces the legacy three-decimal
    // tile rounding (-0.866, -0.5).
    FormationLayout.Offset offset = FormationLayout.calculateOffset(1, 3, tiles(1.0), 0);

    assertThat(offset.x()).isEqualTo(-866);
    assertThat(offset.y()).isEqualTo(-500);
  }

  @Test
  void calculateDeployOffset_shouldApplyLegacySummonRadiusDivisor() {
    // Raw summonRadius 355 / LEGACY_SUMMON_RADIUS_DIVISOR (355) = 1.0 tile = 1000 game units.
    // This is the legacy fallback scale, not the 1000 units per tile coordinate scale.
    float summonRadius = FormationLayout.LEGACY_SUMMON_RADIUS_DIVISOR;
    int collisionRadius = tiles(0.5);

    FormationLayout.Offset deployOffset =
        FormationLayout.calculateDeployOffset(0, 2, summonRadius, collisionRadius);
    FormationLayout.Offset directOffset =
        FormationLayout.calculateOffset(0, 2, tiles(1.0), collisionRadius);

    assertThat(deployOffset).isEqualTo(directOffset);
    assertThat(deployOffset.x()).isEqualTo(tiles(1.0));
  }

  @Test
  void calculateDeployOffset_barbariansRawRadius_preservesLegacyTileRadius() {
    // Barbarians-style raw summonRadius 700 -> 700 / 355 = 1.9718 tiles -> 1972 game units
    FormationLayout.Offset offset = FormationLayout.calculateDeployOffset(0, 2, 700f, tiles(0.5));

    assertThat(offset.x()).isEqualTo(1972);
    assertThat(offset.y()).isZero();
  }

  @Test
  void zeroTotal_shouldReturnZeroOffset() {
    // Edge case: total <= 1 returns (0, 0)
    FormationLayout.Offset offset = FormationLayout.calculateOffset(0, 0, tiles(1.0), tiles(0.5));
    assertThat(offset).isEqualTo(FormationLayout.Offset.ZERO);
  }
}
