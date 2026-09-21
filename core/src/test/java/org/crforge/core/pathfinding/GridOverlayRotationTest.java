package org.crforge.core.pathfinding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.crforge.core.card.Card;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.match.PathfindingMode;
import org.crforge.core.match.Standard1v1Match;
import org.crforge.core.pathfinding.grid.CellGrid;
import org.crforge.core.pathfinding.grid.PathfindingGlobals;
import org.crforge.core.pathfinding.grid.Route;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.player.Deck;
import org.crforge.core.player.LevelConfig;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The end-of-tick rotation of the cost overlay, driven through {@link GameEngine} in grid mode.
 *
 * <p>The overlay is built once per tick into the grid's current array and rotated into its previous
 * array at the end of the same tick, which hands the next build a zeroed array to stamp into. Two
 * things depend on that rotation and neither is visible in the overlay build itself:
 *
 * <ul>
 *   <li>a cell stamped by a building that is gone is cleared, because the array the next build
 *       stamps into is a fresh one rather than the one that already holds the stamp;
 *   <li>route retention can tell a cell that has just become occupied from one that has just become
 *       free, because it compares the two arrays. Without the rotation the previous array stays the
 *       all-zero one the grid was created with, so every comparison is made against nothing.
 * </ul>
 *
 * <p>Both tests here drive a real match rather than the overlay builder, so they hold the tick
 * driver's contract and not just the builder's.
 */
class GridOverlayRotationTest {

  /** Level both the towers and the deployed Knight are scaled to. */
  private static final int LEVEL = 11;

  /**
   * Cells the six crown towers stamp between them on the standard arena. The same count is pinned
   * cell by cell in the overlay builder's own tests.
   */
  private static final int TOWER_CELLS = 136;

  /** The cards every deck in this class is built from. */
  private static final List<String> DECK =
      List.of("knight", "giant", "musketeer", "archer", "goblins", "valkyrie", "bomber", "minions");

  /** Where the Knight is put down, which is the left lane's standard deploy spot. */
  private static final int KNIGHT_X = 3500;

  private static final int KNIGHT_Y = 10_000;

  /** Ticks the Knight is given to leave the deploy state and settle on a route. */
  private static final int TICKS_BEFORE_BLOCKING = 40;

  /** How many waypoints ahead of the Knight the blocking building is put down. */
  private static final int WAYPOINTS_AHEAD = 9;

  /** Collision radius of the blocking building, in game units: one routing cell. */
  private static final int BLOCKER_RADIUS = 500;

  @Test
  @DisplayName(
      "the overlay built this tick becomes the previous one and the next build starts clean")
  void theOverlayRotatesAtTheEndOfEveryTick() {
    GameEngine engine = gridMatch();
    CellGrid grid = engine.getGridPathfindingSystem().getGrid();

    engine.tick();
    assertOverlayRotated(grid, "the first tick");

    engine.tick();
    assertOverlayRotated(grid, "the second tick");
  }

  @Test
  @DisplayName("a route steps around a building put on it and goes straight again once it is gone")
  void aRouteReactsToAnOccluderAppearingAndGoingAway() {
    GameEngine engine = gridMatch();
    CellGrid grid = engine.getGridPathfindingSystem().getGrid();
    Card knight = Objects.requireNonNull(CardRegistry.get("knight"), "knight not found");
    engine
        .getSpawnerSystem()
        .spawnUnit(
            KNIGHT_X,
            KNIGHT_Y,
            Team.BLUE,
            knight.getUnitStats(),
            LEVEL,
            knight.getUnitStats().getDeployTime());

    engine.tick(TICKS_BEFORE_BLOCKING);
    Troop troop = findKnight(engine);
    assertThat(troop).as("the Knight was spawned").isNotNull();
    List<Integer> before = routeNodes(troop);
    assertThat(before).as("the Knight walks a route").hasSizeGreaterThan(WAYPOINTS_AHEAD);

    // A route is goal-first, so the waypoints the unit has yet to reach are at the end of the list.
    int blocked = before.get(before.size() - WAYPOINTS_AHEAD);
    engine.getGameState().spawnEntity(blocker(grid, blocked));

    engine.tick();
    assertThat(grid.getChangeFlags()[0])
        .as("the blue side's overlay changed on the tick the building appeared")
        .isEqualTo(1);
    assertThat(grid.getChangeFlags()[1]).as("the red side stamped nothing new").isZero();
    assertThat(routeNodes(troop))
        .as("the route steps around the blocked cell")
        .doesNotContain(blocked)
        .isNotEqualTo(before);

    // The detour holds for as long as the building stands.
    engine.tick(5);
    assertThat(routeNodes(troop)).as("the detour holds").doesNotContain(blocked);

    findBlocker(engine).getHealth().kill();

    engine.tick();
    assertThat(routeNodes(troop))
        .as("the route goes back through the cell the building had occupied")
        .contains(blocked);
  }

  /**
   * Holds the rotation after one tick: everything the build stamped sits in the previous overlay at
   * the building cost, and the current overlay is the fresh array the next build will stamp into.
   */
  private static void assertOverlayRotated(CellGrid grid, String where) {
    int stamped = 0;
    for (int value : grid.getPrevious()) {
      if (value != 0) {
        stamped++;
        assertThat(value)
            .as("%s: a stamped cell carries the building cost", where)
            .isEqualTo(PathfindingGlobals.PATHFINDING_BUILDING_COST);
      }
    }
    assertThat(stamped)
        .as("%s: the six crown towers are in the previous overlay", where)
        .isEqualTo(TOWER_CELLS);
    assertThat(grid.getCurrent())
        .as("%s: the overlay the next build stamps into is clean", where)
        .containsOnly(0);
  }

  /** A blue building of one cell's radius, centred on the given routing cell. */
  private static Building blocker(CellGrid grid, int node) {
    int column = node % grid.getWidth();
    int row = node / grid.getWidth();
    int centreX = column * TileMap.CELL_UNITS + TileMap.CELL_UNITS / 2;
    int centreY = row * TileMap.CELL_UNITS + TileMap.CELL_UNITS / 2;
    return Building.builder()
        .name("Blocker")
        .team(Team.BLUE)
        .position(new Position(centreX, centreY))
        .health(new Health(5000))
        .movement(new Movement(0, 0, BLOCKER_RADIUS, BLOCKER_RADIUS, MovementType.BUILDING))
        .lifetime(60f)
        .remainingLifetime(60f)
        .deployTime(0f)
        .build();
  }

  /** The route the Knight holds, goal first, as plain cell ids. */
  private static List<Integer> routeNodes(Troop troop) {
    Route route = troop.getGridUnitState().movement().getRoute();
    List<Integer> nodes = new ArrayList<>(route.size());
    for (int index = 0; index < route.size(); index++) {
      nodes.add(route.get(index));
    }
    return nodes;
  }

  /** A grid-mode standard match with both players and the six crown towers, already initialised. */
  private static GameEngine gridMatch() {
    AbstractEntity.resetIdCounter();
    List<Card> deckCards = new ArrayList<>();
    for (String id : DECK) {
      deckCards.add(Objects.requireNonNull(CardRegistry.get(id), id + " not found"));
    }
    Standard1v1Match match = new Standard1v1Match(LEVEL, PathfindingMode.GRID);
    match.addPlayer(new Player(Team.BLUE, new Deck(deckCards), false, new LevelConfig(LEVEL)));
    match.addPlayer(
        new Player(Team.RED, new Deck(new ArrayList<>(deckCards)), false, new LevelConfig(LEVEL)));
    GameEngine engine = new GameEngine();
    engine.setMatch(match);
    engine.initMatch();
    assertThat(engine.getGridPathfindingSystem()).as("the match runs in grid mode").isNotNull();
    return engine;
  }

  private static Troop findKnight(GameEngine engine) {
    for (Entity entity : engine.getGameState().getAliveEntities()) {
      if (entity instanceof Troop troop && "Knight".equals(troop.getName())) {
        return troop;
      }
    }
    return null;
  }

  private static Building findBlocker(GameEngine engine) {
    for (Entity entity : engine.getGameState().getAliveEntities()) {
      if (entity instanceof Building building && "Blocker".equals(building.getName())) {
        return building;
      }
    }
    throw new IllegalStateException("the blocking building is gone");
  }
}
