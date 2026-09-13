package org.crforge.core.physics;

import static org.crforge.core.util.GameUnits.tiles;
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

  // River rows and bridge centers expressed in game units
  private static final int RIVER_Y = tiles(Arena.RIVER_Y);
  private static final int LEFT_BRIDGE_CENTER_X =
      tiles(Arena.LEFT_BRIDGE_X + Arena.BRIDGE_WIDTH / 2.0);
  private static final int RIGHT_BRIDGE_CENTER_X =
      tiles(Arena.RIGHT_BRIDGE_X + Arena.BRIDGE_WIDTH / 2.0);

  @BeforeEach
  void setUp() {
    pathfinder = new BasePathfinder();
    arena = mock(Arena.class);
  }

  @Test
  void testAirUnitsFlyStraight() {
    Position start = new Position(tiles(5), tiles(5));
    int targetX = tiles(10);
    int targetY = tiles(10);

    float angle = pathfinder.getNextMovementAngle(start, MovementType.AIR, targetX, targetY, arena);

    float expected = (float) Math.atan2(5, 5); // 45 degrees
    assertEquals(expected, angle, 0.001f);
  }

  @Test
  void testGroundSameSideMovesStraight() {
    // Both on Blue side (Bottom)
    Position start = new Position(tiles(5), tiles(5));
    int targetX = tiles(10);
    int targetY = tiles(8); // Still south of river (River starts at 15 tiles)

    float angle =
        pathfinder.getNextMovementAngle(start, MovementType.GROUND, targetX, targetY, arena);

    float expected = (float) Math.atan2(3, 5);
    assertEquals(expected, angle, 0.001f);
  }

  @Test
  void testGroundCrossRiverGoesToBridge() {
    // Start Blue (South), Target Red (North)
    Position start = new Position(tiles(1), tiles(5)); // Far left
    int targetX = tiles(15); // Far right
    int targetY = tiles(25); // North

    float angle =
        pathfinder.getNextMovementAngle(start, MovementType.GROUND, targetX, targetY, arena);

    // Should target Left Bridge because 1 is closer to left bridge than right
    int bridgeX = LEFT_BRIDGE_CENTER_X;

    // The Pathfinder aims at BRIDGE_Y - 1 tile (approach point) when south
    int approachY = RIVER_Y - tiles(1);

    float expected = (float) Math.atan2(approachY - tiles(5), bridgeX - tiles(1));
    assertEquals(expected, angle, 0.001f);
  }

  @Test
  void testGroundCrossRiverGoesToRightBridge() {
    // Start Blue (South), Target Red (North)
    Position start = new Position(tiles(17), tiles(5)); // Far right
    int targetX = tiles(5); // Far left
    int targetY = tiles(25); // North

    float angle =
        pathfinder.getNextMovementAngle(start, MovementType.GROUND, targetX, targetY, arena);

    // Should target Right Bridge
    int bridgeX = RIGHT_BRIDGE_CENTER_X;
    int approachY = RIVER_Y - tiles(1);

    float expected = (float) Math.atan2(approachY - tiles(5), bridgeX - tiles(17));
    assertEquals(expected, angle, 0.001f);
  }

  @Test
  void testGroundOnBridgeContinuesStraight() {
    // Start in River zone (center of left bridge)
    Position start = new Position(LEFT_BRIDGE_CENTER_X, RIVER_Y);
    int targetX = tiles(10);
    int targetY = tiles(25);

    float angle =
        pathfinder.getNextMovementAngle(start, MovementType.GROUND, targetX, targetY, arena);

    // In River Zone (on bridge), it should aim for River Max (Exit)
    int exitY = RIVER_Y + tiles(1);

    // Should go straight to exit
    float expected = (float) Math.atan2(exitY - RIVER_Y, 0); // 0 dx, just straight up

    // Note: The logic in BasePathfinder calculates angle to (bridgeX, exitY)
    // Since we are at (bridgeX, RIVER_Y), dx is 0.
    assertEquals(expected, angle, 0.001f);
  }

  @Test
  void testBridgeApproachAlignment() {
    // Scenario: Unit is approaching the left bridge from the south.
    // It is slightly to the left of the bridge center.
    // It should aim for the 'approach' point (RIVER_Y_MIN) before crossing.

    int bridgeX = LEFT_BRIDGE_CENTER_X; // 3.5 tiles
    int startX = bridgeX - tiles(1);
    int startY = RIVER_Y - tiles(3); // South of river

    Position start = new Position(startX, startY);
    int targetX = bridgeX; // Target is directly North across bridge
    int targetY = RIVER_Y + tiles(5);

    float angle =
        pathfinder.getNextMovementAngle(start, MovementType.GROUND, targetX, targetY, arena);

    // We expect it to aim at the bridge entrance (bridgeX, RIVER_Y_MIN)
    // RIVER_Y_MIN is 15 tiles.
    int expectedY = RIVER_Y - tiles(1);
    float expectedAngle = (float) Math.atan2(expectedY - startY, bridgeX - startX);

    assertEquals(expectedAngle, angle, 0.01f, "Should aim for bridge entrance first");
  }

  @Test
  void testInRiverZone_ShouldMoveStraightAcross() {
    // Scenario: Unit is ON the bridge (In River Zone).
    // It should ignore the final target X for a moment and walk straight Y until clear.

    int bridgeX = LEFT_BRIDGE_CENTER_X;

    // Position: On the bridge, slightly misaligned X but safely within 1 tile distance
    Position start = new Position(bridgeX + tiles(0.1), RIVER_Y);

    // Target: Far to the right (would normally cause a 45 deg turn)
    int targetX = bridgeX + tiles(10);
    int targetY = RIVER_Y + tiles(10);

    float angle =
        pathfinder.getNextMovementAngle(start, MovementType.GROUND, targetX, targetY, arena);

    // Expectation: Move towards bridge exit (RIVER_Y_MAX) keeping X aligned with CURRENT X
    // because we are safely on the bridge.
    int exitY = RIVER_Y + tiles(1);

    // UPDATED: Now we expect dx = 0, because targetBridgeX == curX
    float expectedAngle = (float) Math.atan2(exitY - RIVER_Y, 0);

    assertEquals(
        expectedAngle,
        angle,
        0.01f,
        "Should walk straight across bridge (parallel to Y axis) before turning");
  }
}
