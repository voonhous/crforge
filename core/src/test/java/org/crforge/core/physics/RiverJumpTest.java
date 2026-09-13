package org.crforge.core.physics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.crforge.core.util.GameUnits.tiles;

import java.util.List;
import org.crforge.core.arena.Arena;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the river jump mechanic. Jump-enabled troops (Hog Rider, Royal Hogs, etc.) can leap
 * over the river instead of routing through bridges. While jumping, they become untargetable with
 * AIR movement type and a speed boost.
 */
class RiverJumpTest {

  private PhysicsSystem physicsSystem;
  private Arena arena;

  // River zone: Y in [15, 17] tiles
  // Left bridge: X in [2, 5) tiles, Right bridge: X in [13, 16) tiles
  // Non-bridge X for testing: 9 tiles (center of arena, not on any bridge)
  // Constants below are game units.
  private static final int NON_BRIDGE_X = tiles(9.0);
  private static final int RIVER_CENTER_Y = tiles(16.0);
  private static final int BELOW_RIVER_Y = tiles(14.0);
  private static final int ABOVE_RIVER_Y = tiles(18.0);

  @BeforeEach
  void setUp() {
    arena = Arena.standard();
    physicsSystem = new PhysicsSystem(arena);
  }

  @Test
  void jumpEnabled_crossesRiverWithoutBridge() {
    // Place a jump-enabled troop in the river zone at a non-bridge X position
    Troop troop = createJumpTroop("HogRider", NON_BRIDGE_X, RIVER_CENTER_Y);

    physicsSystem.update(List.of(troop), 1f / 30f);

    assertThat(troop.isJumping()).isTrue();
  }

  @Test
  void jumpEnabled_becomesUntargetableWhileJumping() {
    Troop troop = createJumpTroop("HogRider", NON_BRIDGE_X, RIVER_CENTER_Y);

    physicsSystem.update(List.of(troop), 1f / 30f);

    assertThat(troop.isJumping()).isTrue();
    assertThat(troop.isTargetable()).isFalse();
    assertThat(troop.getMovementType()).isEqualTo(MovementType.AIR);
  }

  @Test
  void jumpEnabled_targetableAfterCrossing() {
    // Start in river zone to trigger jump
    Troop troop = createJumpTroop("HogRider", NON_BRIDGE_X, RIVER_CENTER_Y);
    physicsSystem.update(List.of(troop), 1f / 30f);
    assertThat(troop.isJumping()).isTrue();

    // Move troop out of the river zone (above)
    troop.getPosition().set(NON_BRIDGE_X, ABOVE_RIVER_Y);
    physicsSystem.update(List.of(troop), 1f / 30f);

    assertThat(troop.isJumping()).isFalse();
    assertThat(troop.isTargetable()).isTrue();
    assertThat(troop.getMovementType()).isEqualTo(MovementType.GROUND);
  }

  @Test
  void jumpEnabled_speedBoostDuringJump() {
    Troop troop = createJumpTroop("HogRider", NON_BRIDGE_X, RIVER_CENTER_Y);
    float baseSpeed = troop.getMovement().getBaseSpeed();

    physicsSystem.update(List.of(troop), 1f / 30f);

    assertThat(troop.isJumping()).isTrue();
    float expectedSpeed = baseSpeed * (4f / 3f);
    // Speeds are game units per second
    assertThat(troop.getMovement().getEffectiveSpeed()).isCloseTo(expectedSpeed, within(1f));
  }

  @Test
  void jumpEnabled_speedRestoredAfterJump() {
    Troop troop = createJumpTroop("HogRider", NON_BRIDGE_X, RIVER_CENTER_Y);
    float baseSpeed = troop.getMovement().getBaseSpeed();

    // Enter river zone -> jumping
    physicsSystem.update(List.of(troop), 1f / 30f);
    assertThat(troop.isJumping()).isTrue();

    // Exit river zone -> no longer jumping
    troop.getPosition().set(NON_BRIDGE_X, ABOVE_RIVER_Y);
    physicsSystem.update(List.of(troop), 1f / 30f);

    assertThat(troop.isJumping()).isFalse();
    assertThat(troop.getMovement().getEffectiveSpeed()).isCloseTo(baseSpeed, within(1f));
  }

  @Test
  void jumpEnabled_usesBridgeWhenAligned() {
    // Place jump-enabled troop on a bridge X position in the river zone
    int bridgeCenterX = Arena.LEFT_BRIDGE_CENTER_X;
    Troop troop = createJumpTroop("HogRider", bridgeCenterX, RIVER_CENTER_Y);

    physicsSystem.update(List.of(troop), 1f / 30f);

    // Should NOT be jumping because it's on a bridge
    assertThat(troop.isJumping()).isFalse();
    assertThat(troop.getMovementType()).isEqualTo(MovementType.GROUND);
    assertThat(troop.isTargetable()).isTrue();
  }

  @Test
  void normalTroop_cannotJumpRiver() {
    // Regular troop (no jumpEnabled) in the river zone at non-bridge X
    Troop troop = createNormalTroop("Knight", NON_BRIDGE_X, RIVER_CENTER_Y);

    physicsSystem.update(List.of(troop), 1f / 30f);

    assertThat(troop.isJumping()).isFalse();
    assertThat(troop.getMovementType()).isEqualTo(MovementType.GROUND);
    assertThat(troop.isTargetable()).isTrue();
  }

  @Test
  void jumpEnabled_notJumpingOutsideRiverZone() {
    // Jump-enabled troop well below the river zone
    Troop troop = createJumpTroop("HogRider", NON_BRIDGE_X, BELOW_RIVER_Y);

    physicsSystem.update(List.of(troop), 1f / 30f);

    assertThat(troop.isJumping()).isFalse();
    assertThat(troop.getMovementType()).isEqualTo(MovementType.GROUND);
  }

  // -- Pathfinding tests: jump-enabled troops should path straight, not route to bridges --

  @Test
  void jumpEnabled_pathsStraightTowardTarget_noBridgeDetour() {
    // Place a jump-enabled troop south of river at center X (not on any bridge)
    // with a target north of the river at the same X.
    // The troop should move straight north (no X drift toward bridges).
    Troop troop = createJumpTroop("HogRider", NON_BRIDGE_X, tiles(14.0));
    Troop target = createNormalTroop("Target", NON_BRIDGE_X, tiles(20.0));
    troop.getCombat().setCurrentTarget(target);

    int startX = troop.getPosition().getX();

    // Run several ticks
    for (int i = 0; i < 30; i++) {
      physicsSystem.update(List.of(troop, target), 1f / 30f);
    }

    // X should not drift toward a bridge -- stays at the same X (within a small tolerance)
    assertThat(troop.getPosition().getX()).isCloseTo(startX, within(tiles(0.1)));
    // Y should have moved north (increased)
    assertThat(troop.getPosition().getY()).isGreaterThan(tiles(14.0));
  }

  @Test
  void jumpEnabled_entersJumpingStateViaStraightPath() {
    // Place a jump-enabled troop just south of river zone with a target north of river.
    // After enough ticks, it should enter the river zone at non-bridge X and start jumping.
    Troop troop = createJumpTroop("HogRider", NON_BRIDGE_X, tiles(14.5));
    Troop target = createNormalTroop("Target", NON_BRIDGE_X, tiles(20.0));
    troop.getCombat().setCurrentTarget(target);

    boolean jumped = false;
    for (int i = 0; i < 90; i++) {
      physicsSystem.update(List.of(troop, target), 1f / 30f);
      if (troop.isJumping()) {
        jumped = true;
        break;
      }
    }

    assertThat(jumped)
        .as("Jump-enabled troop should enter jumping state via straight path")
        .isTrue();
  }

  @Test
  void normalTroop_routesToBridge_notStraightAcross() {
    // Same setup as above but with a normal (non-jump) troop.
    // It should drift in X toward the nearest bridge instead of going straight.
    Troop troop = createNormalTroop("Knight", NON_BRIDGE_X, tiles(14.0));
    Troop target = createNormalTroop("Target", NON_BRIDGE_X, tiles(20.0));
    troop.getCombat().setCurrentTarget(target);

    int startX = troop.getPosition().getX();

    for (int i = 0; i < 30; i++) {
      physicsSystem.update(List.of(troop, target), 1f / 30f);
    }

    // Normal troop should have drifted in X toward the nearest bridge
    int endX = troop.getPosition().getX();
    assertThat(Math.abs(endX - startX))
        .as("Normal troop should drift toward bridge")
        .isGreaterThan(tiles(0.1));
  }

  // -- Helpers --

  private Troop createJumpTroop(String name, int x, int y) {
    Movement movement = new Movement(tiles(5.0), 1.0f, tiles(0.5), tiles(0.5), MovementType.GROUND);
    movement.setJumpEnabled(true);

    Troop troop =
        Troop.builder()
            .name(name)
            .team(Team.BLUE)
            .position(new Position(x, y))
            .movement(movement)
            .deployTime(0f)
            .build();
    troop.onSpawn();
    return troop;
  }

  private Troop createNormalTroop(String name, int x, int y) {
    Troop troop =
        Troop.builder()
            .name(name)
            .team(Team.BLUE)
            .position(new Position(x, y))
            .movement(new Movement(tiles(5.0), 1.0f, tiles(0.5), tiles(0.5), MovementType.GROUND))
            .deployTime(0f)
            .build();
    troop.onSpawn();
    return troop;
  }
}
