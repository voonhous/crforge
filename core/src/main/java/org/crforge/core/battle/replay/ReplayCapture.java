/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.replay;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;

/**
 * A replay's capture block: the optional top-level object {@value #FIELD} that the tool that saved
 * the replay writes, never the game. It names the game client version and the data the session that
 * recorded the replay ran:
 *
 * <pre>
 * "capture": {"client_version": "16.402.17", "content_version": "16.402.18",
 *             "content_sha": "8aa8015226b0062c7e16a793522de91e564ffdaf",
 *             "captured_at": "2026-10-06T03:47:38Z"}
 * </pre>
 *
 * <p>The client version and the content sha are always given; the data version is left out when the
 * tool knew no data version of that sha, and the capture time (UTC, to the second) may be left out.
 * The block names the data the recording session ran, which is the battle's data unless the game
 * data changed between the battle and the recording. It holds no battle input. The replay mapping
 * ({@link ReplayScenario}) reads it strictly and refuses a replay whose block names other data than
 * the tables it is read against; {@link #of} reads it leniently, so a viewer can pick the tables
 * before the mapping runs.
 *
 * @param clientVersion the game client version, such as {@code 16.402.17}
 * @param contentVersion the data version, or null when the block names none
 * @param contentSha the content sha of the data, as the game tables' headers give it
 * @param capturedAt when the replay was saved, or null when the block gives no time
 */
public record ReplayCapture(
    String clientVersion, String contentVersion, String contentSha, String capturedAt) {

  /** The replay's top-level field that holds the block. */
  public static final String FIELD = "capture";

  /** The block's field naming the client version. */
  public static final String CLIENT_VERSION = "client_version";

  /** The block's field naming the data version. */
  public static final String CONTENT_VERSION = ContentFields.CONTENT_VERSION;

  /** The block's field naming the content sha. */
  public static final String CONTENT_SHA = ContentFields.CONTENT_SHA;

  /** The block's field naming the capture time. */
  public static final String CAPTURED_AT = "captured_at";

  /** Every field the block may hold, in the order the tool writes them. */
  public static final List<String> FIELDS =
      List.of(CLIENT_VERSION, CONTENT_VERSION, CONTENT_SHA, CAPTURED_AT);

  /**
   * The capture block of a replay document, read leniently: present when the document's {@value
   * #FIELD} is an object whose content sha is a string. The mapping decides whether the rest of it
   * is acceptable.
   *
   * @param replay the replay document
   * @return the block, or empty when the replay has none that names a content sha
   */
  public static Optional<ReplayCapture> of(JsonNode replay) {
    JsonNode block = replay.get(FIELD);
    if (block == null || !block.isObject() || !block.path(CONTENT_SHA).isTextual()) {
      return Optional.empty();
    }
    return Optional.of(
        new ReplayCapture(
            text(block, CLIENT_VERSION),
            text(block, CONTENT_VERSION),
            block.path(CONTENT_SHA).asText(),
            text(block, CAPTURED_AT)));
  }

  /**
   * What the replay was recorded on, as a refusal or a viewer names it: {@code client 16.402.17,
   * data version 16.402.18 (content sha 8aa8...)}.
   */
  public String recordedOn() {
    return "client "
        + (clientVersion == null ? "not named" : clientVersion)
        + ", data version "
        + (contentVersion == null ? "not named" : contentVersion)
        + " (content sha "
        + contentSha
        + ")";
  }

  private static String text(JsonNode block, String field) {
    JsonNode value = block.get(field);
    return value != null && value.isTextual() ? value.asText() : null;
  }
}
