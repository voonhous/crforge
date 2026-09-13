package org.crforge.core.physics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.util.GameUnits.tiles;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.crforge.core.arena.Arena;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.unit.Troop;
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
    when(pathfinder.getNextMovementAngle(any(), any(), anyInt(), anyInt(), any())).thenReturn(0f);

    physicsSystem = new PhysicsSystem(arena, pathfinder);
  }

  @Test
  void testTroopToTroopCollision_ShouldPushApart() {
    // Two troops at same position
    Troop t1 = createTroop("T1", 10f, 10f, 1.0f); // Mass 1
    Troop t2 = createTroop("T2", 10f, 10f, 1.0f); // Mass 1

    // Force them slightly apart so collision resolution has a direction vector
    t2.getPosition().set(tiles(10.1), tiles(10)); // T2 is slightly to the right

    List<Entity> entities = List.of(t1, t2);

    // Run physics update
    physicsSystem.update(entities, 0.033f);

    // T1 should be pushed left, T2 pushed right (equal mass)
    assertThat(t1.getPosition().getX()).isLessThan(tiles(10));
    assertThat(t2.getPosition().getX()).isGreaterThan(tiles(10.1));
  }

  @Test
  void testHeavyPushingLight_ShouldDisplaceLightTroopMore() {
    Troop heavy = createTroop("Heavy", 10f, 10f, 10.0f); // Mass 10
    Troop light = createTroop("Light", 10.5f, 10f, 1.0f); // Mass 1, overlapping

    List<Entity> entities = List.of(heavy, light);
    physicsSystem.update(entities, 0.033f);

    // Calculate displacement
    int heavyDisp = Math.abs(heavy.getPosition().getX() - tiles(10));
    int lightDisp = Math.abs(light.getPosition().getX() - tiles(10.5));

    // Light troop should move significantly more than heavy troop
    assertThat(lightDisp).isGreaterThan(heavyDisp);
  }

  @Test
  void testArenaBoundaries() {
    // Place troop outside arena (e.g. X = -5)
    Troop t = createTroop("Out", -5f, 10f, 1.0f);

    List<Entity> entities = List.of(t);
    physicsSystem.update(entities, 0.033f);

    // Should be clamped to radius (0.5 tiles)
    assertThat(t.getPosition().getX()).isEqualTo(tiles(0.5));
  }

  // Helpers
  /** Creates a collidable troop at a tile-space position. */
  private Troop createTroop(String name, float x, float y, float mass) {
    Troop troop =
        Troop.builder()
            .name(name)
            .team(Team.BLUE)
            .position(new Position(tiles(x), tiles(y)))
            .movement(new Movement(tiles(5.0), mass, tiles(0.5), tiles(0.5), MovementType.GROUND))
            .deployTime(0f)
            .build();

    troop.onSpawn(); // Fix: Mark as spawned so it is targetable/collidable
    return troop;
  }
}
