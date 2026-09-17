package org.crforge.core.pathfinding.grid;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.Test;

/** Start validation, goal adjustment and cell-to-node conversion before a route search. */
class RouteSearchWrapperTest {

  private static final int WIDTH = 6;
  private static final int HEIGHT = 6;

  /** A six by six field where the bottom-right two by two block is rejected. */
  private static final CellCostLookup FIELD =
      (col, row) -> {
        if (col < 0 || row < 0 || col >= WIDTH || row >= HEIGHT) {
          return -1;
        }
        return col >= 4 && row >= 4 ? -1 : 7;
      };

  @Test
  void convertsBothCellsToNodesWhenTheGoalIsUsable() {
    RouteSearchWrapper.Nodes nodes =
        RouteSearchWrapper.searchWrapper(WIDTH, HEIGHT, FIELD, 0, 0, 3, 3, 1);

    assertThat(nodes).isNotNull();
    assertThat(nodes.startNode()).isZero();
    assertThat(nodes.goalNode()).isEqualTo(3 * WIDTH + 3);
  }

  @Test
  void movesARejectedGoalToTheNearestUsableCellInTheWindow() {
    RouteSearchWrapper.Nodes nodes =
        RouteSearchWrapper.searchWrapper(WIDTH, HEIGHT, FIELD, 0, 0, 5, 5, 1);

    assertThat(nodes).isNotNull();
    assertThat(nodes.startNode()).isZero();
    assertThat(nodes.goalNode()).isEqualTo(23);
    assertThat(nodes.goalNode() % WIDTH).isEqualTo(5);
    assertThat(nodes.goalNode() / WIDTH).isEqualTo(3);
  }

  @Test
  void leavesARejectedGoalAloneWhenAdjustmentIsNotAsked() {
    assertThat(RouteSearchWrapper.searchWrapper(WIDTH, HEIGHT, FIELD, 0, 0, 5, 5, 0)).isNull();
  }

  @Test
  void givesUpWhenNoCellInTheWindowIsUsable() {
    CellCostLookup nothing = (col, row) -> -1;

    assertThat(RouteSearchWrapper.searchWrapper(WIDTH, HEIGHT, nothing, 0, 0, 5, 5, 1)).isNull();
  }

  @Test
  void givesUpOnAStartOutsideTheMap() {
    assertThat(RouteSearchWrapper.searchWrapper(WIDTH, HEIGHT, FIELD, -1, 0, 3, 3, 1)).isNull();
  }

  @Test
  void acceptsAnyStartCellInsideTheMapByDefault() {
    assertThat(RouteSearchWrapper.startIsValid(WIDTH, HEIGHT, 0, 0, 0, null, false)).isTrue();
    assertThat(RouteSearchWrapper.startIsValid(WIDTH, HEIGHT, 5, 5, 0, null, false)).isTrue();
    assertThat(RouteSearchWrapper.startIsValid(WIDTH, HEIGHT, 6, 0, 0, null, false)).isFalse();
    assertThat(RouteSearchWrapper.startIsValid(WIDTH, HEIGHT, 0, -1, 0, null, false)).isFalse();
  }

  @Test
  void aSetFlagMakesTheStartCellsWaterBitRejectIt() {
    assertThat(RouteSearchWrapper.startIsValid(WIDTH, HEIGHT, 1, 1, 1, (col, row) -> 1, false))
        .isFalse();
    assertThat(RouteSearchWrapper.startIsValid(WIDTH, HEIGHT, 1, 1, 1, (col, row) -> 0, false))
        .isTrue();
  }

  @Test
  void theShortcutAcceptsTheStartBeforeTheWaterBitIsRead() {
    assertThat(RouteSearchWrapper.startIsValid(WIDTH, HEIGHT, 1, 1, 1, (col, row) -> 1, true))
        .isTrue();
  }

  @Test
  void convertsTheStandardArenasLeftLaneQuery() {
    CellGrid grid = new CellGrid(TileMap.standard1v1(), true, 100);
    FootprintOverlay.buildOverlay(grid, StandardTowers.entities());
    CellCostLookup lookup =
        CellCostField.costLookup(
            grid, CellCosts.standard(), GridEntityState.MOVING, 1, false, false);

    RouteSearchWrapper.Nodes nodes =
        RouteSearchWrapper.searchWrapper(36, 64, lookup, 7, 20, 6, 48, 1);

    assertThat(nodes).isNotNull();
    assertThat(nodes.startNode()).isEqualTo(727);
    assertThat(nodes.goalNode()).isEqualTo(1734);
  }
}
