package org.crforge.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.data.GameVersions;
import org.crforge.core.battle.replay.ReplayCapture;
import org.crforge.core.battle.replay.ScenarioItems;
import org.crforge.desktop.battle.DataVersions;
import org.crforge.desktop.battle.TableCopies;
import org.crforge.desktop.render.ViewOrientation;
import org.crforge.desktop.replay.ReplayArchive;
import org.crforge.desktop.replay.ReplayFile;
import org.crforge.desktop.replay.ReplayPlayer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A crawl's output: one record a line, gzip-compressed or not, each holding a replay as a string
 * with the session and battle it was fetched in. The records are built from the synthetic Archer
 * Queen replay; the data root's two versions are copies of the configured tables, the second with
 * another content sha in its headers.
 */
class ReplayArchiveTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The content sha of the root's second version. */
  private static final String OTHER_SHA = "0123456789abcdef0123456789abcdef01234567";

  private static final String FETCHED_AT = "2026-10-07T14:03:38Z";

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
   * The data version both of the root's versions hold: the copies keep the configured tables'
   * version in their headers, only their folders are named 1.0.0 and 2.0.0.
   */
  private String version() {
    return versions.current().version();
  }

  /**
   * The synthetic Archer Queen replay with every field its version writes, as the server sends it:
   * one line of compact JSON. Its plays' items are fitted to the configured tables, whose rows the
   * root's two versions copy: their costs and levels.
   */
  private static String replayText() throws IOException {
    try (InputStream in =
        ReplayArchiveTest.class.getResourceAsStream("/replays/archer_queen_every_field.json")) {
      return MAPPER.writeValueAsString(
          ScenarioItems.fitted((ObjectNode) MAPPER.readTree(in), GameTables.loadConfigured()));
    }
  }

  /** A crawl record holding the replay, its battle on the data of the given content sha. */
  private static String record(String replay, String contentVersion, String contentSha)
      throws IOException {
    ObjectNode record = MAPPER.createObjectNode();
    record.put("format", 1);
    record.put("channel_id", 162000038L);
    ObjectNode session = record.putObject("session");
    session.put("client_version", GameVersions.CLIENT_16_402_17);
    session.put("content_sha", contentSha);
    session.put("fetched_at", FETCHED_AT);
    ObjectNode battle = record.putObject("battle");
    battle.put("content_version", contentVersion);
    battle.put("content_sha", contentSha);
    record.putObject("entry").put("high", 1);
    record.put("replay", replay);
    return MAPPER.writeValueAsString(record);
  }

  /** Writes lines as one gzip member. */
  private Path gzip(String name, List<String> lines) throws IOException {
    Path file = root.resolve(name);
    try (OutputStream gz = new GZIPOutputStream(Files.newOutputStream(file))) {
      gz.write((String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
    }
    return file;
  }

  private String printed() {
    return outBytes.toString(StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("each line is a record: one that cannot be read says why and leaves the others")
  void eachLineIsRead() throws IOException {
    String good = record(replayText(), version(), OTHER_SHA);
    Path file =
        gzip(
            "crawl.jsonl.gz",
            List.of(
                good,
                "",
                "{not json",
                good.replace("\"format\":1", "\"format\":2"),
                record("[1,2]", "2.0.0", OTHER_SHA),
                good));

    ReplayArchive archive = ReplayArchive.read(file);

    assertThat(archive.entries())
        .extracting(ReplayArchive.Entry::line)
        .containsExactly(1, 3, 4, 5, 6);
    assertThat(archive.readable()).isEqualTo(2);
    assertThat(archive.entries().get(1).problem()).startsWith("not JSON: ");
    assertThat(archive.entries().get(2).problem())
        .isEqualTo("record format 2, this viewer reads 1");
    assertThat(archive.entries().get(3).problem()).isEqualTo("its replay is not a JSON object");
    ReplayArchive.Entry entry = archive.entries().get(0);
    assertThat(entry.problem()).isNull();
    assertThat(entry.channelId()).isEqualTo(162000038L);
    assertThat(entry.clientVersion()).isEqualTo(GameVersions.CLIENT_16_402_17);
    assertThat(entry.contentVersion()).isEqualTo(version());
    assertThat(entry.contentSha()).isEqualTo(OTHER_SHA);
    assertThat(entry.gameMode()).isEqualTo(72000006);
    assertThat(entry.endTick()).isEqualTo(400);
    assertThat(entry.replay()).isEqualTo(replayText());
  }

  @Test
  @DisplayName("a file that is not compressed, and one of several gzip members, read the same")
  void plainAndAppendedFiles() throws IOException {
    String good = record(replayText(), version(), OTHER_SHA);
    Path plain = root.resolve("crawl.jsonl");
    Files.writeString(plain, good + "\n" + good + "\n", StandardCharsets.UTF_8);
    // A crawl appending to its output writes one member per write.
    Path appended = gzip("appended.jsonl.gz", List.of(good));
    try (OutputStream gz =
        new GZIPOutputStream(Files.newOutputStream(appended, StandardOpenOption.APPEND))) {
      gz.write((good + "\n").getBytes(StandardCharsets.UTF_8));
    }

    assertThat(ReplayArchive.read(plain).readable()).isEqualTo(2);
    assertThat(ReplayArchive.read(appended).readable()).isEqualTo(2);
    assertThat(ReplayArchive.read(appended).entries())
        .extracting(ReplayArchive.Entry::line)
        .containsExactly(1, 2);
  }

  @Test
  @DisplayName("a replay is opened with the capture block its record names, the file unchanged")
  void theRecordNamesTheData() throws IOException {
    Path file = gzip("crawl.jsonl.gz", List.of(record(replayText(), version(), OTHER_SHA)));
    byte[] before = Files.readAllBytes(file);
    ReplayArchive archive = DesktopLauncher.openArchive(file, out, err);

    ReplayFile replay = DesktopLauncher.openFirst(archive, versions, null, out, err);

    assertThat(replay.playable()).isTrue();
    assertThat(replay.dataNamed()).isTrue();
    assertThat(versions.currentFolder()).isEqualTo(second);
    assertThat(replay.capture())
        .contains(
            new ReplayCapture(GameVersions.CLIENT_16_402_17, version(), OTHER_SHA, FETCHED_AT));
    assertThat(replay.line()).isEqualTo(1);
    assertThat(replay.name()).isEqualTo("crawl.jsonl.gz line 1");
    assertThat(replay.describe())
        .contains("replay: " + file.toAbsolutePath().normalize() + ", line 1");
    assertThat(new ReplayPlayer(replay, versions.current()).statusLines(ViewOrientation.FLIPPED))
        .contains("replay: crawl.jsonl.gz line 1");
    assertThat(printed())
        .contains(
            "replays: " + file.toAbsolutePath().normalize() + ", 1 records, 1 readable",
            "switched from data version 1.0.0 to 2.0.0");
    assertThat(Files.readAllBytes(file)).isEqualTo(before);
  }

  @Test
  @DisplayName("each record is opened on its own data, the first readable one first")
  void eachRecordOnItsData() throws IOException {
    String firstSha = versions.current().contentSha();
    Path file =
        gzip(
            "crawl.jsonl.gz",
            List.of(
                "{not json",
                record(replayText(), version(), OTHER_SHA),
                record(replayText(), version(), firstSha)));
    ReplayArchive archive = ReplayArchive.read(file);

    ReplayFile opened = DesktopLauncher.openFirst(archive, versions, null, out, err);
    assertThat(opened.line()).isEqualTo(2);
    assertThat(versions.currentFolder()).isEqualTo(second);

    ReplayFile third =
        DesktopLauncher.openEntry(archive, archive.entries().get(2), versions, null, out, err);
    assertThat(third.playable()).isTrue();
    assertThat(versions.currentFolder()).isEqualTo(first);

    assertThat(
            DesktopLauncher.openEntry(archive, archive.entries().get(0), versions, null, out, err))
        .isNull();
    assertThat(errBytes.toString(StandardCharsets.UTF_8))
        .contains("Cannot read line 1 of " + file.toAbsolutePath().normalize() + ": not JSON: ");
  }

  @Test
  @DisplayName("a replay that already holds a capture block is not read: the record names its data")
  void aBlockInTheReplayIsRefused() throws IOException {
    ObjectNode replay = (ObjectNode) MAPPER.readTree(replayText());
    replay.putObject("capture").put("content_sha", OTHER_SHA);
    Path file =
        gzip("crawl.jsonl.gz", List.of(record(MAPPER.writeValueAsString(replay), "2.0.0", "x")));

    ReplayArchive.Entry entry = ReplayArchive.read(file).entries().get(0);

    assertThat(entry.problem()).isEqualTo("its replay already holds a capture block");
    assertThatThrownBy(entry::document).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("the list gives each record's time, game mode, arena, ticks and data, or why not")
  void theListsRows() throws IOException {
    Path file =
        gzip(
            "crawl.jsonl.gz", List.of(record(replayText(), version(), OTHER_SHA), "", "{not json"));

    List<String> rows = ReplayArchive.read(file).rows(versions.current());

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0))
        .matches(
            "    1  04-01 \\d\\d:\\d\\d  Ladder +  \\S.* +    400  "
                + version().replace(".", "\\."));
    assertThat(rows.get(1)).startsWith("    3  not read: not JSON: ");
    assertThat(ReplayArchive.columns().indexOf("game mode"))
        .isEqualTo(rows.get(0).indexOf("Ladder"));
  }

  @Test
  @DisplayName("a crawl's output is named .jsonl or .jsonl.gz")
  void theNames() {
    assertThat(ReplayArchive.isArchive(Path.of("2026-10-07T140327Z.jsonl.gz"))).isTrue();
    assertThat(ReplayArchive.isArchive(Path.of("CRAWL.JSONL"))).isTrue();
    assertThat(ReplayArchive.isArchive(Path.of("replay.json"))).isFalse();
    assertThat(ReplayArchive.isArchive(Path.of("tables.gz"))).isFalse();
  }
}
