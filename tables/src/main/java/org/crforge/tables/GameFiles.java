/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.tables;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import org.tukaani.xz.LZMAInputStream;

/**
 * The data files of one content set, decoded to text: every file its fingerprint lists under {@code
 * csv_logic/} and {@code csv_client/}, and {@code data_manifest.toml}, the files the client's table
 * loaders read.
 *
 * <p>A file as served is 5 bytes of LZMA properties, the 4-byte little-endian size of the decoded
 * text, then an LZMA1 stream without an end marker. The decoded text must have exactly the declared
 * size and be UTF-8. Line breaks are read as {@code \n}, whichever of {@code \r\n}, {@code \r} or
 * {@code \n} the file uses.
 *
 * @param dataVersion the data version the set is read as
 * @param contentSha the content sha of the set
 * @param texts the decoded text of each file, by path, sorted by path
 */
record GameFiles(String dataVersion, String contentSha, Map<String, String> texts) {

  /** The folders whose files are read, at every depth. */
  static final String[] FOLDERS = {"csv_logic/", "csv_client/"};

  /** The manifest of the per-unit files. */
  static final String MANIFEST = "data_manifest.toml";

  private static final int HEADER = 9;

  /** Whether the table loaders read a file of this path. */
  static boolean read(String path) {
    for (String folder : FOLDERS) {
      if (path.startsWith(folder)) {
        return true;
      }
    }
    return path.equals(MANIFEST);
  }

  /**
   * Fetches (through the cache) and decodes the files of a data version.
   *
   * @throws IOException when a file cannot be fetched, does not match its fingerprint or does not
   *     decode
   */
  static GameFiles fetch(DataVersion version, AssetSource source, AssetCache cache)
      throws IOException {
    Fingerprint fingerprint = cache.fingerprint(source, version.contentSha());
    Map<String, String> texts = new TreeMap<>();
    for (Fingerprint.Entry entry : fingerprint.files()) {
      if (!read(entry.path())) {
        continue;
      }
      byte[] raw = cache.file(source, version.contentSha(), entry);
      try {
        texts.put(entry.path(), text(decode(raw)));
      } catch (IOException e) {
        throw new IOException(entry.path() + ": " + e.getMessage(), e);
      }
    }
    if (!texts.containsKey(MANIFEST)) {
      throw new IOException("the fingerprint of " + version.contentSha() + " lists no " + MANIFEST);
    }
    return new GameFiles(
        version.version(), version.contentSha(), Collections.unmodifiableMap(texts));
  }

  /** A decoded text of the set, or null when the set has no file of that path. */
  String text(String path) {
    return texts.get(path);
  }

  /**
   * One file as served, decoded to its bytes.
   *
   * @throws IOException when it is not the game's LZMA framing or decodes to another size
   */
  static byte[] decode(byte[] raw) throws IOException {
    if (raw.length < HEADER) {
      throw new IOException("shorter than the 9-byte header");
    }
    int props = raw[0] & 0xff;
    if (props >= 9 * 5 * 5) {
      throw new IOException("invalid LZMA properties");
    }
    ByteBuffer header = ByteBuffer.wrap(raw, 1, 8).order(ByteOrder.LITTLE_ENDIAN);
    int dictSize = header.getInt();
    long size = header.getInt() & 0xffffffffL;
    byte[] text;
    try {
      text = inflate(raw, size, (byte) props, dictSize);
    } catch (IOException e) {
      // a stream that ends with an end marker
      text = inflate(raw, -1, (byte) props, dictSize);
    }
    if (text.length != size) {
      throw new IOException("decoded " + text.length + " bytes, the header declares " + size);
    }
    return text;
  }

  private static byte[] inflate(byte[] raw, long size, byte props, int dictSize)
      throws IOException {
    InputStream stream = new ByteArrayInputStream(raw, HEADER, raw.length - HEADER);
    try (LZMAInputStream in = new LZMAInputStream(stream, size, props, dictSize)) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      in.transferTo(out);
      return out.toByteArray();
    }
  }

  /** Decoded bytes as text: strict UTF-8, line breaks as {@code \n}. */
  static String text(byte[] bytes) throws IOException {
    try {
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      return ClientCsv.universalNewlines(text);
    } catch (CharacterCodingException e) {
      throw new IOException("not UTF-8", e);
    }
  }
}
