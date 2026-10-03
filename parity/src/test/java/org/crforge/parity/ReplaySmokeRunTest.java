package org.crforge.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplaySmokeRunTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir Path folder;

  private Path tablesFolder;
  private Path identity;

  private Path terminalIdentity;

  @BeforeEach
  void writeTheIdentities() throws IOException {
    tablesFolder = GameTables.configuredDirectory().orElseThrow();
    identity = identity("identity.json", SmokeSchema.V1.id(), SmokeSchema.V1.observationScope());
    terminalIdentity =
        identity("identity-v2.json", SmokeSchema.V2.id(), SmokeSchema.V2.observationScope());
  }

  private Path identity(String name, String schema, String scope) throws IOException {
    GameTables tables = GameTables.load(tablesFolder);
    Path file = folder.resolve(name);
    ObjectNode fields = MAPPER.createObjectNode();
    fields.put("schema", schema);
    fields.put("observation_scope", scope);
    fields.put("content_version", tables.version());
    fields.put("content_sha", tables.contentSha());
    MAPPER.writeValue(file.toFile(), fields);
    return file;
  }

  @Test
  void aCompletedRunWritesItsObservationsItsManifestAndItsMarker() throws IOException {
    Path out = folder.resolve("run");

    int exit = run(Scenarios.knight(), out, identity, 30);

    assertThat(exit).isEqualTo(ReplaySmokeRun.COMPLETED);
    JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
    byte[] trace = Files.readAllBytes(out.resolve("observations.jsonl"));
    assertThat(manifest.path("status").asText()).isEqualTo("completed");
    assertThat(manifest.path("engine").asText()).isEqualTo("java");
    assertThat(manifest.path("schema").asText()).isEqualTo(SmokeSchema.V1.id());
    // The exact-horizon schema keeps its fields: no stop predicate, no termination record.
    assertThat(manifest.has("executed_ticks")).isFalse();
    assertThat(manifest.has("termination")).isFalse();
    assertThat(manifest.path("ticks").asInt()).isEqualTo(30);
    assertThat(manifest.path("observations").asInt()).isEqualTo(31);
    assertThat(manifest.path("trace_sha256").asText()).isEqualTo(sha256(trace));
    assertThat(Files.readString(out.resolve("COMPLETE"))).isEqualTo(sha256(trace) + "\n");
    assertThat(manifest.path("scenario_sha256").asText())
        .isEqualTo(sha256(Files.readAllBytes(out.resolve("scenario.json"))));
    assertThat(manifest.path("data").path("files").size()).isGreaterThan(0);

    List<String> lines = Files.readAllLines(out.resolve("observations.jsonl"));
    assertThat(lines).hasSize(31);
    JsonNode first = MAPPER.readTree(lines.get(0));
    assertThat(first.path("tick").asInt()).isZero();
    assertThat(first.path("avatar_count").asInt()).isEqualTo(2);
    assertThat(first.path("entities")).hasSize(6);
    // The towers in creation order, side 0's king first, at the towers' level.
    assertThat(first.path("entities").get(0).path("id").asInt()).isEqualTo(5000000);
    assertThat(first.path("entities").get(0).path("row").asText()).isEqualTo("KingTower");
    assertThat(first.path("entities").get(0).path("hp").asInt()).isEqualTo(2400);
    assertThat(first.path("entities").get(4).path("row").asText()).isEqualTo("PrincessTower");
    assertThat(first.path("entities").get(4).path("side").asInt()).isEqualTo(1);
    assertThat(first.path("sides").get(0).path("elixir").asInt()).isEqualTo(60000);
    // The recorded battle's opening hand and random state: the players' data draw first.
    assertThat(first.path("sides").get(0).path("hand").toString()).isEqualTo("[7,1,0,2]");
    assertThat(first.path("sides").get(1).path("queue").toString()).isEqualTo("[0,1,4,2]");
    assertThat(first.path("rng").asLong()).isEqualTo(4153772180L);
    assertThat(MAPPER.readTree(lines.get(30)).path("tick").asInt()).isEqualTo(30);
    assertThat(first.has("stopped")).isFalse();
  }

  @Test
  void anUnknownSchemaOrAScopeOfAnotherSchemaIsAnInvalidRun() throws IOException {
    Path unknown = identity("unknown.json", "test-schema", SmokeSchema.V1.observationScope());
    Path crossed = identity("crossed.json", SmokeSchema.V2.id(), SmokeSchema.V1.observationScope());

    assertThat(run(Scenarios.knight(), folder.resolve("unknown"), unknown, 30))
        .isEqualTo(ReplaySmokeRun.INVALID);
    assertThat(run(Scenarios.knight(), folder.resolve("crossed"), crossed, 30))
        .isEqualTo(ReplaySmokeRun.INVALID);
    assertThat(folder.resolve("unknown").resolve("COMPLETE")).doesNotExist();
    assertThat(folder.resolve("crossed").resolve("COMPLETE")).doesNotExist();
  }

  @Test
  void aTerminalRunThatReachesItsHorizonStepsEveryTickAndSaysSo() throws IOException {
    Path out = folder.resolve("run");

    int exit = run(Scenarios.knight(), out, terminalIdentity, 30);

    assertThat(exit).isEqualTo(ReplaySmokeRun.COMPLETED);
    JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
    assertThat(manifest.path("schema").asText()).isEqualTo(SmokeSchema.V2.id());
    assertThat(manifest.path("ticks").asInt()).isEqualTo(30);
    assertThat(manifest.path("executed_ticks").asInt()).isEqualTo(30);
    assertThat(manifest.path("observations").asInt()).isEqualTo(31);
    assertThat(manifest.path("termination").path("reason").asText()).isEqualTo("horizon");
    assertThat(manifest.path("termination").path("tick").asInt()).isEqualTo(30);
    for (String line : Files.readAllLines(out.resolve("observations.jsonl"))) {
      assertThat(MAPPER.readTree(line).path("stopped").isBoolean()).isTrue();
      assertThat(MAPPER.readTree(line).path("stopped").asBoolean()).isFalse();
    }
  }

  @Test
  void aTerminalRunStopsAtTheBattlesOwnStopAndKeepsTheEndDelay() throws IOException {
    ObjectNode idle = Scenarios.knight();
    idle.putArray("cmd");
    Path out = folder.resolve("run");

    int exit = run(idle, out, terminalIdentity, 6600);

    assertThat(exit).isEqualTo(ReplaySmokeRun.COMPLETED);
    JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
    List<String> lines = Files.readAllLines(out.resolve("observations.jsonl"));
    int executed = manifest.path("executed_ticks").asInt();
    assertThat(executed).isLessThan(6600);
    assertThat(lines).hasSize(executed + 1);
    assertThat(manifest.path("observations").asInt()).isEqualTo(executed + 1);
    assertThat(manifest.path("termination").path("reason").asText()).isEqualTo("battle_stopped");
    assertThat(manifest.path("termination").path("tick").asInt()).isEqualTo(executed);
    // Only the last observation is stopped; the match was ended for a while before it.
    int firstEnded = -1;
    for (int i = 0; i < lines.size(); i++) {
      JsonNode observation = MAPPER.readTree(lines.get(i));
      assertThat(observation.path("tick").asInt()).isEqualTo(i);
      assertThat(observation.path("stopped").asBoolean()).isEqualTo(i == executed);
      if (firstEnded < 0 && observation.path("ended").asBoolean()) {
        firstEnded = i;
      }
    }
    assertThat(firstEnded).isPositive().isLessThan(executed);
    // The exact-horizon schema cannot represent that battle over the same horizon.
    assertThat(run(idle, folder.resolve("exact"), identity, 6600))
        .isEqualTo(ReplaySmokeRun.INVALID);
  }

  @Test
  void aRunRepeatsByteForByteFromAFreshBattle() throws IOException {
    run(Scenarios.knight(), folder.resolve("one"), identity, 260);
    run(Scenarios.knight(), folder.resolve("two"), identity, 260);

    assertThat(Files.readAllBytes(folder.resolve("one").resolve("observations.jsonl")))
        .isEqualTo(Files.readAllBytes(folder.resolve("two").resolve("observations.jsonl")));
  }

  @Test
  void thePlayRunsOnItsRunTickAndItsUnitIsInTheNextObservation() throws IOException {
    Path out = folder.resolve("run");

    run(Scenarios.knight(), out, identity, 230);

    List<String> lines = Files.readAllLines(out.resolve("observations.jsonl"));
    assertThat(MAPPER.readTree(lines.get(220)).path("entities")).hasSize(6);
    JsonNode after = MAPPER.readTree(lines.get(221));
    assertThat(after.path("entities")).hasSize(7);
    JsonNode knight = after.path("entities").get(6);
    assertThat(knight.path("id").asInt()).isEqualTo(5000006);
    assertThat(knight.path("row").asText()).isEqualTo("Knight");
    assertThat(knight.path("side").asInt()).isZero();
    JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
    assertThat(manifest.path("plays_run").get(0).path("tick").asInt()).isEqualTo(220);
    assertThat(manifest.path("plays_run").get(0).path("placed").asBoolean()).isTrue();
  }

  @Test
  void anUnsupportedScenarioWritesNoObservationAndNoMarker() throws IOException {
    ObjectNode scenario = Scenarios.knight();
    ((ObjectNode) scenario.path("battle").path("deck0").path("sc").get(0)).put("d", 159000004);
    Path out = folder.resolve("run");

    int exit = run(scenario, out, identity, 30);

    assertThat(exit).isEqualTo(ReplaySmokeRun.UNSUPPORTED);
    JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
    assertThat(manifest.path("status").asText()).isEqualTo("unsupported");
    assertThat(manifest.path("unsupported").path("input").asText())
        .isEqualTo("battle.deck0.sc[0].d=159000004");
    assertThat(out.resolve("COMPLETE")).doesNotExist();
    assertThat(out.resolve("observations.jsonl")).doesNotExist();
  }

  @Test
  void tablesOfAnotherContentAreAnInvalidRun() throws IOException {
    ObjectNode fields = (ObjectNode) MAPPER.readTree(identity.toFile());
    fields.put("content_sha", "0000000000000000000000000000000000000000");
    Path other = folder.resolve("other-identity.json");
    MAPPER.writeValue(other.toFile(), fields);
    Path out = folder.resolve("run");

    int exit = run(Scenarios.knight(), out, other, 30);

    assertThat(exit).isEqualTo(ReplaySmokeRun.INVALID);
    JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
    assertThat(manifest.path("status").asText()).isEqualTo("invalid");
    assertThat(out.resolve("COMPLETE")).doesNotExist();
  }

  @Test
  void anExistingRunDirectoryIsNeverWrittenInto() throws IOException {
    Path out = folder.resolve("run");
    Files.createDirectory(out);

    int exit = run(Scenarios.knight(), out, identity, 30);

    assertThat(exit).isEqualTo(ReplaySmokeRun.INVALID);
    assertThat(out.resolve("manifest.json")).doesNotExist();
  }

  private int run(ObjectNode scenario, Path out, Path identityFile, int ticks) throws IOException {
    Path file = folder.resolve("scenario-" + out.getFileName() + ".json");
    MAPPER.writeValue(file.toFile(), scenario);
    return ReplaySmokeRun.run(
        new String[] {
          "--scenario", file.toString(),
          "--tables", tablesFolder.toString(),
          "--identity", identityFile.toString(),
          "--ticks", Integer.toString(ticks),
          "--out", out.toString()
        });
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
