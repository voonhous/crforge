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
}
