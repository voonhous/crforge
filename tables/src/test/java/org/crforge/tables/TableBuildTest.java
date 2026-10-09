package org.crforge.tables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.LZMAOutputStream;

/**
 * Building tables end to end on a small hand-written content set: its files framed as the game
 * serves them, in a folder laid out as the asset CDN, read with the synthetic client schema {@code
 * test-1}. None of the values is the game's.
 */
class TableBuildTest {

  private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
  private static final DataVersion VERSION = new DataVersion("0.0.1", "test-1", SHA);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<List<Object>> LIST = new TypeReference<>() {};

  /** The set's files, by path. */
  static Map<String, String> files() {
    Map<String, String> files = new LinkedHashMap<>();
    files.put(
        "csv_logic/global_ids_oldformat.csv",
        "\"ID\",\"Name\",\"Type\"\n\"int\",\"string\",\"string\"\n34000001,\"Alpha\",\"Character\"\n");
    files.put(
        "csv_logic/characters.csv",
        "Name,Hitpoints,Speed,Tribe,OnDeathAction\n"
            + "String,int,float,String,String\n"
            + "Alpha,700,1.5,Knights,\n"
            + ",,,Humans,\n"
            + "Beta,,0.1,,DeathSpawn\n");
    files.put("csv_logic/buildings.csv", "Name,Hitpoints\r\nString,int\r\nTower,2000\r\n");
    files.put(
        "csv_logic/character_buffs.toml",
        "[Slow]\nSpeedMultiplier = 0.65\nDamagePerSecond = 2147483647\n");
    files.put(
        "csv_logic/actions.toml",
        "[DeathSpawn]\nClassType = \"ActionSpawn\"\nCount = 2\n"
            + "NextAction = { ClassType = \"ActionSpawn\", Count = 1 }\n");
    files.put(
        "csv_logic/characters_evo.toml",
        "[Alpha_EV1]\nBase = \"Alpha\"\nHitpoints = [\"%\", 110]\n");
    files.put("data_manifest.toml", "[Gamma]\n Path='csv_logic/characters/gamma.toml'\n");
    files.put(
        "csv_logic/characters/gamma.toml",
        "[EXT.Gamma]\nBase = \"CHARACTER.Alpha\"\nHitpoints = [\"+\", 50]\n\n"
            + "[CHARACTER.Alpha]\nSpeed = 2.0\n");
    return files;
  }

  @TempDir Path temp;
  private Path cdn;
  private AssetCache cache;

  @BeforeEach
  void serve() throws IOException {
    cdn = temp.resolve("cdn");
    cache = new AssetCache(temp.resolve("cache"));
    serve(cdn, files());
  }

  /** Writes a content set laid out as the CDN serves it: its fingerprint and its framed files. */
  static void serve(Path cdn, Map<String, String> files) throws IOException {
    List<Map<String, String>> listed = new ArrayList<>();
    for (Map.Entry<String, String> f : files.entrySet()) {
      byte[] served = frame(f.getValue().getBytes(StandardCharsets.UTF_8));
      Path path = cdn.resolve(SHA).resolve(f.getKey());
      Files.createDirectories(path.getParent());
      Files.write(path, served);
      listed.add(Map.of("file", f.getKey(), "sha", AssetCache.sha1(served)));
    }
    // a file of the set the table loaders do not read is listed but never fetched
    listed.add(Map.of("file", "sc/ui.sc", "sha", "0000000000000000000000000000000000000000"));
    Map<String, Object> fingerprint = new LinkedHashMap<>();
    fingerprint.put("files", listed);
    fingerprint.put("sha", SHA);
    fingerprint.put("version", "9.9.9");
    Files.write(
        cdn.resolve(SHA).resolve("fingerprint.json"), MAPPER.writeValueAsBytes(fingerprint));
  }

  /** A text framed as the game serves its files: LZMA properties, a 4-byte size, the stream. */
  static byte[] frame(byte[] text) throws IOException {
    ByteArrayOutputStream lzma = new ByteArrayOutputStream();
    try (LZMAOutputStream out = new LZMAOutputStream(lzma, new LZMA2Options(), text.length)) {
      out.write(text);
    }
    byte[] alone = lzma.toByteArray(); // 5 bytes of properties, an 8-byte size, the stream
    ByteBuffer framed = ByteBuffer.allocate(alone.length - 4).order(ByteOrder.LITTLE_ENDIAN);
    framed.put(alone, 0, 5).putInt(text.length).put(alone, 13, alone.length - 13);
    return framed.array();
  }

  private JsonNode table(Path folder, String name) throws IOException {
    return MAPPER.readTree(folder.resolve(name + ".json").toFile());
  }

  @Test
  @DisplayName("a CSV row is typed by its columns' declared types, over its continuation lines")
  void csvRows() throws IOException {
    JsonNode characters =
        table(TableBuild.build(VERSION, AssetSource.folder(cdn), cache), "characters");
    assertThat(characters.path("version").asText()).isEqualTo("0.0.1");
    assertThat(characters.path("content_sha").asText()).isEqualTo(SHA);
    assertThat(characters.path("id").asInt()).isEqualTo(34);
    JsonNode beta = characters.path("rows").path("Beta");
    assertThat(beta.path("index").asInt()).isEqualTo(1);
    assertThat(beta.path("class").asText()).isEqualTo("LogicCharacterData");
    assertThat(beta.path("columns").path("Hitpoints").isNull()).isTrue();
    assertThat(beta.path("columns").path("Speed").asDouble()).isEqualTo((double) 0.1f);
    assertThat(beta.path("columns").path("Tribe").isArray()).isTrue();
    assertThat(beta.path("columns").path("Tribe")).isEmpty();
    JsonNode alpha = characters.path("rows").path("Alpha").path("columns");
    assertThat(MAPPER.convertValue(alpha.path("Tribe"), LIST)).containsExactly("Knights", "Humans");
    assertThat(
            table(cache.tables("0.0.1"), "buildings")
                .path("rows")
                .path("Tower")
                .path("columns")
                .path("Hitpoints")
                .asInt())
        .isEqualTo(2000);
  }

  @Test
  @DisplayName("a row's global id is its fixed id, else the hash of its type and name")
  void globalIds() throws IOException {
    JsonNode rows =
        table(TableBuild.build(VERSION, AssetSource.folder(cdn), cache), "characters").path("rows");
    assertThat(rows.path("Alpha").path("global_id").asLong()).isEqualTo(34000001L);
    assertThat(rows.path("Beta").path("global_id").asLong())
        .isEqualTo(TableExport.fnv1a32("CharacterBeta"));
  }

  @Test
  @DisplayName("an EXT row inherits its base as linked, with its operator resolved against it")
  void extInheritance() throws IOException {
    JsonNode rows =
        table(TableBuild.build(VERSION, AssetSource.folder(cdn), cache), "characters").path("rows");
    JsonNode gamma = rows.path("Gamma").path("columns");
    assertThat(rows.path("Gamma").path("index").asInt()).isEqualTo(2);
    assertThat(gamma.path("Hitpoints").asInt()).isEqualTo(750);
    assertThat(gamma.path("Speed").asDouble()).isEqualTo(2.0);
    assertThat(gamma.path("Name").asText()).isEqualTo("Alpha");
    assertThat(rows.path("Alpha").path("columns").path("Speed").asDouble()).isEqualTo(2.0);
  }

  @Test
  @DisplayName("a patch row is its base with the patch on top, its operator resolved")
  void patchRows() throws IOException {
    JsonNode rows =
        table(TableBuild.build(VERSION, AssetSource.folder(cdn), cache), "characters").path("rows");
    assertThat(rows.path("Alpha_EV1").path("index").asInt()).isEqualTo(3);
    assertThat(rows.path("Alpha_EV1").path("columns").path("Hitpoints").asInt()).isEqualTo(770);
  }

  @Test
  @DisplayName(
      "a TOML value is read with its loader's kind: float32, and the int default as absent")
  void tomlKinds() throws IOException {
    JsonNode slow =
        table(TableBuild.build(VERSION, AssetSource.folder(cdn), cache), "character_buffs")
            .path("rows")
            .path("Slow")
            .path("columns");
    assertThat(slow.path("SpeedMultiplier").asDouble()).isEqualTo((double) 0.65f);
    assertThat(slow.path("DamagePerSecond").isNull()).isTrue();
  }

  @Test
  @DisplayName("an inline action becomes an action row named after its row and column")
  void embeddedActions() throws IOException {
    JsonNode graph = table(TableBuild.build(VERSION, AssetSource.folder(cdn), cache), "actions");
    JsonNode actions = graph.path("actions");
    assertThat(actions.path("DeathSpawn").path("class").asText()).isEqualTo("LogicActionSpawnData");
    assertThat(actions.path("DeathSpawn").path("fields").path("NextAction").path("action").asText())
        .isEqualTo("DeathSpawn_NextAction");
    assertThat(actions.path("DeathSpawn_NextAction").path("fields").path("Count").asInt())
        .isEqualTo(1);
    assertThat(MAPPER.convertValue(graph.path("references"), LIST))
        .containsExactly(
            Arrays.asList("34", "Beta", "OnDeathAction", null, "DeathSpawn", "name"),
            Arrays.asList(
                "133", "DeathSpawn", "NextAction", null, "DeathSpawn_NextAction", "inline"));
  }

  @Test
  @DisplayName("a built folder is used again, and a cached file is not fetched again")
  void cache() throws IOException {
    Path built = TableBuild.build(VERSION, AssetSource.folder(cdn), cache);
    Path stamp = built.resolve(TableBuild.STAMP);
    long stamped = Files.getLastModifiedTime(stamp).toMillis();
    assertThat(TableBuild.build(VERSION, AssetSource.folder(cdn), cache)).isEqualTo(built);
    assertThat(Files.getLastModifiedTime(stamp).toMillis()).isEqualTo(stamped);

    // with the source gone, the tables are built again from the cached files alone
    deleteTree(cdn);
    deleteTree(built);
    Path rebuilt = TableBuild.build(VERSION, AssetSource.folder(cdn), cache);
    assertThat(table(rebuilt, "characters").path("rows").path("Alpha").path("global_id").asLong())
        .isEqualTo(34000001L);
  }

  @Test
  @DisplayName("a file that does not match its fingerprint is refused, naming it")
  void wrongFile() throws IOException {
    Files.write(
        cdn.resolve(SHA).resolve("csv_logic/buildings.csv"),
        frame("Name,Hitpoints\nString,int\nTower,1\n".getBytes(StandardCharsets.UTF_8)));
    assertThatThrownBy(() -> TableBuild.build(VERSION, AssetSource.folder(cdn), cache))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("csv_logic/buildings.csv");
  }

  @Test
  @DisplayName("a fingerprint of another content set is refused")
  void wrongFingerprint() throws IOException {
    DataVersion other = new DataVersion("0.0.2", "test-1", "f".repeat(40));
    Files.createDirectories(cdn.resolve(other.contentSha()));
    Files.copy(
        cdn.resolve(SHA).resolve("fingerprint.json"),
        cdn.resolve(other.contentSha()).resolve("fingerprint.json"));
    assertThatThrownBy(() -> TableBuild.build(other, AssetSource.folder(cdn), cache))
        .isInstanceOf(IOException.class)
        .hasMessageContaining(SHA);
  }

  private static void deleteTree(Path root) throws IOException {
    try (var walk = Files.walk(root)) {
      for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(p);
      }
    }
  }
}
