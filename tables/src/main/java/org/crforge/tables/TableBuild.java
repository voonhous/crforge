package org.crforge.tables;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Builds the game tables of a data version on this machine: fetches the game's files of its content
 * set from an asset source through the cache, reads them with the schema of the data version's
 * client, and writes one JSON file per table plus {@code actions.json} into the cache's {@code
 * tables/<data version>/} folder, the folder the battle core reads.
 *
 * <p>A folder already built from the same content set by the same decoder is used as it is; a
 * {@value #STAMP} file beside the tables records what it was built from. Run as a program it builds
 * one data version and prints the folder: {@code TableBuild <data version> [<asset source URI>]},
 * the source by default the game's asset CDN.
 */
public final class TableBuild {

  /** The version of the decoder's output. Raise it when the tables it writes change. */
  public static final int DECODER = 1;

  /** The file in a built folder that records what it was built from. */
  public static final String STAMP = "build.properties";

  private TableBuild() {}

  /**
   * The folder of the data version's tables, built first when the cache has none of them.
   *
   * @throws IOException when a file cannot be fetched or does not match its fingerprint
   */
  public static Path build(DataVersion version, AssetSource source, AssetCache cache)
      throws IOException {
    Path folder = cache.tables(version.version());
    Properties wanted = stamp(version);
    if (wanted.equals(readStamp(folder))) {
      return folder;
    }
    Map<String, String> documents =
        decode(GameFiles.fetch(version, source, cache), ClientSchema.load(version.clientVersion()));
    Path parent = folder.getParent();
    Files.createDirectories(parent);
    Path staging = Files.createTempDirectory(parent, version.version() + ".");
    try {
      for (Map.Entry<String, String> d : documents.entrySet()) {
        Files.writeString(
            staging.resolve(d.getKey() + ".json"), d.getValue(), StandardCharsets.UTF_8);
      }
      StringBuilder stampText = new StringBuilder();
      wanted.stringPropertyNames().stream()
          .sorted()
          .forEach(k -> stampText.append(k).append('=').append(wanted.getProperty(k)).append('\n'));
      Files.writeString(staging.resolve(STAMP), stampText.toString(), StandardCharsets.UTF_8);
      delete(folder);
      Files.move(staging, folder);
    } finally {
      delete(staging);
    }
    return folder;
  }

  /** The documents of a set's tables, file name without extension to the text written. */
  static Map<String, String> decode(GameFiles files, ClientSchema schema) {
    LoadModel model = new LoadModel(schema, files).build();
    TableResolver resolver = new TableResolver(schema, model).resolve();
    Map<String, String> out = new LinkedHashMap<>();
    new TableExport(schema, files, resolver)
        .documents()
        .forEach((name, document) -> out.put(name, PythonJson.indented(document, 1) + "\n"));
    return out;
  }

  private static Properties stamp(DataVersion version) {
    Properties p = new Properties();
    p.setProperty("data_version", version.version());
    p.setProperty("content_sha", version.contentSha());
    p.setProperty("client_version", version.clientVersion());
    p.setProperty("decoder", Integer.toString(DECODER));
    return p;
  }

  private static Properties readStamp(Path folder) {
    Properties p = new Properties();
    Path file = folder.resolve(STAMP);
    if (Files.isRegularFile(file)) {
      try (var in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        p.load(in);
      } catch (IOException e) {
        return new Properties();
      }
    }
    return p;
  }

  private static void delete(Path folder) throws IOException {
    if (!Files.exists(folder)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(folder)) {
      for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(p);
      }
    }
  }

  /** Builds one data version's tables and prints their folder. */
  public static void main(String[] args) {
    if (args.length < 1 || args.length > 2) {
      System.err.println("usage: TableBuild <data version> [<asset source URI>]");
      System.exit(2);
    }
    DataVersion version = DataVersion.of(args[0]);
    AssetSource source =
        args.length > 1 ? AssetSource.at(URI.create(args[1])) : AssetSource.gameCdn();
    try {
      System.out.println(build(version, source, AssetCache.configured()));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
