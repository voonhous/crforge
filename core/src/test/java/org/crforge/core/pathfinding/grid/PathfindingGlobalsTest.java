package org.crforge.core.pathfinding.grid;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The pathfinding constants carry the values of the standard game configuration. */
class PathfindingGlobalsTest {

  @Test
  void cellCostsAreTheStandardValues() {
    assertThat(PathfindingGlobals.PATHFINDING_DEFAULT_COST).isEqualTo(8);
    assertThat(PathfindingGlobals.PATHFINDING_ROAD_COST).isEqualTo(5);
    assertThat(PathfindingGlobals.PATHFINDING_MATCHINGROAD_COST).isEqualTo(5);
    assertThat(PathfindingGlobals.PATHFINDING_WATER_COST).isEqualTo(7);
    assertThat(PathfindingGlobals.PATHFINDING_BLOCKED_COST).isEqualTo(50);
    assertThat(PathfindingGlobals.PATHFINDING_BUILDING_COST).isEqualTo(50);
  }

  @Test
  void searchConfigurationIsTheStandardOne() {
    assertThat(PathfindingGlobals.PATHFINDING_HEURISTIC_METHOD).isEqualTo(1);
    assertThat(PathfindingGlobals.PATHFINDING_DEFAULTHEURISTIC_COST).isEqualTo(5);
    assertThat(PathfindingGlobals.PATHFINDING_REFRESH_OPENNODES).isTrue();
    assertThat(PathfindingGlobals.PATHFINDING_REOPEN_CLOSEDNODES).isFalse();
    assertThat(PathfindingGlobals.PATHFINDING_SAMEPATH_EPSILON).isEqualTo(3);
    assertThat(PathfindingGlobals.NEW_PATHFINDING_CODE).isTrue();
    assertThat(PathfindingGlobals.DISABLE_PATH_LINEID).isTrue();
  }

  @Test
  void occlusionAndEndpointFlagsAreOn() {
    assertThat(PathfindingGlobals.PATHFINDING_DYNAMIC_OCCLUSIONS).isTrue();
    assertThat(PathfindingGlobals.PATHFINDING_FRIENDLYONLY_OCCLUSIONS).isTrue();
    assertThat(PathfindingGlobals.KS_POS_TO_TARGET_FLYING_NO_WATER).isTrue();
    assertThat(PathfindingGlobals.KS_POS_TO_TARGET_GROUND_AVOID_BUILDINGS).isTrue();
  }

  @Test
  void sightAndRangeValuesAreTheStandardOnes() {
    assertThat(PathfindingGlobals.ADD_CHARACTER_RANGE_TO_RADIUS).isTrue();
    assertThat(PathfindingGlobals.EXTRA_SIGHT_RANGE_TO_BUILDING).isZero();
    assertThat(PathfindingGlobals.EXTRA_SIGHT_RANGE_TO_CROWN_TOWERS).isEqualTo(2000);
    assertThat(PathfindingGlobals.LOGIC_RANGE_EXTENSION_TO_KEEP_TARGET).isEqualTo(25);
    assertThat(PathfindingGlobals.LOGIC_CHARACTER_CONTINUOUS_DAMAGE_ATTACK_CLOSER).isEqualTo(500);
  }

  @Test
  void targetingAndMovementRulesAreTheStandardOnes() {
    assertThat(PathfindingGlobals.LOGIC_SPAWN_PATHFIND_REACHED_RADIUS_FROM_SPEED).isTrue();
    assertThat(PathfindingGlobals.LOGIC_TOUCHDOWN_RESTRICTED_SIDE_MOVEMENT).isTrue();
    assertThat(PathfindingGlobals.LOGIC_PATHFIND_BACKWARDS_TRY_KEEP_TARGET).isTrue();
    assertThat(PathfindingGlobals.LOGIC_XPOS_BASED_TOWER_TARGETING).isTrue();
    assertThat(PathfindingGlobals.LOGIC_DEFAULT_TARGET_USE_LANE_ID).isFalse();
    assertThat(PathfindingGlobals.LOGIC_PRINCESS_TOWERS_ALWAYS_AS_DEFAULT_TARGET).isTrue();
  }
}
