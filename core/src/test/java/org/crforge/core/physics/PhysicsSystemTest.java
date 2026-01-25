package org.crforge.core.physics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.crforge.core.arena.Arena;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.entity.Entity;
import org.crforge.core.entity.MovementType;
import org.crforge.core.entity.Troop;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PhysicsSystemTest {

  private PhysicsSystem physicsSystem;
  private Arena arena;
  private Pathfinder pathfinder;

  @BeforeEach
  void setUp() {
    arena = new Arena("Test Arena");
    pathfinder = mock(Pathfinder.class);
    // Mock pathfinder to return 0 angle (Move right) by default
    when(pathfinder.getNextMovementAngle(any(), any(), anyFloat(), anyFloat(), any()))
        .thenReturn(0f);

    physicsSystem = new PhysicsSystem(arena, pathfinder);
  }

  @Test
  void testTroopToTroopCollision_ShouldPushApart() {
    // Two troops at same position
    Troop t1 = createTroop("T1", 10f, 10f, 1.0f); // Mass 1
    Troop t2 = createTroop("T2", 10f, 10f, 1.0f); // Mass 1

    // Force them slightly apart so collision resolution has a direction vector
    t2.getPosition().set(10.1f, 10f); // T2 is slightly to the right

    List<Entity> entities = List.of(t1, t2);

    // Run physics update
    physicsSystem.update(entities, 0.033f);

    // T1 should be pushed left, T2 pushed right (equal mass)
    assertThat(t1.getPosition().getX()).isLessThan(10f);
    assertThat(t2.getPosition().getX()).isGreaterThan(10.1f);
  }

  @Test
  void testHeavyPushingLight_ShouldDisplaceLightTroopMore() {
    Troop heavy = createTroop("Heavy", 10f, 10f, 10.0f); // Mass 10
    Troop light = createTroop("Light", 10.5f, 10f, 1.0f); // Mass 1, overlapping

    List<Entity> entities = List.of(heavy, light);
    physicsSystem.update(entities, 0.033f);

    // Calculate displacement
    float heavyDisp = Math.abs(heavy.getPosition().getX() - 10f);
    float lightDisp = Math.abs(light.getPosition().getX() - 10.5f);

    // Light troop should move significantly more than heavy troop
    assertThat(lightDisp).isGreaterThan(heavyDisp);
  }

  @Test
  void testArenaBoundaries() {
    // Place troop outside arena (e.g. X = -5)
    Troop t = createTroop("Out", -5f, 10f, 1.0f);

    List<Entity> entities = List.of(t);
    physicsSystem.update(entities, 0.033f);

    // Should be clamped to radius (0.5)
    assertThat(t.getPosition().getX()).isEqualTo(0.5f);
  }

  // Helpers
  private Troop createTroop(String name, float x, float y, float mass) {
    Troop troop = Troop.builder()
        .name(name)
        .team(Team.BLUE)
        .position(new Position(x, y))
        .movement(new Movement(5.0f, mass, 1.0f, MovementType.GROUND))
        .deployTime(0f)
        .build();

    troop.onSpawn(); // Fix: Mark as spawned so it is targetable/collidable
    return troop;
  }
}
