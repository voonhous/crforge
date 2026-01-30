package org.crforge.core.physics;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.arena.Arena;
import org.crforge.core.component.Position;
import org.crforge.core.entity.base.MovementType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ArcherBridgeStuckTest {

  private BasePathfinder pathfinder;
  private Arena arena;

  @BeforeEach
  void setUp() {
    pathfinder = new BasePathfinder();
    arena = Arena.standard();
  }

  @Test
  void testStuckAtRiverBoundary() {
    // Scenario: Unit is exactly at the river exit boundary (North side of river is Y=17.0)
    // Bridge center X is 3.5
    Position start = new Position(3.5f, 17.0f);

    // Target is deep in enemy territory (North)
    float targetX = 3.5f;
    float targetY = 25.0f;

    // Logic trace of the BUG:
    // 1. isNorthOfRiver (y > 17.0) -> False (17.0 is not > 17.0)
    // 2. isSouthOfRiver (y < 15.0) -> False
    // 3. inRiverZone -> True

    // 4. Pathfinder target calculation (Old Code):
    //    exitY = 17.0 (RIVER_Y_MAX).
    //    curY = 17.0.
    //    dy = exitY - curY = 0.
    //    atan2(0, 0) -> 0.0 radians (East).

    // Result: Unit moves sideways or stops instead of moving North.

    float angle = pathfinder.getNextMovementAngle(start, MovementType.GROUND, targetX, targetY,
        arena);

    // Assertion:
    // We expect the unit to continue moving North to escape the river zone.
    // So Sin(angle) should be positive (close to 1).
    // If bug is present, this might be 0.
    assertThat(Math.sin(angle))
        .as("Should move North when at river boundary")
        .isGreaterThan(0.5f);
  }
}