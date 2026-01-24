package org.crforge.core.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import org.crforge.core.arena.Arena;
import org.crforge.core.component.Position;
import org.crforge.core.entity.MovementType;
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

    float angle = pathfinder.getNextMovementAngle(start, MovementType.GROUND, targetX, targetY,
        arena);

    float expected = (float) Math.atan2(3, 5);
    assertEquals(expected, angle, 0.001f);
  }

  @Test
  void testGroundCrossRiverGoesToBridge() {
    // Start Blue (South), Target Red (North)
    Position start = new Position(1, 5); // Far left
    float targetX = 15; // Far right
    float targetY = 25; // North

    float angle = pathfinder.getNextMovementAngle(start, MovementType.GROUND, targetX, targetY,
        arena);

    // Should target Left Bridge because 1 is closer to left bridge than right
    float bridgeX = Arena.LEFT_BRIDGE_X + Arena.BRIDGE_WIDTH / 2f;
    float bridgeY = Arena.RIVER_Y;

    float expected = (float) Math.atan2(bridgeY - 5, bridgeX - 1);
    assertEquals(expected, angle, 0.001f);
  }

  @Test
  void testGroundCrossRiverGoesToRightBridge() {
    // Start Blue (South), Target Red (North)
    Position start = new Position(17, 5); // Far right
    float targetX = 5; // Far left
    float targetY = 25; // North

    float angle = pathfinder.getNextMovementAngle(start, MovementType.GROUND, targetX, targetY,
        arena);

    // Should target Right Bridge
    float bridgeX = Arena.RIGHT_BRIDGE_X + Arena.BRIDGE_WIDTH / 2f;
    float bridgeY = Arena.RIVER_Y;

    float expected = (float) Math.atan2(bridgeY - 5, bridgeX - 17);
    assertEquals(expected, angle, 0.001f);
  }

  @Test
  void testGroundOnBridgeContinuesStraight() {
    // Start in River zone (center of left bridge)
    float leftBridgeCenterX = Arena.LEFT_BRIDGE_X + Arena.BRIDGE_WIDTH / 2f;
    Position start = new Position(leftBridgeCenterX, Arena.RIVER_Y);
    float targetX = 10;
    float targetY = 25;

    float angle = pathfinder.getNextMovementAngle(start, MovementType.GROUND, targetX, targetY,
        arena);

    // Should go straight to target
    float expected = (float) Math.atan2(25 - Arena.RIVER_Y, 10 - leftBridgeCenterX);
    assertEquals(expected, angle, 0.001f);
  }
}