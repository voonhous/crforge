package org.crforge.core.physics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.util.GameUnits.tiles;

import java.util.List;
import org.crforge.core.arena.Arena;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.crforge.core.util.GameUnits;
import org.junit.jupiter.api.Test;

/**
 * Physics behavior that depends on the integer game-unit representation: fractional per-tick
 * movement, boundary clamping, knockback displacement, and exact collision boundaries.
 */
class IntegerUnitPhysicsTest {

  private static final float DT = GameEngine.DELTA_TIME;

  /** Pathfinder that always steers at a fixed angle, isolating integration from routing. */
  private static Pathfinder fixedAngle(float angle) {
    return (startPos, moveType, targetX, targetY, arena) -> angle;
  }

  private static Troop troop(int x, int y, float speed, int collisionRadius) {
    Troop troop =
        Troop.builder()
            .name("Walker")
            .team(Team.BLUE)
            .position(new Position(x, y))
            .movement(
                new Movement(speed, 4f, collisionRadius, collisionRadius, MovementType.GROUND))
            .deployTime(0f)
            .deployTimer(0f)
            .build();
    troop.onSpawn();
    return troop;
  }

  private static void tick(PhysicsSystem physics, List<Entity> entities, int ticks) {
    for (int i = 0; i < ticks; i++) {
      physics.update(entities, DT);
    }
  }

  @Test
  void slowSpeed_travelsFullDistanceOverOneSecond() {
    // Raw speed 45 (Giant) = 750 units/s = 37.5 units per tick; truncation would give 740
    PhysicsSystem physics = new PhysicsSystem(Arena.standard(), fixedAngle(0f));
    Troop giant = troop(tiles(3), tiles(10), GameUnits.rawSpeedToUnitsPerSecond(45f), 750);

    tick(physics, List.of(giant), GameEngine.TICKS_PER_SECOND);

    assertThat(giant.getPosition().getX()).isEqualTo(tiles(3) + 750);
    assertThat(giant.getPosition().getY()).isEqualTo(tiles(10));
  }

  @Test
  void mediumSpeed_travelsFourTilesInFourSeconds() {
    PhysicsSystem physics = new PhysicsSystem(Arena.standard(), fixedAngle((float) (Math.PI / 2)));
    Troop knight = troop(tiles(4), tiles(5), GameUnits.rawSpeedToUnitsPerSecond(60f), 500);

    tick(physics, List.of(knight), 4 * GameEngine.TICKS_PER_SECOND);

    assertThat(knight.getPosition().getY()).isEqualTo(tiles(9));
    // cos(pi/2) is not exactly zero in float math; drift must stay within one game unit
    assertThat(knight.getPosition().getX()).isBetween(tiles(4) - 1, tiles(4) + 1);
  }

  @Test
  void movementAlongArenaEdge_keepsForwardSpeedWhileClamped() {
    // Heading slightly into the left wall: X is clamped every tick. Clamping must not discard the
    // fractional Y progress (resetting the carry each tick would give 37 units/tick = 740).
    float angle = (float) (Math.PI / 2 + 0.05);
    PhysicsSystem physics = new PhysicsSystem(Arena.standard(), fixedAngle(angle));
    int radius = 500;
    Troop troop = troop(radius, tiles(8), 750f, radius);

    tick(physics, List.of(troop), GameEngine.TICKS_PER_SECOND);

    double expectedDy = 750.0 * Math.sin(angle);
    assertThat(troop.getPosition().getX()).isEqualTo(radius);
    assertThat(troop.getPosition().getY() - tiles(8))
        .isBetween((int) Math.floor(expectedDy) - 1, (int) Math.ceil(expectedDy) + 1);
  }

  @Test
  void enforceBounds_clampsToCollisionRadiusInsideArena() {
    PhysicsSystem physics = new PhysicsSystem(Arena.standard(), fixedAngle(0f));
    Troop outside = troop(-250, Arena.HEIGHT_UNITS + 40, 0f, 500);

    physics.update(List.of(outside), DT);

    assertThat(outside.getPosition().getX()).isEqualTo(500);
    assertThat(outside.getPosition().getY()).isEqualTo(Arena.HEIGHT_UNITS - 500);
  }

  @Test
  void knockback_displacesExactDistanceOverActiveFrames() {
    PhysicsSystem physics = new PhysicsSystem(Arena.standard(), fixedAngle(0f));
    Troop troop = troop(tiles(9), tiles(10), 0f, 500);

    // 1000 units over a 1 s time base -> 1000 units/s, active for 0.5 s (10 ticks) = 500 units
    troop.getMovement().startKnockback(0f, 1f, 1000, 0.5f, 1.0f);
    tick(physics, List.of(troop), 15);

    assertThat(troop.getPosition().getY()).isEqualTo(tiles(10) + 500);
    assertThat(troop.getPosition().getX()).isEqualTo(tiles(9));
  }

  @Test
  void touchingCircles_doNotCollide_butOneUnitOverlapDoes() {
    PhysicsSystem physics = new PhysicsSystem(Arena.standard(), fixedAngle(0f));

    // Distance exactly equals the sum of radii (500 + 500): no push
    Troop a = troop(tiles(9), tiles(10), 0f, 500);
    Troop b = troop(tiles(9) + 1000, tiles(10), 0f, 500);
    physics.update(List.of(a, b), DT);
    assertThat(a.getPosition().getX()).isEqualTo(tiles(9));
    assertThat(b.getPosition().getX()).isEqualTo(tiles(9) + 1000);

    // One game unit closer: overlap of 1 unit is resolved (equal mass, pushed apart)
    Troop c = troop(tiles(9), tiles(12), 0f, 500);
    Troop d = troop(tiles(9) + 999, tiles(12), 0f, 500);
    physics.update(List.of(c, d), DT);
    assertThat(d.getPosition().getX() - c.getPosition().getX()).isEqualTo(1000);
  }

  @Test
  void coincidentTroops_separateAlongDefaultDirection() {
    PhysicsSystem physics = new PhysicsSystem(Arena.standard(), fixedAngle(0f));
    Troop a = troop(tiles(9), tiles(10), 0f, 500);
    Troop b = troop(tiles(9), tiles(10), 0f, 500);

    physics.update(List.of(a, b), DT);

    // Full 1000-unit overlap split by equal mass along +X / -X
    assertThat(a.getPosition().getX()).isEqualTo(tiles(9) + 500);
    assertThat(b.getPosition().getX()).isEqualTo(tiles(9) - 500);
    assertThat(a.getPosition().getY()).isEqualTo(tiles(10));
  }
}
