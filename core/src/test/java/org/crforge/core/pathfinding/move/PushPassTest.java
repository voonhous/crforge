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
}
