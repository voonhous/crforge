package org.crforge.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class FormationLayoutTest {

  private static final float EPSILON = 0.001f;

  @Test
  void singleUnit_shouldReturnZeroOffset() {
    Vector2 offset = FormationLayout.calculateOffset(0, 1, 1.0f, 0.5f);
    assertThat(offset.getX()).isEqualTo(0f);
    assertThat(offset.getY()).isEqualTo(0f);
  }

  @Test
  void twoUnits_even_shouldProduceSymmetricHorizontalPair() {
    // N=2 (even): startAngle = 0
    // Unit 0: angle=0 -> (r, 0)
    // Unit 1: angle=pi -> (-r, 0)
    float formationRadius = 1.0f;
    float collisionRadius = 0.5f;
    float r = formationRadius + collisionRadius;

    Vector2 offset0 = FormationLayout.calculateOffset(0, 2, formationRadius, collisionRadius);
    Vector2 offset1 = FormationLayout.calculateOffset(1, 2, formationRadius, collisionRadius);

    assertThat(offset0.getX()).isCloseTo(r, within(EPSILON));
    assertThat(offset0.getY()).isCloseTo(0f, within(EPSILON));

    assertThat(offset1.getX()).isCloseTo(-r, within(EPSILON));
    assertThat(offset1.getY()).isCloseTo(0f, within(EPSILON));
  }

  @Test
  void threeUnits_odd_shouldProduceTriangleWithFirstAtTop() {
    // N=3 (odd): startAngle = pi/2
    // Unit 0: angle=pi/2 -> (0, r)
    // Unit 1: angle=pi/2 + 2pi/3 -> (-r*cos(30), -r*sin(30)) roughly
    // Unit 2: angle=pi/2 + 4pi/3
    float formationRadius = 1.0f;
    float collisionRadius = 0.0f;
    float r = formationRadius;

    Vector2 offset0 = FormationLayout.calculateOffset(0, 3, formationRadius, collisionRadius);

    // First unit should be at the top (0, r)
    assertThat(offset0.getX()).isCloseTo(0f, within(EPSILON));
    assertThat(offset0.getY()).isCloseTo(r, within(EPSILON));

    // All three offsets should be at distance r from origin
    for (int i = 0; i < 3; i++) {
      Vector2 offset = FormationLayout.calculateOffset(i, 3, formationRadius, collisionRadius);
      float dist = (float) Math.sqrt(offset.getX() * offset.getX() + offset.getY() * offset.getY());
      assertThat(dist).isCloseTo(r, within(EPSILON));
    }
  }

  @Test
  void fourUnits_even_shouldProduceCrossPattern() {
    // N=4 (even): startAngle = 0
    // Unit 0: angle=0 -> (r, 0)
    // Unit 1: angle=pi/2 -> (0, r)
    // Unit 2: angle=pi -> (-r, 0)
    // Unit 3: angle=3pi/2 -> (0, -r)
    float formationRadius = 1.0f;
    float collisionRadius = 0.5f;
    float r = formationRadius + collisionRadius;

    Vector2 offset0 = FormationLayout.calculateOffset(0, 4, formationRadius, collisionRadius);
    Vector2 offset1 = FormationLayout.calculateOffset(1, 4, formationRadius, collisionRadius);
    Vector2 offset2 = FormationLayout.calculateOffset(2, 4, formationRadius, collisionRadius);
    Vector2 offset3 = FormationLayout.calculateOffset(3, 4, formationRadius, collisionRadius);

    assertThat(offset0.getX()).isCloseTo(r, within(EPSILON));
    assertThat(offset0.getY()).isCloseTo(0f, within(EPSILON));

    assertThat(offset1.getX()).isCloseTo(0f, within(EPSILON));
    assertThat(offset1.getY()).isCloseTo(r, within(EPSILON));

    assertThat(offset2.getX()).isCloseTo(-r, within(EPSILON));
    assertThat(offset2.getY()).isCloseTo(0f, within(EPSILON));

    assertThat(offset3.getX()).isCloseTo(0f, within(EPSILON));
    assertThat(offset3.getY()).isCloseTo(-r, within(EPSILON));
  }

  @Test
  void fiveUnits_odd_shouldProducePentagonWithFirstAtTop() {
    // N=5 (odd): startAngle = pi/2
    // Unit 0: angle=pi/2 -> (0, r)
    float formationRadius = 2.0f;
    float collisionRadius = 0.3f;
    float r = formationRadius + collisionRadius;

    Vector2 offset0 = FormationLayout.calculateOffset(0, 5, formationRadius, collisionRadius);

    // First unit should be at the top
    assertThat(offset0.getX()).isCloseTo(0f, within(EPSILON));
    assertThat(offset0.getY()).isCloseTo(r, within(EPSILON));

    // All five offsets should be at distance r from origin
    for (int i = 0; i < 5; i++) {
      Vector2 offset = FormationLayout.calculateOffset(i, 5, formationRadius, collisionRadius);
      float dist = (float) Math.sqrt(offset.getX() * offset.getX() + offset.getY() * offset.getY());
      assertThat(dist).isCloseTo(r, within(EPSILON));
    }
  }

  @Test
  void offsets_shouldBeRoundedToThreeDecimalPlaces() {
    // Use values that produce non-round results
    Vector2 offset = FormationLayout.calculateOffset(1, 3, 1.0f, 0.0f);

    // Verify rounding: multiply by 1000, should be an integer
    float xTimes1000 = offset.getX() * 1000f;
    float yTimes1000 = offset.getY() * 1000f;
    assertThat(xTimes1000).isCloseTo(Math.round(xTimes1000), within(0.01f));
    assertThat(yTimes1000).isCloseTo(Math.round(yTimes1000), within(0.01f));
  }

  @Test
  void calculateDeployOffset_shouldDivideSummonRadiusByTileScale() {
    float summonRadius = 355.0f; // Raw CSV value
    float collisionRadius = 0.5f;

    // After dividing by TILE_SCALE (355), formationRadius = 1.0
    // Same as calling calculateOffset with formationRadius=1.0
    Vector2 deployOffset = FormationLayout.calculateDeployOffset(
        0, 2, summonRadius, collisionRadius);
    Vector2 directOffset = FormationLayout.calculateOffset(
        0, 2, 1.0f, collisionRadius);

    assertThat(deployOffset.getX()).isEqualTo(directOffset.getX());
    assertThat(deployOffset.getY()).isEqualTo(directOffset.getY());
  }

  @Test
  void zeroTotal_shouldReturnZeroOffset() {
    // Edge case: total <= 1 returns (0, 0)
    Vector2 offset = FormationLayout.calculateOffset(0, 0, 1.0f, 0.5f);
    assertThat(offset.getX()).isEqualTo(0f);
    assertThat(offset.getY()).isEqualTo(0f);
  }
}
