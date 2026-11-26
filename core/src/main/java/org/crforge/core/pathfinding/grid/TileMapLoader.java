/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.pathfinding.grid;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a {@link TileMap} from a bundled text resource.
 *
 * <p>The format is deliberately plain so the data stays readable and reviewable: lines beginning
 * with {@code #} are comments and blank lines are ignored; every other line is one row of the map,
 * top row first, holding whitespace-separated integer cell values, left to right. The number of
 * values on the first row fixes the width and every later row must match it.
 *
 * <p>Values are stored as published; {@link TileMap} masks them to the bits routing uses.
 */
public final class TileMapLoader {

  private TileMapLoader() {
    // Utility class
  }

  /**
   * Loads a tile map from a classpath resource.
   *
   * @param resourcePath absolute classpath path, for example {@code /arena/standard_1v1_cells.txt}
   * @throws IllegalArgumentException if the resource is missing or malformed
   */
  public static TileMap load(String resourcePath) {
    try (InputStream stream = TileMapLoader.class.getResourceAsStream(resourcePath)) {
      if (stream == null) {
        throw new IllegalArgumentException("Tile map resource not found: " + resourcePath);
      }
      return parse(
          new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)), resourcePath);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read tile map resource " + resourcePath, e);
    }
  }

  /** The name of a tile map file's first section, the map's cells. */
  private static final String MAP_SECTION = "Map";

  /**
   * Reads a tile map file as the game writes it, the file a location names as its tile data: a
   * header of three lines (the section's name, {@value #MAP_SECTION}, the column names and their
   * types), then one line per row of the map, top row first, each led by an empty cell and holding
   * a value per column, an empty value being 0. The map's rows end at the next line that names a
   * section, or at the file's end. Every row must be as wide as the first.
   *
   * @param text the file's text
   * @param source the file's name, for errors
   * @return the map
   * @throws IllegalArgumentException for a file that is not such a map
   */
  public static TileMap parseGameFile(String text, String source) {
    String[] lines = text.split("\r?\n");
    if (lines.length < 4) {
      throw new IllegalArgumentException(source + " holds no map rows after its header");
    }
    String section = lines[0].split(",", -1)[0].replace("\"", "");
    if (!MAP_SECTION.equals(section)) {
      throw new IllegalArgumentException(
          source + " opens with the section " + section + ", not the map's, " + MAP_SECTION);
    }
    List<int[]> rows = new ArrayList<>();
    for (int i = 3; i < lines.length; i++) {
      String[] cells = lines[i].split(",", -1);
      if (!cells[0].isEmpty()) {
        // The next section: the map's rows have ended.
        break;
      }
      int[] row = new int[cells.length - 1];
      for (int c = 1; c < cells.length; c++) {
        try {
          row[c - 1] = cells[c].isEmpty() ? 0 : Integer.parseInt(cells[c]);
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException(
              source + " line " + (i + 1) + ": not an integer: " + cells[c], e);
        }
      }
      if (!rows.isEmpty() && row.length != rows.get(0).length) {
        throw new IllegalArgumentException(
            source
                + " line "
                + (i + 1)
                + ": expected "
                + rows.get(0).length
                + " values, got "
                + row.length);
      }
      rows.add(row);
    }
    if (rows.isEmpty()) {
      throw new IllegalArgumentException(source + " holds no map rows");
    }
    int width = rows.get(0).length;
    int[] values = new int[width * rows.size()];
    for (int row = 0; row < rows.size(); row++) {
      System.arraycopy(rows.get(row), 0, values, row * width, width);
    }
    return new TileMap(width, rows.size(), values);
  }

  private static TileMap parse(BufferedReader reader, String source) throws IOException {
    List<int[]> rows = new ArrayList<>();
    int width = -1;
    String line;
    int lineNumber = 0;
    while ((line = reader.readLine()) != null) {
      lineNumber++;
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.charAt(0) == '#') {
        continue;
      }
      String[] tokens = trimmed.split("\\s+");
      if (width == -1) {
        width = tokens.length;
      } else if (tokens.length != width) {
        throw new IllegalArgumentException(
            source
                + " line "
                + lineNumber
                + ": expected "
                + width
                + " values, got "
                + tokens.length);
      }
      int[] row = new int[tokens.length];
      for (int i = 0; i < tokens.length; i++) {
        try {
          row[i] = Integer.parseInt(tokens[i]);
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException(
              source + " line " + lineNumber + ": not an integer: " + tokens[i], e);
        }
      }
      rows.add(row);
    }
    if (rows.isEmpty()) {
      throw new IllegalArgumentException(source + " holds no map rows");
    }
    int height = rows.size();
    int[] values = new int[width * height];
    for (int row = 0; row < height; row++) {
      System.arraycopy(rows.get(row), 0, values, row * width, width);
    }
    return new TileMap(width, height, values);
  }
}
