package org.crforge.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.unit.Standard1v1Battle;
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
  void aCannoneerTowerSelectionBuildsItsSidesTowersAndTheyFireAtTheirLevel() throws IOException {
    ObjectNode scenario = Scenarios.knight();
    ((ObjectNode) scenario.path("battle").path("deck1").path("sc").get(0)).put("d", 159000001);
    Path out = folder.resolve("run");

    int exit = run(scenario, out, identity, 300);

    assertThat(exit).isEqualTo(ReplaySmokeRun.COMPLETED);
    List<String> lines = Files.readAllLines(out.resolve("observations.jsonl"));
    JsonNode first = MAPPER.readTree(lines.get(0)).path("entities");
    // Side 0 keeps the princess towers; side 1 stands its king and two Cannoneer rows in the
    // princess slots, five levels above their first.
    assertThat(first.get(1).path("row").asText()).isEqualTo("PrincessTower");
    assertThat(first.get(1).path("hp").asInt()).isEqualTo(1400);
    assertThat(first.get(3).path("row").asText()).isEqualTo("KingTower");
    assertThat(first.get(3).path("hp").asInt()).isEqualTo(2400);
    for (int i = 4; i <= 5; i++) {
      JsonNode cannoneer = first.get(i);
      assertThat(cannoneer.path("row").asText()).isEqualTo("Cannoneer");
      assertThat(cannoneer.path("side").asInt()).isEqualTo(1);
      assertThat(cannoneer.path("y").asInt()).isEqualTo(25500);
      assertThat(cannoneer.path("hp").asInt()).isEqualTo(1740);
    }
    assertThat(first.get(4).path("x").asInt()).isEqualTo(3500);
    assertThat(first.get(5).path("x").asInt()).isEqualTo(14500);
    // Side 0's Knight walks up the left lane into the low Cannoneer's range: its first shot takes
    // 200, the projectile's 125 at the tower's level, on tick 299.
    int firstHit = -1;
    for (String line : lines) {
      JsonNode observation = MAPPER.readTree(line);
      for (JsonNode entity : observation.path("entities")) {
        if (entity.path("row").asText().equals("Knight") && entity.path("hp").asInt() < 690) {
          assertThat(entity.path("hp").asInt()).isEqualTo(490);
          firstHit = observation.path("tick").asInt();
          break;
        }
      }
      if (firstHit >= 0) {
        break;
      }
    }
    assertThat(firstHit).isEqualTo(299);
  }

  @Test
  void aTowerSelectionLevelRaisesItsSidesPrincessTowersButNotItsKing() throws IOException {
    ObjectNode scenario = Scenarios.knight();
    // Side 0 selects the princess towers one level up, side 1 eight levels up.
    ((ObjectNode) scenario.path("battle").path("deck0").path("sc").get(0)).put("l", 1);
    ((ObjectNode) scenario.path("battle").path("deck1").path("sc").get(0)).put("l", 8);
    Path out = folder.resolve("run");

    int exit = run(scenario, out, identity, 330);

    assertThat(exit).isEqualTo(ReplaySmokeRun.COMPLETED);
    List<String> lines = Files.readAllLines(out.resolve("observations.jsonl"));
    JsonNode first = MAPPER.readTree(lines.get(0)).path("entities");
    // Each king stands at the avatar's level (exp level 1: the first level), whatever its side's
    // selection level is; the princess towers stand at their own side's selection level.
    for (int i : new int[] {0, 3}) {
      assertThat(first.get(i).path("row").asText()).isEqualTo("KingTower");
      assertThat(first.get(i).path("hp").asInt()).isEqualTo(2400);
    }
    for (int i : new int[] {1, 2}) {
      assertThat(first.get(i).path("row").asText()).isEqualTo("PrincessTower");
      assertThat(first.get(i).path("hp").asInt()).isEqualTo(1512);
    }
    for (int i : new int[] {4, 5}) {
      assertThat(first.get(i).path("row").asText()).isEqualTo("PrincessTower");
      assertThat(first.get(i).path("hp").asInt()).isEqualTo(2534);
    }
    // Side 0's Knight walks up the left lane into side 1's low princess tower's range: its first
    // arrow takes 90, the projectile's 50 at the tower's level.
    int firstHp = -1;
    for (String line : lines) {
      for (JsonNode entity : MAPPER.readTree(line).path("entities")) {
        if (entity.path("row").asText().equals("Knight") && entity.path("hp").asInt() < 690) {
          firstHp = entity.path("hp").asInt();
          break;
        }
      }
      if (firstHp >= 0) {
        break;
      }
    }
    assertThat(firstHp).isEqualTo(600);
  }

  @Test
  void aRoyalChefTowerSelectionCooksAPancakeThatRaisesAFriendlyTroopsLevel() throws IOException {
    Path out = folder.resolve("run");

    int exit = run(Scenarios.knightAgainstTheRoyalChef(), out, identity, 700);

    assertThat(exit).isEqualTo(ReplaySmokeRun.COMPLETED);
    List<String> lines = Files.readAllLines(out.resolve("observations.jsonl"));
    JsonNode first = MAPPER.readTree(lines.get(0)).path("entities");
    // Side 1 stands the Royal Chef's king row and two ChefTower rows, eight levels above their
    // first.
    assertThat(first.get(3).path("row").asText()).isEqualTo("ChefTowerKing");
    assertThat(first.get(3).path("hp").asInt()).isEqualTo(2400);
    for (int i = 4; i <= 5; i++) {
      assertThat(first.get(i).path("row").asText()).isEqualTo("ChefTower");
      assertThat(first.get(i).path("hp").asInt()).isEqualTo(2244);
    }
    // The cooking starts 7 s in and fills at 40 a step while the low tower shoots side 0's Knight
    // and 50 a step while both towers idle; the full bar throws a pancake from the tower nearer
    // side 1's Giant, 200 toward it, on tick 638. Its landing raises the Giant one level: its hit
    // points and its maximum from 1875 to 2061 on tick 644.
    JsonNode pancake = null;
    int pancakeTick = -1;
    int levelUpTick = -1;
    for (String line : lines) {
      JsonNode observation = MAPPER.readTree(line);
      int tick = observation.path("tick").asInt();
      for (JsonNode entity : observation.path("entities")) {
        if (pancake == null
            && tick > 450
            && !entity.has("row")
            && entity.path("side").asInt() == 1) {
          pancake = entity;
          pancakeTick = tick;
        }
        if (levelUpTick < 0
            && entity.path("row").asText().equals("Giant")
            && entity.path("max_hp").asInt() != 1875) {
          assertThat(entity.path("max_hp").asInt()).isEqualTo(2061);
          assertThat(entity.path("hp").asInt()).isEqualTo(2061);
          levelUpTick = tick;
        }
      }
    }
    assertThat(pancakeTick).isEqualTo(638);
    assertThat(pancake.path("x").asInt()).isEqualTo(3440);
    assertThat(pancake.path("y").asInt()).isEqualTo(25310);
    assertThat(levelUpTick).isEqualTo(644);
  }

  @Test
  void aDaggerDuchessSpendsItsEightChargesThenAttacksOnlyAsItRecharges() throws IOException {
    Path out = folder.resolve("run");

    int exit = run(Scenarios.giantVsDuchessTower(), out, identity, 820);

    assertThat(exit).isEqualTo(ReplaySmokeRun.COMPLETED);
    List<String> lines = Files.readAllLines(out.resolve("observations.jsonl"));
    JsonNode first = MAPPER.readTree(lines.get(0)).path("entities");
    for (int i = 4; i <= 5; i++) {
      assertThat(first.get(i).path("row").asText()).isEqualTo("DaggerDuchess");
      assertThat(first.get(i).path("hp").asInt()).isEqualTo(2298);
    }
    // The low Duchess's knives take 89 off the Giant. Its first seven hits come at the full pace,
    // every nine or ten ticks; the eighth, its last charge's, at the slower pace of its entry 2;
    // with no charge left it cannot attack until a charge comes back, 900 ms later, and then
    // throws it at the depleted entry's pace: one hit every 31 ticks until the Giant dies.
    List<Integer> hits = new ArrayList<>();
    int hp = -1;
    for (String line : lines) {
      JsonNode observation = MAPPER.readTree(line);
      for (JsonNode entity : observation.path("entities")) {
        if (entity.path("row").asText().equals("Giant")) {
          int now = entity.path("hp").asInt();
          if (hp >= 0 && now != hp) {
            assertThat(hp - now).isEqualTo(89);
            hits.add(observation.path("tick").asInt());
          }
          hp = now;
        }
      }
    }
    assertThat(hits)
        .containsExactly(
            298, 307, 317, 326, 336, 345, 355, 369, 399, 429, 460, 491, 522, 553, 584, 615, 646,
            677, 708, 739, 770);
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
  void anInProcessRunGivesTheTraceAndTheOutcomeOfARunFromTheCommandLine() throws IOException {
    GameTables tables = GameTables.load(tablesFolder);
    ObjectNode idle = Scenarios.knight();
    idle.putArray("cmd");
    run(idle, folder.resolve("terminal"), terminalIdentity, 6600);
    ObjectNode unsupported = Scenarios.knight();
    ((ObjectNode) unsupported.path("battle").path("deck0").path("sc").get(0)).put("d", 159000003);
    run(unsupported, folder.resolve("unsupported"), identity, 30);
    run(idle, folder.resolve("invalid"), identity, 6600);

    ReplaySmokeRun.InProcessRun terminal =
        ReplaySmokeRun.runInProcess(SmokeSchema.V2, 6600, MAPPER.writeValueAsBytes(idle), tables);
    ReplaySmokeRun.InProcessRun refused =
        ReplaySmokeRun.runInProcess(
            SmokeSchema.V1, 30, MAPPER.writeValueAsBytes(unsupported), tables);
    ReplaySmokeRun.InProcessRun stopped =
        ReplaySmokeRun.runInProcess(SmokeSchema.V1, 6600, MAPPER.writeValueAsBytes(idle), tables);

    // The same trace, digest, steps and termination as the run written to disk.
    JsonNode manifest = MAPPER.readTree(folder.resolve("terminal/manifest.json").toFile());
    assertThat(terminal.status()).isEqualTo("completed");
    assertThat(terminal.trace())
        .isEqualTo(Files.readAllBytes(folder.resolve("terminal/observations.jsonl")));
    JsonNode inProcess = MAPPER.valueToTree(terminal.manifest());
    for (String field :
        List.of(
            "trace_sha256",
            "observations",
            "executed_ticks",
            "termination",
            "scenario_sha256",
            "schema",
            "ticks")) {
      assertThat(inProcess.get(field)).as(field).isEqualTo(manifest.get(field));
    }
    // The same refusal, and the same error.
    JsonNode refusedManifest =
        MAPPER.readTree(folder.resolve("unsupported/manifest.json").toFile());
    assertThat(refused.status()).isEqualTo("unsupported");
    assertThat(MAPPER.valueToTree(refused.manifest()).get("unsupported"))
        .isEqualTo(refusedManifest.get("unsupported"));
    assertThat(refused.trace()).isEmpty();
    JsonNode invalidManifest = MAPPER.readTree(folder.resolve("invalid/manifest.json").toFile());
    assertThat(stopped.status()).isEqualTo("invalid");
    assertThat(stopped.manifest().get("error")).isEqualTo(invalidManifest.path("error").asText());
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
  void anEvolutionSlotsCardIsPlayedPlainTwiceAndEvolvedOnItsThirdPlay() throws IOException {
    Path out = folder.resolve("run");

    int exit = run(Scenarios.knightEvolvedThirdPlay(), out, terminalIdentity, 1430);

    assertThat(exit).isEqualTo(ReplaySmokeRun.COMPLETED);
    JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
    assertThat(manifest.path("plays_run")).hasSize(11);
    for (JsonNode play : manifest.path("plays_run")) {
      assertThat(play.path("placed").asBoolean()).as(play.toString()).isTrue();
    }
    // Each Knight play's unit is in the observation after its run tick, as the row its item casts.
    List<String> lines = Files.readAllLines(out.resolve("observations.jsonl"));
    assertThat(rowOfNewestUnit(lines.get(221))).isEqualTo("Knight");
    assertThat(rowOfNewestUnit(lines.get(631))).isEqualTo("Knight");
    assertThat(rowOfNewestUnit(lines.get(1417))).isEqualTo("Knight_EV1");
    assertThat(manifest.has("items_not_built")).isFalse();
  }

  @Test
  void anAbilityCommandRunsOnItsRunTickPaysAndCastsTheNamedChampionsAbility() throws IOException {
    ObjectNode without = Scenarios.archerQueenAbility();
    ((ArrayNode) without.path("cmd")).remove(1);
    run(without, folder.resolve("without"), terminalIdentity, 360);
    Path out = folder.resolve("run");

    int exit = run(Scenarios.archerQueenAbility(), out, terminalIdentity, 360);

    assertThat(exit).isEqualTo(ReplaySmokeRun.COMPLETED);
    List<String> lines = Files.readAllLines(out.resolve("observations.jsonl"));
    List<String> plain =
        Files.readAllLines(folder.resolve("without").resolve("observations.jsonl"));
    // Nothing differs before the command's run tick, 350.
    assertThat(lines.subList(0, 351)).isEqualTo(plain.subList(0, 351));
    // In the observation after it the Archer Queen's ability cost, 1 elixir, is spent and she
    // casts (state 10) where she shot (state 2).
    JsonNode after = MAPPER.readTree(lines.get(351));
    JsonNode plainAfter = MAPPER.readTree(plain.get(351));
    assertThat(after.path("sides").get(0).path("elixir").asInt())
        .isEqualTo(plainAfter.path("sides").get(0).path("elixir").asInt() - 10000);
    assertThat(entity(after, 5000006).path("state").asInt()).isEqualTo(10);
    assertThat(entity(plainAfter, 5000006).path("state").asInt()).isEqualTo(2);
    JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
    JsonNode used = manifest.path("abilities_run").get(0);
    assertThat(used.path("name").asText()).isEqualTo("cmd1");
    assertThat(used.path("tick").asInt()).isEqualTo(350);
    assertThat(used.path("code").asInt()).isZero();
  }

  @Test
  void anAbilityCommandNamingNoLiveUnitIsRefusedAndChangesNothing() throws IOException {
    ObjectNode without = Scenarios.archerQueenAbility();
    ((ArrayNode) without.path("cmd")).remove(1);
    run(without, folder.resolve("without"), terminalIdentity, 360);
    ObjectNode scenario = Scenarios.archerQueenAbility();
    ((ObjectNode) scenario.path("cmd").get(1).path("c")).put("cgid", 5000099);
    Path out = folder.resolve("run");

    int exit = run(scenario, out, terminalIdentity, 360);

    assertThat(exit).isEqualTo(ReplaySmokeRun.COMPLETED);
    assertThat(Files.readAllBytes(out.resolve("observations.jsonl")))
        .isEqualTo(Files.readAllBytes(folder.resolve("without").resolve("observations.jsonl")));
    JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
    // Refused: no champion found (0x3ee).
    assertThat(manifest.path("abilities_run").get(0).path("code").asInt()).isEqualTo(0x3ee);
  }

  @Test
  void anEvolutionSlotsPlayThatNeverRunsIsListedAsNotBuilt() throws IOException {
    Path out = folder.resolve("run");

    // The horizon ends before the third Knight play's run tick, 1416.
    int exit = run(Scenarios.knightEvolvedThirdPlay(), out, terminalIdentity, 1400);

    assertThat(exit).isEqualTo(ReplaySmokeRun.COMPLETED);
    JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
    assertThat(manifest.path("plays_run")).hasSize(10);
    assertThat(manifest.path("items_not_built").toString()).isEqualTo("[\"cmd[10]\"]");
  }

  @Test
  void aPlayWhoseItemIsNotTheItemTheSimulatorBuildsIsUnsupported() throws IOException {
    ObjectNode scenario = Scenarios.knightEvolvedThirdPlay();
    // The first play claims the evolved item of the third: field 1 at the count plus 1 of 3.
    ((ObjectNode) scenario.path("cmd").get(0).path("c").path("sel")).put("pd", 0x30480181);
    Path out = folder.resolve("run");

    int exit = run(scenario, out, terminalIdentity, 1430);

    assertThat(exit).isEqualTo(ReplaySmokeRun.UNSUPPORTED);
    JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
    assertThat(manifest.path("status").asText()).isEqualTo("unsupported");
    assertThat(manifest.path("unsupported").path("feature").asText()).contains("packed item");
    assertThat(manifest.path("unsupported").path("input").asText())
        .startsWith("cmd[0].c.sel.pd=" + 0x30480181);
    assertThat(out.resolve("COMPLETE")).doesNotExist();
  }

  @Test
  void aPlayWhoseCountIsNotTheSimulatorsIsUnsupported() throws IOException {
    ObjectNode scenario = Scenarios.knightEvolvedThirdPlay();
    // The second Knight play repeats the first's count plus 1, 1, where the simulator counts 2.
    ((ObjectNode) scenario.path("cmd").get(5).path("c").path("sel")).put("pd", 0x30480080);
    Path out = folder.resolve("run");

    int exit = run(scenario, out, terminalIdentity, 1430);

    assertThat(exit).isEqualTo(ReplaySmokeRun.UNSUPPORTED);
    JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
    assertThat(manifest.path("unsupported").path("input").asText())
        .startsWith("cmd[5].c.sel.pd=" + 0x30480080);
    assertThat(out.resolve("COMPLETE")).doesNotExist();
  }

  @Test
  void aMirrorPlayRepeatsItsSidesLastCardOneLevelAboveTheMirrorsForItsItem() throws IOException {
    Path out = folder.resolve("run");

    int exit = run(Scenarios.knightThenMirror(), out, terminalIdentity, 420);

    assertThat(exit).isEqualTo(ReplaySmokeRun.COMPLETED);
    JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
    JsonNode mirror = manifest.path("plays_run").get(4);
    assertThat(mirror.path("name").asText()).isEqualTo("cmd4");
    assertThat(mirror.path("tick").asInt()).isEqualTo(410);
    assertThat(mirror.path("placed").asBoolean()).isTrue();
    assertThat(mirror.path("units").asInt()).isEqualTo(1);
    List<String> lines = Files.readAllLines(out.resolve("observations.jsonl"));
    // The Knight again, at the Mirror's level field plus 1, field 6: 1214 hit points, where the
    // Knight played at its own level index 0 has 690.
    JsonNode repeated = newestUnit(MAPPER.readTree(lines.get(411)));
    assertThat(repeated.path("row").asText()).isEqualTo("Knight");
    assertThat(repeated.path("side").asInt()).isZero();
    assertThat(repeated.path("hp").asInt()).isEqualTo(1214);
    // The Mirror costs 1 more than the Knight: 4 elixir, less one step's regeneration.
    int before = MAPPER.readTree(lines.get(410)).path("sides").get(0).path("elixir").asInt();
    int after = MAPPER.readTree(lines.get(411)).path("sides").get(0).path("elixir").asInt();
    assertThat(before - after).isBetween(40000 - 200, 40000);
  }

  @Test
  void aMirrorPlayNamingAnotherRepeatedCardThanTheSimulatorsIsUnsupported() throws IOException {
    ObjectNode scenario = Scenarios.knightThenMirror();
    // The Archer was played before the Knight: the Mirror repeats the Knight, the last card.
    ((ObjectNode) scenario.path("cmd").get(4).path("c").path("sel")).put("fs", Scenarios.ARCHER);
    Path out = folder.resolve("run");

    int exit = run(scenario, out, terminalIdentity, 420);

    assertThat(exit).isEqualTo(ReplaySmokeRun.UNSUPPORTED);
    JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
    assertThat(manifest.path("unsupported").path("feature").asText())
        .isEqualTo(
            "a Mirror play whose repeated card is not the one the simulator's Mirror repeats");
    assertThat(manifest.path("unsupported").path("input").asText())
        .isEqualTo(
            "cmd[4].c.sel.fs="
                + Scenarios.ARCHER
                + " (Archer), where the simulator repeats Knight");
    assertThat(out.resolve("COMPLETE")).doesNotExist();
  }

  @Test
  void aMirrorPlayWhoseCostOrLevelIsNotTheSimulatorsIsUnsupported() throws IOException {
    // The Mirror's own cost, 1, and its own level field, 5, where it repeats the Knight.
    for (int item : new int[] {0x11801800, 0x41801400}) {
      ObjectNode scenario = Scenarios.knightThenMirror();
      ((ObjectNode) scenario.path("cmd").get(4).path("c").path("sel")).put("pd", item);
      Path out = folder.resolve("run-" + Integer.toHexString(item));

      int exit = run(scenario, out, terminalIdentity, 420);

      assertThat(exit).isEqualTo(ReplaySmokeRun.UNSUPPORTED);
      JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
      assertThat(manifest.path("unsupported").path("feature").asText()).contains("packed item");
      // The item the simulator builds: level field 6, deck index field 6, cost 4.
      assertThat(manifest.path("unsupported").path("input").asText())
          .startsWith("cmd[4].c.sel.pd=" + item)
          .endsWith(
              "where the simulator builds "
                  + 0x41801800
                  + " (evolution field 0, option field 0, count field 0, level field 6, cosmetic"
                  + " field 0, slot flags field 0, deck index field 6, cost 4)");
    }
  }

  @Test
  void aVariantPlayFromAFullBarRunsAsTheMountedMaidenForItsCost() throws IOException {
    Path out = folder.resolve("run");

    int exit = run(Scenarios.mergeMaidenMounted(), out, terminalIdentity, 250);

    assertThat(exit).isEqualTo(ReplaySmokeRun.COMPLETED);
    JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
    JsonNode maiden = manifest.path("plays_run").get(0);
    assertThat(maiden.path("name").asText()).isEqualTo("cmd0");
    assertThat(maiden.path("tick").asInt()).isEqualTo(240);
    assertThat(maiden.path("placed").asBoolean()).isTrue();
    assertThat(manifest.has("items_not_built")).isFalse();
    List<String> lines = Files.readAllLines(out.resolve("observations.jsonl"));
    JsonNode unit = newestUnit(MAPPER.readTree(lines.get(241)));
    assertThat(unit.path("row").asText()).isEqualTo("MergeMaiden_Mounted");
    assertThat(unit.path("side").asInt()).isZero();
    // The mounted maiden's cost, 6 elixir, less one step's regeneration.
    int before = MAPPER.readTree(lines.get(240)).path("sides").get(0).path("elixir").asInt();
    int after = MAPPER.readTree(lines.get(241)).path("sides").get(0).path("elixir").asInt();
    assertThat(before - after).isBetween(60000 - 200, 60000);
  }

  @Test
  void aVariantPlayBelowTheMountedTriggerRunsAsTheMaidenOnFootForItsCost() throws IOException {
    Path out = folder.resolve("run");

    int exit = run(Scenarios.mergeMaidenOnFoot(), out, terminalIdentity, 280);

    assertThat(exit).isEqualTo(ReplaySmokeRun.COMPLETED);
    List<String> lines = Files.readAllLines(out.resolve("observations.jsonl"));
    JsonNode unit = newestUnit(MAPPER.readTree(lines.get(271)));
    assertThat(unit.path("row").asText()).isEqualTo("MergeMaiden_Normal");
    int before = MAPPER.readTree(lines.get(270)).path("sides").get(0).path("elixir").asInt();
    int after = MAPPER.readTree(lines.get(271)).path("sides").get(0).path("elixir").asInt();
    assertThat(before - after).isBetween(30000 - 200, 30000);
  }

  @Test
  void aVariantPlayGivenAsAnotherOptionThanTheSimulatorPicksIsUnsupported() throws IOException {
    ObjectNode scenario = Scenarios.mergeMaidenMounted();
    // The maiden on foot, where the client picks the mounted maiden from a full bar.
    ((ObjectNode) scenario.path("cmd").get(0).path("c").path("sel"))
        .put("pd", Scenarios.MAIDEN_ON_FOOT_ITEM);
    Path out = folder.resolve("run");

    int exit = run(scenario, out, terminalIdentity, 250);

    assertThat(exit).isEqualTo(ReplaySmokeRun.UNSUPPORTED);
    JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
    assertThat(manifest.path("unsupported").path("feature").asText())
        .isEqualTo("a play whose packed item is not the item the simulator builds as it runs");
    assertThat(manifest.path("unsupported").path("input").asText())
        .isEqualTo(
            "cmd[0].c.sel.pd="
                + Scenarios.MAIDEN_ON_FOOT_ITEM
                + " (evolution field 0, option field 2, count field 0, level field 8, cosmetic"
                + " field 0, slot flags field 0, deck index field 1, cost 3) for MergeMaiden, where"
                + " the simulator builds "
                + Scenarios.MOUNTED_MAIDEN_ITEM
                + " (evolution field 0, option field 1, count field 0, level field 8, cosmetic"
                + " field 0, slot flags field 0, deck index field 1, cost 6)");
    assertThat(out.resolve("COMPLETE")).doesNotExist();
  }

  @Test
  void aMirrorOfAVariantPlayIsRefusedByTheBattle() throws IOException {
    Path out = folder.resolve("run");

    int exit = run(Scenarios.mergeMaidenThenMirror(), out, terminalIdentity, 700);

    // The maiden's play runs; the Mirror, which would repeat the option it was played as, is
    // refused as it runs.
    assertThat(exit).isEqualTo(ReplaySmokeRun.UNSUPPORTED);
    JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
    assertThat(manifest.path("unsupported").path("feature").asText())
        .isEqualTo(
            "a Mirror of MergeMaiden, which repeats the option it was played as, which no"
                + " reference holds");
    assertThat(manifest.path("unsupported").path("input").asText()).isEqualTo("the run");
  }

  @Test
  void anUnsupportedScenarioWritesNoObservationAndNoMarker() throws IOException {
    ObjectNode scenario = Scenarios.knight();
    ((ObjectNode) scenario.path("battle").path("deck0").path("sc").get(0)).put("d", 159000003);
    Path out = folder.resolve("run");

    int exit = run(scenario, out, identity, 30);

    assertThat(exit).isEqualTo(ReplaySmokeRun.UNSUPPORTED);
    JsonNode manifest = MAPPER.readTree(out.resolve("manifest.json").toFile());
    assertThat(manifest.path("status").asText()).isEqualTo("unsupported");
    assertThat(manifest.path("unsupported").path("input").asText())
        .isEqualTo("battle.deck0.sc[0].d=159000003");
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

  /** The entity an observation lists under a game object id, or a missing node. */
  private static JsonNode entity(JsonNode observation, int id) {
    for (JsonNode entity : observation.path("entities")) {
      if (entity.path("id").asInt() == id) {
        return entity;
      }
    }
    return MAPPER.missingNode();
  }

  /** The entity with the highest id an observation lists, the newest unit. */
  private static JsonNode newestUnit(JsonNode observation) {
    JsonNode newest = MAPPER.missingNode();
    for (JsonNode entity : observation.path("entities")) {
      if (newest.isMissingNode() || entity.path("id").asInt() > newest.path("id").asInt()) {
        newest = entity;
      }
    }
    return newest;
  }

  /** The row of the entity with the highest id an observation lists, the newest unit. */
  private static String rowOfNewestUnit(String line) throws IOException {
    JsonNode newest = null;
    for (JsonNode entity : MAPPER.readTree(line).path("entities")) {
      if (newest == null || entity.path("id").asInt() > newest.path("id").asInt()) {
        newest = entity;
      }
    }
    return newest == null ? null : newest.path("row").asText();
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

  @Test
  void buildsTheBattleAScenarioGivesWithItsCommandsQueuedBeforeItsFirstStep() {
    GameTables tables = GameTables.load(tablesFolder);
    ScenarioPlan plan = new ReplayScenario(tables).translate(Scenarios.archerQueenAbility());

    Standard1v1Battle battle = ReplaySmokeRun.build(tables, plan);

    assertThat(battle.getBattle().getTick()).isZero();
    assertThat(battle.getMatch()).isNotNull();
    int checked = 0;
    for (int tick = 0; tick < 360; tick++) {
      battle.getBattle().step();
      checked = ReplaySmokeRun.checkItems(battle, plan, checked);
    }
    assertThat(checked).isEqualTo(1);
    assertThat(battle.getPlays()).extracting(Standard1v1Battle.Play::name).containsExactly("cmd0");
    assertThat(battle.getAbilityUses())
        .extracting(Standard1v1Battle.AbilityUse::name)
        .containsExactly("cmd1");
  }

  @Test
  void checkingThePlaysThatRanRefusesAnItemOtherThanTheOneTheBattleBuilt() {
    GameTables tables = GameTables.load(tablesFolder);
    ObjectNode scenario = Scenarios.knightEvolvedThirdPlay();
    // The third Knight play given without its evolution field, which the battle sets.
    ArrayNode commands = (ArrayNode) scenario.path("cmd");
    ((ObjectNode) commands.get(10).path("c").path("sel")).put("pd", 0x30480180);
    ScenarioPlan plan = new ReplayScenario(tables).translate(scenario);
    Standard1v1Battle battle = ReplaySmokeRun.build(tables, plan);

    assertThatThrownBy(
            () -> {
              int checked = 0;
              for (int tick = 0; tick < 1500; tick++) {
                battle.getBattle().step();
                checked = ReplaySmokeRun.checkItems(battle, plan, checked);
              }
            })
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessageContaining("cmd[10].c.sel.pd=" + 0x30480180);
  }
}
