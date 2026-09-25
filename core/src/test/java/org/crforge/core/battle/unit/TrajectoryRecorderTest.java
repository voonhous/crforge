package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.crforge.core.battle.Battle;
import org.crforge.core.card.UnitDataMapper;
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Writes the kill run, the Musketeer run and the two runs in which the towers fight back out
 * through {@link TrajectoryRecorder} and holds each file to its committed reference byte for byte.
 * One comparison checks the export's layout and replays the whole run: every record, every event,
 * every projectile position, every tower event and the header.
 *
 * <p>The reference's ticks count from the character's first tick in the holder, so the placement
 * tick does not show in the file; one test moves it and expects the same text.
 */
class TrajectoryRecorderTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String REFERENCE = "/pathfinding/golden/knight_left_kill.json";

  private static final String MUSKETEER_REFERENCE = "/pathfinding/golden/musketeer_left_kill.json";

  /** The latest placement tick a test here uses. */
  private static final int MAX_PLACEMENT_TICK = 3;

  /** Ticks a reference with the towers fighting plays on after the unit's removal. */
  private static final int UNIT_REMOVED_TAIL = 20;

  @Test
  @DisplayName("the exported kill run is the committed reference, byte for byte")
  void theExportIsTheReferenceByteForByte(@TempDir Path directory) throws IOException {
    String expected = reference(REFERENCE);
    JsonNode reference = MAPPER.readTree(expected);
    TrajectoryRecorder recorder = record(reference, 0);

    Path file = directory.resolve("knight_left_kill.json");
    recorder.writeTo(file);

    assertThat(recorder.recordCount()).isEqualTo(reference.get("records").size());
    assertSameText(Files.readString(file, StandardCharsets.UTF_8), expected);
  }

  @Test
  @DisplayName(
      "the exported Musketeer run, launches, impacts and projectile positions included, is the"
          + " committed reference byte for byte")
  void theExportedMusketeerRunIsTheReferenceByteForByte(@TempDir Path directory)
      throws IOException {
    String expected = reference(MUSKETEER_REFERENCE);
    JsonNode reference = MAPPER.readTree(expected);
    assertThat(reference.get("projectiles"))
        .as("the reference lists projectile positions")
        .hasSize(105);
    TrajectoryRecorder recorder = record(reference, 0);

    Path file = directory.resolve("musketeer_left_kill.json");
    recorder.writeTo(file);

    assertThat(recorder.recordCount()).isEqualTo(reference.get("records").size());
    assertSameText(Files.readString(file, StandardCharsets.UTF_8), expected);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        BattleTowerRunTest.KNIGHT_REFERENCE,
        BattleTowerRunTest.MUSKETEER_REFERENCE,
        BattleTowerRunTest.WIZARD_REFERENCE,
        BattleTowerRunTest.LEVEL_ONE_REFERENCE
      })
  void theExportedRunWithTheTowersFightingIsTheReferenceByteForByte(
      String resource, @TempDir Path directory) throws IOException {
    String expected = reference(resource);
    JsonNode reference = MAPPER.readTree(expected);
    int ticks = reference.get("records").size();
    Standard1v1Battle match = new Standard1v1Battle(reference.get("tower_level").asInt());
    Battle battle = match.getBattle();
    CharacterEntity unit = BattleTowerRunTest.deploy(match, reference);
    TrajectoryRecorder recorder = new TrajectoryRecorder(unit);
    match.getWorld().addObserver(recorder);

    // The run ends with the unit's removal; the reference plays twenty ticks more to show what the
    // towers do once it has gone.
    stepUntilRecorded(battle, recorder, ticks);
    for (int tick = 0; tick < UNIT_REMOVED_TAIL; tick++) {
      battle.step();
    }
    Path file = directory.resolve("run.json");
    recorder.writeTo(file);

    assertThat(recorder.recordCount()).isEqualTo(ticks);
    assertSameText(Files.readString(file, StandardCharsets.UTF_8), expected);
  }

  @Test
  @DisplayName("a placement on a later tick records the same run, counted from its first tick")
  void aLaterPlacementRecordsTheSameRun() throws IOException {
    String expected = reference(REFERENCE);
    TrajectoryRecorder recorder = record(MAPPER.readTree(expected), MAX_PLACEMENT_TICK);
    assertSameText(recorder.text(), expected);
  }

  /**
   * Plays the reference's character placed on the given tick through the whole run, recording it.
   * The reference names the unit; its card is the unit's name in lower case.
   */
  private static TrajectoryRecorder record(JsonNode reference, int placementTick) {
    int ticks = reference.get("records").size();
    String cardId = reference.get("card").asText().toLowerCase();
    Standard1v1Battle match = new Standard1v1Battle(reference.get("level").asInt(), false);
    Battle battle = match.getBattle();
    CharacterEntity unit =
        match.deploy(
            placementTick,
            UnitDataMapper.toUnitData(
                Objects.requireNonNull(CardRegistry.get(cardId), cardId + " not found")),
            reference.get("level").asInt(),
            reference.get("side").asInt(),
            reference.get("deploy").get(0).asInt(),
            reference.get("deploy").get(1).asInt());
    TrajectoryRecorder recorder = new TrajectoryRecorder(unit);
    match.getWorld().addObserver(recorder);

    // Run until the recorder has the whole run: which step the unit is first visited in depends
    // on which of the two command passes admits it, and the recorder counts from that tick.
    stepUntilRecorded(battle, recorder, ticks);
    return recorder;
  }

  /**
   * Steps the battle until the recorder holds the given number of records. A run that stops short
   * of them, because the unit left the holder early, fails instead of stepping for ever.
   */
  private static void stepUntilRecorded(Battle battle, TrajectoryRecorder recorder, int records) {
    int limit = records + MAX_PLACEMENT_TICK + 2;
    for (int step = 0; recorder.recordCount() < records && step < limit; step++) {
      battle.step();
    }
    assertThat(recorder.recordCount()).as("records written").isEqualTo(records);
  }

  /** Compares line by line first, so that a disagreement names its line, then byte for byte. */
  private static void assertSameText(String actual, String expected) {
    List<String> actualLines = actual.lines().toList();
    List<String> expectedLines = expected.lines().toList();
    for (int i = 0; i < Math.min(actualLines.size(), expectedLines.size()); i++) {
      assertThat(actualLines.get(i)).as("line %d", i + 1).isEqualTo(expectedLines.get(i));
    }
    assertThat(actualLines).as("line count").hasSameSizeAs(expectedLines);
    assertThat(actual.getBytes(StandardCharsets.UTF_8))
        .isEqualTo(expected.getBytes(StandardCharsets.UTF_8));
  }

  private static String reference(String resource) {
    try (InputStream stream = TrajectoryRecorderTest.class.getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IllegalStateException("Missing test resource " + resource);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read " + resource, e);
    }
  }
}
