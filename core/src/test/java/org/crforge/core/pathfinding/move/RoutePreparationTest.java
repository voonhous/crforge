package org.crforge.core.pathfinding.move;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.grid.CellGrid;
import org.crforge.core.pathfinding.grid.PathfindingGlobals;
import org.crforge.core.pathfinding.grid.Route;
import org.crforge.core.pathfinding.grid.TileMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Behaviour of the destination choice, the cached-route gate and the replan. */
class RoutePreparationTest {

  private static final int WIDTH = 36;

  /** The goal cell of the left lane, column 6 row 48, packed the way the endpoint scan answers. */
  private static final int LEFT_GOAL_PACKED = (6 << 16) | 48;

  /** That same cell as a route node. */
  private static final int LEFT_GOAL_NODE = 48 * WIDTH + 6;

  private MovementState component;
  private GridEntity owner;
  private RecordingQueries queries;
  private CellGrid grid;
  private MovementChain chain;

  /** A stub that remembers what route preparation asked the search for. */
  private static final class RecordingQueries extends StubMovementQueries {
    private final List<int[]> searches = new ArrayList<>();

    @Override
    public Route search(int startCol, int startRow, int goalCol, int goalRow, int adjust) {
      searches.add(new int[] {startCol, startRow, goalCol, goalRow, adjust});
      return searchResult;
    }
  }

  @BeforeEach
  void setUp() {
    component = MovementState.forSide(0, 3500, 10000);
    owner = new GridEntity();
    owner.setX(3500);
    owner.setY(10000);
    owner.setState(GridEntityState.MOVING);
    queries = new RecordingQueries();
    queries.endpoint = LEFT_GOAL_PACKED;
    grid = new CellGrid(TileMap.standard1v1(), true, PathfindingGlobals.PATHFINDING_BUILDING_COST);
    chain =
        new MovementChain(
            component,
            owner,
            grid,
            MovementConfig.forGroundUnit(),
            MovementGlobals.forStandardArena(WIDTH),
            new ReferencePoint(3500, 25500),
            List.of(),
            queries);
  }

  private void prepare() {
    chain.prepareRoute();
  }

  @Test
  void aUnitWithoutARouteSearchesFromItsOwnCellToTheEndpoint() {
    queries.searchResult = Route.of(LEFT_GOAL_NODE, 47 * WIDTH + 7, 46 * WIDTH + 7);

    prepare();

    assertThat(queries.searches).hasSize(1);
    assertThat(queries.searches.get(0)).containsExactly(7, 20, 6, 48, 1);
    assertThat(component.getRoute().toArray())
        .containsExactly(LEFT_GOAL_NODE, 47 * WIDTH + 7, 46 * WIDTH + 7);
    assertThat(chain.markers())
        .containsExactly(
            "endpoint",
            "owner_side",
            "ground",
            "search",
            "search_notify",
            "search_stats",
            "farther",
            "direction_init");
  }

  @Test
  void adjacentDuplicatesAndALeadingPlaceholderAreDropped() {
    queries.searchResult =
        Route.of(-1, LEFT_GOAL_NODE, LEFT_GOAL_NODE, 47 * WIDTH + 7, 47 * WIDTH + 7);

    prepare();

    assertThat(component.getRoute().toArray()).containsExactly(LEFT_GOAL_NODE, 47 * WIDTH + 7);
  }

  @Test
  void aRouteWhoseGoalStillMatchesIsReusedWithoutSearching() {
    component.setRoute(Route.of(LEFT_GOAL_NODE, 47 * WIDTH + 7));

    prepare();

    assertThat(queries.searches).isEmpty();
    assertThat(component.getRoute().toArray()).containsExactly(LEFT_GOAL_NODE, 47 * WIDTH + 7);
    assertThat(chain.markers()).containsExactly("endpoint", "owner_side", "ground");
  }

  @Test
  void aChangedGoalReplans() {
    component.setRoute(Route.of(LEFT_GOAL_NODE + 1, 47 * WIDTH + 7));
    queries.searchResult = Route.of(LEFT_GOAL_NODE, 47 * WIDTH + 7);

    prepare();

    assertThat(queries.searches).hasSize(1);
    assertThat(component.getRoute().toArray()).containsExactly(LEFT_GOAL_NODE, 47 * WIDTH + 7);
  }

  @Test
  void aChangedOverlayReplansButKeepsTheOldRouteWhenNeitherIsAffected() {
    component.setRoute(Route.of(LEFT_GOAL_NODE, 47 * WIDTH + 7));
    grid.getChangeFlags()[0] = 1;
    queries.searchResult = Route.of(LEFT_GOAL_NODE, 46 * WIDTH + 7);

    prepare();

    assertThat(queries.searches).hasSize(1);
    assertThat(component.getRoute().toArray()).containsExactly(LEFT_GOAL_NODE, 47 * WIDTH + 7);
  }

  @Test
  void anOldRouteCrossingANewlyOccupiedCellIsReplaced() {
    component.setRoute(Route.of(LEFT_GOAL_NODE, 47 * WIDTH + 7));
    grid.getChangeFlags()[0] = 1;
    grid.getCurrent()[47 * WIDTH + 7] = PathfindingGlobals.PATHFINDING_BUILDING_COST;
    queries.searchResult = Route.of(LEFT_GOAL_NODE, 46 * WIDTH + 7);

    prepare();

    assertThat(component.getRoute().toArray()).containsExactly(LEFT_GOAL_NODE, 46 * WIDTH + 7);
  }

  @Test
  void aFlyingUnitGetsASingleNodeRouteWithoutSearching() {
    queries.ground = 0;

    prepare();

    assertThat(queries.searches).isEmpty();
    assertThat(component.getRoute().toArray()).containsExactly(LEFT_GOAL_NODE);
    assertThat(chain.markers())
        .containsExactly("endpoint", "owner_side", "ground", "direction_init");
  }

  @Test
  void anExplicitDestinationSkipsTheEndpointScan() {
    component.setExplicitX(3500);
    component.setExplicitY(25500);
    queries.searchResult = Route.of(LEFT_GOAL_NODE);

    prepare();

    assertThat(queries.searches.get(0)).containsExactly(7, 20, 7, 51, 1);
    assertThat(chain.markers()).doesNotContain("endpoint");
  }

  @Test
  void anEndpointScanThatFoundNothingFallsBackToTheReferencePosition() {
    queries.endpoint = -1;
    queries.searchResult = Route.of(LEFT_GOAL_NODE);

    prepare();

    assertThat(queries.searches).hasSize(1);
    assertThat(queries.searches.get(0)).containsExactly(7, 20, 3500, 25500, 1);
  }

  @Test
  void aUnitWithNoReferenceAndNoForcedDestinationKeepsWhatItHas() {
    MovementChain noReference =
        new MovementChain(
            component,
            owner,
            grid,
            MovementConfig.forGroundUnit(),
            MovementGlobals.forStandardArena(WIDTH),
            null,
            List.of(),
            queries);
    component.setRoute(Route.of(LEFT_GOAL_NODE, 47 * WIDTH + 7));

    noReference.prepareRoute();

    assertThat(queries.searches).isEmpty();
    assertThat(component.getRoute().toArray()).containsExactly(LEFT_GOAL_NODE, 47 * WIDTH + 7);
    assertThat(noReference.markers()).containsExactly("game_mode_goal");
  }

  @Test
  void aForcedDestinationIsRoutedToLikeAnyOther() {
    MovementChain noReference =
        new MovementChain(
            component,
            owner,
            grid,
            MovementConfig.forGroundUnit(),
            MovementGlobals.forStandardArena(WIDTH),
            null,
            List.of(),
            queries);
    queries.gameModeGoal = LEFT_GOAL_PACKED;
    queries.searchResult = Route.of(LEFT_GOAL_NODE);

    noReference.prepareRoute();

    assertThat(queries.searches.get(0)).containsExactly(7, 20, 6, 48, 1);
    assertThat(noReference.markers()).doesNotContain("farther");
  }

  @Test
  void aRouteThatLeadsPastTheReferenceSetsTheRouteLeadsAwayBit() {
    queries.searchResult = Route.of(LEFT_GOAL_NODE, 47 * WIDTH + 7);
    queries.farther = 1;

    prepare();

    assertThat(component.getRouteLeadsAway()).isEqualTo(1);
  }

  @Test
  void theCellUnitIsWhatTurnsAWorldPositionIntoACell() {
    assertThat(TileMap.CELL_UNITS).isEqualTo(500);
  }
}
