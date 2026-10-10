/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.tables;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A data version the decoder can build the game tables of: the version the game client names the
 * data by, the client version whose schema reads it, and the content sha its files are served
 * under. The asset CDN labels a set with a version of its own, so the data version is named here,
 * never taken from the CDN's fingerprint. The pairs are those of {@code docs/game-versions.md}.
 *
 * @param version the data version, e.g. {@code 16.402.19}
 * @param clientVersion the client version whose schema reads it
 * @param contentSha the content sha of its files
 */
public record DataVersion(String version, String clientVersion, String contentSha) {

  private static final Map<String, DataVersion> KNOWN = load();

  /**
   * The data version of that name.
   *
   * @throws IllegalArgumentException when the decoder does not know it
   */
  public static DataVersion of(String version) {
    DataVersion known = KNOWN.get(version);
    if (known == null) {
      throw new IllegalArgumentException(
          "data version " + version + " is not one the decoder knows; known: " + KNOWN.keySet());
    }
    return known;
  }

  /** Every data version the decoder knows, by name, in the order listed. */
  public static Map<String, DataVersion> known() {
    return KNOWN;
  }

  private static Map<String, DataVersion> load() {
    try (InputStream in = DataVersion.class.getResourceAsStream("data-versions.json")) {
      JsonNode root = new ObjectMapper().readTree(in);
      Map<String, DataVersion> out = new LinkedHashMap<>();
      root.properties()
          .forEach(
              e ->
                  out.put(
                      e.getKey(),
                      new DataVersion(
                          e.getKey(),
                          e.getValue().path("client_version").asText(),
                          e.getValue().path("content_sha").asText())));
      return Collections.unmodifiableMap(out);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read the data versions", e);
    }
  }
}
