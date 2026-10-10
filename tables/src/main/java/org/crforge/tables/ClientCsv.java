/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.tables;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits the CSV files of the game's data into records and cells, as the game tables have always
 * been read: comma separated, a cell quoted with {@code "} may hold commas and line breaks, {@code
 * ""} inside quotes is one quote, and a quote in the middle of an unquoted cell is an ordinary
 * character, as are any characters after a closing quote. A blank line is a record of no cells.
 */
final class ClientCsv {

  private enum State {
    START_RECORD,
    START_FIELD,
    IN_FIELD,
    IN_QUOTED_FIELD,
    QUOTE_IN_QUOTED_FIELD,
    EAT_NEWLINE
  }

  /** U+2028 and U+2029, written as numbers so the source stays ASCII. */
  private static final char LINE_SEPARATOR = (char) 0x2028;

  private static final char PARAGRAPH_SEPARATOR = (char) 0x2029;

  private ClientCsv() {}

  /** The text's lines with their line breaks, as {@code \n}, {@code \r\n} or {@code \r}. */
  static String universalNewlines(String text) {
    return text.replace("\r\n", "\n").replace('\r', '\n');
  }

  /**
   * The records of a text whose line breaks are {@code \n} (see {@link #universalNewlines}): each
   * line keeps its {@code \n}, and a quoted cell may go on over the next lines.
   */
  static List<List<String>> records(String text) {
    List<String> lines = new ArrayList<>();
    int start = 0;
    while (start < text.length()) {
      int end = text.indexOf('\n', start);
      end = end < 0 ? text.length() : end + 1;
      lines.add(text.substring(start, end));
      start = end;
    }
    return parse(lines);
  }

  /**
   * The records of a text split into lines without their line breaks, at every line boundary
   * Python's {@code str.splitlines} knows.
   */
  static List<List<String>> splitLineRecords(String text) {
    List<String> lines = new ArrayList<>();
    int start = 0;
    int i = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
        lines.add(text.substring(start, i));
        i += 2;
        start = i;
      } else if (isLineBoundary(c)) {
        lines.add(text.substring(start, i));
        i++;
        start = i;
      } else {
        i++;
      }
    }
    if (start < text.length()) {
      lines.add(text.substring(start));
    }
    return parse(lines);
  }

  private static boolean isLineBoundary(char c) {
    return c == '\n'
        || c == '\r'
        || c == '\u000b'
        || c == '\u000c'
        || c == '\u001c'
        || c == '\u001d'
        || c == '\u001e'
        || c == '\u0085'
        || c == LINE_SEPARATOR
        || c == PARAGRAPH_SEPARATOR;
  }

  private static List<List<String>> parse(List<String> lines) {
    List<List<String>> records = new ArrayList<>();
    List<String> fields = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    State state = State.START_RECORD;
    int next = 0;
    while (true) {
      if (next == lines.size()) {
        // the end of the text inside a record: keep what was read
        if (field.length() > 0 || state == State.IN_QUOTED_FIELD) {
          fields.add(field.toString());
          records.add(fields);
        }
        return records;
      }
      String line = lines.get(next++);
      for (int i = 0; i <= line.length(); i++) {
        boolean eol = i == line.length();
        char c = eol ? 0 : line.charAt(i);
        switch (state) {
          case START_RECORD:
            if (eol) {
              break;
            }
            if (c == '\n' || c == '\r') {
              state = State.EAT_NEWLINE;
              break;
            }
            state = State.START_FIELD;
          // fall through: the character starts the first cell
          case START_FIELD:
            if (eol || c == '\n' || c == '\r') {
              fields.add(field.toString());
              field.setLength(0);
              state = eol ? State.START_RECORD : State.EAT_NEWLINE;
            } else if (c == '"') {
              state = State.IN_QUOTED_FIELD;
            } else if (c == ',') {
              fields.add(field.toString());
              field.setLength(0);
            } else {
              field.append(c);
              state = State.IN_FIELD;
            }
            break;
          case IN_FIELD:
            if (eol || c == '\n' || c == '\r') {
              fields.add(field.toString());
              field.setLength(0);
              state = eol ? State.START_RECORD : State.EAT_NEWLINE;
            } else if (c == ',') {
              fields.add(field.toString());
              field.setLength(0);
              state = State.START_FIELD;
            } else {
              field.append(c);
            }
            break;
          case IN_QUOTED_FIELD:
            if (eol) {
              break; // the cell goes on over the next line
            }
            if (c == '"') {
              state = State.QUOTE_IN_QUOTED_FIELD;
            } else {
              field.append(c);
            }
            break;
          case QUOTE_IN_QUOTED_FIELD:
            if (!eol && c == '"') {
              field.append('"');
              state = State.IN_QUOTED_FIELD;
            } else if (!eol && c == ',') {
              fields.add(field.toString());
              field.setLength(0);
              state = State.START_FIELD;
            } else if (eol || c == '\n' || c == '\r') {
              fields.add(field.toString());
              field.setLength(0);
              state = eol ? State.START_RECORD : State.EAT_NEWLINE;
            } else {
              field.append(c);
              state = State.IN_FIELD;
            }
            break;
          case EAT_NEWLINE:
            if (eol) {
              state = State.START_RECORD;
            } else if (c != '\n' && c != '\r') {
              throw new IllegalArgumentException("a line break inside an unquoted cell");
            }
            break;
          default:
            throw new IllegalStateException(state.name());
        }
      }
      if (state == State.START_RECORD) {
        records.add(fields);
        fields = new ArrayList<>();
      }
    }
  }
}
