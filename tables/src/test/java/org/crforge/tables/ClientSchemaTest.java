/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.tables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.crforge.tables.ClientSchema.DataClass;
import org.crforge.tables.ClientSchema.Registration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shipped client schemas hold together: every class, table and token one part names is one
 * another part defines. The values themselves are the client's and are held by the table decoder's
 * output, not here.
 */
class ClientSchemaTest {

  /** The client versions a schema ships for. */
  private static final String[] CLIENT_VERSIONS = {"16.402.17"};

  @Test
  @DisplayName("each shipped schema loads as the schema of its own client version")
  void loadsItsClientVersion() {
    for (String version : CLIENT_VERSIONS) {
      ClientSchema schema = ClientSchema.load(version);
      assertThat(schema.clientVersion()).isEqualTo(version);
      assertThat(schema.registrations()).isNotEmpty();
      assertThat(schema.classes()).isNotEmpty();
    }
  }

  @Test
  @DisplayName("a client version with no schema is refused, naming the version")
  void unknownClientVersion() {
    assertThatThrownBy(() -> ClientSchema.load("0.0.1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("0.0.1");
  }

  @Test
  @DisplayName("a schema of another format is refused")
  void otherFormat() {
    assertThatThrownBy(() -> ClientSchema.load("format-0"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("format 0");
  }

  @Test
  @DisplayName("every class a schema names is one it defines")
  void classesDefined() {
    for (String version : CLIENT_VERSIONS) {
      ClientSchema schema = ClientSchema.load(version);
      Map<String, DataClass> classes = schema.classes();
      for (DataClass c : classes.values()) {
        if (c.parent() != null) {
          assertThat(classes).containsKey(c.parent());
        }
        assertThat(classes.keySet()).containsAll(c.refs().values());
      }
      schema.tableClasses().values().forEach(cs -> assertThat(classes.keySet()).containsAll(cs));
      assertThat(classes.keySet()).containsAll(schema.loaderKinds().keySet());
    }
  }

  @Test
  @DisplayName("a ClassType selects one class only")
  void classTypesUnique() {
    for (String version : CLIENT_VERSIONS) {
      Set<String> seen = new HashSet<>();
      for (DataClass c : ClientSchema.load(version).classes().values()) {
        if (c.classType() != null) {
          assertThat(seen.add(c.classType())).as(c.classType()).isTrue();
        }
      }
    }
  }

  @Test
  @DisplayName("every table a schema exports or reads by number is registered or has a token")
  void tablesKnown() {
    for (String version : CLIENT_VERSIONS) {
      ClientSchema schema = ClientSchema.load(version);
      Set<String> registered =
          schema.registrations().stream().map(Registration::tableKey).collect(Collectors.toSet());
      Set<String> tokenTables =
          schema.typeTokens().values().stream().map(String::valueOf).collect(Collectors.toSet());
      Set<String> known = new HashSet<>(registered);
      known.addAll(tokenTables);

      assertThat(registered).containsAll(schema.exportTables().values());
      assertThat(known).containsAll(schema.tableClasses().keySet());
      assertThat(known).containsAll(schema.arrayReadCsvColumns().keySet());
      for (Map.Entry<Integer, Integer> patch : schema.patchTargets().entrySet()) {
        assertThat(registered).contains(String.valueOf(patch.getKey()));
        assertThat(tokenTables).contains(String.valueOf(patch.getValue()));
      }
      schema.spellTables().forEach(t -> assertThat(tokenTables).contains(String.valueOf(t)));
      schema.combinedCharacters().forEach(t -> assertThat(tokenTables).contains(String.valueOf(t)));
      schema
          .referenceTables()
          .values()
          .forEach(t -> assertThat(tokenTables).contains(String.valueOf(t)));
      assertThat(schema.exportTables().keySet()).containsAll(schema.globalIdTypes().keySet());
    }
  }

  @Test
  @DisplayName("every loader reads its columns with a kind the decoder knows")
  void loaderKindsKnown() {
    Set<String> kinds = Set.of("int", "bool", "float", "string", "asset", "string_flags");
    for (String version : CLIENT_VERSIONS) {
      ClientSchema.load(version)
          .loaderKinds()
          .values()
          .forEach(
              perTable ->
                  perTable
                      .values()
                      .forEach(columns -> assertThat(kinds).containsAll(columns.values())));
    }
  }
}
