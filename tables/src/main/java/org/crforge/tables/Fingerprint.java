/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.tables;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The list of a content set's files the asset CDN serves as {@code <content sha>/fingerprint.json}:
 * the set's sha, the CDN's label for it, and every file's path and SHA-1.
 *
 * @param sha the content sha
 * @param label the CDN's version label of the set (not the data version)
 * @param files every file of the set
 */
record Fingerprint(String sha, String label, List<Fingerprint.Entry> files) {

  /**
   * One file of the set.
   *
   * @param path its path in the set, e.g. {@code csv_logic/characters.csv}
   * @param sha1 the SHA-1 of the file as served, in lowercase hex
   */
  record Entry(String path, String sha1) {}

  static Fingerprint parse(byte[] json) throws IOException {
    JsonNode root = new ObjectMapper().readTree(json);
    List<Entry> files = new ArrayList<>();
    for (JsonNode f : root.path("files")) {
      files.add(new Entry(f.path("file").asText(), f.path("sha").asText()));
    }
    return new Fingerprint(
        root.path("sha").asText(), root.path("version").asText(), List.copyOf(files));
  }
}
