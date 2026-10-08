package org.crforge.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.data.GameVersions;
import org.crforge.core.battle.replay.ReplayCapture;
import org.crforge.desktop.battle.DataVersions;
import org.crforge.desktop.battle.TableCopies;
import org.crforge.desktop.render.ViewOrientation;
import org.crforge.desktop.replay.ReplayFile;
import org.crforge.desktop.replay.ReplayItems;
import org.crforge.desktop.replay.ReplayPlayer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The tables a replay is opened on: the data version whose content sha the replay's capture block
 * names, looked up in the data root; a refusal when no version has it or the version is fixed by
 * the launch; and the version on screen, marked assumed, for a replay that names none. The data
 * root's two versions are copies of the configured tables, the second with another content sha in
 * its headers.
 */
class ReplayDataTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The content sha of the root's second version. */
  private static final String OTHER_SHA = "0123456789abcdef0123456789abcdef01234567";

  /** A content sha no version of the root has. */
  private static final String UNKNOWN_SHA = "fedcba9876543210fedcba9876543210fedcba98";

  @TempDir Path root;

  private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
  private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
  private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
  private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

  private Path first;
  private Path second;
  private DataVersions versions;

  @BeforeEach
  void twoVersions() throws IOException {
    first = TableCopies.copy(root, "1.0.0");
    second = TableCopies.withContentSha(root, "2.0.0", OTHER_SHA);
    versions = new DataVersions(root, List.of("1.0.0", "2.0.0"), first, GameTables.load(first));
  }

  /**
   * The synthetic Archer Queen replay with every field its version writes, with a capture block
   * naming the given content sha.
   */
  private Path replay(String contentSha) throws IOException {
    ObjectNode document = fixture();
    ObjectNode capture = document.putObject("capture");
    capture.put("client_version", GameVersions.CLIENT_16_402_17);
    capture.put(ReplayCapture.CONTENT_VERSION, versions.current().version());
    capture.put(ReplayCapture.CONTENT_SHA, contentSha);
    capture.put("captured_at", "2026-10-06T03:47:38Z");
    return write(document);
  }

  /**
   * The fixture, its plays' items fitted to the configured tables, whose rows the root's two
   * versions copy: their costs and levels.
   */
  private static ObjectNode fixture() throws IOException {
    try (InputStream in =
        ReplayDataTest.class.getResourceAsStream("/replays/archer_queen_every_field.json")) {
      return ReplayItems.fitted((ObjectNode) MAPPER.readTree(in), GameTables.loadConfigured());
    }
  }

  private Path write(ObjectNode document) throws IOException {
    Path file = root.resolve("replay.json");
    MAPPER.writeValue(file.toFile(), document);
    return file;
  }

  private String printed() {
    return outBytes.toString(StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("a table file's header gives a version's content sha without loading its tables")
  void theContentShaOfAFolder() {
    assertThat(DataSelection.contentSha(second)).contains(OTHER_SHA);
    assertThat(DataSelection.contentSha(first)).contains(GameTables.load(first).contentSha());
    assertThat(DataSelection.contentSha(root.resolve("nowhere"))).isEmpty();
  }

  @Test
  @DisplayName("a replay naming another version's content sha opens on that version")
  void theVersionIsChosenBySha() throws IOException {
    ReplayFile replay = DesktopLauncher.openReplay(replay(OTHER_SHA), versions, null, out, err);

    assertThat(replay).isNotNull();
    assertThat(replay.refusals()).isEmpty();
    assertThat(replay.playable()).isTrue();
    assertThat(versions.currentFolder()).isEqualTo(second);
    assertThat(versions.current().contentSha()).isEqualTo(OTHER_SHA);
    assertThat(versions.source()).isEqualTo("named by the replay's capture block");
    assertThat(printed())
        .contains(
            "the replay names content sha "
                + OTHER_SHA
                + ": switched from data version 1.0.0 to 2.0.0 ("
                + second.toAbsolutePath().normalize()
                + ")");
    assertThat(replay.describe())
        .contains(
            "  recorded on: client 16.402.17, data version "
                + versions.current().version()
                + " (content sha "
                + OTHER_SHA
                + "), captured 2026-10-06T03:47:38Z",
            "  data version: "
                + versions.current().version()
                + " (content sha "
                + OTHER_SHA
                + "), named by the replay");
    assertThat(replay.dataLine())
        .isEqualTo(
            "Replay: data named by its capture block (client 16.402.17, captured"
                + " 2026-10-06T03:47:38Z)");
    assertThat(new ReplayPlayer(replay, versions.current()).statusLines(ViewOrientation.FLIPPED))
        .contains("recorded: client 16.402.17, 2026-10-06T03:47:38Z");
  }

  @Test
  @DisplayName("a replay naming the version on screen opens on it")
  void theVersionOnScreen() throws IOException {
    GameTables before = versions.current();
    ReplayFile replay =
        DesktopLauncher.openReplay(replay(before.contentSha()), versions, null, out, err);

    assertThat(replay.playable()).isTrue();
    assertThat(versions.current()).isSameAs(before);
    assertThat(printed()).doesNotContain("switched");
    assertThat(replay.describe())
        .contains(
            "  data version: "
                + before.version()
                + " (content sha "
                + before.contentSha()
                + "), named by the replay");
  }

  @Test
  @DisplayName("a replay of data no version of the root has is refused, never played on other data")
  void noVersionHasTheSha() throws IOException {
    GameTables before = versions.current();

    ReplayFile replay = DesktopLauncher.openReplay(replay(UNKNOWN_SHA), versions, null, out, err);

    assertThat(replay.playable()).isFalse();
    assertThat(versions.current()).isSameAs(before);
    assertThat(versions.currentFolder()).isEqualTo(first);
    assertThat(replay.refusals().get(0))
        .isEqualTo(
            "no data version of the data root "
                + root.toAbsolutePath().normalize()
                + " has the content sha "
                + UNKNOWN_SHA
                + " the replay was recorded on; it is not played on other data");
    assertThat(replay.refusals())
        .anySatisfy(
            refusal ->
                assertThat(refusal)
                    .startsWith(
                        "a replay recorded on client 16.402.17, data version "
                            + before.version()
                            + " (content sha "
                            + UNKNOWN_SHA
                            + "), read against the game tables of data version "
                            + before.version()
                            + " (content sha "
                            + before.contentSha()
                            + ")"));
    assertThat(printed()).contains("  refused, ");
  }

  @Test
  @DisplayName("a data version fixed at launch that is not the replay's is refused the same way")
  void anExplicitVersionThatDisagrees() throws IOException {
    GameTables before = versions.current();

    ReplayFile replay =
        DesktopLauncher.openReplay(
            replay(OTHER_SHA), versions, "--data-version 1.0.0 in the data root", out, err);

    assertThat(replay.playable()).isFalse();
    assertThat(versions.current()).isSameAs(before);
    assertThat(replay.refusals().get(0))
        .isEqualTo(
            "the data version is fixed by --data-version 1.0.0 in the data root, and the replay was"
                + " recorded on client 16.402.17, data version "
                + before.version()
                + " (content sha "
                + OTHER_SHA
                + "); it is not played on other data");
  }

  @Test
  @DisplayName("a replay with no capture block opens on the version on screen, marked assumed")
  void noBlockIsAssumed() throws IOException {
    GameTables before = versions.current();

    ReplayFile replay = DesktopLauncher.openReplay(write(fixture()), versions, null, out, err);

    assertThat(replay.playable()).isTrue();
    assertThat(versions.current()).isSameAs(before);
    String assumed =
        "  data version: "
            + before.version()
            + " (content sha "
            + before.contentSha()
            + "), assumed: the replay does not name the data it was recorded on";
    assertThat(replay.describe())
        .contains("  recorded on: not named by the replay (no capture block)", assumed);
    assertThat(printed()).contains(assumed);
    assertThat(replay.dataLine())
        .isEqualTo(
            "Replay: data version assumed, the replay does not name the data it was recorded on");
  }
}
