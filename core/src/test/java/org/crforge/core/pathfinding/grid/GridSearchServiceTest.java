package org.crforge.core.pathfinding.grid;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The whole route query: cost field, wrapper and search, with the standard game's settings. */
class GridSearchServiceTest {

  private CellGrid grid;

  @BeforeEach
  void setUp() {
    grid = new CellGrid(TileMap.standard1v1(), true, PathfindingGlobals.PATHFINDING_BUILDING_COST);
    FootprintOverlay.buildOverlay(grid, StandardTowers.entities());
  }

  private Route route(int startCol, int startRow, int goalCol, int goalRow) {
    return GridSearchService.route(
        grid,
        CellCosts.standard(),
        GridEntityState.MOVING,
        1,
        false,
        false,
        startCol,
        startRow,
        goalCol,
        goalRow,
        1);
  }

  @Test
  void routesALeftLaneUnitFromItsDeployCellToTheEnemyPrincessTower() {
    Route route = route(7, 20, 6, 48);

    assertThat(route.size()).isEqualTo(28);
    assertThat(route.get(0)).isEqualTo(48 * 36 + 6);
    assertThat(route.last()).isEqualTo(21 * 36 + 7);
  }

  @Test
  void crossesTheRiverInsideTheLeftBridgesColumns() {
    Route route = route(7, 20, 6, 48);

    for (int i = 0; i < route.size(); i++) {
      int row = route.get(i) / 36;
      if (row >= 30 && row <= 33) {
        assertThat(route.get(i) % 36).isBetween(6, 7);
      }
    }
  }

  @Test
  void answersAnEmptyRouteWhenTheStartIsOffTheMap() {
    assertThat(route(-1, 20, 6, 48).isEmpty()).isTrue();
    assertThat(route(36, 20, 6, 48).isEmpty()).isTrue();
  }

  @Test
  void movesAGoalOffTheMapOntoTheNearestUsableCell() {
    Route adjusted = route(7, 20, 6, 70);

    assertThat(adjusted.size()).isEqualTo(43);
    assertThat(adjusted.get(0)).isEqualTo(63 * 36 + 6);
  }

  @Test
  void aPathfindingUnitIgnoresTheRoadsAndTakesADifferentRoute() {
    Route moving =
        GridSearchService.route(
            grid, CellCosts.standard(), GridEntityState.MOVING, 1, false, false, 0, 10, 35, 50, 1);
    Route pathfinding =
        GridSearchService.route(
            grid,
            CellCosts.standard(),
            GridEntityState.SPAWN_PATHFIND,
            1,
            false,
            false,
            0,
            10,
            35,
            50,
            1);

    assertThat(moving.size()).isEqualTo(63);
    assertThat(pathfinding.size()).isEqualTo(47);
  }

  @Test
  void reportsTheSearchCountersAlongsideTheRoute() {
    RouteSearchResult result =
        GridSearchService.search(
            grid, CellCosts.standard(), GridEntityState.MOVING, 1, false, false, 7, 20, 6, 48, 1);

    assertThat(result.route().size()).isEqualTo(28);
    assertThat(result.counters()).containsExactly(121, 0, 0, 66);
    assertThat(result.remainingBudget()).isZero();
  }

  @Test
  void aRejectedQueryReportsZeroedCounters() {
    RouteSearchResult result =
        GridSearchService.search(
            grid, CellCosts.standard(), GridEntityState.MOVING, 1, false, false, -1, 20, 6, 48, 1);

    assertThat(result.route().isEmpty()).isTrue();
    assertThat(result.counters()).containsExactly(0, 0, 0, 0);
  }
}
