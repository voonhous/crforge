package org.crforge.core.pathfinding.move;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.grid.CellGrid;
import org.crforge.core.pathfinding.grid.PathfindingGlobals;
import org.crforge.core.pathfinding.grid.TileMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Behaviour of the pass that sums the pushes a unit takes from the units standing next to it. */
class PushPassTest {

  private static final CellGrid GRID =
      new CellGrid(TileMap.standard1v1(), true, PathfindingGlobals.PATHFINDING_BUILDING_COST);

  private MovementState component;
  private GridEntity owner;
  private StubMovementQueries queries;

  private static GridEntity neighbour(
      int id, int x, int y, int side, int mass, int radius, boolean moving) {
    GridEntity entity = new GridEntity();
    entity.setId(id);
    entity.setX(x);
    entity.setY(y);
    entity.setSide(side);
    entity.setMass(mass);
    entity.setCollisionRadius(radius);
    entity.setMovementComponent(moving);
    entity.setMovementActive(moving);
    return entity;
  }

  @BeforeEach
  void setUp() {
    component = MovementState.forSide(0, 3500, 10000);
    owner = neighbour(1, 3500, 10000, 0, 6, 500, true);
    queries = new StubMovementQueries();
  }

  private void run(List<GridEntity> others) {
    MovementChain chain =
        new MovementChain(
            component,
            owner,
            GRID,
            MovementConfig.forGroundUnit(),
            MovementGlobals.forStandardArena(36),
            null,
            others,
            queries);
    PushPass.pushPass(component, owner, others, queries, chain);
  }

  @Test
  void aUnitStandingAloneIsNotPushedAtAll() {
    run(List.of());

    assertThat(component.getPushX()).isZero();
    assertThat(component.getPushY()).isZero();
    assertThat(component.getPushCount()).isZero();
  }

  @Test
  void anAllyToTheRightPushesTheUnitLeft() {
    run(List.of(neighbour(2, 3700, 10000, 0, 6, 500, true)));

    assertThat(component.getPushX()).isEqualTo(-300);
    assertThat(component.getPushY()).isZero();
    assertThat(component.getPushCount()).isEqualTo(1);
  }

  @Test
  void twoNeighboursEachAddTheirOwnPush() {
    run(
        List.of(
            neighbour(2, 3700, 10000, 0, 6, 500, true),
            neighbour(3, 3400, 10300, 1, 6, 500, true)));

    assertThat(component.getPushX()).isEqualTo(-206);
    assertThat(component.getPushY()).isEqualTo(-284);
    assertThat(component.getPushCount()).isEqualTo(2);
  }

  @Test
  void aUnitSharingAPositionWithABuildingIsPushedBackTowardItsOwnSide() {
    run(List.of(neighbour(4, 3500, 10000, 0, 12, 1000, false)));

    assertThat(component.getPushX()).isZero();
    assertThat(component.getPushY()).isEqualTo(-300);
    assertThat(component.getPushCount()).isEqualTo(1);
  }

  @Test
  void theOppositeSideIsPushedTheOtherWayOutOfACoincidentPosition() {
    owner.setSide(1);
    queries.ownerSide = 1;

    run(List.of(neighbour(4, 3500, 10000, 0, 12, 1000, false)));

    assertThat(component.getPushY()).isEqualTo(300);
  }

  @Test
  void aUnitWithoutACollisionRadiusIsNeverPushed() {
    owner.setCollisionRadius(0);

    run(List.of(neighbour(2, 3700, 10000, 0, 6, 500, true)));

    assertThat(component.getPushCount()).isZero();
  }

  @Test
  void aNeighbourOutOfReachAddsNothing() {
    run(List.of(neighbour(2, 5000, 10000, 0, 6, 500, true)));

    assertThat(component.getPushCount()).isZero();
  }

  @Test
  void aNeighbourWithAnInactiveMovementComponentIsNotStatic() {
    // A unit waiting to deploy keeps its movement component, switched off. The push pass tests
    // whether the component exists, so the neighbour reaches with the unit's whole radius of 700,
    // not the 500 a static neighbour is clamped to: 500 + 700 covers the 1100 between them.
    owner.setCollisionRadius(700);
    GridEntity waiting = neighbour(2, 4600, 10000, 0, 6, 500, true);
    waiting.setMovementActive(false);

    run(List.of(waiting));

    assertThat(component.getPushCount()).isEqualTo(1);
    assertThat(component.getPushX()).isNegative();
  }

  @Test
  void twoAlignedStaticNeighboursCopyASingleAxisPushOntoTheOtherAxis() {
    // Two buildings of mass 0 on the unit's x each push it 1 unit away along the length; with a
    // second static entity on its x the aligned check answers 1 and the push is copied across.
    run(
        List.of(
            neighbour(2, 3500, 10900, 0, 0, 500, false),
            neighbour(3, 3500, 11000, 0, 0, 500, false)));

    assertThat(component.getPushY()).isEqualTo(-2);
    assertThat(component.getPushX()).isEqualTo(-2);
    assertThat(component.getPushCount()).isEqualTo(2);
  }

  @Test
  void oneStaticNeighbourLeavesASingleAxisPushAlone() {
    run(List.of(neighbour(2, 3500, 10900, 0, 0, 500, false)));

    assertThat(component.getPushY()).isEqualTo(-1);
    assertThat(component.getPushX()).isZero();
  }
}
