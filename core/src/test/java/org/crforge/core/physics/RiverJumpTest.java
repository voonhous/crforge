package org.crforge.core.physics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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
 * Tests for the river jump mechanic. Jump-enabled troops (Hog Rider, Royal Hogs, etc.)
 * can leap over the river instead of routing through bridges. While jumping, they become
 * untargetable with AIR movement type and a speed boost.
 */
class RiverJumpTest {

  private PhysicsSystem physicsSystem;
  private Arena arena;

  // River zone: Y in [15.0, 17.0]
  // Left bridge: X in [2, 5), Right bridge: X in [13, 16)
  // Non-bridge X for testing: 9.0 (center of arena, not on any bridge)
  private static final float NON_BRIDGE_X = 9.0f;
  private static final float RIVER_CENTER_Y = 16.0f;
  private static final float BELOW_RIVER_Y = 14.0f;
  private static final float ABOVE_RIVER_Y = 18.0f;

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
    assertThat(troop.getMovement().getEffectiveSpeed()).isCloseTo(expectedSpeed, within(0.001f));
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
    assertThat(troop.getMovement().getEffectiveSpeed()).isCloseTo(baseSpeed, within(0.001f));
  }

  @Test
  void jumpEnabled_usesBridgeWhenAligned() {
    // Place jump-enabled troop on a bridge X position in the river zone
    float bridgeCenterX = Arena.LEFT_BRIDGE_X + Arena.BRIDGE_WIDTH / 2f;
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

  // -- Helpers --

  private Troop createJumpTroop(String name, float x, float y) {
    Movement movement = new Movement(5.0f, 1.0f, 0.5f, 0.5f, MovementType.GROUND);
    movement.setJumpEnabled(true);

    Troop troop = Troop.builder()
        .name(name)
        .team(Team.BLUE)
        .position(new Position(x, y))
        .movement(movement)
        .deployTime(0f)
        .build();
    troop.onSpawn();
    return troop;
  }

  private Troop createNormalTroop(String name, float x, float y) {
    Troop troop = Troop.builder()
        .name(name)
        .team(Team.BLUE)
        .position(new Position(x, y))
        .movement(new Movement(5.0f, 1.0f, 0.5f, 0.5f, MovementType.GROUND))
        .deployTime(0f)
        .build();
    troop.onSpawn();
    return troop;
  }
}
