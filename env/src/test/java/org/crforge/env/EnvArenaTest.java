/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTable;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.replay.ReplayScenario;
import org.crforge.core.battle.replay.ScenarioItems;
import org.crforge.core.battle.replay.ScenarioPlan;
import org.crforge.core.battle.replay.ScenarioWriter;
import org.crforge.core.battle.replay.Scenarios;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The arena each player's avatar names when an episode is written as a replay: {@link
 * EnvConfig#arena} for a fresh episode, refused when none is named; a replay's own for an episode
 * started on one. The arenas are read from the tables, never typed here.
 */
class EnvArenaTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final List<String> BASIC =
      List.of("Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Arrows", "Zap");

  private static GameTables tables;

  @BeforeAll
  static void loadTables() {
    tables = GameData.tables();
  }

  /** An arena row's data id, from the tables: its table's id times a million plus its index. */
  private static int id(GameRow arena) {
    return Integer.parseInt(tables.table(ScenarioWriter.ARENAS).id()) * 1_000_000 + arena.index();
  }

  /** The arenas table's last row. */
  private static GameRow lastArena() {
    GameTable arenas = tables.table(ScenarioWriter.ARENAS);
    return arenas.rows().stream().reduce((first, second) -> second).orElseThrow();
  }

  @Test
  void aFreshEpisodesReplayNamesTheConfiguredArenaForBothPlayers() throws Exception {
    String arena = Scenarios.arena(tables);
    EnvConfig config = EnvConfig.DEFAULT.withArena(arena);
    try (VectorEnv env = VectorEnv.ladder(tables, config, 1, 1)) {
      env.reset(new int[] {3}, BASIC.toArray(String[]::new), BASIC.toArray(String[]::new));

      JsonNode replay = MAPPER.readTree(env.replayJson(0));

      int expected = id(tables.table(ScenarioWriter.ARENAS).row(arena));
      assertThat(replay.path("battle").path("avatar0").path("arena").asInt()).isEqualTo(expected);
      assertThat(replay.path("battle").path("avatar1").path("arena").asInt()).isEqualTo(expected);
    }
  }

  @Test
  void aFreshEpisodesReplayWithNoArenaConfiguredIsRefusedNamingTheSetting() {
    try (VectorEnv env = VectorEnv.ladder(tables, EnvConfig.DEFAULT, 1, 1)) {
      env.reset(new int[] {3}, BASIC.toArray(String[]::new), BASIC.toArray(String[]::new));

      assertThatThrownBy(() -> env.replayJson(0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("EnvConfig.arena");
    }
  }

  @Test
  void aConfiguredArenaTheTablesDoNotHoldIsRefusedAsTheEnvironmentIsMade() {
    EnvConfig config = EnvConfig.DEFAULT.withArena("NoSuchArena");

    assertThatThrownBy(() -> new LadderEnv(tables, VectorEnv.vocabulary(tables), config))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("NoSuchArena");
  }

  @Test
  void anEpisodeStartedOnAReplayKeepsTheReplaysOwnArenas() {
    ObjectNode scenario = ScenarioItems.fitted(Scenarios.knight(), tables);
    GameRow last = lastArena();
    ((ObjectNode) scenario.path("battle").path("avatar1")).put("arena", id(last));
    ScenarioPlan plan = new ReplayScenario(tables).translate(scenario);
    LadderEnv env =
        new LadderEnv(
            tables,
            VectorEnv.vocabulary(tables),
            EnvConfig.DEFAULT.withArena(Scenarios.arena(tables)));
    env.bind(
        IntBuffer.allocate(2 * Schema.SEAT_INTS),
        ByteBuffer.allocate(2 * Schema.ACTIONS),
        FloatBuffer.allocate(2),
        IntBuffer.allocate(Schema.INFO_INTS));

    env.resetReplay(plan);

    assertThat(env.arenas()).isEqualTo(plan.arenas());
    assertThat(env.arenas().get(1)).isEqualTo(last.name());
  }
}
