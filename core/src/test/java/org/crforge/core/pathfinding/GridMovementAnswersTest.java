/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.pathfinding;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.battle.GameData;
import org.crforge.core.pathfinding.move.MovementConfig;
import org.crforge.core.pathfinding.move.MovementState;
import org.crforge.core.pathfinding.move.SpeedConfig;
import org.crforge.core.pathfinding.state.StateTimers;
import org.crforge.core.pathfinding.state.StateVisitConfig;
import org.crforge.core.pathfinding.target.TargetView;
import org.crforge.core.pathfinding.target.TargetingConfig;
import org.crforge.core.pathfinding.target.TargetingState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The answers the grid driver hands the movement pass that it cannot work out from the routing grid
 * alone.
 *
 * <p>These are stubbed here rather than driven through a whole match, because the branches that
 * read them - the dash in particular - belong to units the driver does not manage yet.
 */
class GridMovementAnswersTest {

  /** Which bit of a tag word each flag is, as the configured tables number the game tags. */
  private static final EntityFlags BITS = EntityFlags.of(GameData.tables());

  /** Attack range column of the unit under test, in game units. */
  private static final int RANGE = 1200;

  /** Collision radius of the unit under test, in game units. */
  private static final int UNIT_RADIUS = 500;

  /** Collision radius of the target, in game units. */
  private static final int TARGET_RADIUS = 1000;

  @Test
  @DisplayName("a troop with no reference is never in range of one")
  void noReferenceIsNotInRange() {
    GridUnitState unit = unitWithReferenceAt(null);

    assertThat(answersFor(unit).referenceInRange()).isZero();
  }

  @Test
  @DisplayName("a reference inside the attack range answers in range, one outside it does not")
  void theAnswerFollowsTheAttackRange() {
    // Attack range is the range column plus the unit's own radius, and the range test adds the
    // target's radius on top: 1200 + 500 + 1000 = 2700 game units between the two centres.
    assertThat(answersFor(unitWithReferenceAt(2700)).referenceInRange()).isEqualTo(1);
    assertThat(answersFor(unitWithReferenceAt(2701)).referenceInRange()).isZero();
  }

  @Test
  @DisplayName(
      "a unit flying direct paths heads for its attack range from its reference, on the line to"
          + " itself, or along the length when it stands on the reference")
  void theSpecialWaypointIsAtTheAttackRange() {
    // The attack range is the range column plus the unit's own radius: 1700 short of the reference.
    assertThat(answersFor(unitWithReferenceAt(5000)).specialWaypoint()).containsExactly(0, 3300);
    assertThat(answersFor(unitWithReferenceAt(0)).specialWaypoint()).containsExactly(0, 1700);
  }

  @Test
  @DisplayName(
      "a hovering unit hovers while neither force tag is in its word, and never under either; one"
          + " that does not hover never does")
  void hoveringAnswersFromTheLayer() {
    GridUnitState hovering =
        unitWithReferenceAt(null, MovementConfig.forGroundUnit().withHovering(true));
    assertThat(answersFor(hovering).hovering()).isEqualTo(1);
    for (long tag : new long[] {BITS.forceIsAir(), BITS.forceIsGround()}) {
      hovering.entity().setFlags(tag);
      assertThat(answersFor(hovering).hovering()).isZero();
    }
    GridUnitState walking = unitWithReferenceAt(null);
    assertThat(answersFor(walking).hovering()).isZero();
  }

  /**
   * The movement answers for one visit of the unit. The range answer reads nothing but the unit's
   * own targeting state, so no routing grid, costs or neighbours are needed.
   */
  private static GridMovementQueries answersFor(GridUnitState unit) {
    return new GridMovementQueries(unit, null, null, other -> null);
  }

  /**
   * A grid-driven troop standing at the origin whose reference stands the given distance away along
   * the arena's length, or holds no reference when the distance is null.
   */
  private static GridUnitState unitWithReferenceAt(Integer distance) {
    return unitWithReferenceAt(distance, MovementConfig.forGroundUnit());
  }

  /** The same unit with movement columns of its own. */
  private static GridUnitState unitWithReferenceAt(Integer distance, MovementConfig movement) {
    GridEntity owner = new GridEntity();
    owner.setFlagBits(BITS);
    owner.setX(0);
    owner.setY(0);
    owner.setCollisionRadius(UNIT_RADIUS);
    owner.setState(GridEntityState.MOVING);

    TargetingState targeting = new TargetingState();
    targeting.setOwner(owner);
    targeting.setConfig(TargetingConfig.forUnit(RANGE, 5500, UNIT_RADIUS, 1200, 700, true, true));
    targeting.setMovementComponentActive(true);
    if (distance != null) {
      GridEntity target = new GridEntity();
      target.setFlagBits(BITS);
      target.setX(0);
      target.setY(distance);
      target.setCollisionRadius(TARGET_RADIUS);
      targeting.setReference(
          new TargetView(
              target, TargetingConfig.tower("tower", 1200, 7500, TARGET_RADIUS, 800, 0, true)));
    }

    return new GridUnitState(
        owner,
        MovementState.forSide(0, 0, 0),
        targeting,
        new StateTimers(),
        movement,
        SpeedConfig.forGroundUnit(60),
        StateVisitConfig.forGroundUnit(1000),
        null,
        null);
  }
}
