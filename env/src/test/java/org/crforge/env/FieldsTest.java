/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The field registry ({@link Fields}): its layout, its tags and frames, its map onto schema v4, and
 * the committed snapshot of its metadata, so that any change of the registry shows in review.
 */
class FieldsTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** The committed snapshot, the build's path to it or the module's relative one. */
  private static Path snapshot() {
    String path = System.getProperty("crforge.fieldsSnapshot");
    return path != null ? Path.of(path) : Path.of("../python/tests/data/fields_v1.json");
  }

  @Test
  void theRegistrySerialisesAsTheCommittedSnapshot() throws IOException {
    JsonNode committed = JSON.readTree(Files.readString(snapshot()));
    assertThat(Fields.toNode()).isEqualTo(committed);
  }

  @Test
  void theMetadataPublishesTheRegistry() throws IOException {
    JsonNode metadata = JSON.readTree(VectorEnv.metadataJson(null, null));
    assertThat(metadata.get("fields")).isEqualTo(Fields.toNode());
    JsonNode node = Fields.toNode();
    assertThat(node.get("version").asText()).isEqualTo(Fields.VERSION).matches("\\d+\\.\\d+");
    assertThat(node.get("ints").asInt()).isEqualTo(Fields.INTS);
    assertThat(node.get("entity_capacity").asInt()).isEqualTo(Fields.ENTITY_CAPACITY);
    assertThat(node.get("fields")).hasSize(Fields.fields().size());
  }

  @Test
  void theFieldsCoverTheBlockWithoutOverlap() {
    List<Fields.Field> stored = new ArrayList<>();
    for (Fields.Field field : Fields.fields()) {
      if (field.aliasOf() == null) {
        stored.add(field);
      }
    }
    stored.sort((a, b) -> Integer.compare(a.offset(), b.offset()));
    int next = 0;
    for (Fields.Field field : stored) {
      assertThat(field.offset()).as("offset of %s", field.name()).isEqualTo(next);
      assertThat(field.size()).as("size of %s", field.name()).isPositive();
      next += field.size();
    }
    assertThat(next).isEqualTo(Fields.INTS);
  }

  @Test
  void everyNameIsUniqueAndPlain() {
    Set<String> names = new HashSet<>();
    for (Fields.Field field : Fields.fields()) {
      assertThat(field.name()).matches("[a-z_.0-9]+").doesNotStartWith(".").doesNotEndWith(".");
      assertThat(names.add(field.name())).as("%s named twice", field.name()).isTrue();
      assertThat(Fields.field(field.name())).isSameAs(field);
    }
  }

  @Test
  void eachTagHasItsAxesFramesAndUnits() {
    for (Fields.Field field : Fields.fields()) {
      String name = field.name();
      assertThat(field.units()).as("units of %s", name).satisfies(FieldsTest::knownUnits);
      if (field.tag() == Fields.Tag.SEAT) {
        // A seat field has the seat axis first, and holds no game-frame coordinate.
        assertThat(field.shape().get(0)).as("seat axis of %s", name).isEqualTo(2);
        assertThat(field.frame()).as("frame of %s", name).isNotEqualTo(Fields.Frame.GAME);
        assertThat(field.aliasOf()).isNull();
        assertThat(name).doesNotEndWith(".true");
      } else if (field.aliasOf() == null) {
        // Public and hidden fields are in the game frame.
        assertThat(field.frame())
            .as("frame of %s", name)
            .isIn(Fields.Frame.GAME, Fields.Frame.NONE);
      }
      if (field.units().equals("game units") && !name.startsWith("entity.radius")) {
        assertThat(field.frame()).as("frame of %s", name).isNotEqualTo(Fields.Frame.NONE);
      }
      if (field.frame() != Fields.Frame.NONE) {
        assertThat(field.units()).as("units of %s", name).isEqualTo("game units");
      }
      if (name.startsWith("entity.")
          && !name.equals("entity.count")
          && !name.equals("entity.overflow")
          && !name.equals("entity.count.true")
          && !name.equals("entity.export_overflow")) {
        int axis = field.tag() == Fields.Tag.SEAT ? 1 : 0;
        assertThat(field.shape().get(axis))
            .as("entity axis of %s", name)
            .isEqualTo(Fields.ENTITY_CAPACITY);
      }
    }
  }

  private static void knownUnits(String units) {
    Set<String> plain =
        Set.of(
            "ticks",
            "ms",
            "game units",
            "1/10000 elixir",
            "elixir",
            "hit points",
            "percent",
            "level",
            "count",
            "index",
            "bool",
            "vocabulary id",
            "buff id",
            "seat id",
            "battle id");
    assertThat(plain.contains(units) || units.matches("(enum|flags):[a-z_]+"))
        .as("unknown units %s", units)
        .isTrue();
  }

  @Test
  void aliasesAreHiddenAndReadASeatField() {
    int aliases = 0;
    for (Fields.Field field : Fields.fields()) {
      if (field.aliasOf() == null) {
        continue;
      }
      aliases++;
      Fields.Field of = Fields.field(field.aliasOf());
      assertThat(of).as("alias target of %s", field.name()).isNotNull();
      assertThat(of.tag()).isEqualTo(Fields.Tag.SEAT);
      assertThat(of.aliasOf()).isNull();
      assertThat(field.tag()).isEqualTo(Fields.Tag.HIDDEN);
      assertThat(field.name()).startsWith("enemy.").endsWith(".true");
      assertThat(field.offset()).isEqualTo(of.offset());
      assertThat(field.shape()).isEqualTo(of.shape());
      assertThat(field.units()).isEqualTo(of.units());
      // The seat axis flipped: a coordinate of the other seat is in that seat's frame.
      assertThat(field.frame())
          .isEqualTo(of.frame() == Fields.Frame.SEAT ? Fields.Frame.ENEMY_SEAT : of.frame());
    }
    assertThat(aliases).isPositive();
    assertThat(Fields.field("enemy.elixir.true").aliasOf()).isEqualTo("own.elixir");
  }

  @Test
  void everyTrueValueHasTheFieldItIsTheTruthOf() {
    for (Fields.Field field : Fields.fields()) {
      String name = field.name();
      if (!name.endsWith(".true") || field.aliasOf() != null) {
        continue;
      }
      assertThat(field.tag()).as("tag of %s", name).isEqualTo(Fields.Tag.HIDDEN);
      Fields.Field seen = Fields.field(name.substring(0, name.length() - ".true".length()));
      assertThat(seen).as("the seat field %s is the truth of", name).isNotNull();
      assertThat(seen.tag()).isEqualTo(Fields.Tag.SEAT);
      // The same units, but the battle's ids for the seat's and the game's sides for the seat's.
      String units =
          switch (seen.units()) {
            case "seat id" -> "battle id";
            case "enum:relative_side" -> "enum:side";
            default -> seen.units();
          };
      assertThat(field.units()).as("units of %s", name).isEqualTo(units);
      assertThat(field.frame())
          .isEqualTo(seen.frame() == Fields.Frame.SEAT ? Fields.Frame.GAME : seen.frame());
      // The truth drops the seat axis, but for the enemy's plays, whose rows are each seat's own.
      List<Integer> shape = seen.shape();
      List<Integer> expected = name.startsWith("enemy.") ? shape : shape.subList(1, shape.size());
      assertThat(field.shape()).as("shape of %s", name).isEqualTo(expected);
    }
  }

  @Test
  void everySchemaFourIntIsMappedByExactlyOneSeatOrPublicField() {
    List<String> header = Schema.headerFields();
    Map<Integer, String> headerBy = new HashMap<>();
    Map<Integer, String> entityBy = new HashMap<>();
    for (Fields.Field field : Fields.fields()) {
      Fields.V4Source source = Fields.v4Source(field.name());
      if (source == null) {
        continue;
      }
      assertThat(field.tag())
          .as("tag of %s", field.name())
          .isIn(Fields.Tag.SEAT, Fields.Tag.PUBLIC);
      // The source lists a v4 int per element of the field's axes past the seat and row axes.
      List<Integer> shape = field.shape();
      int skip = (field.tag() == Fields.Tag.SEAT ? 1 : 0) + (source.entity() ? 1 : 0);
      int elements = 1;
      for (int axis = skip; axis < shape.size(); axis++) {
        elements *= shape.get(axis);
      }
      assertThat(source.indices()).as("v4 source of %s", field.name()).hasSize(elements);
      Map<Integer, String> by = source.entity() ? entityBy : headerBy;
      for (int index : source.indices()) {
        assertThat(by.put(index, field.name()))
            .as("v4 %s int %d mapped twice", source.entity() ? "entity" : "header", index)
            .isNull();
      }
    }
    for (int index = 0; index < Schema.HEADER_INTS; index++) {
      boolean reserved = header.get(index).equals("reserved");
      assertThat(headerBy.containsKey(index))
          .as("header int %d (%s) mapped", index, header.get(index))
          .isEqualTo(!reserved);
    }
    for (int index = 0; index < Schema.ENTITY_INTS; index++) {
      assertThat(entityBy).as("entity int %d mapped", index).containsKey(index);
    }
    assertThat(entityBy).hasSize(Schema.ENTITY_INTS);
  }

  @Test
  void theMapNamesTheSchemaFourFieldsItReads() {
    assertThat(Fields.v4Source("game.tick").indices()).containsExactly(Schema.H_TICK);
    assertThat(Fields.v4Source("own.hand.card").indices())
        .containsExactly(
            Schema.H_HAND_CARD,
            Schema.H_HAND_CARD + 1,
            Schema.H_HAND_CARD + 2,
            Schema.H_HAND_CARD + 3);
    assertThat(Fields.v4Source("tower.max_hp").indices().get(5)).isEqualTo(Schema.H_TOWERS + 11);
    assertThat(Fields.v4Source("enemy.plays.y").indices().get(1))
        .isEqualTo(Schema.H_ENEMY_PLAYS + Schema.ENEMY_PLAY_INTS + 5);
    assertThat(Fields.v4Source("entity.buff_ms").entity()).isTrue();
    assertThat(Fields.v4Source("entity.buff_ms").indices())
        .containsExactly(Schema.E_BUFFS + 1, Schema.E_BUFFS + 3, Schema.E_BUFFS + 5);
    assertThat(Fields.v4Source("entity.visible")).isNull();
    assertThat(Fields.v4Source("entity.hp.true")).isNull();
    // Every v4 header field a field reads is named alike, but the towers', named by tower.
    for (Fields.Field field : Fields.fields()) {
      Fields.V4Source source = Fields.v4Source(field.name());
      if (source == null || source.entity() || field.name().startsWith("tower.")) {
        continue;
      }
      Set<String> v4 = new HashSet<>();
      source.indices().forEach(i -> v4.add(Schema.headerFields().get(i)));
      assertThat(v4).as("v4 fields of %s", field.name()).hasSize(1);
    }
  }
}
