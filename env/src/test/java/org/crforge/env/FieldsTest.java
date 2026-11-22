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
import java.util.SortedMap;
import org.crforge.core.battle.unit.ChampionController;
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

  /** The snapshot of registry 1.0, as committed with it: what every 1.x registry keeps. */
  private static Path snapshotOneZero() {
    return snapshot().resolveSibling("fields_v1_0.json");
  }

  @Test
  void everyOneZeroFieldKeepsItsNameOffsetShapeUnitsAndFrame() throws IOException {
    JsonNode before = JSON.readTree(Files.readString(snapshotOneZero()));
    JsonNode now = Fields.toNode();
    assertThat(before.get("version").asText()).isEqualTo("1.0");
    // The same major version, a later minor one.
    String[] version = Fields.VERSION.split("\\.");
    assertThat(version[0]).isEqualTo("1");
    assertThat(Integer.parseInt(version[1])).isPositive();
    assertThat(now.get("entity_capacity")).isEqualTo(before.get("entity_capacity"));
    // Every 1.0 field as it was, in the same place of the list: the 1.0 list is a prefix.
    JsonNode fields = now.get("fields");
    int oneZero = before.get("fields").size();
    assertThat(fields.size()).isGreaterThan(oneZero);
    for (int k = 0; k < oneZero; k++) {
      assertThat(fields.get(k)).as("1.0 field %d", k).isEqualTo(before.get("fields").get(k));
    }
    // The fields added hold integers past the 1.0 block only, so a 1.0 reader's offsets hold.
    int ints = before.get("ints").asInt();
    for (int k = oneZero; k < fields.size(); k++) {
      JsonNode field = fields.get(k);
      assertThat(field.get("alias_of").isNull()).as(field.get("name").asText()).isTrue();
      assertThat(field.get("offset").asInt())
          .as(field.get("name").asText())
          .isGreaterThanOrEqualTo(ints);
    }
    // Every 1.0 code and bit keeps its label.
    before
        .get("enums")
        .fields()
        .forEachRemaining(
            entry -> assertThat(now.get("enums").get(entry.getKey())).isEqualTo(entry.getValue()));
  }

  @Test
  void theSeatsOwnPlaysMirrorTheEnemysPlays() {
    List<String> columns = new ArrayList<>();
    for (Fields.Field field : Fields.fields()) {
      String name = field.name();
      if (!name.startsWith("enemy.plays.") || field.tag() != Fields.Tag.SEAT) {
        continue;
      }
      String column = name.substring("enemy.plays.".length());
      columns.add(column);
      Fields.Field own = Fields.field("own.plays." + column);
      assertThat(own).as("own.plays.%s", column).isNotNull();
      assertThat(own.tag()).isEqualTo(Fields.Tag.SEAT);
      assertThat(own.shape()).isEqualTo(field.shape());
      assertThat(own.units()).isEqualTo(field.units());
      assertThat(own.frame()).isEqualTo(field.frame());
      assertThat(own.aliasOf()).isNull();
      // Not in the v4 observation, and nothing hides it: a seat sees all of its own plays.
      assertThat(Fields.v4Source(own.name())).isNull();
      assertThat(Fields.field(own.name() + ".true")).isNull();
    }
    assertThat(columns)
        .containsExactly(
            "count", "card", "cast", "form", "ticks", "x", "y", "kind", "flags", "point_ticks");
    int own = 0;
    for (Fields.Field field : Fields.fields()) {
      own += field.name().startsWith("own.plays.") ? 1 : 0;
    }
    assertThat(own).isEqualTo(columns.size());
  }

  @Test
  void theRowFactsAreListedWithTheirUnitsAndNoValuesWithoutAVocabulary() {
    JsonNode rows = Fields.toNode().get("rows");
    assertThat(rows).isNotNull();
    List<String> names = new ArrayList<>();
    rows.fieldNames().forEachRemaining(names::add);
    assertThat(names)
        .containsExactly(
            "bar_rule",
            "hit_speed_ms",
            "load_time_ms",
            "attack_step_ms",
            "attack_start_delay_ms",
            "attack_traits");
    for (Fields.RowFact fact : Fields.rowFacts()) {
      JsonNode node = rows.get(fact.name());
      assertThat(node.get("units").asText()).isEqualTo(fact.units());
      assertThat(node.get("values").isArray()).isTrue();
      assertThat(node.get("values")).isEmpty();
      knownUnits(fact.units());
    }
    assertThat(Fields.enums().get("bar_rule"))
        .containsEntry(Fields.BAR_NOT_UNIT, "not_unit")
        .containsEntry(Fields.BAR_NEVER, "never")
        .containsEntry(Fields.BAR_ALWAYS, "always")
        .containsEntry(Fields.BAR_DAMAGED, "damaged")
        .containsEntry(Fields.BAR_DAMAGED_ROLLING, "damaged_rolling")
        .hasSize(5);
    assertThat(Fields.enums().get("attack_traits"))
        .containsEntry(Integer.numberOfTrailingZeros(Fields.TRAIT_LOAD_FIRST_HIT), "load_first_hit")
        .containsEntry(Integer.numberOfTrailingZeros(Fields.TRAIT_CHARGE), "charge")
        .containsEntry(Integer.numberOfTrailingZeros(Fields.TRAIT_DASH), "dash")
        .containsEntry(Integer.numberOfTrailingZeros(Fields.TRAIT_SPECIAL), "special")
        .hasSize(4);
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

  @Test
  void theMetadataGivesEachFieldItsSchemaFourSource() {
    JsonNode fields = Fields.toNode().get("fields");
    int sources = 0;
    for (JsonNode node : fields) {
      Fields.V4Source source = Fields.v4Source(node.get("name").asText());
      JsonNode v4 = node.get("v4");
      assertThat(v4).as("v4 of %s", node.get("name")).isNotNull();
      if (source == null) {
        assertThat(v4.isNull()).isTrue();
        continue;
      }
      sources++;
      assertThat(v4.get("region").asText()).isEqualTo(source.entity() ? "entity" : "header");
      List<Integer> indices = new ArrayList<>();
      v4.get("indices").forEach(i -> indices.add(i.asInt()));
      assertThat(indices).isEqualTo(source.indices());
    }
    assertThat(sources).isPositive();
  }

  @Test
  void everyEnumAndFlagsUnitHasItsLabels() {
    Map<String, SortedMap<Integer, String>> enums = Fields.enums();
    Set<String> used = new HashSet<>();
    for (Fields.Field field : Fields.fields()) {
      String units = field.units();
      if (units.startsWith("enum:") || units.startsWith("flags:")) {
        String name = units.substring(units.indexOf(':') + 1);
        assertThat(enums).as("labels of %s", units).containsKey(name);
        assertThat(enums.get(name)).as("labels of %s", units).isNotEmpty();
        used.add(name);
      }
    }
    // The row facts' units name labels too.
    for (Fields.RowFact fact : Fields.rowFacts()) {
      String units = fact.units();
      if (units.startsWith("enum:") || units.startsWith("flags:")) {
        String name = units.substring(units.indexOf(':') + 1);
        assertThat(enums).as("labels of %s", units).containsKey(name);
        used.add(name);
      }
    }
    // Every list is a unit's, and no name is both an enum's and a flags'.
    assertThat(enums.keySet()).isEqualTo(used);
    Set<String> enumNames = new HashSet<>();
    Set<String> flagsNames = new HashSet<>();
    List<String> allUnits = new ArrayList<>();
    Fields.fields().forEach(field -> allUnits.add(field.units()));
    Fields.rowFacts().forEach(fact -> allUnits.add(fact.units()));
    for (String units : allUnits) {
      String name = units.substring(units.indexOf(':') + 1);
      if (units.startsWith("enum:")) {
        enumNames.add(name);
      } else if (units.startsWith("flags:")) {
        flagsNames.add(name);
      }
    }
    assertThat(enumNames).doesNotContainAnyElementsOf(flagsNames);
    // The labels sit at the codes' own values and bits.
    List<String> refusals = Schema.refusalNames();
    for (int code = 0; code < refusals.size(); code++) {
      assertThat(enums.get("refusal").get(code)).isEqualTo(refusals.get(code));
    }
    assertThat(enums.get("refusal"))
        .hasSize((int) refusals.stream().filter(n -> n != null).count());
    assertThat(List.copyOf(enums.get("state").values())).isEqualTo(Schema.stateNames());
    assertThat(List.copyOf(enums.get("entity").values())).isEqualTo(Schema.flagNames());
    // Only the defined codes: the sparse gate codes, not a list up to the highest.
    assertThat(enums.get("ability_refusal")).hasSize(11).containsEntry(0, "none");
    assertThat(enums.get("run_refusal")).hasSize(7);
    assertThat(enums.get("ability_state").get(ChampionController.READY)).isEqualTo("Ready");
    assertThat(enums.get("run_refusal").get(Schema.RUN_NOT_PLACED)).isEqualTo("not_placed");
    assertThat(enums.get("entity_kind").get(Schema.KIND_AREA)).isEqualTo("area");
    assertThat(enums.get("slot").get(Integer.numberOfTrailingZeros(Schema.SLOT_HERO)))
        .isEqualTo("hero");
    JsonNode node = Fields.toNode().get("enums");
    assertThat(node.size()).isEqualTo(enums.size());
    assertThat(node.get("result").get(Integer.toString(Schema.LOST)).asText()).isEqualTo("lost");
    // Each name's object holds its codes as strings, in ascending order, every label a string.
    node.fields()
        .forEachRemaining(
            entry -> {
              List<Integer> codes = new ArrayList<>();
              entry.getValue().fieldNames().forEachRemaining(k -> codes.add(Integer.parseInt(k)));
              assertThat(codes).as("codes of %s", entry.getKey()).isSorted();
              assertThat(codes).isEqualTo(List.copyOf(enums.get(entry.getKey()).keySet()));
              entry.getValue().forEach(label -> assertThat(label.isTextual()).isTrue());
            });
  }
}
