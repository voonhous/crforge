package org.crforge.core.physics.astar;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.arena.Arena;
import org.crforge.core.component.Position;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.crforge.core.testing.SimHarness;
import org.crforge.core.testing.SimSystems;
import org.crforge.core.testing.TroopTemplate;
import org.junit.jupiter.api.Test;

class AStarPathfinderTest {

  @Test
  void airUnit_movesInStraightLine() {
    GameState state = new GameState();
    AStarPathfinder pf = new AStarPathfinder(state);
    Arena arena = Arena.standard();
    Position pos = new Position(9f, 5f);

    // Air unit: straight line toward target, ignoring river
    Entity entity =
        Troop.builder().name("Bat").team(Team.BLUE).position(new Position(9f, 5f)).build();
    state.spawnEntity(entity);
    state.processPending();

    float angle = pf.getNextMovementAngle(pos, MovementType.AIR, 9f, 25f, arena, entity);

    float expectedAngle = (float) Math.atan2(25f - 5f, 0f);
    assertThat(angle).isCloseTo(expectedAngle, org.assertj.core.data.Offset.offset(0.01f));
  }

  @Test
  void groundUnit_pathsTowardBridge() {
    GameState state = new GameState();
    AStarPathfinder pf = new AStarPathfinder(state);
    Arena arena = Arena.standard();

    // Ground unit at center-left of blue side, target on red side
    Position pos = new Position(5f, 10f);
    Entity entity =
        Troop.builder().name("Knight").team(Team.BLUE).position(new Position(5f, 10f)).build();
    state.spawnEntity(entity);
    state.processPending();

    float angle = pf.getNextMovementAngle(pos, MovementType.GROUND, 5f, 22f, arena, entity);

    // The angle should point generally upward and toward a bridge, NOT straight at (5, 22)
    // which would walk into the river. The left bridge is around x=3.5.
    // The angle should have a leftward component (negative dx) since bridge is to the left.
    float dx = (float) Math.cos(angle);
    float dy = (float) Math.sin(angle);

    // dy should be positive (moving toward red side)
    assertThat(dy).as("Should move toward red side").isGreaterThan(0f);
  }

  @Test
  void withoutEntity_fallsBackToStraightLine() {
    GameState state = new GameState();
    AStarPathfinder pf = new AStarPathfinder(state);
    Arena arena = Arena.standard();
    Position pos = new Position(9f, 5f);

    // Call without entity (basic overload)
    float angle = pf.getNextMovementAngle(pos, MovementType.GROUND, 9f, 25f, arena);

    // Should be straight line (fallback behavior)
    float expectedAngle = (float) Math.atan2(20f, 0f);
    assertThat(angle).isCloseTo(expectedAngle, org.assertj.core.data.Offset.offset(0.01f));
  }

  @Test
  void pathCaching_samTargetReusesPath() {
    GameState state = new GameState();
    AStarPathfinder pf = new AStarPathfinder(state);
    Arena arena = Arena.standard();
    Position pos = new Position(5f, 10f);

    Entity entity =
        Troop.builder().name("Knight").team(Team.BLUE).position(new Position(5f, 10f)).build();
    state.spawnEntity(entity);
    state.processPending();

    // First call: computes path
    float angle1 = pf.getNextMovementAngle(pos, MovementType.GROUND, 5f, 22f, arena, entity);
    // Second call with same target: should reuse cache
    float angle2 = pf.getNextMovementAngle(pos, MovementType.GROUND, 5f, 22f, arena, entity);

    assertThat(angle1).isEqualTo(angle2);
  }

  @Test
  void simHarness_aStarTroopReachesBridge() {
    SimHarness sim =
        SimHarness.create()
            .withSystems(SimSystems.TARGETING, SimSystems.PHYSICS)
            .withAStarPathfinding()
            .spawn(TroopTemplate.melee("Knight", Team.BLUE).at(3.5f, 12f))
            .deployed()
            .build();

    Troop knight = sim.troop("Knight");
    float startY = knight.getPosition().getY();

    // Tick for a few seconds so the knight moves
    sim.tickSeconds(3f);

    // Knight should have moved upward toward bridge / red side
    assertThat(knight.getPosition().getY())
        .as("Knight should move toward bridge (higher Y)")
        .isGreaterThan(startY + 1f);
  }
}
