/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.data.GameVersions;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Replays of data version 16.402.19, which game client 16.402.17 runs since 2026-10-06 after
 * 16.402.18. The replay fields, the generated cases' fields and the command types are the client's,
 * so a replay or generated case of the newer data is read as one of 16.402.18, against the newer
 * data's own tables: the folder of 16.402.19 beside the configured tables, as in a checkout of the
 * game data repository. Skipped without it.
 */
class ReplayScenarioNewerDataTest {

  /** The content sha of data version 16.402.19. */
  private static final String CONTENT_SHA = "7e76080b5dc3b2cfaf74795093e4ac5e39cb61ec";

  private static GameTables tables;

  @BeforeAll
  static void loadTables() {
    Optional<Path> configured = GameTables.configuredDirectory();
    Path folder =
        configured
            .map(path -> path.toAbsolutePath().resolveSibling(GameVersions.DATA_16_402_19))
            .orElse(null);
    assumeTrue(
        folder != null && Files.isDirectory(folder),
        "no game tables of " + GameVersions.DATA_16_402_19 + " beside the configured ones");
    tables = GameTables.load(folder);
  }

  /** The 16.402.18 knight replay, as the capture tool saves it from a session on 16.402.19. */
  private static ObjectNode knightOnTheNewerData() {
    ObjectNode scenario = Scenarios.knightWithEveryField();
    ObjectNode capture = scenario.putObject(ReplayCapture.FIELD);
    capture.put(ReplayCapture.CLIENT_VERSION, GameVersions.CLIENT_16_402_17);
    capture.put(ReplayCapture.CONTENT_VERSION, GameVersions.DATA_16_402_19);
    capture.put(ReplayCapture.CONTENT_SHA, CONTENT_SHA);
    capture.put(ReplayCapture.CAPTURED_AT, "2026-10-06T15:55:22Z");
    return scenario;
  }

  @Test
  void theTablesAreOfTheNewerData() {
    assertThat(tables.version()).isEqualTo(GameVersions.DATA_16_402_19);
    assertThat(tables.contentSha()).isEqualTo(CONTENT_SHA);
  }

  @Test
  void readsEveryFieldOfAReplayOfTheNewerData() {
    // Its events, request lists, profiles and player data are carried as in a 16.402.18 replay,
    // not refused by the 14.593.1 fields.
    assertThat(new ReplayScenario(tables).survey(knightOnTheNewerData())).isEmpty();
  }

  @Test
  void translatesThePlayAndTheKingLevelsAsOnTheOlderData() {
    ReplayScenario mapping = new ReplayScenario(tables);
    ScenarioPlan plan = mapping.translate(knightOnTheNewerData());

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
  void readsACaseGeneratedForTheNewerDataByTheFieldsOfTheOlderDatasGeneratedCases() {
    // A generated case's fields are the client's too: read as on 16.402.18, not refused.
    ReplayScenario mapping = new ReplayScenario(tables, ScenarioShape.GENERATED);
    ObjectNode scenario = ScenarioItems.fitted(Scenarios.generatedKnight(), tables);

    assertThat(mapping.survey(scenario)).isEmpty();
    assertThat(mapping.translate(scenario).towers())
        .extracting(Standard1v1Battle.Towers::kingLevel)
        .containsExactly(1, 1);
    assertThat(mapping.mapping().get("cmd[i].ct")).startsWith("consumed: 153, a card play, or 189");
  }
}
