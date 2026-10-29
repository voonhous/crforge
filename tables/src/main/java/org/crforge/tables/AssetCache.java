/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.tables;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The local cache of the game's files and the tables built from them, so a file is fetched once.
 * Under its root:
 *
 * <ul>
 *   <li>{@code assets/<sha1>}: a file as served, named by its SHA-1, so a file shared by several
 *       data versions is kept once;
 *   <li>{@code fingerprints/<content sha>.json}: a content set's fingerprint as served;
 *   <li>{@code tables/<data version>/}: the game tables built for a data version (see {@link
 *       TableBuild}).
 * </ul>
 *
 * <p>A file is checked against the SHA-1 its fingerprint lists before it is kept, and again when it
 * is read back; one that does not match is fetched again. The asset database, whose fingerprint
 * value is not its SHA-1, is checked with its own hash instead ({@link AssetDatabase}). The build
 * names the root (the Gradle property {@code crforge.cache}, by default {@code .crforge} in the
 * user's home).
 */
public final class AssetCache {

  private final Path root;

  /** A cache at a root folder. */
  public AssetCache(Path root) {
    this.root = root;
  }

  /** The cache root. */
  public Path root() {
    return root;
  }

  /** The folder the tables of a data version are built into. */
  public Path tables(String dataVersion) {
    return root.resolve("tables").resolve(dataVersion);
  }

  /** A content set's fingerprint, from the cache or else from the source. */
  Fingerprint fingerprint(AssetSource source, String contentSha) throws IOException {
    Path path = root.resolve("fingerprints").resolve(contentSha + ".json");
    if (Files.isRegularFile(path)) {
      Fingerprint cached = Fingerprint.parse(Files.readAllBytes(path));
      if (cached.sha().equals(contentSha)) {
        return cached;
      }
    }
    byte[] served = source.fingerprint(contentSha);
    Fingerprint fingerprint = Fingerprint.parse(served);
    if (!fingerprint.sha().equals(contentSha)) {
      throw new IOException(
          "the fingerprint "
              + source
              + " serves for "
              + contentSha
              + " is of "
              + fingerprint.sha());
    }
    write(path, served);
    return fingerprint;
  }

  /** A check of a file as served: it throws when the file is not the one its set lists. */
  @FunctionalInterface
  interface Check {
    void verify(byte[] served) throws IOException;
  }

  /**
   * One file of a content set as served, from the cache or else from the source, checked against
   * the SHA-1 its fingerprint lists.
   */
  byte[] file(AssetSource source, String contentSha, Fingerprint.Entry entry) throws IOException {
    return file(
        source,
        contentSha,
        entry,
        served -> {
          String got = sha1(served);
          if (!got.equals(entry.sha1())) {
            throw new IOException(
                "it has SHA-1 "
                    + got
                    + "; the fingerprint of "
                    + contentSha
                    + " lists "
                    + entry.sha1());
          }
        });
  }

  /**
   * One file of a content set as served, from the cache or else from the source, checked by a check
   * of its own; kept under the SHA-1 its fingerprint lists all the same.
   */
  byte[] file(AssetSource source, String contentSha, Fingerprint.Entry entry, Check check)
      throws IOException {
    Path path = root.resolve("assets").resolve(entry.sha1());
    if (Files.isRegularFile(path)) {
      byte[] cached = Files.readAllBytes(path);
      try {
        check.verify(cached);
        return cached;
      } catch (IOException e) {
        // not the file: fetched again
      }
    }
    byte[] served = source.file(contentSha, entry.path());
    try {
      check.verify(served);
    } catch (IOException e) {
      throw new IOException(entry.path() + " from " + source + ": " + e.getMessage(), e);
    }
    write(path, served);
    return served;
  }

  /** Writes a file whole or not at all: to a temporary file beside it, then moved into place. */
  static void write(Path path, byte[] bytes) throws IOException {
    Files.createDirectories(path.getParent());
    Path tmp = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
    try {
      Files.write(tmp, bytes);
      try {
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  static String sha1(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
