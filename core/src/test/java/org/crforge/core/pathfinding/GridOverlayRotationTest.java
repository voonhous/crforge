package org.crforge.core.pathfinding;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.unit.CharacterEntity;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.core.battle.unit.WorldObserver;
import org.crforge.core.pathfinding.grid.CellGrid;
import org.crforge.core.pathfinding.grid.FootprintOverlay;
import org.crforge.core.pathfinding.grid.Route;
import org.crforge.core.pathfinding.grid.TileMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The end-of-tick rotation of the cost overlay, driven through the battle's own step.
 *
 * <p>The overlay is built once per tick into the grid's current array, at the head of the tick, and
 * rotated into its previous array by the holder's post-pass at the end of the same tick, which
 * hands the next build a zeroed array to stamp into. Two things depend on that rotation and neither
 * is visible in the overlay build itself:
 *
 * <ul>
 *   <li>a cell stamped by a building that is gone is cleared, because the array the next build
 *       stamps into is a fresh one rather than the one that already holds the stamp;
 *   <li>route retention can tell a cell that has just become occupied from one that has just become
 *       free, because it compares the two arrays. Without the rotation the previous array stays the
 *       all-zero one the grid was created with, so every comparison is made against nothing.
 * </ul>
 *
 * <p>Both tests here step a real battle rather than call the overlay builder, so they hold the tick
 * driver's contract and not just the builder's. The scene writes the columns its walk and its
 * stamps are read from: the towers' places and footprints, the Knight's walk and the blocking
 * Cannon's radius and hit points.
 */
class GridOverlayRotationTest {

  /** The level the towers and the units stand at. */
  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** Where the Knight is put down, on the bottom side's left lane. */
  private static final int KNIGHT_X = 3500;

  private static final int KNIGHT_Y = 10_000;

  /** Ticks the Knight is given to finish deploying and settle on a route. */
  private static final int TICKS_BEFORE_BLOCKING = 40;

  /** How many waypoints ahead of the Knight the blocking building is put down. */
  private static final int WAYPOINTS_AHEAD = 9;

  /** The blocking building's radius, in game units: one routing cell. */
  private static final int BLOCKER_RADIUS = 500;

  @TempDir static Path tablesFolder;

  /** The configured tables with the scene's columns written. */
  private static GameTables tables;

  @BeforeAll
  static void writeTheRows() throws IOException {
    GameData.altered(
        tablesFolder,
        "characters",
        rows ->
            GameData.columns(rows, "Knight")
                .put("Hitpoints", 690)
                .put("Damage", 79)
                .put("HitSpeed", 1200)
                .put("Speed", 60)
                .put("Mass", 6)
                .put("CollisionRadius", 500)
                .put("Range", 1200)
                .put("SightRange", 5500)
                .put("DeployTime", 1000));
    GameData.alterLoaded(
        tablesFolder,
        "buildings",
        rows ->
            GameData.columns(rows, "Cannon")
                .put("CollisionRadius", BLOCKER_RADIUS)
                .put("Hitpoints", 5000)
                .put("LifeTime", 60_000)
                .put("DeployTime", 0));
    GameData.writeTowers(tablesFolder);
    tables = GameTables.load(tablesFolder);
  }

  @Test
  @DisplayName(
      "the overlay built this tick becomes the previous one and the next build starts clean")
  void theOverlayRotatesAtTheEndOfEveryTick() {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    CellGrid grid = match.getWorld().getGrid();
    List<int[]> built = new ArrayList<>();
    List<int[]> copies = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void afterPrePass(int tick, List<WorldEntity> present) {
                // The overlay this tick's build stamped, as the tick's passes will read it.
                built.add(grid.getCurrent());
                copies.add(grid.getCurrent().clone());
              }
            });

    match.getBattle().step();
    assertOverlayRotated(grid, built.get(0), copies.get(0), "the first tick");

    match.getBattle().step();
    assertOverlayRotated(grid, built.get(1), copies.get(1), "the second tick");
    assertThat(built.get(1))
        .as("each tick builds into the other of the two overlays")
        .isNotSameAs(built.get(0));

    match.getBattle().step();
    assertOverlayRotated(grid, built.get(2), copies.get(2), "the third tick");
    assertThat(built.get(2))
        .as("the overlays rotate: the third tick builds into the first tick's, cleared")
        .isSameAs(built.get(0));
  }

  @Test
  @DisplayName("a route steps around a building put on it and goes straight again once it is gone")
  void aRouteReactsToAnOccluderAppearingAndGoingAway() {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    CellGrid grid = match.getWorld().getGrid();
    CharacterEntity knight =
        match.deploy(0, match.getWorld().getRecords().unit("Knight"), LEVEL, 0, KNIGHT_X, KNIGHT_Y);

    stepTo(match, TICKS_BEFORE_BLOCKING);
    List<Integer> before = routeNodes(knight);
    assertThat(before).as("the Knight walks a route").hasSizeGreaterThan(WAYPOINTS_AHEAD);

    // A route is goal-first, so the waypoints the unit has yet to reach are at the end of the list.
    int blocked = before.get(before.size() - WAYPOINTS_AHEAD);
    int column = blocked % grid.getWidth();
    int row = blocked / grid.getWidth();
    CharacterEntity blocker =
        match.deploy(
            // Due on the next step: the battle runs a command at the head of the step its tick
            // names.
            TICKS_BEFORE_BLOCKING,
            match.getWorld().getRecords().unit("Cannon"),
            LEVEL,
            0,
            column * TileMap.CELL_UNITS + TileMap.CELL_UNITS / 2,
            row * TileMap.CELL_UNITS + TileMap.CELL_UNITS / 2);

    match.getBattle().step();
    assertThat(grid.getChangeFlags()[0])
        .as("the bottom side's overlay changed on the tick the building appeared")
        .isEqualTo(1);
    assertThat(grid.getChangeFlags()[1]).as("the top side stamped nothing new").isZero();
    assertThat(routeNodes(knight))
        .as("the route steps around the blocked cell")
        .doesNotContain(blocked)
        .isNotEqualTo(before);

    // The detour holds for as long as the building stands.
    stepTo(match, match.getBattle().getTick() + 5);
    assertThat(routeNodes(knight)).as("the detour holds").doesNotContain(blocked);

    blocker.getHitPoints().setHitPoints(0);
    stepTo(match, match.getBattle().getTick() + 1);
    assertThat(match.getBattle().getHolder().entities())
        .as("the building is gone")
        .doesNotContain(blocker);
    assertThat(routeNodes(knight))
        .as("the route goes back through the cell the building had occupied")
        .contains(blocked);
  }

  /**
   * Holds the rotation after one tick: the overlay the tick built is now the previous one,
   * unchanged, every stamped cell carrying the building cost and every cell of the tick's
   * footprints stamped; the current overlay is the other array, cleared, which the next build will
   * stamp into.
   */
  private static void assertOverlayRotated(CellGrid grid, int[] built, int[] copy, String where) {
    assertThat(grid.getPrevious())
        .as("%s: the overlay built this tick is the previous one", where)
        .isSameAs(built)
        .isEqualTo(copy);
    int stamped = 0;
    for (int value : grid.getPrevious()) {
      if (value != 0) {
        stamped++;
        assertThat(value)
            .as("%s: a stamped cell carries the building cost", where)
            .isEqualTo(grid.getBuildingCost());
      }
    }
    int footprintCells = 0;
    for (int packed : grid.getFootprints()) {
      footprintCells +=
          (FootprintOverlay.footprintLastCol(packed)
                  - FootprintOverlay.footprintFirstCol(packed)
                  + 1)
              * (FootprintOverlay.footprintLastRow(packed)
                  - FootprintOverlay.footprintFirstRow(packed)
                  + 1);
    }
    assertThat(grid.getFootprints()).as("%s: the six crown towers stamped", where).hasSize(6);
    assertThat(stamped)
        .as("%s: the towers' footprints are in the previous overlay", where)
        .isEqualTo(footprintCells);
    assertThat(grid.getCurrent())
        .as("%s: the overlay the next build stamps into is clean", where)
        .isNotSameAs(built)
        .containsOnly(0);
  }

  /** Steps the battle until its tick is the given one. */
  private static void stepTo(Standard1v1Battle match, int tick) {
    while (match.getBattle().getTick() < tick) {
      match.getBattle().step();
    }
  }

  /** The route the unit holds, goal first, as plain cell ids. */
  private static List<Integer> routeNodes(CharacterEntity unit) {
    Route route = unit.getUnit().movement().getRoute();
    List<Integer> nodes = new ArrayList<>(route.size());
    for (int index = 0; index < route.size(); index++) {
      nodes.add(route.get(index));
    }
    return nodes;
  }
}
