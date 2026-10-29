/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.tables;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.airlift.compress.MalformedInputException;
import io.airlift.compress.zstd.ZstdDecompressor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.sqlite.SQLiteConfig;

/**
 * The game's asset database, as a content set serves it, and the data assets the client's
 * chaos-config step loads from it ({@link ClientSchema.AssetDatabase}).
 *
 * <p>The file as served is a container: the 4 bytes {@code scdb}, then little-endian 32-bit values
 * (the container version 1, the compression 1 for zstd, the compressed size, the database's size),
 * then the 16-byte XXH3 128-bit hash of the database (its low 64 bits, then its high 64 bits, each
 * little-endian), then one zstd frame of the database. The container's hash, not the SHA-1 the
 * content set's fingerprint lists for the file, is what the file is checked with: the fingerprint's
 * value is not the SHA-1 of the file served.
 *
 * <p>The database is SQLite. Its table {@code meta} names each asset's path, asset type and uuid;
 * its table {@code asset} holds each asset's {@code data}, an {@code extref} and a {@code format}.
 * An asset of format 0 is JSON text. The client loads nothing for an asset of a format other than 0
 * and 1; format 1, an asset whose data lies elsewhere ({@code extref}), and a listed asset whose
 * path and uuid name different assets are not read here and refuse the build.
 */
final class AssetDatabase {

  /** The container's first 4 bytes. */
  static final byte[] MAGIC = "scdb".getBytes(StandardCharsets.US_ASCII);

  /** The container's header: magic, version, compression, two sizes, the hash. */
  static final int HEADER = 36;

  private static final int VERSION = 1;
  private static final int ZSTD = 1;

  /** The JSON text form of an asset. */
  static final int FORMAT_JSON = 0;

  /** The binary form of an asset, which the decoder does not read. */
  static final int FORMAT_BINARY = 1;

  /** The text between a listed asset's path and its uuid. */
  static final String REFERENCE_SEPARATOR = " | ";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * One data asset the chaos-config step loads, in the config's list order.
   *
   * @param path its path in the database
   * @param text its JSON text
   */
  record DataAsset(String path, String text) {}

  private AssetDatabase() {}

  /**
   * Checks a served file: the container's header, its sizes, and the hash of the database it holds.
   *
   * @throws IOException when it is not the container or its database does not match its hash
   */
  static void check(byte[] served) throws IOException {
    unpack(served);
  }

  /**
   * The database a served file holds.
   *
   * @throws IOException when it is not the container or its database does not match its hash
   */
  static byte[] unpack(byte[] served) throws IOException {
    if (served.length < HEADER || !Arrays.equals(served, 0, 4, MAGIC, 0, 4)) {
      throw new IOException("not an asset database container (no scdb header)");
    }
    ByteBuffer header = ByteBuffer.wrap(served, 4, HEADER - 4).order(ByteOrder.LITTLE_ENDIAN);
    int version = header.getInt();
    int compression = header.getInt();
    long packed = header.getInt() & 0xffffffffL;
    long size = header.getInt() & 0xffffffffL;
    long hashLo = header.getLong();
    long hashHi = header.getLong();
    if (version != VERSION || compression != ZSTD) {
      throw new IOException(
          "an asset database container of version "
              + version
              + " and compression "
              + compression
              + "; the decoder reads version "
              + VERSION
              + " with zstd ("
              + ZSTD
              + ")");
    }
    if (packed > served.length - HEADER) {
      throw new IOException(
          "the container declares " + packed + " compressed bytes; it holds fewer");
    }
    if (size > Integer.MAX_VALUE - 8) {
      throw new IOException("the container declares a database of " + size + " bytes");
    }
    byte[] database = new byte[(int) size];
    int got;
    try {
      got =
          new ZstdDecompressor()
              .decompress(served, HEADER, (int) packed, database, 0, database.length);
    } catch (MalformedInputException e) {
      throw new IOException("the container's zstd frame does not decode: " + e.getMessage(), e);
    }
    if (got != size) {
      throw new IOException("decoded " + got + " bytes, the container declares " + size);
    }
    long[] hash = Xxh3.hash128(database);
    if (hash[0] != hashLo || hash[1] != hashHi) {
      throw new IOException("the database does not match the container's hash");
    }
    return database;
  }

  /**
   * The data assets the chaos-config step loads from a database, in the config's list order: each
   * listed asset of the data asset type with JSON data. A listed asset the database does not have
   * at all, or one of a format the client loads nothing for, is left out, as the client leaves it.
   *
   * @throws IOException when the database cannot be read, or a listed asset is of a kind the
   *     decoder does not read (see the class comment)
   */
  static List<DataAsset> chaosAssets(byte[] database, ClientSchema.AssetDatabase schema)
      throws IOException {
    Path file = Files.createTempFile("crforge-assets", ".db");
    try {
      Files.write(file, database);
      SQLiteConfig config = new SQLiteConfig();
      config.setReadOnly(true);
      try (Connection db = config.createConnection("jdbc:sqlite:" + file.toAbsolutePath())) {
        return chaosAssets(db, schema);
      } catch (SQLException e) {
        throw new IOException("the asset database cannot be read: " + e.getMessage(), e);
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  private static List<DataAsset> chaosAssets(Connection db, ClientSchema.AssetDatabase schema)
      throws SQLException, IOException {
    String[] configType = meta(db, "path", schema.config());
    if (configType == null || !configType[1].equals(schema.configClass())) {
      throw new IOException(
          "the asset database has no "
              + schema.configClass()
              + " at "
              + schema.config()
              + (configType == null ? "" : " (it has a " + configType[1] + ")"));
    }
    String configText = text(db, schema.config(), true);
    JsonNode list;
    try {
      list = MAPPER.readTree(configText).path(schema.configList());
    } catch (JacksonException e) {
      throw new IOException(schema.config() + " is not JSON: " + e.getOriginalMessage(), e);
    }
    if (!list.isArray()) {
      throw new IOException(schema.config() + " has no list " + schema.configList());
    }
    List<DataAsset> out = new ArrayList<>();
    for (JsonNode entry : list) {
      String reference = entry.asText();
      int at = reference.indexOf(REFERENCE_SEPARATOR);
      if (!entry.isTextual() || at < 0) {
        throw new IOException(schema.config() + " lists " + entry + ", not \"<path> | <uuid>\"");
      }
      String path = reference.substring(0, at);
      String uuid = reference.substring(at + REFERENCE_SEPARATOR.length());
      String[] byPath = meta(db, "path", path);
      String[] byUuid = meta(db, "uuid", uuid);
      if (byPath == null && byUuid == null) {
        continue; // an asset the database does not have: the client finds none and goes on
      }
      if (byPath == null || byUuid == null || !byPath[0].equals(byUuid[0])) {
        throw new IOException(
            schema.config()
                + " lists "
                + reference
                + ", whose path and uuid name different assets; which one the client loads is"
                + " not known to the decoder");
      }
      if (!byPath[1].equals(schema.dataClass())) {
        throw new IOException(
            schema.config()
                + " lists "
                + path
                + ", a "
                + byPath[1]
                + "; the decoder reads only a "
                + schema.dataClass());
      }
      String text = text(db, path, false);
      if (text != null) {
        out.add(new DataAsset(path, text));
      }
    }
    return out;
  }

  /** The meta row of an asset by a column: its path and asset type, or null. */
  private static String[] meta(Connection db, String column, String value) throws SQLException {
    try (PreparedStatement q =
        db.prepareStatement("SELECT path, assetType FROM meta WHERE " + column + " = ?")) {
      q.setString(1, value);
      try (ResultSet r = q.executeQuery()) {
        if (!r.next()) {
          return null;
        }
        String[] row = {r.getString(1), r.getString(2)};
        if (r.next()) {
          throw new SQLException("more than one asset has the " + column + " " + value);
        }
        return row;
      }
    }
  }

  /**
   * An asset's JSON text, or null when its format is one the client loads nothing for.
   *
   * @param required whether an asset without JSON data refuses the build
   */
  private static String text(Connection db, String path, boolean required)
      throws SQLException, IOException {
    try (PreparedStatement q =
        db.prepareStatement("SELECT data, extref, format FROM asset WHERE path = ?")) {
      q.setString(1, path);
      try (ResultSet r = q.executeQuery()) {
        if (!r.next()) {
          throw new IOException("the asset database has no data of " + path);
        }
        byte[] data = r.getBytes(1);
        String extref = r.getString(2);
        int format = r.getInt(3);
        if (extref != null) {
          throw new IOException(
              path + " lies outside the asset database (" + extref + "); the decoder reads none");
        }
        if (format == FORMAT_BINARY) {
          throw new IOException(path + " is in the binary asset form; the decoder reads JSON only");
        }
        if (format != FORMAT_JSON) {
          if (required) {
            throw new IOException(path + " has the asset format " + format);
          }
          return null; // the client loads no data for it
        }
        if (data == null) {
          throw new IOException(path + " has no data");
        }
        String text = GameFiles.text(data);
        if (text.indexOf('\0') >= 0) {
          throw new IOException(path + " holds a NUL character");
        }
        return text;
      }
    }
  }
}
