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
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;
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
    Building building = createBuilding("Building", 10f, 10f, 1.5f);

    // Troop at (9, 9) moving North-East towards building center
    Troop troop = createTroop("Troop", 9f, 9f);

    // Set troop intent to move North-East (45 degrees) into the building
    when(pathfinder.getNextMovementAngle(any(), any(), anyInt(), anyInt(), any()))
        .thenReturn((float) Math.toRadians(45));

    List<Entity> entities = List.of(building, troop);

    physicsSystem.update(entities, 0.033f);

    // Physics should push troop OUT of collision (away from 10,10)
    float distAfter = building.getPosition().distance(troop.getPosition());
    assertThat(distAfter).isGreaterThan(tiles(1.414));
  }

  @Test
  void testSliding_GlancingBlow() {
    Building building = createBuilding("Building", 10f, 20f, 1.5f);
    Troop troop = createTroop("Slider", 8.8f, 18.8f);

    // Intent: Move mostly UP (80 degrees)
    float moveAngle = (float) Math.toRadians(80);
    troop.getPosition().setRotation(moveAngle);

    when(pathfinder.getNextMovementAngle(any(), any(), anyInt(), anyInt(), any()))
        .thenReturn(moveAngle);

    List<Entity> entities = List.of(building, troop);
    physicsSystem.update(entities, 0.033f);

    // Verify it moved UP (Y increased)
    assertThat(troop.getPosition().getY()).isGreaterThan(tiles(18.8));
    // Verify it moved LEFT (X decreased) because it slid around the corner
    assertThat(troop.getPosition().getX()).isLessThan(tiles(8.8));
  }

  // Helpers
  /** Creates a troop at a tile-space position. */
  private Troop createTroop(String name, float x, float y) {
    Troop troop =
        Troop.builder()
            .name(name)
            .team(Team.BLUE)
            .position(new Position(tiles(x), tiles(y)))
            .movement(new Movement(tiles(5.0), 1.0f, tiles(0.5), tiles(0.5), MovementType.GROUND))
            .deployTime(0f)
            .build();
    troop.onSpawn();
    return troop;
  }

  /** Creates a building at a tile-space position with a radius in tiles. */
  private Building createBuilding(String name, float x, float y, float radius) {
    Building building =
        Building.builder()
            .name(name)
            .team(Team.RED)
            .position(new Position(tiles(x), tiles(y)))
            .movement(new Movement(0, 0, tiles(radius), tiles(radius * 1.5), MovementType.BUILDING))
            .build();
    building.onSpawn();
    return building;
  }
}
