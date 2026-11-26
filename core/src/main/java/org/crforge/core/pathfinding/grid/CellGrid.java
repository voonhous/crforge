/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.pathfinding.grid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * Per-match routing state of one arena: the static cell map together with the dynamic cost overlay
 * that buildings stamp into.
 *
 * <p>This is a plain data holder. The overlay builder writes {@link #getCurrent()}, {@link
 * #getFootprints()}, {@link #getHash()}, {@link #getChanged()} and {@link #getActive()}; the cell
 * tests, route preparation and the grid move read them. No routing rule lives here.
 *
 * <p>Two overlays are kept. {@code current} is the one being built or just built for this tick and
 * {@code previous} is the one from the tick before; route preparation compares them to decide
 * whether a cached route is still good. {@link #swap()} performs the end-of-tick rotation.
 *
 * <p>{@code hash} and {@code changed} hold one entry per team (0 and 1, see {@link #team(int)}):
 * the hash summarises which occluding entities of that team were stamped, and the changed flag says
 * whether that hash differs from the previous build's. In a battle of two players a team is its one
 * side; in a team battle sides 0 and 2 share team 0's entry and sides 1 and 3 team 1's. {@code
 * changeFlags} is the copy of {@code changed} taken once the build is finished, which is what
 * readers during the tick see.
 *
 * <p>Overlay arrays are row-major with one entry per cell, so a cell's slot is {@code row * width +
 * column}, the same id space routes use.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the overlay's two buffers and their rotation, the per-team hash and change"
            + " flags; a team battle's side 3 unit replanning on the step after its side's"
            + " building fell is held by the recorded team battles. Not checked against a"
            + " recorded battle of two: a tick on which the set of occluders actually changes.")
public final class CellGrid {

  /** One entry per team of the match that carries a hash: teams 0 and 1. */
  private static final int SIDES = 2;

  /** Side of a neutral object, which forms a team of its own. */
  private static final int NEUTRAL_SIDE = 100;

  /** Team of a neutral object: outside the two that carry a hash. */
  private static final int NEUTRAL_TEAM = 2;

  /** The arena's static cell map. */
  @Getter private final TileMap tileMap;

  /** Number of columns, taken from the tile map. */
  @Getter private final int width;

  /** Number of rows, taken from the tile map. */
  @Getter private final int height;

  /** Whether buildings stamp their footprints into the overlay at all. */
  @Getter private final boolean dynamicOcclusionsEnabled;

  /** Overlay cost stamped over the cells a building occupies. */
  @Getter private final int buildingCost;

  /** Overlay cost of each cell for the current tick, row-major. */
  @Getter @Setter private int[] current;

  /** Overlay cost of each cell for the previous tick, row-major. */
  @Getter @Setter private int[] previous;

  /** Per-team summary of the occluding entities stamped by the last build. */
  @Getter @Setter private int[] hash;

  /** Per-team flag saying whether that team's hash differs from the previous build's. */
  @Getter @Setter private int[] changed;

  /** Copy of {@link #changed} taken once the build finished, read during the rest of the tick. */
  @Getter @Setter private int[] changeFlags;

  /** Packed footprints stamped by the last build, one per occluding entity that fitted the grid. */
  @Getter @Setter private List<Integer> footprints;

  /**
   * Counter the overlay builder resets to zero at the start of every build that stamps footprints.
   * Nothing on the build path increments it, so its exact role is not established; it is kept so
   * the builder can reproduce the reset.
   */
  @Getter @Setter private int footprintCounter;

  /** 1 once a build with occlusions enabled has run this tick, 0 otherwise. */
  @Getter @Setter private int active;

  /**
   * The route search's working arrays for this arena, reused by every search the battle runs, one
   * at a time.
   */
  @Getter private final RouteSearch.Buffers searchBuffers = new RouteSearch.Buffers();

  /**
   * The team whose hash an occluder of a side folds into and whose change flag a unit of the side
   * reads: the side's remainder by two, so sides 0 and 2 are team 0 and sides 1 and 3 team 1, and
   * the neutral side is team 2. The remainder keeps the side's sign, as the game's does, so a
   * negative side reads a negative team; neither a negative team nor team 2 carries a hash.
   *
   * @param side the object's side
   * @return its team
   */
  public static int team(int side) {
    return side == NEUTRAL_SIDE ? NEUTRAL_TEAM : side % 2;
  }

  /**
   * Creates a grid over the given cell map with zeroed overlays, no footprints and an inactive
   * build.
   *
   * @param tileMap the arena's static cell map
   * @param dynamicOcclusions whether buildings stamp footprints into the overlay
   * @param buildingCost overlay cost stamped over a building's cells
   */
  public CellGrid(TileMap tileMap, boolean dynamicOcclusions, int buildingCost) {
    this.tileMap = tileMap;
    this.width = tileMap.width();
    this.height = tileMap.height();
    this.dynamicOcclusionsEnabled = dynamicOcclusions;
    this.buildingCost = buildingCost;
    this.current = new int[width * height];
    this.previous = new int[width * height];
    this.hash = new int[SIDES];
    this.changed = new int[SIDES];
    this.changeFlags = new int[SIDES];
    this.footprints = new ArrayList<>();
    this.footprintCounter = 0;
    this.active = 0;
  }

  /** {@link #isDynamicOcclusionsEnabled()} as the 0 or 1 the overlay tests compare against. */
  public int dynamicEnabled() {
    return dynamicOcclusionsEnabled ? 1 : 0;
  }

  /** Routing flag word of a cell, from the static cell map. */
  public int tiles(int col, int row) {
    return tileMap.bits(col, row);
  }

  /** 1 when a cell is water, 0 otherwise. */
  public int water(int col, int row) {
    return (tileMap.bits(col, row) >> 5) & 1;
  }

  /** Row-major cell id of a column and row. */
  public int index(int col, int row) {
    return row * width + col;
  }

  /**
   * Rotates the overlays at the end of a tick: the overlay just built becomes the previous one and
   * the older one, zeroed, becomes the current overlay, ready for the next build. The two arrays
   * are reused tick after tick, so the rotation allocates nothing; a reader that keeps an overlay
   * past the next rotation sees it cleared.
   */
  public void swap() {
    int[] older = previous;
    previous = current;
    Arrays.fill(older, 0);
    current = older;
  }
}
