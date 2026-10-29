/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.tables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.tables.AssetDatabaseFixture.Asset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Building tables end to end with the asset database: the content set of {@link TableBuildTest}
 * plus a small hand-written asset database, read with the synthetic client schema {@code test-2},
 * which names the chaos-config step. None of the values is the game's.
 */
class TableBuildAssetDatabaseTest {

  private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
  private static final DataVersion VERSION = new DataVersion("0.0.3", "test-2", SHA);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ClientSchema.AssetDatabase SCHEMA =
      ClientSchema.load("test-2").assetDatabase();

  /** The value the fingerprint lists for the asset database: not the SHA-1 of the file served. */
  private static final String LISTED = "ab".repeat(20);

  @TempDir Path temp;
  private Path cdn;
  private AssetCache cache;

  /** The data assets the config lists, in its order. */
  static List<Asset> chaos() {
    return List.of(
        Asset.json(
            "logic/chaos/second",
            SCHEMA.dataClass(),
            "u-2",
            "{\"data\":{"
                + "\"BUFF\":{\"Rage\":{\"SpeedMultiplier\":1.35,\"DamagePerSecond\":5}},"
                + "\"EXT\":{\"Alpha_chaos\":{\"Base\":\"CHARACTER.Alpha\",\"Hitpoints\":[\"+\",1]}},"
                + "\"NOT_A_TOKEN\":{\"Nothing\":{\"A\":1}}}}"),
        Asset.json(
            "logic/chaos/first",
            SCHEMA.dataClass(),
            "u-1",
            "{\"data\":{"
                + "\"CHARACTER\":{\"Delta\":{\"Hitpoints\":10}},"
                + "\"ACTION\":{\"ChaosSpawn\":{\"ClassType\":\"ActionSpawn\",\"Count\":3}}}}"),
        Asset.json("logic/chaos/empty", SCHEMA.dataClass(), "u-3", "{\"other\":{}}"));
  }

  @BeforeEach
  void serve() throws IOException {
    cdn = temp.resolve("cdn");
    cache = new AssetCache(temp.resolve("cache"));
    TableBuildTest.serve(cdn, TableBuildTest.files());
    List<String> references = chaos().stream().map(Asset::reference).toList();
    List<Asset> assets =
        new ArrayList<>(
            List.of(
                Asset.json(
                    SCHEMA.config(),
                    SCHEMA.configClass(),
                    "c0",
                    AssetDatabaseFixture.config(SCHEMA.configList(), references))));
    assets.addAll(chaos());
    serveDatabase(
        AssetDatabaseFixture.container(AssetDatabaseFixture.database(temp.resolve("db"), assets)));
  }

  /** Serves the asset database and lists it in the fingerprint. */
  private void serveDatabase(byte[] served) throws IOException {
    Files.write(cdn.resolve(SHA).resolve(SCHEMA.file()), served);
    Path fingerprint = cdn.resolve(SHA).resolve("fingerprint.json");
    ObjectNode root = (ObjectNode) MAPPER.readTree(fingerprint.toFile());
    ArrayNode files = (ArrayNode) root.path("files");
    boolean listed = false;
    for (JsonNode f : files) {
      listed |= f.path("file").asText().equals(SCHEMA.file());
    }
    if (!listed) {
      files.addObject().put("file", SCHEMA.file()).put("sha", LISTED);
    }
    Files.write(fingerprint, MAPPER.writeValueAsBytes(root));
  }

  private JsonNode table(String name) throws IOException {
    Path folder = TableBuild.build(VERSION, AssetSource.folder(cdn), cache);
    return MAPPER.readTree(folder.resolve(name + ".json").toFile());
  }

  @Test
  @DisplayName("the data assets' rows follow the files' rows, in the config's list order")
  void rowsAfterTheFiles() throws IOException {
    JsonNode buffs = table("character_buffs").path("rows");
    assertThat(buffs.path("Slow").path("index").asInt()).isEqualTo(0);
    JsonNode rage = buffs.path("Rage");
    assertThat(rage.path("index").asInt()).isEqualTo(1);
    assertThat(rage.path("columns").path("SpeedMultiplier").asDouble()).isEqualTo((double) 1.35f);
    assertThat(rage.path("columns").path("DamagePerSecond").asInt()).isEqualTo(5);

    JsonNode characters = table("characters").path("rows");
    assertThat(characters.path("Gamma").path("index").asInt()).isEqualTo(2);
    // the config lists "second" before "first", so the rows of "second" come first
    assertThat(characters.path("Alpha_chaos").path("index").asInt()).isEqualTo(3);
    assertThat(characters.path("Delta").path("index").asInt()).isEqualTo(4);
    // the patch tables apply after the whole load, the data assets included
    assertThat(characters.path("Alpha_EV1").path("index").asInt()).isEqualTo(5);
    assertThat(characters.path("Delta").path("columns").path("Hitpoints").asInt()).isEqualTo(10);
  }

  @Test
  @DisplayName("an EXT row of a data asset is linked as a manifest file's, its operator resolved")
  void extRows() throws IOException {
    JsonNode alpha = table("characters").path("rows").path("Alpha_chaos").path("columns");
    assertThat(alpha.path("Hitpoints").asInt()).isEqualTo(701);
    assertThat(alpha.path("Speed").asDouble()).isEqualTo(2.0);
  }

  @Test
  @DisplayName("an action of a data asset is an action row of its ClassType's class")
  void actions() throws IOException {
    JsonNode spawn = table("actions").path("actions").path("ChaosSpawn");
    assertThat(spawn.path("class").asText()).isEqualTo("LogicActionSpawnData");
    assertThat(spawn.path("fields").path("Count").asInt()).isEqualTo(3);
  }

  @Test
  @DisplayName("a served database that does not match its hash is refused, naming it")
  void wrongDatabase() throws IOException {
    byte[] served = Files.readAllBytes(cdn.resolve(SHA).resolve(SCHEMA.file()));
    served[24] ^= 1;
    serveDatabase(served);
    assertThatThrownBy(() -> TableBuild.build(VERSION, AssetSource.folder(cdn), cache))
        .isInstanceOf(IOException.class)
        .hasMessageContaining(SCHEMA.file())
        .hasMessageContaining("hash");
  }

  @Test
  @DisplayName("a content set whose fingerprint lists no asset database is refused")
  void noDatabase() throws IOException {
    TableBuildTest.serve(cdn, TableBuildTest.files());
    assertThatThrownBy(() -> TableBuild.build(VERSION, AssetSource.folder(cdn), cache))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("lists no " + SCHEMA.file());
  }
}
