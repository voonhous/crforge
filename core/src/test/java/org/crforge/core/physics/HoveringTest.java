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
 * Tests for the hovering river-crossing mechanic. Hovering troops (BattleHealer, Royal Ghost)
 * ignore river tile restrictions for pathfinding but stay GROUND, remain targetable, and get no
 * speed boost -- unlike jumpEnabled troops which become AIR and untargetable with a speed boost.
 */
class HoveringTest {

  private PhysicsSystem physicsSystem;
  private Arena arena;

  private static final float NON_BRIDGE_X = 9.0f;
  private static final float RIVER_CENTER_Y = 16.0f;

  @BeforeEach
  void setUp() {
    arena = Arena.standard();
    physicsSystem = new PhysicsSystem(arena);
  }

  @Test
  void hovering_pathsStraightAcrossRiver() {
    // Hovering troop south of river at center X with a target north of the river.
    // Should move straight north without drifting toward a bridge.
    Troop troop = createHoveringTroop("BattleHealer", NON_BRIDGE_X, 14.0f);
    Troop target = createNormalTroop("Target", NON_BRIDGE_X, 20.0f);
    troop.getCombat().setCurrentTarget(target);

    float startX = troop.getPosition().getX();

    for (int i = 0; i < 30; i++) {
      physicsSystem.update(List.of(troop, target), 1f / 30f);
    }

    // X should not drift toward a bridge
    assertThat(troop.getPosition().getX()).isCloseTo(startX, within(0.1f));
    // Y should have moved north
    assertThat(troop.getPosition().getY()).isGreaterThan(14.0f);
  }

  @Test
  void hovering_staysGroundTypeInRiverZone() {
    // A hovering troop in the river zone should stay GROUND, not become AIR
    Troop troop = createHoveringTroop("BattleHealer", NON_BRIDGE_X, RIVER_CENTER_Y);

    physicsSystem.update(List.of(troop), 1f / 30f);

    assertThat(troop.getMovementType()).isEqualTo(MovementType.GROUND);
    // Should NOT enter jumping state
    assertThat(troop.isJumping()).isFalse();
  }

  @Test
  void hovering_staysTargetableInRiverZone() {
    // Hovering troops remain targetable while crossing the river (unlike jumping troops)
    Troop troop = createHoveringTroop("BattleHealer", NON_BRIDGE_X, RIVER_CENTER_Y);

    physicsSystem.update(List.of(troop), 1f / 30f);

    assertThat(troop.isTargetable()).isTrue();
  }

  @Test
  void hovering_noSpeedBoostInRiverZone() {
    // Hovering troops should NOT get the 4/3x speed boost that jumping troops get
    Troop troop = createHoveringTroop("BattleHealer", NON_BRIDGE_X, RIVER_CENTER_Y);
    float baseSpeed = troop.getMovement().getBaseSpeed();

    physicsSystem.update(List.of(troop), 1f / 30f);

    assertThat(troop.getMovement().getEffectiveSpeed()).isCloseTo(baseSpeed, within(0.001f));
  }

  @Test
  void normalTroop_stillRoutesToBridge() {
    // Sanity check: a normal (non-hovering, non-jumping) troop should still route to a bridge
    Troop troop = createNormalTroop("Knight", NON_BRIDGE_X, 14.0f);
    Troop target = createNormalTroop("Target", NON_BRIDGE_X, 20.0f);
    troop.getCombat().setCurrentTarget(target);

    float startX = troop.getPosition().getX();

    for (int i = 0; i < 30; i++) {
      physicsSystem.update(List.of(troop, target), 1f / 30f);
    }

    // Normal troop should have drifted in X toward the nearest bridge
    float endX = troop.getPosition().getX();
    assertThat(Math.abs(endX - startX))
        .as("Normal troop should drift toward bridge")
        .isGreaterThan(0.1f);
  }

  // -- Helpers --

  private Troop createHoveringTroop(String name, float x, float y) {
    Movement movement = new Movement(5.0f, 1.0f, 0.5f, 0.5f, MovementType.GROUND);
    movement.setHovering(true);

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

  private Troop createNormalTroop(String name, float x, float y) {
    Troop troop =
        Troop.builder()
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
