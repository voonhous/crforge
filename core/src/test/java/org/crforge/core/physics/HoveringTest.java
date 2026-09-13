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
 * Tests for the hovering river-crossing mechanic. Hovering troops (BattleHealer, Royal Ghost)
 * ignore river tile restrictions for pathfinding but stay GROUND, remain targetable, and get no
 * speed boost -- unlike jumpEnabled troops which become AIR and untargetable with a speed boost.
 */
class HoveringTest {

  private PhysicsSystem physicsSystem;
  private Arena arena;

  // Game-unit coordinates: X = 9 tiles (not on a bridge), river center Y = 16 tiles
  private static final int NON_BRIDGE_X = tiles(9.0);
  private static final int RIVER_CENTER_Y = tiles(16.0);

  @BeforeEach
  void setUp() {
    arena = Arena.standard();
    physicsSystem = new PhysicsSystem(arena);
  }

  @Test
  void hovering_pathsStraightAcrossRiver() {
    // Hovering troop south of river at center X with a target north of the river.
    // Should move straight north without drifting toward a bridge.
    Troop troop = createHoveringTroop("BattleHealer", NON_BRIDGE_X, tiles(14.0));
    Troop target = createNormalTroop("Target", NON_BRIDGE_X, tiles(20.0));
    troop.getCombat().setCurrentTarget(target);

    int startX = troop.getPosition().getX();

    for (int i = 0; i < 30; i++) {
      physicsSystem.update(List.of(troop, target), 1f / 30f);
    }

    // X should not drift toward a bridge
    assertThat(troop.getPosition().getX()).isCloseTo(startX, within(tiles(0.1)));
    // Y should have moved north
    assertThat(troop.getPosition().getY()).isGreaterThan(tiles(14.0));
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

    // Speeds are game units per second
    assertThat(troop.getMovement().getEffectiveSpeed()).isCloseTo(baseSpeed, within(1f));
  }

  @Test
  void normalTroop_stillRoutesToBridge() {
    // Sanity check: a normal (non-hovering, non-jumping) troop should still route to a bridge
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

  private Troop createHoveringTroop(String name, int x, int y) {
    Movement movement = new Movement(tiles(5.0), 1.0f, tiles(0.5), tiles(0.5), MovementType.GROUND);
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
