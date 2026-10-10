/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.replay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.replay.ReplayCapture;
import org.crforge.core.battle.replay.ReplayScenario;

/**
 * A crawl's output: many replays in one JSON Lines file, gzip-compressed or not, one record a line.
 * A record holds the replay as the server sent it, as a string, with what the crawl knew of it:
 *
 * <pre>
 * {"format": 1, "channel_id": 162000038,
 *  "session": {"client_version": "16.402.17", "content_sha": "7e76...", "fetched_at": "..."},
 *  "battle": {"content_version": "16.402.19", "content_sha": "7e76..."},
 *  "entry": {...the listing the replay was found in...},
 *  "replay": "{\"battle\":{...},\"cmd\":[...],...}"}
 * </pre>
 *
 * <p>Each record is read on its own, so one that cannot be read is listed with why and leaves the
 * others readable. A replay is opened with a capture block ({@link ReplayCapture}) built from its
 * record, added in memory only: the session's client version, the battle's data version and content
 * sha, and the fetch time. The file is never changed.
 */
public final class ReplayArchive {

  /** The record format this viewer reads. */
  public static final int FORMAT = 1;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The battle time as a list row shows it, in UTC. */
  private static final DateTimeFormatter BATTLE_TIME =
      DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC);

  /** The most characters a row gives a game mode or arena name. */
  private static final int NAME_SHOWN = 20;

  private final Path file;
  private final List<Entry> entries;

  /**
   * One record of the file, read leniently for the list: what is missing is -1 or null.
   *
   * @param line the record's line in the file, from 1
   * @param channelId the channel the replay was listed on, or -1
   * @param clientVersion the game client version of the session that fetched it, or null
   * @param fetchedAt when the replay was fetched (UTC, to the second), or null
   * @param contentVersion the battle's data version, or null
   * @param contentSha the battle's content sha, or null
   * @param gameMode the replay's game mode id, or -1
   * @param arena the replay's arena id, or -1
   * @param endTick the replay's last tick, or -1
   * @param battleTime the replay's time, in seconds since the epoch, or -1
   * @param replay the replay document as received, or null when the record cannot be read
   * @param problem why the record cannot be read, or null when it can
   */
  public record Entry(
      int line,
      long channelId,
      String clientVersion,
      String fetchedAt,
      String contentVersion,
      String contentSha,
      int gameMode,
      int arena,
      int endTick,
      long battleTime,
      String replay,
      String problem) {

    /** A record that cannot be read, with why. */
    static Entry unreadable(int line, String problem) {
      return new Entry(line, -1, null, null, null, null, -1, -1, -1, -1, null, problem);
    }

    /**
     * The replay document with the capture block its record names, built fresh on each call. A
     * record without a content sha gives no block, so the replay is read on the data assumed.
     *
     * @throws IllegalStateException when the record cannot be read
     * @throws IOException when the replay is not JSON (checked when the file was read)
     */
    public ObjectNode document() throws IOException {
      if (problem != null) {
        throw new IllegalStateException("line " + line + " cannot be read: " + problem);
      }
      ObjectNode document = (ObjectNode) MAPPER.readTree(replay);
      if (contentSha != null) {
        ObjectNode capture = document.putObject(ReplayCapture.FIELD);
        put(capture, ReplayCapture.CLIENT_VERSION, clientVersion);
        put(capture, ReplayCapture.CONTENT_VERSION, contentVersion);
        put(capture, ReplayCapture.CONTENT_SHA, contentSha);
        put(capture, ReplayCapture.CAPTURED_AT, fetchedAt);
      }
      return document;
    }

    private static void put(ObjectNode block, String field, String value) {
      if (value != null) block.put(field, value);
    }
  }

  private ReplayArchive(Path file, List<Entry> entries) {
    this.file = file;
    this.entries = List.copyOf(entries);
  }

  /** Whether a file is named as a crawl's output: {@code .jsonl} or {@code .jsonl.gz}. */
  public static boolean isArchive(Path file) {
    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
    return name.endsWith(".jsonl") || name.endsWith(".jsonl.gz");
  }

  /**
   * Reads a crawl's output. A gzip-compressed file is found by its first two bytes, not its name;
   * blank lines are skipped.
   *
   * @param file the file
   * @return its records, those that cannot be read among them
   * @throws IOException when the file cannot be read or decompressed
   */
  public static ReplayArchive read(Path file) throws IOException {
    List<Entry> entries = new ArrayList<>();
    try (InputStream raw = new BufferedInputStream(Files.newInputStream(file));
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(decompressed(raw), StandardCharsets.UTF_8))) {
      String text;
      int line = 0;
      while ((text = reader.readLine()) != null) {
        line++;
        if (!text.isBlank()) {
          entries.add(entry(line, text));
        }
      }
    }
    return new ReplayArchive(file, entries);
  }

  /** The stream itself, or its gzip decompression when it starts with gzip's magic bytes. */
  private static InputStream decompressed(InputStream raw) throws IOException {
    raw.mark(2);
    int first = raw.read();
    int second = raw.read();
    raw.reset();
    // A crawl that appends to its output writes one gzip member per write; the stream reads all.
    return first == 0x1f && second == 0x8b ? new GZIPInputStream(raw) : raw;
  }

  /** One line's record, or why it cannot be read. */
  private static Entry entry(int line, String text) {
    JsonNode record;
    try {
      record = MAPPER.readTree(text);
    } catch (JsonProcessingException e) {
      return Entry.unreadable(line, "not JSON: " + e.getOriginalMessage());
    }
    if (!record.isObject()) {
      return Entry.unreadable(line, "not a record");
    }
    if (record.path("format").asInt(-1) != FORMAT) {
      return Entry.unreadable(
          line, "record format " + record.get("format") + ", this viewer reads " + FORMAT);
    }
    JsonNode replayText = record.get("replay");
    if (replayText == null || !replayText.isTextual()) {
      return Entry.unreadable(line, "no replay string");
    }
    JsonNode replay;
    try {
      replay = MAPPER.readTree(replayText.asText());
    } catch (JsonProcessingException e) {
      return Entry.unreadable(line, "its replay is not JSON: " + e.getOriginalMessage());
    }
    if (!replay.isObject()) {
      return Entry.unreadable(line, "its replay is not a JSON object");
    }
    if (replay.has(ReplayCapture.FIELD)) {
      // The block is added from the record; a replay that already holds one is not the server's.
      return Entry.unreadable(line, "its replay already holds a capture block");
    }
    JsonNode session = record.path("session");
    JsonNode battle = record.path("battle");
    JsonNode header = replay.path("battle");
    return new Entry(
        line,
        record.path("channel_id").asLong(-1),
        text(session, "client_version"),
        text(session, "fetched_at"),
        text(battle, ReplayCapture.CONTENT_VERSION),
        text(battle, ReplayCapture.CONTENT_SHA),
        header.path("gamemode").asInt(-1),
        header.path("arena").asInt(-1),
        replay.path("endTick").asInt(-1),
        replay.path("time").asLong(-1),
        replayText.asText(),
        null);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isTextual() ? value.asText() : null;
  }

  /** The file the records were read from. */
  public Path file() {
    return file;
  }

  /** The records, in the file's order. */
  public List<Entry> entries() {
    return entries;
  }

  /** How many records can be opened. */
  public long readable() {
    return entries.stream().filter(entry -> entry.problem() == null).count();
  }

  /**
   * The list's rows, one a record: its line, the battle's time (UTC), game mode, arena, length in
   * ticks and data version, each id named by its row in the tables where they hold it; a record
   * that cannot be read gives its line and why.
   *
   * @param tables the tables that name the ids
   */
  public List<String> rows(GameTables tables) {
    ReplayScenario names = new ReplayScenario(tables);
    List<String> rows = new ArrayList<>();
    for (Entry entry : entries) {
      if (entry.problem() != null) {
        rows.add(String.format(Locale.ROOT, "%5d  not read: %s", entry.line(), entry.problem()));
        continue;
      }
      rows.add(
          String.format(
              Locale.ROOT,
              "%5d  %-11s  %-20s  %-20s  %5s  %s",
              entry.line(),
              entry.battleTime() < 0
                  ? "?"
                  : BATTLE_TIME.format(Instant.ofEpochSecond(entry.battleTime())),
              name(entry.gameMode(), names),
              name(entry.arena(), names),
              entry.endTick() < 0 ? "?" : String.valueOf(entry.endTick()),
              entry.contentVersion() == null ? "data ?" : entry.contentVersion()));
    }
    return rows;
  }

  /** The list's column titles, aligned with {@link #rows}. */
  public static String columns() {
    return String.format(
        Locale.ROOT,
        "%5s  %-11s  %-20s  %-20s  %5s  %s",
        "line",
        "battle UTC",
        "game mode",
        "arena",
        "ticks",
        "data");
  }

  /** An id's row name, cut to fit its column, else the id, else "?" when the record gives none. */
  private static String name(int id, ReplayScenario names) {
    if (id < 0) {
      return "?";
    }
    String name = names.find(id).map(GameRow::name).orElse("#" + id);
    return name.length() <= NAME_SHOWN ? name : name.substring(0, NAME_SHOWN - 1) + "~";
  }
}
