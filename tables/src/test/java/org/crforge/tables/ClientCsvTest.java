package org.crforge.tables;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The CSV files split as Python's csv module splits them; each expectation is Python's output. */
class ClientCsvTest {

  @Test
  @DisplayName("a quoted cell holds commas, doubled quotes and line breaks")
  void quotedCells() {
    assertThat(ClientCsv.records("a,\"b,c\",d\n")).containsExactly(List.of("a", "b,c", "d"));
    assertThat(ClientCsv.records("\"x\"\"y\",z\n")).containsExactly(List.of("x\"y", "z"));
    assertThat(ClientCsv.records("\"multi\nline\",2\n"))
        .containsExactly(List.of("multi\nline", "2"));
  }

  @Test
  @DisplayName(
      "a quote inside an unquoted cell, or after a closing quote, is an ordinary character")
  void strayQuotes() {
    assertThat(ClientCsv.records("ab\"c\"d,e\n")).containsExactly(List.of("ab\"c\"d", "e"));
    assertThat(ClientCsv.records("\"q\"rest,1\n")).containsExactly(List.of("qrest", "1"));
  }

  @Test
  @DisplayName("a blank line is a record of no cells, and a last line needs no line break")
  void blankAndLastLines() {
    assertThat(ClientCsv.records("a\n\nb")).containsExactly(List.of("a"), List.of(), List.of("b"));
  }

  @Test
  @DisplayName("every line break is read as a newline")
  void lineBreaks() {
    assertThat(ClientCsv.universalNewlines("a\r\nb\rc\n")).isEqualTo("a\nb\nc\n");
  }
}
