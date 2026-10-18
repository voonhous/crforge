/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.battle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.deploy.DeployMask;
import org.crforge.core.battle.deploy.MaskEntity;
import org.crforge.core.battle.deploy.PlacementSearch;
import org.crforge.core.battle.unit.CharacterEntity;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.util.GameUnits;

/**
 * The deploy overlay's model: of a card a side would play now, the tiles it lands on without being
 * moved, and where a drop on a tile is moved to.
 *
 * <p>The open tiles follow the environment's landing mask (mask rule 2): a tile is open when the
 * battle core's placement search, on the deploy mask of the card's row and of the buildings and
 * towers the side sees, does not move a drop at its centre ({@link PlacementSearch#landingTiles}).
 * An enemy building the side does not see (hidden, invisible or underground) closes no tile, so a
 * tile may be open where the battle's own search, which reads every character, still moves a drop;
 * the hovered tile's landing then says so. For a Mirror that repeats a listed play, whose giving
 * searches two cards, a tile is open when both land on it.
 *
 * <p>The tiles are kept per side and card row until the field the side sees changes: a building
 * placed or destroyed, a tower fallen, a hidden building come up.
 */
public final class LandingTiles {

  /**
   * One side's tiles for one play, in the battle's own columns and rows (row 0 at side 0's end).
   *
   * @param width the arena's width in tiles
   * @param height the arena's length in tiles
   * @param flags one flag per tile, row after row: true where the card lands unmoved
   */
  public record Tiles(int width, int height, boolean[] flags) {

    /** Whether the card lands unmoved on a tile; false for a tile off the arena. */
    public boolean open(int col, int row) {
      return col >= 0 && row >= 0 && col < width && row < height && flags[row * width + col];
    }

    /** How many tiles are open. */
    public int openCount() {
      int count = 0;
      for (boolean flag : flags) {
        count += flag ? 1 : 0;
      }
      return count;
    }
  }

  /**
   * Where a drop at a tile's centre goes, as the screen sends it.
   *
   * @param tileX the dropped tile's column
   * @param tileY the dropped tile's row
   * @param open whether the overlay shows the tile open
   * @param point the point the battle's placement search sends the drop to now, in game units, or
   *     null when it finds no legal tile
   */
  public record Landing(int tileX, int tileY, boolean open, int[] point) {

    /** Whether the drop is moved off its tile, or finds no tile at all. */
    public boolean moved() {
      int[] target = targetTile();
      return target == null || target[0] != tileX || target[1] != tileY;
    }

    /** The tile the drop is sent to, as {column, row}, or null for none. */
    public int[] targetTile() {
      return point == null
          ? null
          : new int[] {
            Math.floorDiv(point[0], GameUnits.UNITS_PER_TILE),
            Math.floorDiv(point[1], GameUnits.UNITS_PER_TILE)
          };
    }

    /** The status line for the hovered tile. */
    public String status() {
      String where = "tile (" + tileX + ", " + tileY + ")";
      int[] target = targetTile();
      if (target == null) {
        return "landing: " + where + " closed, no legal tile";
      }
      if (!moved()) {
        return "landing: " + where + (open ? " open, lands here" : " closed, lands here");
      }
      String to = "moved to tile (" + target[0] + ", " + target[1] + ")";
      return open
          ? "landing: " + where + " open, but " + to + " (a building the side does not see)"
          : "landing: " + where + " closed, " + to;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Landing other
          && tileX == other.tileX
          && tileY == other.tileY
          && open == other.open
          && Arrays.equals(point, other.point);
    }

    @Override
    public int hashCode() {
      return 31 * (31 * (31 * tileX + tileY) + Boolean.hashCode(open)) + Arrays.hashCode(point);
    }

    @Override
    public String toString() {
      return status();
    }
  }

  private final Standard1v1Battle battle;

  /** Per side: the characters its tiles were last built on, those the deploy mask reads. */
  private final List<List<MaskEntity>> fieldSeen =
      new ArrayList<>(List.of(new ArrayList<>(), new ArrayList<>()));

  /** Per side: each card row's landing flags on that field, by row name. */
  private final List<Map<String, boolean[]>> kept = List.of(new HashMap<>(), new HashMap<>());

  /** Per side: the tiles last handed out and the cards they were built for. */
  private final Tiles[] lastTiles = new Tiles[2];

  private final List<List<String>> lastCards = new ArrayList<>(List.of(List.of(), List.of()));

  /**
   * The model of one battle.
   *
   * @param battle the battle whose field and search the tiles are read from
   */
  public LandingTiles(Standard1v1Battle battle) {
    this.battle = battle;
  }

  /**
   * Whether a side's player sees a character or tower, as the environment's observation shows it:
   * every one of its own side and every tower; an enemy character unless it is hidden, invisible or
   * underground.
   */
  public static boolean seenBy(int side, WorldEntity world) {
    if (world.side() == side || !(world instanceof CharacterEntity character)) {
      return true;
    }
    return !character.hidden() && !character.invisible() && !character.underground();
  }

  /**
   * The tiles a play lands on unmoved now, from the cards the screen's search is handed for it. The
   * same object is handed back while the side's field and the cards stay the same.
   *
   * @param side the playing side
   * @param cards the cards the giving searches, the card placed first; not empty
   * @return the tiles
   */
  public Tiles tiles(int side, List<DeployCard> cards) {
    seeField(side);
    List<String> names = cards.stream().map(DeployCard::name).toList();
    if (lastTiles[side] != null && lastCards.get(side).equals(names)) {
      return lastTiles[side];
    }
    TileMap map = battle.getWorld().getTileMap();
    List<MaskEntity> field = fieldSeen.get(side);
    boolean[] lands = null;
    for (DeployCard card : cards) {
      boolean[] own =
          kept.get(side)
              .computeIfAbsent(
                  card.name(),
                  name ->
                      PlacementSearch.landingTiles(
                          map, card, PlacementSearch.mask(map, card, side, field)));
      if (lands == null) {
        lands = own.clone();
      } else {
        // A Mirror whose item repeats a listed play: the giving searches both cards.
        for (int t = 0; t < lands.length; t++) {
          lands[t] &= own[t];
        }
      }
    }
    lastTiles[side] = new Tiles(map.width() >> 1, map.height() >> 1, lands);
    lastCards.set(side, names);
    return lastTiles[side];
  }

  /**
   * Where a drop at a tile's centre goes now: the point the battle's own placement search sends it
   * to, as the screen sends a play, with whether the tiles show the tile open.
   *
   * @param side the playing side
   * @param cards the cards the giving searches, the card placed first; not empty
   * @param tileX the dropped tile's column
   * @param tileY the dropped tile's row
   */
  public Landing landing(int side, List<DeployCard> cards, int tileX, int tileY) {
    Tiles tiles = tiles(side, cards);
    int[] point =
        battle.searchPlacement(
            cards.get(0), side, GameUnits.tileCenter(tileX), GameUnits.tileCenter(tileY));
    return new Landing(tileX, tileY, tiles.open(tileX, tileY), point);
  }

  /**
   * Reads the characters a side's tiles are built on now: those the deploy mask reads (alive
   * buildings and king towers) that the side sees. When they differ from the last read, the tiles
   * kept for the side are dropped.
   */
  private void seeField(int side) {
    List<MaskEntity> now = new ArrayList<>();
    for (MaskEntity e : battle.placementEntities(w -> seenBy(side, w))) {
      if (DeployMask.reads(e)) {
        now.add(e);
      }
    }
    if (!now.equals(fieldSeen.get(side))) {
      fieldSeen.set(side, now);
      kept.get(side).clear();
      lastTiles[side] = null;
    }
  }
}
