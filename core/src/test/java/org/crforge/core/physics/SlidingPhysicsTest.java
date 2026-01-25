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
import org.crforge.core.entity.Building;
import org.crforge.core.entity.Entity;
import org.crforge.core.entity.MovementType;
import org.crforge.core.entity.Troop;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SlidingPhysicsTest {

  private PhysicsSystem physicsSystem;
  private Arena arena;
  private Pathfinder pathfinder;

  @BeforeEach
  void setUp() {
    arena = new Arena("Test Arena");
    pathfinder = mock(Pathfinder.class);
    physicsSystem = new PhysicsSystem(arena, pathfinder);
  }

  @Test
  void testTroopCollidingWithBuilding_ShouldSlide() {
    // Building at (10, 10) with size 3.0 (Radius 1.5)
    Building building = createBuilding("Building", 10f, 10f, 3.0f);

    // Troop at (9, 9) moving North-East towards building center
    Troop troop = createTroop("Troop", 9f, 9f);

    // Set troop intent to move North-East (45 degrees) into the building
    when(pathfinder.getNextMovementAngle(any(), any(), anyFloat(), anyFloat(), any()))
        .thenReturn((float) Math.toRadians(45));

    List<Entity> entities = List.of(building, troop);

    physicsSystem.update(entities, 0.033f);

    // Physics should push troop OUT of collision (away from 10,10)
    float distAfter = building.getPosition().distanceTo(troop.getPosition());
    assertThat(distAfter).isGreaterThan(1.414f);
  }

  @Test
  void testSliding_GlancingBlow() {
    Building building = createBuilding("Building", 10f, 20f, 3.0f);
    Troop troop = createTroop("Slider", 8.8f, 18.8f);

    // Intent: Move mostly UP (80 degrees)
    float moveAngle = (float) Math.toRadians(80);
    troop.getPosition().setRotation(moveAngle);

    when(pathfinder.getNextMovementAngle(any(), any(), anyFloat(), anyFloat(), any()))
        .thenReturn(moveAngle);

    List<Entity> entities = List.of(building, troop);
    physicsSystem.update(entities, 0.033f);

    // Verify it moved UP (Y increased)
    assertThat(troop.getPosition().getY()).isGreaterThan(18.8f);
    // Verify it moved LEFT (X decreased) because it slid around the corner
    assertThat(troop.getPosition().getX()).isLessThan(8.8f);
  }

  // Helpers
  private Troop createTroop(String name, float x, float y) {
    Troop troop = Troop.builder()
        .name(name)
        .team(Team.BLUE)
        .position(new Position(x, y))
        .movement(new Movement(5.0f, 1.0f, 1.0f, MovementType.GROUND))
        .deployTime(0f)
        .build();
    troop.onSpawn();
    return troop;
  }

  private Building createBuilding(String name, float x, float y, float size) {
    Building building = Building.builder()
        .name(name)
        .team(Team.RED)
        .position(new Position(x, y))
        .movement(new Movement(0, 0, size, MovementType.BUILDING))
        .build();
    building.onSpawn();
    return building;
  }
}
