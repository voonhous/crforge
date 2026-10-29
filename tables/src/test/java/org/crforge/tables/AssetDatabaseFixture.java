/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.tables;

import io.airlift.compress.zstd.ZstdCompressor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Small asset databases for the tests, written here: a SQLite database with the tables {@code meta}
 * and {@code asset}, and the container the game serves it in. None of the values is the game's.
 */
final class AssetDatabaseFixture {

  /**
   * One asset of a fixture database.
   *
   * @param path its path
   * @param type its asset type
   * @param uuid its uuid
   * @param data its data, or null
   * @param extref where its data lies outside the database, or null
   * @param format its format
   */
  record Asset(String path, String type, String uuid, String data, String extref, int format) {

    /** A JSON asset. */
    static Asset json(String path, String type, String uuid, String data) {
      return new Asset(path, type, uuid, data, null, AssetDatabase.FORMAT_JSON);
    }

    /** How a config lists it. */
    String reference() {
      return path + AssetDatabase.REFERENCE_SEPARATOR + uuid;
    }
  }

  private AssetDatabaseFixture() {}

  /** A SQLite database of the assets, its bytes. */
  static byte[] database(Path folder, List<Asset> assets) throws IOException {
    Files.createDirectories(folder);
    Path file = Files.createTempFile(folder, "assets", ".db");
    Files.delete(file);
    try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        Statement s = db.createStatement()) {
      s.execute(
          "CREATE TABLE meta(path TEXT PRIMARY KEY, assetType TEXT, shortid INTEGER,"
              + " assetFilename TEXT, uuid TEXT, tags TEXT, data BLOB, format INTEGER,"
              + " isbundle INTEGER, parent TEXT) STRICT");
      s.execute(
          "CREATE TABLE asset(path TEXT PRIMARY KEY, data BLOB, extref TEXT, format INTEGER)"
              + " STRICT");
      for (Asset a : assets) {
        try (PreparedStatement m =
            db.prepareStatement(
                "INSERT INTO meta(path, assetType, uuid, format, isbundle) VALUES (?, ?, ?, 1, 0)")) {
          m.setString(1, a.path());
          m.setString(2, a.type());
          m.setString(3, a.uuid());
          m.executeUpdate();
        }
        try (PreparedStatement d =
            db.prepareStatement(
                "INSERT INTO asset(path, data, extref, format) VALUES (?, ?, ?, ?)")) {
          d.setString(1, a.path());
          d.setBytes(2, a.data() == null ? null : a.data().getBytes(StandardCharsets.UTF_8));
          d.setString(3, a.extref());
          d.setInt(4, a.format());
          d.executeUpdate();
        }
      }
    } catch (SQLException e) {
      throw new IOException(e);
    }
    try {
      return Files.readAllBytes(file);
    } finally {
      Files.delete(file);
    }
  }

  /** A database in the container the game serves it in. */
  static byte[] container(byte[] database) {
    ZstdCompressor zstd = new ZstdCompressor();
    byte[] packed = new byte[zstd.maxCompressedLength(database.length)];
    int length = zstd.compress(database, 0, database.length, packed, 0, packed.length);
    long[] hash = Xxh3.hash128(database);
    ByteBuffer out = ByteBuffer.allocate(AssetDatabase.HEADER + length);
    out.order(ByteOrder.LITTLE_ENDIAN)
        .put(AssetDatabase.MAGIC)
        .putInt(1)
        .putInt(1)
        .putInt(length)
        .putInt(database.length)
        .putLong(hash[0])
        .putLong(hash[1])
        .put(packed, 0, length);
    return out.array();
  }

  /** A config asset listing the references, as JSON. */
  static String config(String list, List<String> references) {
    StringBuilder out = new StringBuilder("{\"").append(list).append("\":[");
    for (int i = 0; i < references.size(); i++) {
      out.append(i == 0 ? "" : ",").append('"').append(references.get(i)).append('"');
    }
    return out.append("]}").toString();
  }
}
