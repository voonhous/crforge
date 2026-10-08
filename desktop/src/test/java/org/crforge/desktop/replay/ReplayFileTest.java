package org.crforge.desktop.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.replay.ScenarioItems;
import org.crforge.desktop.render.ViewOrientation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A replay file read for the viewer: its header named from the tables, its translation when the
 * mapping reads all of it, and every reason when the mapping, the tables or the battle refuse it.
 */
class ReplayFileTest {

  @TempDir static Path refusedFolder;

  private static GameTables refusedTables;

  @TempDir Path folder;

  @BeforeAll
  static void copyTheTables() throws IOException {
    refusedTables = Replays.refusedTables(refusedFolder);
  }

  /** A replay document with its plays' items fitted to the tables: their rows' costs and levels. */
  private static ObjectNode fit(ObjectNode document) {
    return ScenarioItems.fitted(document, Replays.tables());
  }

  @Test
  @DisplayName("a replay the mapping reads is playable, its header named from the tables")
  void aPlayableReplay() throws IOException {
    Path file = Replays.write(folder, "replay.json", fit(Replays.archerQueen()));

    ReplayFile replay = ReplayFile.read(file, Replays.tables());

    assertThat(replay.playable()).isTrue();
    assertThat(replay.refusals()).isEmpty();
    assertThat(replay.header().gameMode()).isEqualTo("Ladder");
    assertThat(replay.header().decks().get(0))
        .containsExactly(
            "ArcherQueen",
            "Archer",
            "Goblins",
            "Giant",
            "Minions",
            "Musketeer",
            "Fireball",
            "Arrows");
    assertThat(replay.header().endTick()).isEqualTo(400);
    assertThat(replay.header().commands()).isEqualTo(2);
    assertThat(replay.header().commandTypes())
        .containsExactly(Map.entry(153, 1), Map.entry(189, 1));
    assertThat(replay.plan().plays()).hasSize(1);
    assertThat(replay.plan().abilities()).hasSize(1);
    assertThat(replay.recordedResult()).isEqualTo("none in the replay");
    List<String> lines = replay.describe();
    assertThat(lines.get(0)).isEqualTo("replay: " + file.toAbsolutePath().normalize());
    // The decks are labelled by side and by the viewer's default view: side 1 at the bottom.
    assertThat(lines)
        .contains(
            "  side 0 deck (red, top): ArcherQueen, Archer, Goblins, Giant, Minions, Musketeer,"
                + " Fireball, Arrows",
            "  side 1 deck (blue, bottom): " + String.join(", ", replay.header().decks().get(1)));
    assertThat(replay.describe(ViewOrientation.STANDARD))
        .contains("  side 1 deck (red, top): " + String.join(", ", replay.header().decks().get(1)));
    assertThat(lines)
        .contains(
            "  end tick: 400",
            "  commands: 2 (type 153 x1, a card play; type 189 x1, an ability command)",
            "  recorded result: none in the replay",
            "  mapping: every field read; 1 plays, 1 ability commands");
  }

  @Test
  @DisplayName("command types the data version does not map are refused, with their counts")
  void unmappedCommandTypes() {
    ObjectNode document = fit(Replays.archerQueen());
    ArrayNode commands = (ArrayNode) document.path("cmd");
    commands.add(commands.get(0).deepCopy());
    // The play and the ability command of another data version, 14.593.1.
    ((ObjectNode) commands.get(0)).put("ct", 124);
    ((ObjectNode) commands.get(2)).put("ct", 124);
    ((ObjectNode) commands.get(1)).put("ct", 178);

    ReplayFile replay = ReplayFile.parse(folder.resolve("x.json"), document, Replays.tables());

    assertThat(replay.playable()).isFalse();
    assertThat(replay.plan()).isNull();
    assertThat(replay.refusals())
        .containsExactly(
            "the command type 124: cmd[0].ct and 1 more (2 in all)",
            "the command type 178: cmd[1].ct");
    assertThat(replay.describe())
        .contains(
            "  commands: 3 (type 124 x2, not mapped in data version "
                + Replays.tables().version()
                + "; type 178 x1, not mapped in data version "
                + Replays.tables().version()
                + ")",
            "  refused, 2 reasons:",
            "    - the command type 124: cmd[0].ct and 1 more (2 in all)");
  }

  @Test
  @DisplayName("every field the mapping refuses is listed, not only the first")
  void everyRefusedField() {
    ObjectNode document = fit(Replays.archerQueen());
    document.putArray("srq").add(1);
    ((ObjectNode) document.path("battle")).put("hm", true);
    ((ObjectNode) document.path("battle").path("avatar0")).put("clan", "Test Clan");

    ReplayFile replay = ReplayFile.parse(folder.resolve("x.json"), document, Replays.tables());

    assertThat(replay.refusals())
        .containsExactly(
            "a value of srq other than [], which has no production input: srq=[1]",
            "a value of hm other than false, which has no production input: hm=true",
            "the field clan, which has no mapping: battle.avatar0.clan");
  }

  @Test
  @DisplayName("tables the battle core refuses are listed beside the mapping's refusals")
  void refusedTablesAndMapping() {
    ObjectNode document = fit(Replays.archerQueen());
    ((ObjectNode) document.path("cmd").get(0)).put("ct", 124);

    ReplayFile replay = ReplayFile.parse(folder.resolve("x.json"), document, refusedTables);

    assertThat(replay.playable()).isFalse();
    assertThat(replay.refusals()).hasSize(2);
    assertThat(replay.refusals().get(0)).isEqualTo("the command type 124: cmd[0].ct");
    assertThat(replay.refusals().get(1))
        .startsWith(
            "the battle core refuses the game tables of data version "
                + refusedTables.version()
                + ": UnsupportedOperationException: the variables row")
        .contains("sets Tid, which is not modelled");
  }

  @Test
  @DisplayName("a replay the mapping reads is refused when the battle core refuses its battle")
  void refusedBattle() {
    ReplayFile replay =
        ReplayFile.parse(folder.resolve("x.json"), fit(Replays.archerQueen()), refusedTables);

    assertThat(replay.playable()).isFalse();
    assertThat(replay.refusals())
        .singleElement()
        .asString()
        .startsWith("the battle core refuses to set up the replay's battle:")
        .contains("sets Tid");
  }

  @Test
  @DisplayName("a replay with every field its version writes is read and playable")
  void replayWithEveryField() throws IOException {
    Path file = Replays.write(folder, "replay.json", fit(Replays.archerQueenWithEveryField()));

    ReplayFile replay = ReplayFile.read(file, Replays.tables());

    assertThat(replay.dataVersion()).isEqualTo(Replays.tables().version());
    assertThat(replay.header().location()).isEqualTo("PvP_spiritempress");
    assertThat(replay.header().commandTypes())
        .containsExactly(Map.entry(153, 1), Map.entry(189, 1));
    assertThat(replay.describe())
        .contains("  commands: 2 (type 153 x1, a card play; type 189 x1, an ability command)");
    // The mapping reads every field, and the battle core plays the configured tables.
    assertThat(replay.refusals()).isEmpty();
    assertThat(replay.playable()).isTrue();
  }

  @Test
  @DisplayName("a file that is not JSON cannot be read")
  void notJson() throws IOException {
    Path file = folder.resolve("broken.json");
    Files.writeString(file, "{ not json");

    assertThatThrownBy(() -> ReplayFile.read(file, Replays.tables()))
        .isInstanceOf(IOException.class);
  }
}
