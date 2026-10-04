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

  @Test
  @DisplayName("a replay the mapping reads is playable, its header named from the tables")
  void aPlayableReplay() throws IOException {
    Path file = Replays.write(folder, "replay.json", Replays.archerQueen());

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
        .containsExactly(Map.entry(124, 1), Map.entry(178, 1));
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
            "  commands: 2 (type 124 x1, a card play; type 178 x1, an ability command)",
            "  recorded result: none in the replay",
            "  mapping: every field read; 1 plays, 1 ability commands");
  }

  @Test
  @DisplayName("command types the data version does not map are refused, with their counts")
  void unmappedCommandTypes() {
    ObjectNode document = Replays.archerQueen();
    ArrayNode commands = (ArrayNode) document.path("cmd");
    commands.add(commands.get(0).deepCopy());
    ((ObjectNode) commands.get(0)).put("ct", 153);
    ((ObjectNode) commands.get(2)).put("ct", 153);
    ((ObjectNode) commands.get(1)).put("ct", 189);

    ReplayFile replay = ReplayFile.parse(folder.resolve("x.json"), document, Replays.tables());

    assertThat(replay.playable()).isFalse();
    assertThat(replay.plan()).isNull();
    assertThat(replay.refusals())
        .containsExactly(
            "the command type 153: cmd[0].ct and 1 more (2 in all)",
            "the command type 189: cmd[1].ct");
    assertThat(replay.describe())
        .contains(
            "  commands: 3 (type 153 x2, not mapped in data version "
                + Replays.tables().version()
                + "; type 189 x1, not mapped in data version "
                + Replays.tables().version()
                + ")",
            "  refused, 2 reasons:",
            "    - the command type 153: cmd[0].ct and 1 more (2 in all)");
  }

  @Test
  @DisplayName("every field the mapping refuses is listed, not only the first")
  void everyRefusedField() {
    ObjectNode document = Replays.archerQueen();
    document.putArray("srq");
    ((ObjectNode) document.path("battle")).put("arena", 54000144);
    ((ObjectNode) document.path("battle").path("avatar0")).put("clan_name", "Test Clan");

    ReplayFile replay = ReplayFile.parse(folder.resolve("x.json"), document, Replays.tables());

    assertThat(replay.refusals())
        .containsExactly(
            "the field srq, which has no mapping: $.srq",
            "a value of arena other than 54000001, which has no production input: arena=54000144",
            "the field clan_name, which has no mapping: battle.avatar0.clan_name");
  }

  @Test
  @DisplayName("tables the battle core refuses are listed beside the mapping's refusals")
  void refusedTablesAndMapping() {
    ObjectNode document = Replays.archerQueen();
    ((ObjectNode) document.path("cmd").get(0)).put("ct", 153);

    ReplayFile replay = ReplayFile.parse(folder.resolve("x.json"), document, refusedTables);

    assertThat(replay.playable()).isFalse();
    assertThat(replay.refusals()).hasSize(2);
    assertThat(replay.refusals().get(0)).isEqualTo("the command type 153: cmd[0].ct");
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
        ReplayFile.parse(folder.resolve("x.json"), Replays.archerQueen(), refusedTables);

    assertThat(replay.playable()).isFalse();
    assertThat(replay.refusals())
        .singleElement()
        .asString()
        .startsWith("the battle core refuses to set up the replay's battle:")
        .contains("sets Tid");
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
