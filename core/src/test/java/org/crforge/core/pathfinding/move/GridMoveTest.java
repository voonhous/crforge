package org.crforge.core.pathfinding.move;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.grid.CellGrid;
import org.crforge.core.pathfinding.grid.PathfindingGlobals;
import org.crforge.core.pathfinding.grid.TileMap;
import org.junit.jupiter.api.Test;

/** Behaviour of the per-axis cell-edge clamp that every displacement passes its step through. */
class GridMoveTest {

  private static final CellGrid GRID =
      new CellGrid(TileMap.standard1v1(), true, PathfindingGlobals.PATHFINDING_BUILDING_COST);

  private static final GridMoveEntity WALKING =
      new GridMoveEntity(GridMoveEntity.CHARACTER_TYPE, GridEntityState.MOVING, false, false);

  private static final GridMoveEntity PLACING =
      new GridMoveEntity(GridMoveEntity.CHARACTER_TYPE, GridEntityState.DEPLOYING, false, false);

  private static int[] move(int x, int y, int dx, int dy, GridMoveEntity entity) {
    int[] position = {x, y};
    GridMove.gridMove(GRID, position, dx, dy, entity, 0);
    return position;
  }

  @Test
  void anOpenStepCrossesTheCellEdgeUntouched() {
    assertThat(move(3499, 10000, 60, 0, WALKING)).containsExactly(3559, 10000);
    assertThat(move(3499, 10000, 0, 60, WALKING)).containsExactly(3499, 10060);
    assertThat(move(3499, 10000, 0, -60, WALKING)).containsExactly(3499, 9940);
    assertThat(move(3499, 10000, 400, 0, WALKING)).containsExactly(3899, 10000);
  }

  @Test
  void aUnitBeingPlacedIsClampedAtTheEdgeOfTheWater() {
    assertThat(move(1750, 15499, 0, 60, PLACING)).containsExactly(1750, 15499);
  }

  @Test
  void anOrdinaryUnitWalksIntoTheWater() {
    assertThat(move(1750, 15499, 0, 60, WALKING)).containsExactly(1750, 15559);
  }

  @Test
  void aStepWithoutAnEntityIsNeverBlockedByWater() {
    assertThat(move(1750, 15499, 0, 60, null)).containsExactly(1750, 15559);
  }
}
