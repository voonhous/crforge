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
