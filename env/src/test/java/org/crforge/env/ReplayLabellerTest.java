/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.airlift.compress.zstd.ZstdOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.zip.GZIPOutputStream;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.replay.ReplayCapture;
import org.crforge.core.battle.replay.ReplayScenario;
import org.crforge.core.battle.replay.ScenarioPlan;
import org.crforge.core.battle.replay.ScenarioShape;
import org.crforge.core.battle.replay.Scenarios;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A replay's labels: episodes of the Ladder environment written as replays and labelled back, and
 * the Knight replay of the core's test fixtures. The decks name rows of the tables; every cost and
 * elixir is read from the observation.
 */
class ReplayLabellerTest {

  private static final List<String> BASIC =
      List.of("Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Arrows", "Zap");

  /** A champion, the Archer Queen, and seven cards to cycle her back with. */
  private static final List<String> QUEEN =
      List.of(
          "ArcherQueen",
          "Knight",
          "Archer",
          "Musketeer",
          "Giant",
          "MiniPekka",
          "Fireball",
          "Arrows");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static GameTables tables;
  private static Vocabulary vocabulary;

  @TempDir Path folder;

  @BeforeAll
  static void loadTables() {
    tables = GameData.tables();
    vocabulary = VectorEnv.vocabulary(tables);
  }

  /** An episode played at 1-tick decisions: each decision's rows and actions, and its replay. */
  private record Episode(
      List<int[]> observations, List<byte[]> masks, List<int[]> actions, String replay) {}

  /**
   * Plays one Ladder episode at 1-tick decisions for a number of decisions. Each seat takes its
   * first ability action the mask opens, else a random open play about one decision in fifteen,
   * else the wait. When {@code twoInARow} is set, seat 0 plays two cards on consecutive ticks once
   * its elixir, read from the observation, covers both.
   */
  private static Episode play(
      List<String> deck0, List<String> deck1, int seed, int decisions, boolean twoInARow) {
    return play(deck0, deck1, seed, decisions, twoInARow, null);
  }

  /** As above, seat 0 playing {@code prefer} whenever its mask opens it. */
  private static Episode play(
      List<String> deck0,
      List<String> deck1,
      int seed,
      int decisions,
      boolean twoInARow,
      String prefer) {
    EnvConfig config = EnvConfig.DEFAULT.withDecisionTicks(1).withArena(Scenarios.arena(tables));
    try (VectorEnv env = VectorEnv.ladder(tables, config, 1, 1)) {
      env.setAutoreset(false);
      env.reset(new int[] {seed}, deck0, deck1);
      SplittableRandom random = new SplittableRandom(seed);
      List<int[]> observations = new ArrayList<>();
      List<byte[]> masks = new ArrayList<>();
      List<int[]> actions = new ArrayList<>();
      IntBuffer obs = env.observations().asIntBuffer();
      ByteBuffer mask = env.masks();
      IntBuffer act = env.actions().asIntBuffer();
      int secondSlot = -1;
      boolean pairDone = !twoInARow;
      for (int d = 0; d < decisions && env.dones().get(0) == 0; d++) {
        int[] o = new int[2 * Schema.SEAT_INTS];
        byte[] m = new byte[2 * Schema.ACTIONS];
        obs.get(0, o);
        mask.get(0, m);
        observations.add(o);
        masks.add(m);
        int[] chosen = new int[2];
        for (int seat = 0; seat < 2; seat++) {
          chosen[seat] = choose(o, m, seat, random);
        }
        if (prefer != null && chosen[0] == Schema.WAIT) {
          for (int k = 0; k < Schema.HAND_SLOTS; k++) {
            if (o[Schema.H_HAND_CARD + k] == vocabulary.id(prefer) && slotOpen(m, 0, k)) {
              chosen[0] = Schema.play(k, Schema.COLS / 2, 8);
            }
          }
        }
        if (secondSlot >= 0) {
          chosen[0] = Schema.play(secondSlot, Schema.COLS / 2, 8);
          secondSlot = -1;
          pairDone = true;
        } else if (!pairDone) {
          int[] pair = affordablePair(o, m);
          if (pair != null) {
            chosen[0] = Schema.play(pair[0], Schema.COLS / 2 - 3, 8);
            secondSlot = pair[1];
          }
        }
        act.put(0, chosen[0]);
        act.put(1, chosen[1]);
        actions.add(chosen);
        env.step();
      }
      return new Episode(observations, masks, actions, env.replayJson(0));
    }
  }

  private static int choose(int[] o, byte[] m, int seat, SplittableRandom random) {
    for (int k = 0; k < Schema.ABILITY_SLOTS; k++) {
      if (m[seat * Schema.ACTIONS + Schema.ability(k)] == 1) {
        return Schema.ability(k);
      }
    }
    if (random.nextInt(15) != 0) {
      return Schema.WAIT;
    }
    List<Integer> open = new ArrayList<>();
    for (int a = 1; a < Schema.FIRST_ABILITY; a++) {
      if (m[seat * Schema.ACTIONS + a] == 1) {
        open.add(a);
      }
    }
    return open.isEmpty() ? Schema.WAIT : open.get(random.nextInt(open.size()));
  }

  /**
   * Whether any tile of a seat's hand slot is open in both seats' masks: the card may be played.
   */
  private static boolean slotOpen(byte[] m, int seat, int slot) {
    for (int t = 0; t < Schema.TILES; t++) {
      if (m[seat * Schema.ACTIONS + 1 + slot * Schema.TILES + t] == 1) {
        return true;
      }
    }
    return false;
  }

  /** Two open hand slots of seat 0 whose costs together the elixir covers, or null. */
  private static int[] affordablePair(int[] o, byte[] m) {
    int elixir = o[Schema.H_ELIXIR] / 10_000;
    for (int a = 0; a < Schema.HAND_SLOTS; a++) {
      for (int b = a + 1; b < Schema.HAND_SLOTS; b++) {
        if (slotOpen(m, 0, a)
            && slotOpen(m, 0, b)
            && o[Schema.H_HAND_COST + a] + o[Schema.H_HAND_COST + b] <= elixir) {
          return new int[] {a, b};
        }
      }
    }
    return null;
  }

  private static ScenarioPlan plan(String replay) throws IOException {
    return new ReplayScenario(tables).translate(MAPPER.readTree(replay));
  }

  private static int[] row(int[] observations, int row) {
    int[] out = new int[Schema.SEAT_INTS];
    System.arraycopy(observations, row * Schema.SEAT_INTS, out, 0, Schema.SEAT_INTS);
    return out;
  }

  private static int[] seat(int[] both, int seat) {
    int[] out = new int[Schema.SEAT_INTS];
    System.arraycopy(both, seat * Schema.SEAT_INTS, out, 0, Schema.SEAT_INTS);
    return out;
  }

  @Test
  void anEpisodeAtOneTickIsLabelledWithItsOwnRowsAndADriveFromTheLabelsReproducesIt()
      throws IOException {
    Episode episode = play(BASIC, BASIC, 7, 1500, false);
    ScenarioPlan plan = plan(episode.replay());
    assertThat(plan.plays()).as("the episode plays cards").hasSizeGreaterThan(10);

    ReplayLabeller labeller = new ReplayLabeller(tables, 1);
    ReplayLabeller.Labels labels = labeller.label(episode.replay());

    assertThat(labels.refusal()).isNull();
    assertThat(labels.rows()).isEqualTo(2 * episode.actions().size());
    for (int d = 0; d < episode.actions().size(); d++) {
      for (int s = 0; s < 2; s++) {
        int row = 2 * d + s;
        assertThat(labels.seats()[row]).isEqualTo(s);
        assertThat(labels.ticks()[row]).isEqualTo(d);
        assertThat(row(labels.observations(), row))
            .as("seat %d's observation at decision %d", s, d)
            .isEqualTo(seat(episode.observations().get(d), s));
        int action = episode.actions().get(d)[s];
        int label = labels.actions()[row];
        assertThat(labels.flags()[row]).as("flags at decision %d", d).isZero();
        if (action == Schema.WAIT) {
          assertThat(label).as("a wait at decision %d", d).isEqualTo(Schema.WAIT);
        } else {
          // The same hand slot; the tile is the one holding the point the screen sent, which the
          // policy's own tile's search may have moved.
          assertThat(Schema.slot(label))
              .as("the slot at decision %d", d)
              .isEqualTo(Schema.slot(action));
        }
      }
    }
    for (int row = 0; row < labels.rows(); row++) {
      int seatOf = labels.seats()[row];
      assertThat(
              Arrays.equals(
                  labels.masks(),
                  row * Schema.ACTIONS,
                  (row + 1) * Schema.ACTIONS,
                  episode.masks().get(row / 2),
                  seatOf * Schema.ACTIONS,
                  (seatOf + 1) * Schema.ACTIONS))
          .as("the mask of row %d", row)
          .isTrue();
    }
    ReplayLabeller.Counts counts = labels.counts();
    assertThat(counts.commands()).isEqualTo(plan.plays().size() + plan.abilities().size());
    assertThat(counts.labelled()).isEqualTo(counts.commands());
    assertThat(counts.collisions() + counts.inexpressible() + counts.labelMasked()).isZero();
    assertThat(counts.givenAsRecorded() + counts.pointUnmatched() + counts.afterEnd()).isZero();

    ReplayLabeller.Drive drive = labeller.drive(episode.replay(), labels);
    assertThat(drive.divergence()).isNull();
    assertThat(drive.reproduced()).isTrue();
    assertThat(drive.matchedCommands()).isEqualTo(counts.commands());
    assertThat(drive.stepsMatched()).isEqualTo(episode.actions().size());
    assertThat(drive.rowsMatched()).isEqualTo(labels.rows());
  }

  @Test
  void anAbilityCommandIsLabelledWithItsChampionSlotsAction() throws IOException {
    Episode episode = play(QUEEN, BASIC, 3, 2400, false, "ArcherQueen");
    ScenarioPlan plan = plan(episode.replay());
    assertThat(plan.abilities()).as("the queen's ability was tapped").isNotEmpty();

    ReplayLabeller labeller = new ReplayLabeller(tables, 1);
    ReplayLabeller.Labels labels = labeller.label(episode.replay());

    int abilityLabels = 0;
    for (int row = 0; row < labels.rows(); row++) {
      int action = episode.actions().get(row / 2)[labels.seats()[row]];
      if (Schema.isAbility(action)) {
        assertThat(labels.actions()[row]).isEqualTo(action);
        abilityLabels++;
      }
    }
    assertThat(abilityLabels).isEqualTo(plan.abilities().size());
    assertThat(labels.counts().abilities()).isEqualTo(plan.abilities().size());
    assertThat(labels.counts().abilityUnitUnknown()).isZero();
    ReplayLabeller.Drive drive = labeller.drive(episode.replay(), labels);
    assertThat(drive.divergence()).isNull();
    assertThat(drive.reproduced()).isTrue();
  }

  @Test
  void atFiveTicksACommandIsLabelledAtTheDecisionWhoseWindowHoldsItsGivenTick() throws IOException {
    Episode episode = play(BASIC, BASIC, 11, 1500, true);
    ScenarioPlan plan = plan(episode.replay());

    ReplayLabeller labeller = new ReplayLabeller(tables, 5);
    ReplayLabeller.Labels labels = labeller.label(episode.replay());
    ReplayLabeller.Counts counts = labels.counts();

    assertThat(labels.refusal()).isNull();
    assertThat(Arrays.stream(labels.ticks()).allMatch(tick -> tick % 5 == 0)).isTrue();
    // Every command is a label, a collision or inexpressible: none is dropped.
    assertThat(counts.labelled() + counts.collisions() + counts.inexpressible() + counts.afterEnd())
        .isEqualTo(counts.commands());
    assertThat(counts.collisions()).as("seat 0's two plays on consecutive ticks").isPositive();
    for (ScenarioPlan.Play play : plan.plays()) {
      int row = 2 * (play.givenTick() / 5) + play.side();
      assertThat(labels.ticks()[row]).isEqualTo(play.givenTick() / 5 * 5);
      int label = labels.actions()[row];
      int flags = labels.flags()[row];
      if (label == Schema.WAIT) {
        assertThat(flags & ReplayLabeller.INEXPRESSIBLE).isNotZero();
      } else if (!Schema.isAbility(label)) {
        assertThat(row(labels.observations(), row)[Schema.H_HAND_CARD + Schema.slot(label)])
            .as("the label's slot holds a card the seat played in the window")
            .isIn(
                plan.plays().stream()
                    .filter(
                        p -> p.side() == play.side() && p.givenTick() / 5 == play.givenTick() / 5)
                    .map(p -> vocabulary.id(p.card()))
                    .toList());
      }
    }
    // Driven at 5 ticks, a play given between two decisions is given at the decision before it.
    ReplayLabeller.Drive drive = labeller.drive(episode.replay(), labels);
    assertThat(drive.reproduced()).isFalse();
    assertThat(drive.firstDivergentTick()).isNotNegative();
    assertThat(drive.divergence()).isNotNull();
  }

  @Test
  void theKnightOnATileCentreIsLabelledAndReproduced() {
    ObjectNode knight = Scenarios.knight();
    // A point the screen sends: the centre of a tile of side 0's half, less 1 across where the
    // placement search's symmetrical snap shifts it.
    ObjectNode body = (ObjectNode) knight.path("cmd").path(0).path("c");
    body.put("px", 3_499);
    body.put("py", 13_500);
    ReplayLabeller labeller = new ReplayLabeller(tables, vocabulary, 1, ScenarioShape.REPLAY);
    ReplayLabeller.Labels labels = labeller.label(cut(knight, 400));

    assertThat(labels.refusal()).isNull();
    int row = 2 * body.path("t").asInt();
    int label = labels.actions()[row];
    assertThat(labels.seats()[row]).isZero();
    assertThat(Schema.isAbility(label) || label == Schema.WAIT).isFalse();
    int[] observation = row(labels.observations(), row);
    assertThat(observation[Schema.H_HAND_CARD + Schema.slot(label)])
        .isEqualTo(vocabulary.id("Knight"));
    assertThat(Schema.gameX(0, Schema.col(label))).isEqualTo(body.path("px").asInt() + 1);
    assertThat(Schema.gameY(0, Schema.row(label))).isEqualTo(13_500);
    assertThat(labels.counts().labelled()).isEqualTo(1);
    assertThat(labels.flags()[row]).isZero();
    ReplayLabeller.Drive drive = labeller.drive(cut(knight, 400), labels);
    assertThat(drive.divergence()).isNull();
    assertThat(drive.reproduced()).isTrue();
  }

  @Test
  void aRecordedPointNoTileCentreGivesIsLabelledWithItsTileAndCounted() {
    // The Knight's point lies on the border of two tiles: no player's screen sends it.
    ObjectNode knight = Scenarios.knight();
    ReplayLabeller labeller = new ReplayLabeller(tables, vocabulary, 1, ScenarioShape.REPLAY);
    ReplayLabeller.Labels labels = labeller.label(cut(knight, 400));

    assertThat(labels.refusal()).isNull();
    assertThat(labels.counts().pointUnmatched()).isEqualTo(1);
    assertThat(labels.counts().pointMoved()).isEqualTo(1);
    int row = 2 * knight.path("cmd").path(0).path("c").path("t").asInt();
    assertThat(labels.flags()[row] & ReplayLabeller.POINT_UNMATCHED).isNotZero();
    assertThat(labels.flags()[row] & ReplayLabeller.GIVEN_AS_RECORDED).isNotZero();
    // Driven from the label, the play is sent on its tile's centre, not the recorded point; the
    // run's search places both on the same tile, so the battle is the replay's but for the point.
    ReplayLabeller.Drive drive = labeller.drive(cut(knight, 400), labels);
    assertThat(drive.reproduced()).isFalse();
    assertThat(drive.matchedCommands()).isZero();
    assertThat(drive.pointsDiffer()).isEqualTo(1);
    assertThat(drive.stateReproduced()).isTrue();
    assertThat(drive.divergence()).contains("point");
  }

  @Test
  void aReplayTheTablesCannotReadIsRefusedWithNoRows() {
    ReplayLabeller.Labels labels = new ReplayLabeller(tables, 5).label("{\"battle\": 1}");
    assertThat(labels.rows()).isZero();
    assertThat(labels.refusal()).isNotNull();
    assertThat(new ReplayLabeller(tables, 5).label("not json").refusal()).startsWith("not JSON");
  }

  @Test
  void theMetadataIsTheEnvironmentsWithTheLabellersInterval() throws IOException {
    JsonNode metadata = MAPPER.readTree(new ReplayLabeller(tables, 1).metadataJson());
    assertThat(metadata.path("schema").path("seat_ints").asInt()).isEqualTo(Schema.SEAT_INTS);
    assertThat(metadata.path("config").path("decision_ticks").asInt()).isEqualTo(1);
    assertThat(metadata.path("vocabulary").path("hash").asText()).isEqualTo(vocabulary.hash());
  }

  @Test
  void theReplaysOfAFileAreReadPlainOrCompressedWithTheirRecordsCapture() throws IOException {
    String replay = Scenarios.knight().toString();
    ObjectNode record = MAPPER.createObjectNode();
    record.put("format", ReplayFiles.FORMAT);
    record.putObject("session").put("client_version", "1.2.3").put("fetched_at", "now");
    record
        .putObject("battle")
        .put(ReplayCapture.CONTENT_VERSION, tables.version())
        .put(ReplayCapture.CONTENT_SHA, tables.contentSha());
    record.put("replay", replay);
    ObjectNode unsure = record.deepCopy();
    ((ObjectNode) unsure.path("battle")).putObject("candidates");
    String lines = record + "\n\n" + unsure + "\n";

    Path json = folder.resolve("one.json");
    Files.writeString(json, replay);
    Path plain = folder.resolve("crawl.jsonl");
    Files.writeString(plain, lines);
    Path gzip = folder.resolve("crawl.jsonl.gz");
    try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(gzip))) {
      out.write(lines.getBytes(StandardCharsets.UTF_8));
    }
    Path zstd = folder.resolve("crawl.jsonl.zst");
    try (OutputStream out = new ZstdOutputStream(Files.newOutputStream(zstd))) {
      out.write(lines.getBytes(StandardCharsets.UTF_8));
    }

    List<ReplayFiles.Source> single = ReplayFiles.read(json);
    assertThat(single).hasSize(1);
    assertThat(MAPPER.readTree(single.get(0).document())).isEqualTo(MAPPER.readTree(replay));
    for (Path file : List.of(plain, gzip, zstd)) {
      List<ReplayFiles.Source> sources = ReplayFiles.read(file);
      assertThat(sources).as(file.toString()).hasSize(2);
      assertThat(sources.get(0).name()).endsWith(":1");
      JsonNode document = MAPPER.readTree(sources.get(0).document());
      assertThat(document.path(ReplayCapture.FIELD).path(ReplayCapture.CONTENT_SHA).asText())
          .isEqualTo(tables.contentSha());
      assertThat(sources.get(1).name()).endsWith(":3");
      assertThat(sources.get(1).document()).isNull();
      assertThat(sources.get(1).problem()).contains("candidates");
    }
  }

  /** A replay document with its last tick set, so its battle is labelled that far. */
  private static String cut(ObjectNode replay, int endTick) {
    ObjectNode copy = replay.deepCopy();
    copy.put("endTick", endTick);
    return copy.toString();
  }

  @Test
  void aReplayStopsAtItsEndTickAndCountsTheCommandsAfterIt() {
    ObjectNode knight = Scenarios.knight();
    ((ObjectNode) knight.path("cmd").path(0).path("c")).put("px", 3_499).put("py", 13_500);
    ReplayLabeller labeller = new ReplayLabeller(tables, vocabulary, 5, ScenarioShape.REPLAY);
    ReplayLabeller.Labels labels = labeller.label(cut(knight, 150));
    assertThat(labels.stopTick()).isEqualTo(150);
    assertThat(labels.rows()).isEqualTo(2 * 30);
    assertThat(labels.counts().afterEnd()).isEqualTo(1);
    assertThat(labels.counts().labelled()).isZero();
  }
}
