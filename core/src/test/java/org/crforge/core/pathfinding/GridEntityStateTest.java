package org.crforge.core.pathfinding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The gate masks select exactly the states they are documented to select. */
class GridEntityStateTest {

  @Test
  void stateValuesAreConsecutive() {
    assertThat(GridEntityState.STANDING).isZero();
    assertThat(GridEntityState.MOVING).isEqualTo(1);
    assertThat(GridEntityState.ATTACKING).isEqualTo(2);
    assertThat(GridEntityState.DASHING).isEqualTo(3);
    assertThat(GridEntityState.DEPLOYING).isEqualTo(4);
    assertThat(GridEntityState.JUMPING).isEqualTo(5);
    assertThat(GridEntityState.SPAWN_PATHFIND).isEqualTo(6);
    assertThat(GridEntityState.INGAME_PATHFIND).isEqualTo(7);
    assertThat(GridEntityState.CLONE_SETUP).isEqualTo(8);
    assertThat(GridEntityState.MORPHING).isEqualTo(9);
    assertThat(GridEntityState.CASTING).isEqualTo(10);
    assertThat(GridEntityState.WAITING_TO_DEPLOY).isEqualTo(11);
    assertThat(GridEntityState.FOLLOWING_REMOVED).isEqualTo(12);
    assertThat(GridEntityState.FOLLOWING_REMOVED_BUILDING).isEqualTo(13);
    assertThat(GridEntityState.COMPONENTS_DISABLED).isEqualTo(14);
    assertThat(GridEntityState.ROUTE_FOLLOWING_ALTERNATE).isEqualTo(15);
    assertThat(GridEntityState.ABILITY_FOLLOW_UP).isEqualTo(16);
    assertThat(GridEntityState.MAX_STATE).isEqualTo(16);
  }

  @Test
  void speedZeroStatesAreStandingAttackingDeployingCastingAndTheFollowingPair() {
    assertThat(statesIn(GridEntityState.SPEED_ZERO_STATES)).containsExactly(0, 2, 4, 10, 12, 13);
  }

  @Test
  void facingGateAddsCloneSetupToTheSpeedZeroStates() {
    assertThat(statesIn(GridEntityState.FACING_GATE_MASK)).containsExactly(0, 2, 4, 8, 10, 12, 13);
  }

  @Test
  void avoidanceGateCoversTheStatesThatSteerThemselves() {
    assertThat(statesIn(GridEntityState.AVOIDANCE_GATE_MASK))
        .containsExactly(0, 2, 3, 5, 6, 7, 8, 10, 12, 13);
  }

  @Test
  void pushGateCoversSpawnPathfindCloneSetupAndTheFollowingPair() {
    assertThat(statesIn(GridEntityState.PUSH_GATE_MASK)).containsExactly(6, 8, 12, 13);
  }

  @Test
  void delaySkipCoversDeployingSpawnPathfindAndWaitingToDeploy() {
    assertThat(statesIn(GridEntityState.DELAY_SKIP_MASK)).containsExactly(4, 6, 11);
  }

  @Test
  void statesAboveThirteenAreNeverGated() {
    for (int state = 14; state <= GridEntityState.MAX_STATE; state++) {
      assertThat(GridEntityState.inMask(state, GridEntityState.SPEED_ZERO_STATES)).isFalse();
      assertThat(GridEntityState.inMask(state, GridEntityState.FACING_GATE_MASK)).isFalse();
      assertThat(GridEntityState.inMask(state, GridEntityState.AVOIDANCE_GATE_MASK)).isFalse();
      assertThat(GridEntityState.inMask(state, GridEntityState.PUSH_GATE_MASK)).isFalse();
      assertThat(GridEntityState.inMask(state, GridEntityState.DELAY_SKIP_MASK)).isFalse();
    }
  }

  @Test
  void negativeStatesAreNeverGated() {
    assertThat(GridEntityState.inMask(-1, GridEntityState.SPEED_ZERO_STATES)).isFalse();
  }

  private static List<Integer> statesIn(int mask) {
    return IntStream.rangeClosed(0, GridEntityState.MAX_STATE)
        .filter(state -> GridEntityState.inMask(state, mask))
        .boxed()
        .toList();
  }
}
