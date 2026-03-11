package org.crforge.core.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import org.crforge.core.arena.Arena;
import org.crforge.core.component.Position;
import org.crforge.core.entity.base.MovementType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BasePathfinderTest {

  private BasePathfinder pathfinder;
  private Arena arena;

  @BeforeEach
  void setUp() {
    pathfinder = new BasePathfinder();
    arena = mock(Arena.class);
  }

  @Test
  void testAirUnitsFlyStraight() {
    Position start = new Position(5, 5);
    float targetX = 10;
    float targetY = 10;

    float angle = pathfinder.getNextMovementAngle(start, MovementType.AIR, targetX, targetY, arena);

    float expected = (float) Math.atan2(5, 5); // 45 degrees
    assertEquals(expected, angle, 0.001f);
  }

  @Test
  void testGroundSameSideMovesStraight() {
    // Both on Blue side (Bottom)
    Position start = new Position(5, 5);
    float targetX = 10;
    float targetY = 8; // Still south of river (River starts at 15)

    float angle =
        pathfinder.getNextMovementAngle(start, MovementType.GROUND, targetX, targetY, arena);

    float expected = (float) Math.atan2(3, 5);
    assertEquals(expected, angle, 0.001f);
  }

  @Test
  void testGroundCrossRiverGoesToBridge() {
    // Start Blue (South), Target Red (North)
    Position start = new Position(1, 5); // Far left
    float targetX = 15; // Far right
    float targetY = 25; // North

    float angle =
        pathfinder.getNextMovementAngle(start, MovementType.GROUND, targetX, targetY, arena);

    // Should target Left Bridge because 1 is closer to left bridge than right
    float bridgeX = Arena.LEFT_BRIDGE_X + Arena.BRIDGE_WIDTH / 2f;
    float bridgeY = Arena.RIVER_Y;

    // The Pathfinder aims at BRIDGE_Y - 1.0 (approach point) when south
    float approachY = Arena.RIVER_Y - 1.0f;

    float expected = (float) Math.atan2(approachY - 5, bridgeX - 1);
    assertEquals(expected, angle, 0.001f);
  }

  @Test
  void testGroundCrossRiverGoesToRightBridge() {
    // Start Blue (South), Target Red (North)
    Position start = new Position(17, 5); // Far right
    float targetX = 5; // Far left
    float targetY = 25; // North

    float angle =
        pathfinder.getNextMovementAngle(start, MovementType.GROUND, targetX, targetY, arena);

    // Should target Right Bridge
    float bridgeX = Arena.RIGHT_BRIDGE_X + Arena.BRIDGE_WIDTH / 2f;
    float approachY = Arena.RIVER_Y - 1.0f;

    float expected = (float) Math.atan2(approachY - 5, bridgeX - 17);
    assertEquals(expected, angle, 0.001f);
  }

  @Test
  void testGroundOnBridgeContinuesStraight() {
    // Start in River zone (center of left bridge)
    float leftBridgeCenterX = Arena.LEFT_BRIDGE_X + Arena.BRIDGE_WIDTH / 2f;
    Position start = new Position(leftBridgeCenterX, Arena.RIVER_Y);
    float targetX = 10;
    float targetY = 25;

    float angle =
        pathfinder.getNextMovementAngle(start, MovementType.GROUND, targetX, targetY, arena);

    // In River Zone (on bridge), it should aim for River Max (Exit)
    float exitY = Arena.RIVER_Y + 1.0f;

    // Should go straight to exit
    float expected = (float) Math.atan2(exitY - Arena.RIVER_Y, 0); // 0 dx, just straight up

    // Note: The logic in BasePathfinder calculates angle to (bridgeX, exitY)
    // Since we are at (bridgeX, RIVER_Y), dx is 0.
    assertEquals(expected, angle, 0.001f);
  }

  @Test
  void testBridgeApproachAlignment() {
    // Scenario: Unit is approaching the left bridge from the south.
    // It is slightly to the left of the bridge center.
    // It should aim for the 'approach' point (RIVER_Y_MIN) before crossing.

    float bridgeX = Arena.LEFT_BRIDGE_X + Arena.BRIDGE_WIDTH / 2f; // e.g., 3.5
    float startX = bridgeX - 1.0f;
    float startY = Arena.RIVER_Y - 3.0f; // South of river

    Position start = new Position(startX, startY);
    float targetX = bridgeX; // Target is directly North across bridge
    float targetY = Arena.RIVER_Y + 5.0f;

    float angle =
        pathfinder.getNextMovementAngle(start, MovementType.GROUND, targetX, targetY, arena);

    // We expect it to aim at the bridge entrance (bridgeX, RIVER_Y_MIN)
    // RIVER_Y_MIN is roughly 15.0.
    float expectedY = Arena.RIVER_Y - 1.0f; // 15.0
    float expectedAngle = (float) Math.atan2(expectedY - startY, bridgeX - startX);

    assertEquals(expectedAngle, angle, 0.01f, "Should aim for bridge entrance first");
  }

  @Test
  void testInRiverZone_ShouldMoveStraightAcross() {
    // Scenario: Unit is ON the bridge (In River Zone).
    // It should ignore the final target X for a moment and walk straight Y until clear.

    float bridgeX = Arena.LEFT_BRIDGE_X + Arena.BRIDGE_WIDTH / 2f;

    // Position: On the bridge, slightly misaligned X but safely within 1.0 distance
    Position start = new Position(bridgeX + 0.1f, Arena.RIVER_Y);

    // Target: Far to the right (would normally cause a 45 deg turn)
    float targetX = bridgeX + 10f;
    float targetY = Arena.RIVER_Y + 10f;

    float angle =
        pathfinder.getNextMovementAngle(start, MovementType.GROUND, targetX, targetY, arena);

    // Expectation: Move towards bridge exit (RIVER_Y_MAX) keeping X aligned with CURRENT X
    // because we are safely on the bridge.
    float exitY = Arena.RIVER_Y + 1.0f;

    // UPDATED: Now we expect dx = 0, because targetBridgeX == curX
    float expectedAngle = (float) Math.atan2(exitY - Arena.RIVER_Y, 0);

    assertEquals(
        expectedAngle,
        angle,
        0.01f,
        "Should walk straight across bridge (parallel to Y axis) before turning");
  }
}
