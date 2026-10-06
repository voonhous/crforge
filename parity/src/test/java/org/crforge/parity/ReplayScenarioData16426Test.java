package org.crforge.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Replays of data version 16.426.22, which game client 16.402.17 runs since 2026-10-06 after
 * 16.402.18. The replay fields and command types are the client's, so a replay of the newer data is
 * read as one of 16.402.18, against the newer data's own tables. Skipped without tables of
 * 16.426.22 ({@link Version16Tables}).
 */
class ReplayScenarioData16426Test {

  /** The content sha of data version 16.426.22. */
  private static final String CONTENT_SHA = "7e76080b5dc3b2cfaf74795093e4ac5e39cb61ec";

  private static GameTables tables;

  @BeforeAll
  static void loadTables() {
    tables = Version16Tables.load(Version16Tables.VERSION_16_426_22);
  }

  /** The 16.402.18 knight replay, as the capture tool saves it from a session on 16.426.22. */
  private static ObjectNode knightOn16426() {
    ObjectNode scenario = Scenarios.knightOfVersion16();
    ObjectNode capture = scenario.putObject(ReplayCapture.FIELD);
    capture.put(ReplayCapture.CLIENT_VERSION, "16.402.17");
    capture.put(ReplayCapture.CONTENT_VERSION, Version16Tables.VERSION_16_426_22);
    capture.put(ReplayCapture.CONTENT_SHA, CONTENT_SHA);
    capture.put(ReplayCapture.CAPTURED_AT, "2026-10-06T15:55:22Z");
    return scenario;
  }

  @Test
  void theTablesAreOfTheNewerData() {
    assertThat(tables.version()).isEqualTo("16.426.22");
    assertThat(tables.contentSha()).isEqualTo(CONTENT_SHA);
  }

  @Test
  void readsEveryFieldOfAReplayOfTheNewerData() {
    // Its events, request lists, profiles and player data are carried as in a 16.402.18 replay,
    // not refused by the 14.593.1 fields.
    assertThat(new ReplayScenario(tables).survey(knightOn16426())).isEmpty();
  }

  @Test
  void translatesThePlayAndTheKingLevelsAsOnTheOlderData() {
    ReplayScenario mapping = new ReplayScenario(tables);
    ScenarioPlan plan = mapping.translate(knightOn16426());

    assertThat(plan.towers())
        .containsExactly(
            new Standard1v1Battle.Towers("King_PrincessTowers", 15, 1),
            new Standard1v1Battle.Towers("King_PrincessTowers", 16, 1));
    assertThat(plan.plays())
        .containsExactly(
            new ScenarioPlan.Play(
                0, 200, 220, 0, "Knight", 1, 3500, 14000, 0x30440000, null, null));
    assertThat(mapping.mapping().get("cmd[i].ct")).startsWith("consumed: 153, a card play, or 189");
    assertThat(mapping.mapping().get("evt")).startsWith("carried:");
  }

  @Test
  void hasNoGeneratedCaseFieldsUntilItsRecordedBattlesEstablishThem() {
    assertThat(ReplayFormat.of("16.426.22")).isPresent();
    assertThat(ReplayFormat.generated("16.426.22")).isEmpty();
  }
}
