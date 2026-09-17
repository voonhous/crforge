package org.crforge.core.pathfinding.target;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.pathfinding.GridEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The attack range of a unit and the geometric test that decides whether it may attack. */
class RangeTestTest {

  private static final int KNIGHT_RANGE = 1200;
  private static final int KNIGHT_SIGHT_RANGE = 5500;
  private static final int KNIGHT_COLLISION_RADIUS = 500;

  private TargetingState knight;
  private TargetView leftPrincessTower;

  @BeforeEach
  void setUp() {
    GridEntity unit = new GridEntity();
    unit.setName("owner");
    unit.setId(7);
    unit.setSide(0);
    unit.setX(3500);
    unit.setY(10000);
    unit.setCollisionRadius(KNIGHT_COLLISION_RADIUS);
    unit.setState(1);
    unit.setMovementActive(true);

    knight = new TargetingState();
    knight.setOwner(unit);
    knight.setConfig(
        TargetingConfig.forUnit(
            KNIGHT_RANGE, KNIGHT_SIGHT_RANGE, KNIGHT_COLLISION_RADIUS, 1200, 700, true, false));
    knight.setMovementComponentActive(true);

    GridEntity tower = new GridEntity();
    tower.setName("PrincessTower_1_1");
    tower.setId(5);
    tower.setSide(1);
    tower.setX(3500);
    tower.setY(25500);
    tower.setCollisionRadius(1000);
    tower.setBuilding(true);
    tower.setKingCandidate(1);
    tower.setTargetable(1);
    leftPrincessTower =
        new TargetView(
            tower, TargetingConfig.tower("PrincessTower", 7500, 7500, 1000, 800, 0, true));
  }

  @Test
  @DisplayName(
      "the attack range adds the unit's own collision radius and the minimum range is zero")
  void attackRangeIncludesTheCollisionRadius() {
    assertThat(AttackRange.attackRange(knight)).isEqualTo(1700);
    assertThat(AttackRange.minRange(knight)).isEqualTo(0);
    assertThat(AttackRange.attackRangeWithRadius(knight)).isEqualTo(1700);
  }

  @Test
  @DisplayName("the attack-range accessor answers zero without an active targeting component")
  void attackRangeWithRadiusNeedsTheComponent() {
    knight.setTargetingComponentActive(false);

    assertThat(AttackRange.attackRangeWithRadius(knight)).isZero();
  }

  @Test
  @DisplayName("the range test compares against the target's radius plus the attack range")
  void rangeTestUsesTheTargetRadius() {
    int threshold = (1000 + 1700) * (1000 + 1700);
    assertThat(threshold).isEqualTo(7290000);

    assertThat(RangeTest.squaredDistance(3500, 25500, 3731, 22854)).isEqualTo(7054677);
    assertThat(RangeTest.rangeTest(leftPrincessTower, 3731, 22854, 1700, 0, false)).isTrue();

    assertThat(RangeTest.squaredDistance(3500, 25500, 3731, 22800)).isEqualTo(7343361);
    assertThat(RangeTest.rangeTest(leftPrincessTower, 3731, 22800, 1700, 0, false)).isFalse();
  }

  @Test
  @DisplayName("the squared distance saturates rather than overflowing")
  void squaredDistanceSaturates() {
    assertThat(RangeTest.squaredDistance(0, 0, 46341, 0)).isEqualTo(Integer.MAX_VALUE);
    assertThat(RangeTest.squaredDistance(0, 0, 46340, 0)).isEqualTo(46340 * 46340);
  }

  @Test
  @DisplayName("the reference test moves the unit's position against the target with an extension")
  void referenceInRangeUsesTheOwnerPosition() {
    knight.getOwner().setX(3731);
    knight.getOwner().setY(22700);

    assertThat(RangeTest.referenceInRange(knight, leftPrincessTower, 0)).isFalse();
    assertThat(RangeTest.referenceInRange(knight, leftPrincessTower, 25)).isFalse();
    assertThat(RangeTest.referenceInRange(knight, leftPrincessTower, 500)).isTrue();

    // The 25-unit extension is exactly what keeps a reference that has just slipped out of range.
    knight.getOwner().setY(22800);
    assertThat(RangeTest.referenceInRange(knight, leftPrincessTower, 0)).isFalse();
    assertThat(RangeTest.referenceInRange(knight, leftPrincessTower, 25)).isTrue();
  }

  @Test
  @DisplayName("a positive minimum range rejects a target that is too close")
  void minimumRangeRejectsCloseTargets() {
    TargetingConfig ranged = knight.getConfig().toBuilder().minimumRange(3000).build();
    knight.setConfig(ranged);

    // The minimum range also picks up the unit's own collision radius.
    assertThat(AttackRange.minRange(knight)).isEqualTo(3500);
    // 1500 units from the tower centre is inside the 4500-unit minimum, so the attack is refused
    // whatever the outer range says; 5500 units away it is accepted again.
    assertThat(RangeTest.rangeTest(leftPrincessTower, 3500, 24000, 9000, 3500, false)).isFalse();
    assertThat(RangeTest.rangeTest(leftPrincessTower, 3500, 20000, 9000, 3500, false)).isTrue();
    assertThat(RangeTest.rangeTest(leftPrincessTower, 3500, 20000, 1700, 3500, false)).isFalse();
  }

  @Test
  @DisplayName("the inner-only flag keeps just the minimum-range half of the test")
  void innerOnlyFlagDropsTheOuterTest() {
    assertThat(RangeTest.rangeTest(leftPrincessTower, 3500, 0, 1700, 3500, true)).isTrue();
    assertThat(RangeTest.rangeTest(leftPrincessTower, 3500, 0, 1700, 0, true)).isTrue();
    assertThat(RangeTest.rangeTest(leftPrincessTower, 3500, 25500, 1700, 3500, true)).isFalse();
  }

  @Test
  @DisplayName("a unit with an attack sequence loses range while it walks")
  void continuousDamageAttackerWalksCloser() {
    knight.setConfig(knight.getConfig().toBuilder().attackSequenceMode(1).build());

    knight.getOwner().setState(1);
    assertThat(AttackRange.attackRange(knight)).isEqualTo(1200);

    knight.getOwner().setState(2);
    assertThat(AttackRange.attackRange(knight)).isEqualTo(1700);
  }

  @Test
  @DisplayName("an attack sequence step overrides the range and the minimum range")
  void sequenceStepOverridesTheRange() {
    knight.setConfig(
        knight.getConfig().toBuilder()
            .attackSequenceEntries(List.of(new AttackSequenceEntry(300, 900, -1)))
            .build());
    knight.setAttackSequenceIndex(0);

    assertThat(AttackRange.attackRange(knight)).isEqualTo(800);
    assertThat(AttackRange.minRange(knight)).isEqualTo(1400);
  }

  @Test
  @DisplayName("a special load in progress switches to the special range")
  void specialLoadUsesTheSpecialRange() {
    knight.setConfig(knight.getConfig().toBuilder().specialRange(4000).build());
    knight.setSpecialLoadPending(true);

    assertThat(AttackRange.attackRange(knight)).isEqualTo(4500);
  }
}
