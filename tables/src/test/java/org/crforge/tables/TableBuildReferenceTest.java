/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.tables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The tables the decoder builds are the game tables of the game data repository, byte for byte, for
 * every data version that repository has both the tables and the asset files of. The asset files
 * are read from the repository's {@code cdn/} copy, never from the game's CDN. The repository is
 * named by the system property {@value #PROPERTY}; without it the test is skipped.
 */
class TableBuildReferenceTest {

  /** The system property naming a checkout of the game data repository. */
  static final String PROPERTY = "crforge.dataRoot";

  static Stream<String> versions() {
    return DataVersion.known().keySet().stream();
  }

  @ParameterizedTest
  @MethodSource("versions")
  @DisplayName("a data version's tables, built from the repository's asset files, are its tables")
  void buildsTheRepositoryTables(String name, @TempDir Path cacheRoot) throws Exception {
    String root = System.getProperty(PROPERTY);
    assumeTrue(root != null && !root.isBlank(), "no " + PROPERTY + " set");
    DataVersion version = DataVersion.of(name);
    Path data = Paths.get(root);
    Path expected = data.resolve(name);
    Path assets = data.resolve("cdn");
    assumeTrue(Files.isDirectory(expected), "no tables of " + name + " in " + data);
    assumeTrue(
        Files.isDirectory(assets.resolve(version.contentSha())),
        "no asset files of " + name + " in " + assets);

    Path built = TableBuild.build(version, AssetSource.folder(assets), new AssetCache(cacheRoot));

    assertThat(jsonFiles(built)).isEqualTo(jsonFiles(expected));
    for (String file : jsonFiles(expected)) {
      assertThat(Files.readString(built.resolve(file), StandardCharsets.UTF_8))
          .as(name + "/" + file)
          .isEqualTo(Files.readString(expected.resolve(file), StandardCharsets.UTF_8));
    }
  }

  private static TreeSet<String> jsonFiles(Path folder) throws Exception {
    try (Stream<Path> listing = Files.list(folder)) {
      return listing
          .map(p -> p.getFileName().toString())
          .filter(n -> n.endsWith(".json"))
          .collect(Collectors.toCollection(TreeSet::new));
    }
  }
}
