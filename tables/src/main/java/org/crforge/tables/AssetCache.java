package org.crforge.tables;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
 * is read back; one that does not match is fetched again. The root is named by the system property
 * {@value #PROPERTY}, else the environment variable {@value #ENVIRONMENT}, else it is {@code
 * .crforge} in the user's home.
 */
public final class AssetCache {

  /** The system property naming the cache root. */
  public static final String PROPERTY = "crforge.cache";

  /** The environment variable naming the cache root, when the property is not set. */
  public static final String ENVIRONMENT = "CRFORGE_CACHE";

  private final Path root;

  /** A cache at a root folder. */
  public AssetCache(Path root) {
    this.root = root;
  }

  /** The configured cache. */
  public static AssetCache configured() {
    String configured = System.getProperty(PROPERTY);
    if (configured == null || configured.isBlank()) {
      configured = System.getenv(ENVIRONMENT);
    }
    if (configured == null || configured.isBlank()) {
      return new AssetCache(Paths.get(System.getProperty("user.home"), ".crforge"));
    }
    return new AssetCache(Paths.get(configured));
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

  /** One file of a content set as served, from the cache or else from the source. */
  byte[] file(AssetSource source, String contentSha, Fingerprint.Entry entry) throws IOException {
    Path path = root.resolve("assets").resolve(entry.sha1());
    if (Files.isRegularFile(path)) {
      byte[] cached = Files.readAllBytes(path);
      if (sha1(cached).equals(entry.sha1())) {
        return cached;
      }
    }
    byte[] served = source.file(contentSha, entry.path());
    String got = sha1(served);
    if (!got.equals(entry.sha1())) {
      throw new IOException(
          entry.path()
              + " from "
              + source
              + " has SHA-1 "
              + got
              + "; the fingerprint of "
              + contentSha
              + " lists "
              + entry.sha1());
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
