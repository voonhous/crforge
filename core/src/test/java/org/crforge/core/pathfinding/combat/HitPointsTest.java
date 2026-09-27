package org.crforge.core.pathfinding.combat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The hit-points object at creation, and the two tests read from it. */
class HitPointsTest {

  @Test
  @DisplayName("the object is created at its maximum, with the team pools full and no shield")
  void createdAtTheMaximum() {
    HitPoints hitPoints = new HitPoints(3052);

    assertThat(hitPoints.getHitPoints()).isEqualTo(3052);
    assertThat(hitPoints.getMaximum()).isEqualTo(3052);
    assertThat(hitPoints.teamPool(0)).isEqualTo(3052);
    assertThat(hitPoints.teamPool(1)).isEqualTo(3052);
    assertThat(hitPoints.getLastHitHeading()).isZero();
    assertThat(hitPoints.getShield()).isZero();
    assertThat(hitPoints.getShieldMaximum()).isZero();
    assertThat(hitPoints.dedupeIds()).isEmpty();
  }

  @Test
  @DisplayName("a non-positive maximum is refused: such an entity carries no object")
  void aNonPositiveMaximumIsRefused() {
    assertThatThrownBy(() -> new HitPoints(0)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("alive is hit points above zero, and an entity without the object is alive")
  void theAliveTest() {
    HitPoints hitPoints = new HitPoints(10);
    assertThat(hitPoints.alive()).isTrue();
    assertThat(HitPoints.alive(hitPoints)).isTrue();

    hitPoints.setHitPoints(1);
    assertThat(hitPoints.alive()).isTrue();
    hitPoints.setHitPoints(0);
    assertThat(hitPoints.alive()).isFalse();
    hitPoints.setHitPoints(-5);
    assertThat(hitPoints.alive()).isFalse();

    assertThat(HitPoints.alive(null)).isTrue();
  }

  @Test
  @DisplayName("removable is a removal request, or not alive")
  void theRemovalTest() {
    HitPoints hitPoints = new HitPoints(10);
    assertThat(HitPoints.removable(false, hitPoints)).isFalse();
    assertThat(HitPoints.removable(true, hitPoints)).isTrue();

    hitPoints.setHitPoints(0);
    assertThat(HitPoints.removable(false, hitPoints)).isTrue();

    assertThat(HitPoints.removable(false, null)).as("no object, no request").isFalse();
    assertThat(HitPoints.removable(true, null)).as("no object, requested").isTrue();
  }

  @Test
  @DisplayName("dedupe ids are listed with their tick and refreshed when they land again")
  void theDedupeList() {
    HitPoints hitPoints = new HitPoints(10);
    assertThat(hitPoints.isDedupeListed(7)).isFalse();

    hitPoints.listDedupe(7, 120);
    hitPoints.listDedupe(9, 121);
    assertThat(hitPoints.isDedupeListed(7)).isTrue();
    assertThat(hitPoints.dedupeIds()).containsExactly(7, 9);
    assertThat(hitPoints.dedupeTick(7)).isEqualTo(120);

    hitPoints.refreshDedupe(7, 130);
    assertThat(hitPoints.dedupeTick(7)).isEqualTo(130);
    assertThat(hitPoints.dedupeTick(9)).isEqualTo(121);
    assertThatThrownBy(() -> hitPoints.dedupeTick(8)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("the team pools are set apart")
  void theTeamPools() {
    HitPoints hitPoints = new HitPoints(10);
    hitPoints.setTeamPool(1, 4);
    assertThat(hitPoints.teamPool(0)).isEqualTo(10);
    assertThat(hitPoints.teamPool(1)).isEqualTo(4);
  }

  @Test
  @DisplayName(
      "the decay's step is the maximum over the lifetime per visit, and it kills on the visit the"
          + " traced lives end on")
  void theDecay() {
    // Maximum, lifetime, step and the decaying visit that takes the last hit point, for the Cannon
    // and the Tombstone at levels 11 and 1, the Goblin Hut and the Goblin Drill at level 11.
    int[][] lives = {
      {824, 30000, 137, 602},
      {322, 30000, 53, 608},
      {529, 30000, 88, 602},
      {207, 30000, 34, 609},
      {847, 29000, 146, 581},
      {1313, 10000, 656, 201}
    };
    for (int[] life : lives) {
      HitPoints hitPoints = new HitPoints(life[0]);
      hitPoints.setDecayStep(HitPoints.decayStep(life[0], life[1]));
      assertThat(hitPoints.getDecayStep()).as("step for %d", life[0]).isEqualTo(life[2]);
      int visits = 1;
      while (!hitPoints.decay()) {
        visits++;
      }
      assertThat(visits).as("visits for %d", life[0]).isEqualTo(life[3]);
      assertThat(hitPoints.getHitPoints()).isZero();
      assertThat(hitPoints.getDecayCarry()).isZero();
    }
    assertThat(HitPoints.decayStep(824, 0)).as("no lifetime").isZero();
  }

  @Test
  @DisplayName("the decay carries the hundredths below a whole hit point to the next visit")
  void theDecayCarries() {
    HitPoints hitPoints = new HitPoints(824);
    hitPoints.setDecayStep(137);
    assertThat(hitPoints.decay()).isFalse();
    assertThat(hitPoints.getHitPoints()).isEqualTo(823);
    assertThat(hitPoints.getDecayCarry()).isEqualTo(37);
    hitPoints.decay();
    assertThat(hitPoints.getHitPoints()).isEqualTo(822);
    assertThat(hitPoints.getDecayCarry()).isEqualTo(74);
    hitPoints.decay();
    assertThat(hitPoints.getHitPoints()).isEqualTo(820);
    assertThat(hitPoints.getDecayCarry()).isEqualTo(11);
  }
}
