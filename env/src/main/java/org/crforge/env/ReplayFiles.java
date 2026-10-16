/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.airlift.compress.zstd.ZstdInputStream;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import org.crforge.core.battle.replay.ReplayCapture;

/**
 * The replays of a file, for a labeller: a replay document ({@code .json}), or a crawl's JSON Lines
 * output ({@code .jsonl}, plain, zstd or gzip, found by its first bytes) as the desktop replay
 * viewer reads it: one record a line, format {@value #FORMAT}, the replay as a string, the battle's
 * data version and content sha beside it. A record's replay is given the capture block its record
 * names (the session's client version, the battle's data version and content sha, the fetch time),
 * so it is read only on the tables of that version. A record that cannot be read, or names
 * candidate data versions instead of one, is listed with why.
 */
public final class ReplayFiles {

  /** The crawl record format read. */
  public static final int FORMAT = 1;

  /** RFC 8878: the zstd frame magic number, little-endian. */
  private static final byte[] ZSTD = {0x28, (byte) 0xb5, 0x2f, (byte) 0xfd};

  /** RFC 1952: the gzip magic number. */
  private static final byte[] GZIP = {0x1f, (byte) 0x8b};

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ReplayFiles() {
    // Utility class
  }

  /**
   * One replay of a file.
   *
   * @param name the file's name, with {@code :<line>} for a record of a JSON Lines file
   * @param document the replay document as JSON text, or null when it cannot be read
   * @param problem why it cannot be read, or null
   */
  public record Source(String name, String document, String problem) {}

  /**
   * Reads a file's replays.
   *
   * @param file a replay document, or a crawl's JSON Lines output
   * @return its replays, in the file's order, those that cannot be read among them
   * @throws IOException when the file cannot be read or decompressed
   */
  public static List<Source> read(Path file) throws IOException {
    String name = file.toString();
    String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
    if (lower.endsWith(".json")) {
      return List.of(new Source(name, Files.readString(file, StandardCharsets.UTF_8), null));
    }
    List<Source> sources = new ArrayList<>();
    try (InputStream raw = new BufferedInputStream(Files.newInputStream(file));
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(decompressed(raw), StandardCharsets.UTF_8))) {
      String text;
      int line = 0;
      while ((text = reader.readLine()) != null) {
        line++;
        if (!text.isBlank()) {
          sources.add(record(name + ":" + line, text));
        }
      }
    }
    return sources;
  }

  /** The stream decompressed by the format its first bytes name, or itself. */
  private static InputStream decompressed(InputStream raw) throws IOException {
    raw.mark(ZSTD.length);
    byte[] head = raw.readNBytes(ZSTD.length);
    raw.reset();
    if (head.length >= ZSTD.length && Arrays.equals(head, ZSTD)) {
      return new ZstdInputStream(raw);
    }
    if (head.length >= GZIP.length && head[0] == GZIP[0] && head[1] == GZIP[1]) {
      return new GZIPInputStream(raw);
    }
    return raw;
  }

  /** One record's replay with its capture block, or why it cannot be read. */
  private static Source record(String name, String text) {
    JsonNode record;
    try {
      record = MAPPER.readTree(text);
    } catch (JsonProcessingException e) {
      return new Source(name, null, "not JSON: " + e.getOriginalMessage());
    }
    if (!record.isObject() || record.path("format").asInt(-1) != FORMAT) {
      return new Source(name, null, "not a record of format " + FORMAT);
    }
    JsonNode replayText = record.get("replay");
    if (replayText == null || !replayText.isTextual()) {
      return new Source(name, null, "no replay string");
    }
    JsonNode battle = record.path("battle");
    if (battle.has("candidates")) {
      return new Source(name, null, "its data version is unsure: it names candidates");
    }
    ObjectNode replay;
    try {
      JsonNode parsed = MAPPER.readTree(replayText.asText());
      if (!parsed.isObject()) {
        return new Source(name, null, "its replay is not a JSON object");
      }
      replay = (ObjectNode) parsed;
    } catch (JsonProcessingException e) {
      return new Source(name, null, "its replay is not JSON: " + e.getOriginalMessage());
    }
    if (replay.has(ReplayCapture.FIELD)) {
      return new Source(name, null, "its replay already holds a capture block");
    }
    String contentSha = text(battle, ReplayCapture.CONTENT_SHA);
    if (contentSha != null) {
      ObjectNode capture = replay.putObject(ReplayCapture.FIELD);
      put(capture, ReplayCapture.CLIENT_VERSION, text(record.path("session"), "client_version"));
      put(capture, ReplayCapture.CONTENT_VERSION, text(battle, ReplayCapture.CONTENT_VERSION));
      put(capture, ReplayCapture.CONTENT_SHA, contentSha);
      put(capture, ReplayCapture.CAPTURED_AT, text(record.path("session"), "fetched_at"));
    }
    try {
      return new Source(name, MAPPER.writeValueAsString(replay), null);
    } catch (JsonProcessingException e) {
      return new Source(name, null, "cannot be written: " + e.getOriginalMessage());
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isTextual() ? value.asText() : null;
  }

  private static void put(ObjectNode block, String field, String value) {
    if (value != null) {
      block.put(field, value);
    }
  }
}
