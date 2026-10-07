package org.crforge.core.battle.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.data.GameVersions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The capture block of a replay: the client version, data version, content sha and capture time the
 * tool that saved the replay names. It is no battle input; its content sha, and its data version
 * when given, must be the tables'.
 */
class ReplayCaptureBlockTest {

  private static GameTables tables;

  @BeforeAll
  static void loadTables() {
    tables = GameTables.loadConfigured();
  }

  /** The knight scenario with a capture block naming the given data. */
  private static ObjectNode recordedOn(String contentVersion, String contentSha) {
    ObjectNode scenario = Scenarios.knight();
    ObjectNode capture = scenario.putObject("capture");
    capture.put("client_version", GameVersions.CLIENT_16_402_17);
    if (contentVersion != null) {
      capture.put(ReplayCapture.CONTENT_VERSION, contentVersion);
    }
    capture.put(ReplayCapture.CONTENT_SHA, contentSha);
    capture.put("captured_at", "2026-10-06T03:47:38Z");
    return scenario;
  }

  @Test
  void readsAReplayWhoseCaptureBlockNamesTheTablesAsWithoutIt() {
    ObjectNode scenario = recordedOn(tables.version(), tables.contentSha());

    ReplayScenario mapping = new ReplayScenario(tables);
    ScenarioPlan plan = mapping.translate(scenario);

    assertThat(plan)
        .usingRecursiveComparison()
        .isEqualTo(new ReplayScenario(tables).translate(Scenarios.knight()));
    assertThat(mapping.mapping()).containsKey("capture");
    assertThat(mapping.mapping().get("capture")).startsWith("checked:");
    assertThat(new ReplayScenario(tables).survey(scenario)).isEmpty();
  }

  @Test
  void readsACaptureBlockWithoutItsDataVersionOrTime() {
    ObjectNode scenario = recordedOn(null, tables.contentSha());
    ((ObjectNode) scenario.path("capture")).remove("captured_at");

    assertThat(new ReplayScenario(tables).survey(scenario)).isEmpty();
  }

  @Test
  void refusesAReplayRecordedOnOtherDataNamingBoth() {
    ObjectNode scenario =
        recordedOn(GameVersions.DATA_16_402_19, "7e76080b5dc3b2cfaf74795093e4ac5e39cb61ec");

    assertThatThrownBy(() -> new ReplayScenario(tables).translate(scenario))
        .isInstanceOf(UnsupportedScenarioException.class)
        .hasMessageContaining(
            "a replay recorded on client 16.402.17, data version 16.402.19 (content sha"
                + " 7e76080b5dc3b2cfaf74795093e4ac5e39cb61ec), read against the game tables of"
                + " data version "
                + tables.version()
                + " (content sha "
                + tables.contentSha()
                + ")");
    // A survey lists it first and reads on.
    List<ReplayScenario.Refusal> refusals = new ReplayScenario(tables).survey(scenario);
    assertThat(refusals).hasSize(1);
    assertThat(refusals.get(0).input())
        .isEqualTo("capture.content_sha=7e76080b5dc3b2cfaf74795093e4ac5e39cb61ec");
  }

  @Test
  void refusesACaptureBlockWhoseDataVersionIsNotTheTablesThoughItsShaIs() {
    ObjectNode scenario = recordedOn("0.0.1", tables.contentSha());

    assertThat(new ReplayScenario(tables).survey(scenario))
        .singleElement()
        .satisfies(
            refusal -> {
              assertThat(refusal.feature())
                  .startsWith("a replay recorded on client 16.402.17, data version 0.0.1");
              assertThat(refusal.input()).isEqualTo("capture.content_version=0.0.1");
            });
  }

  @Test
  void refusesAnotherKeyOrShapeInTheCaptureBlock() {
    ObjectNode extra = recordedOn(tables.version(), tables.contentSha());
    ((ObjectNode) extra.path("capture")).put("player", "someone");
    assertThat(new ReplayScenario(tables).survey(extra))
        .containsExactly(
            new ReplayScenario.Refusal("the field player, which has no mapping", "capture.player"));

    ObjectNode number = recordedOn(tables.version(), tables.contentSha());
    ((ObjectNode) number.path("capture")).put("captured_at", 1775066287);
    assertThat(new ReplayScenario(tables).survey(number))
        .containsExactly(
            new ReplayScenario.Refusal(
                "a capture block field that is not a string", "capture.captured_at=1775066287"));

    ObjectNode noSha = recordedOn(tables.version(), tables.contentSha());
    ((ObjectNode) noSha.path("capture")).remove(ReplayCapture.CONTENT_SHA);
    assertThat(new ReplayScenario(tables).survey(noSha))
        .containsExactly(
            new ReplayScenario.Refusal(
                "a capture block that names no content_sha", "capture.content_sha"));

    ObjectNode notAnObject = Scenarios.knight();
    notAnObject.put("capture", GameVersions.CLIENT_16_402_17);
    assertThat(new ReplayScenario(tables).survey(notAnObject))
        .containsExactly(
            new ReplayScenario.Refusal(
                "a capture block that is not an object", "capture=\"16.402.17\""));
  }

  @Test
  void aReplayWithoutACaptureBlockIsReadAsBefore() {
    ReplayScenario mapping = new ReplayScenario(tables);

    mapping.translate(Scenarios.knight());

    assertThat(mapping.mapping()).doesNotContainKey("capture");
    assertThat(ReplayCapture.of(Scenarios.knight())).isEmpty();
  }

  @Test
  void readsTheBlockLenientlyForTheViewer() {
    ObjectNode scenario =
        recordedOn(GameVersions.DATA_16_402_18, "8aa8015226b0062c7e16a793522de91e564ffdaf");

    ReplayCapture capture = ReplayCapture.of(scenario).orElseThrow();

    assertThat(capture)
        .isEqualTo(
            new ReplayCapture(
                GameVersions.CLIENT_16_402_17,
                GameVersions.DATA_16_402_18,
                "8aa8015226b0062c7e16a793522de91e564ffdaf",
                "2026-10-06T03:47:38Z"));
    assertThat(capture.recordedOn())
        .isEqualTo(
            "client 16.402.17, data version 16.402.18 (content sha"
                + " 8aa8015226b0062c7e16a793522de91e564ffdaf)");
    ((ObjectNode) scenario.path("capture")).remove(ReplayCapture.CONTENT_VERSION);
    assertThat(ReplayCapture.of(scenario).orElseThrow().recordedOn())
        .isEqualTo(
            "client 16.402.17, data version not named (content sha"
                + " 8aa8015226b0062c7e16a793522de91e564ffdaf)");
  }
}
