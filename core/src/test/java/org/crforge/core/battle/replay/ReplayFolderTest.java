package org.crforge.core.battle.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.Test;

/**
 * Replays recorded by the game client of the version whose data version is 16.402.18, read against
 * the tables of that version: the mapping reads every one without a refusal. The battle core may
 * still refuse to set up a replay's battle on those tables, while it does not model all of them:
 * each such refusal is printed, and must be the core's own, never the mapping's.
 *
 * <p>Such replays name real players, so none is kept in the repository: the check reads a folder of
 * them given at run time, by the {@code CRFORGE_REPLAY_FOLDER} variable, each replay a {@code
 * replay.json} in a folder of its own, and is skipped without one or without tables of 16.402.18
 * beside the configured ones. It prints only each replay's folder name and the refusals, with no
 * account id.
 */
class ReplayFolderTest {

  /** The variable that names the folder of replays. */
  private static final String VARIABLE = "CRFORGE_REPLAY_FOLDER";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The package of the battle core, whose refusals of the tables are its own. */
  private static final String BATTLE_CORE = "org.crforge.core.battle.";

  /** The replay mapping's package, inside the battle core's but not its own refusals. */
  private static final String REPLAY = ReplayBattle.class.getPackageName() + ".";

  @Test
  void everyReplayInTheFolderIsReadWithoutARefusal() throws IOException {
    String folder = System.getenv(VARIABLE);
    assumeTrue(folder != null && !folder.isEmpty(), "no folder of replays named by " + VARIABLE);
    GameTables tables = GameData.tables();
    List<Path> replays;
    try (Stream<Path> files = Files.walk(Paths.get(folder), 2)) {
      replays =
          files.filter(p -> p.getFileName().toString().equals("replay.json")).sorted().toList();
    }
    assertThat(replays).isNotEmpty();
    List<String> refused = new ArrayList<>();
    for (Path replay : replays) {
      JsonNode document = MAPPER.readTree(Files.readAllBytes(replay));
      List<ReplayScenario.Refusal> refusals = new ReplayScenario(tables).survey(document);
      String name = replay.getParent().getFileName().toString();
      System.out.println(name + ": " + refusals.size() + " refusals");
      for (ReplayScenario.Refusal refusal : refusals) {
        // An account id a refusal names is not printed.
        String line =
            (name + ": " + refusal.feature() + " -- " + refusal.input())
                .replaceAll("the account -?\\d+/-?\\d+", "an account");
        System.out.println("  " + line);
        refused.add(line);
      }
      if (refusals.isEmpty()) {
        ScenarioPlan plan = new ReplayScenario(tables).translate(document);
        try {
          ReplayBattle.build(tables, plan);
        } catch (UnsupportedOperationException e) {
          System.out.println("  the battle core refuses its battle: " + e.getMessage());
          // The refusal is the battle core's: thrown from its own classes.
          String thrower = e.getStackTrace()[0].getClassName();
          if (!thrower.startsWith(BATTLE_CORE) || thrower.startsWith(REPLAY)) {
            refused.add(name + ": a refusal thrown by " + thrower + ": " + e.getMessage());
          }
        }
      }
    }
    assertThat(refused).isEmpty();
  }
}
