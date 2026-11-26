/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.pathfinding.grid;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The bundled standard arena map is the data version's own: the tile map file the game's locations
 * name for the standard arena, {@value #TILE_MAP}, which the table build writes beside the tables.
 * Nothing else ties the two, so a data version whose arena changed would otherwise play on the old
 * map unnoticed.
 */
class StandardArenaTileMapTest {

  /** The standard arena's tile map file in a folder of game tables, as the game names it. */
  static final String TILE_MAP = "tilemaps/tilemap.csv";

  /** The bundled map, as {@link TileMap#standard1v1()} loads it. */
  private static final String BUNDLED = "/arena/standard_1v1_cells.txt";

  @Test
  @DisplayName("the bundled standard arena map is the data version's tile map, cell for cell")
  void theBundledMapIsTheDataVersionsTileMap() throws IOException {
    Path file = GameTables.configuredDirectory().orElseThrow().resolve(TILE_MAP);
    assertThat(file).as("the data version's tile map").isRegularFile();
    List<int[]> game = gameCells(Files.readString(file, StandardCharsets.UTF_8));
    List<int[]> bundled = bundledCells();
    assertThat(game).hasSameSizeAs(bundled);
    for (int row = 0; row < bundled.size(); row++) {
      assertThat(game.get(row)).as("row " + row).containsExactly(bundled.get(row));
    }
    TileMap map = TileMap.standard1v1();
    assertThat(game).hasSize(map.height());
    assertThat(game.get(0)).hasSize(map.width());
  }

  /**
   * The cells of a tile map file as the game writes it: a header of three lines (the name, the
   * column names and their types), then one line per row of the map, top row first, each led by an
   * empty cell and a value per column, an empty value being 0; the rows end at the next line that
   * names a section.
   */
  private static List<int[]> gameCells(String text) {
    String[] lines = text.split("\n");
    List<int[]> rows = new ArrayList<>();
    for (int i = 3; i < lines.length; i++) {
      String[] cells = lines[i].split(",", -1);
      if (!cells[0].isEmpty()) {
        break;
      }
      int[] row = new int[cells.length - 1];
      for (int c = 1; c < cells.length; c++) {
        row[c - 1] = cells[c].isEmpty() ? 0 : Integer.parseInt(cells[c]);
      }
      rows.add(row);
    }
    return rows;
  }

  /** The bundled map's cells as published, before the routing mask. */
  private static List<int[]> bundledCells() throws IOException {
    List<int[]> rows = new ArrayList<>();
    try (InputStream in = TileMap.class.getResourceAsStream(BUNDLED)) {
      assertThat(in).as(BUNDLED).isNotNull();
      for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
          continue;
        }
        String[] tokens = trimmed.split("\\s+");
        int[] row = new int[tokens.length];
        for (int c = 0; c < tokens.length; c++) {
          row[c] = Integer.parseInt(tokens[c]);
        }
        rows.add(row);
      }
    }
    return rows;
  }
}
