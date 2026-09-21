package org.crforge.core.pathfinding.grid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.Test;

/** The route search itself: its heuristics, its counters and its exact tie-breaking. */
class RouteSearchTest {

  /**
   * A four by six cost field on which the heap's tie-breaking is observable. Several open nodes
   * share a priority, so which of them is expanded next decides the route.
   */
  private static final int[] TIE_FIELD = {
    5, 5, 5, 100,
    7, 5, 100, 5,
    7, 7, 5, 5,
    100, 7, 7, 5,
    100, 5, 5, 7,
    7, 5, 7, 5
  };

  private static RouteSearchResult tieSearch(boolean refresh, boolean reopen, int budget) {
    return RouteSearch.search(4, 6, TIE_FIELD, 0, 23, 1, 5, false, refresh, reopen, budget);
  }

  @Test
  void combinesTheAxesAccordingToTheHeuristicMethod() {
    assertThat(RouteSearch.heuristic(3, 4, 1, false)).isEqualTo(52);
    assertThat(RouteSearch.heuristic(3, 4, 2, false)).isEqualTo(52);
    assertThat(RouteSearch.heuristic(3, 4, 0, false)).isEqualTo(40);
    assertThat(RouteSearch.heuristic(3, 4, 3, false)).isEqualTo(50);
  }

  @Test
  void theAccumulatedModeAlwaysUsesTheLongerAxisAlone() {
    assertThat(RouteSearch.heuristic(3, 4, 1, true)).isEqualTo(40);
    assertThat(RouteSearch.heuristic(3, 4, 3, true)).isEqualTo(40);
  }

  @Test
  void treatsTheAxesAsDistancesRegardlessOfSign() {
    assertThat(RouteSearch.heuristic(-3, -4, 1, false)).isEqualTo(52);
    assertThat(RouteSearch.heuristic(-3, -4, 3, false)).isEqualTo(50);
  }

  @Test
  void rejectsAHeuristicMethodItDoesNotKnow() {
    assertThatThrownBy(() -> RouteSearch.heuristic(1, 1, 4, false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * The route below is only produced by a heap whose sift-down examines the right child first. A
   * conventional heap, java.util.PriorityQueue among them, prefers the left child on a tie and
   * walks this same field through (3,4) and (3,3) instead.
   */
  @Test
  void breaksPriorityTiesTheSameWayTheStandardGameDoes() {
    RouteSearchResult result = tieSearch(true, false, 0);

    assertThat(result.route().toArray()).containsExactly(23, 18, 14, 10, 5);
    assertThat(cells(result, 4)).containsExactly("(3,5)", "(2,4)", "(2,3)", "(2,2)", "(1,1)");
    assertThat(result.counters()).containsExactly(20, 3, 0, 13);
  }

  @Test
  void leavingOpenNodesAloneChangesTheRouteAndDropsTheRefreshCount() {
    RouteSearchResult result = tieSearch(false, false, 0);

    assertThat(result.route().toArray()).containsExactly(23, 19, 15, 10, 5);
    assertThat(result.counters()).containsExactly(19, 0, 0, 12);
  }

  @Test
  void reopeningClosedNodesChangesNothingWhenNoneWouldBeReopened() {
    RouteSearchResult result = tieSearch(true, true, 0);

    assertThat(result.route().toArray()).containsExactly(23, 18, 14, 10, 5);
    assertThat(result.counters()).containsExactly(20, 3, 0, 13);
  }

  @Test
  void theAccumulatedModeCarriesTheWeightedCostIntoTheNextStep() {
    RouteSearchResult result =
        RouteSearch.search(4, 6, TIE_FIELD, 0, 23, 1, 5, true, true, false, 0);

    assertThat(result.route().toArray()).containsExactly(23, 18, 14, 10, 5);
    assertThat(result.counters()).containsExactly(23, 5, 0, 10);
  }

  @Test
  void theStraightLineHeuristicVisitsADifferentSetOfNodes() {
    RouteSearchResult result =
        RouteSearch.search(4, 6, TIE_FIELD, 0, 23, 3, 5, false, true, false, 0);

    assertThat(result.route().toArray()).containsExactly(23, 18, 14, 10, 5);
    assertThat(result.counters()).containsExactly(21, 6, 0, 13);
  }

  @Test
  void aPositiveBudgetStopsTheSearchAndLeavesNoRoute() {
    RouteSearchResult result = tieSearch(true, false, 3);

    assertThat(result.route().isEmpty()).isTrue();
    assertThat(result.counters()).containsExactly(15, 0, 0, 12);
    assertThat(result.remainingBudget()).isZero();
  }

  @Test
  void aBudgetOfZeroIsUnlimited() {
    assertThat(tieSearch(true, false, 0).remainingBudget()).isZero();
    assertThat(tieSearch(true, false, -5).remainingBudget()).isEqualTo(-5);
  }

  @Test
  void aStartWithNowhereToGoKeepsItselfAsTheRoute() {
    RouteSearchResult result =
        RouteSearch.search(1, 1, new int[] {7}, 0, 0, 1, 5, false, true, false, 0);

    assertThat(result.route().toArray()).containsExactly(0);
    assertThat(result.counters()).containsExactly(0, 0, 0, 0);
  }

  @Test
  void anUnreachableGoalLeavesAnEmptyRoute() {
    int[] split = {
      7, -1, 7,
      7, -1, 7,
      7, -1, 7
    };
    RouteSearchResult result = RouteSearch.search(3, 3, split, 0, 2, 1, 5, false, true, false, 0);

    assertThat(result.route().isEmpty()).isTrue();
    assertThat(result.counters()).containsExactly(2, 0, 0, 1);
  }

  @Test
  void walksTheLeftLaneToTheEnemyPrincessTowerOnTheStandardArena() {
    CellGrid grid = new CellGrid(TileMap.standard1v1(), true, 100);
    FootprintOverlay.buildOverlay(grid, StandardTowers.entities());
    int[] field =
        CellCostField.costField(
            grid, CellCosts.standard(), GridEntityState.MOVING, 1, false, false);

    RouteSearchResult result =
        RouteSearch.search(
            36,
            64,
            field,
            20 * 36 + 7,
            48 * 36 + 6,
            PathfindingGlobals.PATHFINDING_HEURISTIC_METHOD,
            PathfindingGlobals.PATHFINDING_DEFAULTHEURISTIC_COST,
            !PathfindingGlobals.NEW_PATHFINDING_CODE,
            PathfindingGlobals.PATHFINDING_REFRESH_OPENNODES,
            PathfindingGlobals.PATHFINDING_REOPEN_CLOSEDNODES,
            0);

    assertThat(result.route().size()).isEqualTo(28);
    assertThat(result.route().get(0)).isEqualTo(48 * 36 + 6);
    assertThat(result.route().last()).isEqualTo(21 * 36 + 7);
    assertThat(cells(result, 36))
        .startsWith("(6,48)", "(7,47)", "(7,46)")
        .endsWith("(7,22)", "(7,21)");
    // The bridge rows: the route crosses the river inside the left bridge's columns.
    for (int i = 0; i < result.route().size(); i++) {
      int node = result.route().get(i);
      int row = node / 36;
      if (row >= 30 && row <= 33) {
        assertThat(node % 36).isBetween(6, 7);
      }
    }
    assertThat(result.counters()).containsExactly(121, 0, 0, 66);
  }

  private static String[] cells(RouteSearchResult result, int width) {
    String[] cells = new String[result.route().size()];
    for (int i = 0; i < cells.length; i++) {
      int node = result.route().get(i);
      cells[i] = "(" + (node % width) + "," + (node / width) + ")";
    }
    return cells;
  }
}
