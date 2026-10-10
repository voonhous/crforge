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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * What the game client's data loaders do with the game's files, for one client version: the facts
 * the table decoder needs to read a data set the way the client does. The game's files themselves
 * are not here; they are fetched from the game's asset CDN and decoded on the user's machine.
 *
 * <p>A table is named by its key: the table's number ({@code "34"}), or for the other kinds of
 * registered data the kind and its number ({@code "game tags/0"}). A class is named by its full
 * name ({@code "logic.data.LogicCharacterData"}).
 *
 * <p>One schema file per client version, at {@code schema/<client version>.json} beside this class.
 * Every data version a client of that version is served decodes with that client's schema.
 */
public final class ClientSchema {

  /** The schema format this reader reads. */
  public static final int FORMAT = 1;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** When an {@code [EXT.Name]} section is linked to the row its {@code Base} names. */
  public enum ExtLink {
    /** When the EXT row is made if its base exists, else when its base row is made. */
    AT_CREATION("at creation"),
    /**
     * Making an EXT row only records it as pending. It is linked when a later section that is not
     * an EXT section makes or extends its base row, or else by a pass over every table's pending
     * EXT rows after the last file is loaded.
     */
    DEFERRED("deferred");

    private final String name;

    ExtLink(String name) {
      this.name = name;
    }

    static ExtLink named(String name) {
      for (ExtLink rule : values()) {
        if (rule.name.equals(name)) {
          return rule;
        }
      }
      throw new IllegalArgumentException("unknown EXT link rule: " + name);
    }
  }

  /**
   * One registration of the client's data load, in load order: a table and the files that feed it.
   *
   * @param kind the kind of data: {@code table}, {@code table without a file}, {@code texts},
   *     {@code game tags}, {@code global ids (old format)} or {@code manifest files}
   * @param id the table's number, or the number within its kind
   * @param files the files the client loads into it, in order
   */
  public record Registration(String kind, int id, List<String> files) {

    /** The table key this registration loads into. */
    public String tableKey() {
      return kind.equals("table") || kind.equals("table without a file")
          ? Integer.toString(id)
          : kind + "/" + id;
    }
  }

  /**
   * One class of the client's data.
   *
   * @param parent the class it extends, or null
   * @param key {@code ClassType} when the rows of its tables are chosen by their ClassType column,
   *     else null
   * @param classType the ClassType value that selects this class, or null
   * @param tables the tables the class declares
   * @param refs the class's own fields that name a row of another class, field to that class
   */
  public record DataClass(
      String parent,
      String key,
      String classType,
      List<Integer> tables,
      Map<String, String> refs) {}

  private final String clientVersion;
  private final ExtLink extLink;
  private final Map<String, Integer> typeTokens;
  private final List<Registration> registrations;
  private final Map<Integer, Integer> patchTargets;
  private final Set<Integer> spellTables;
  private final List<Integer> combinedCharacters;
  private final Map<String, Integer> referenceTables;
  private final Map<String, Set<String>> arrayReadCsvColumns;
  private final Map<String, String> exportTables;
  private final Map<String, String> globalIdTypes;
  private final Map<String, Set<String>> tableClasses;
  private final Map<String, DataClass> classes;
  private final Map<String, Map<String, Map<String, String>>> loaderKinds;

  private ClientSchema(JsonNode root) {
    int format = root.path("format").asInt();
    if (format != FORMAT) {
      throw new IllegalArgumentException(
          "client schema format " + format + "; this reader reads format " + FORMAT);
    }
    clientVersion = root.path("client_version").asText();
    extLink = ExtLink.named(root.path("ext_link").asText());
    typeTokens = map(root.path("type_tokens"), JsonNode::asInt);
    List<Registration> loads = new ArrayList<>();
    for (JsonNode entry : root.path("registrations")) {
      loads.add(
          new Registration(
              entry.path("kind").asText(), entry.path("id").asInt(), strings(entry.path("files"))));
    }
    registrations = Collections.unmodifiableList(loads);
    Map<Integer, Integer> patches = new LinkedHashMap<>();
    root.path("patch_targets")
        .properties()
        .forEach(e -> patches.put(Integer.parseInt(e.getKey()), e.getValue().asInt()));
    patchTargets = Collections.unmodifiableMap(patches);
    spellTables =
        Collections.unmodifiableSet(new LinkedHashSet<>(integers(root.path("spell_tables"))));
    combinedCharacters = integers(root.path("combined_characters"));
    referenceTables = map(root.path("reference_tables"), JsonNode::asInt);
    arrayReadCsvColumns = map(root.path("array_read_csv_columns"), ClientSchema::stringSet);
    exportTables = map(root.path("export_tables"), JsonNode::asText);
    globalIdTypes = map(root.path("global_id_types"), JsonNode::asText);
    tableClasses = map(root.path("table_classes"), ClientSchema::stringSet);
    classes =
        map(
            root.path("classes"),
            n ->
                new DataClass(
                    text(n.path("parent")),
                    text(n.path("key")),
                    text(n.path("classtype")),
                    integers(n.path("tables")),
                    map(n.path("refs"), JsonNode::asText)));
    loaderKinds =
        map(
            root.path("loader_kinds"),
            perTable -> map(perTable, kinds -> map(kinds, JsonNode::asText)));
  }

  /**
   * Loads the schema of a client version.
   *
   * @param clientVersion the client version, e.g. {@code 16.402.17}
   * @return its schema
   * @throws IllegalArgumentException when there is no schema of that client version
   */
  public static ClientSchema load(String clientVersion) {
    String resource = "schema/" + clientVersion + ".json";
    try (InputStream in = ClientSchema.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalArgumentException("no client schema of client version " + clientVersion);
      }
      return new ClientSchema(MAPPER.readTree(in));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read the client schema " + resource, e);
    }
  }

  /** The client version this schema is of. */
  public String clientVersion() {
    return clientVersion;
  }

  /** When an EXT row is linked to its base. */
  public ExtLink extLink() {
    return extLink;
  }

  /** The type token of a {@code [TOKEN.Name]} section, to the table its rows go to. */
  public Map<String, Integer> typeTokens() {
    return typeTokens;
  }

  /**
   * Every registration of the data load, in load order; one the client makes twice is listed twice.
   */
  public List<Registration> registrations() {
    return registrations;
  }

  /**
   * The patch tables, to the table each patches. They apply last: a patch row becomes the target
   * table's row of the patch's name, on the base row's layers.
   */
  public Map<Integer, Integer> patchTargets() {
    return patchTargets;
  }

  /** The spell tables: a section of one only extends a row its spells CSV already made. */
  public Set<Integer> spellTables() {
    return spellTables;
  }

  /**
   * The characters and buildings tables, in the order an EXT base named as either is looked up: the
   * client's combined characters table holds the characters, then the buildings.
   */
  public List<Integer> combinedCharacters() {
    return combinedCharacters;
  }

  /** The reference columns read with a fixed target table, column to table. */
  public Map<String, Integer> referenceTables() {
    return referenceTables;
  }

  /**
   * The CSV columns a loader reads with the array reader although the CSV's type row declares a
   * scalar, by table key: every line's cell of a row is read, not the first alone.
   */
  public Map<String, Set<String>> arrayReadCsvColumns() {
    return arrayReadCsvColumns;
  }

  /** The tables the simulator reads, export file name to table key. */
  public Map<String, String> exportTables() {
    return exportTables;
  }

  /**
   * The exported tables whose rows carry a global id, to the type name the id is keyed by: a row's
   * fixed id, else the FNV-1a 32 hash of the type name and the row name.
   */
  public Map<String, String> globalIdTypes() {
    return globalIdTypes;
  }

  /** The classes the client builds the rows of each table with, by table key. */
  public Map<String, Set<String>> tableClasses() {
    return tableClasses;
  }

  /** Every class of the client's data, by full name. */
  public Map<String, DataClass> classes() {
    return classes;
  }

  /**
   * The kind each class's loader reads a column with, by class, then table key, then column: one of
   * {@code int}, {@code bool}, {@code float}, {@code string}, {@code asset} or {@code
   * string_flags}. A column a CSV declares is read by its declared type instead.
   */
  public Map<String, Map<String, Map<String, String>>> loaderKinds() {
    return loaderKinds;
  }

  private static <V> Map<String, V> map(JsonNode node, Function<JsonNode, V> value) {
    Map<String, V> out = new LinkedHashMap<>();
    node.properties().forEach(e -> out.put(e.getKey(), value.apply(e.getValue())));
    return Collections.unmodifiableMap(out);
  }

  private static List<String> strings(JsonNode node) {
    List<String> out = new ArrayList<>();
    node.forEach(n -> out.add(n.asText()));
    return List.copyOf(out);
  }

  private static Set<String> stringSet(JsonNode node) {
    return Collections.unmodifiableSet(new LinkedHashSet<>(strings(node)));
  }

  private static List<Integer> integers(JsonNode node) {
    List<Integer> out = new ArrayList<>();
    node.forEach(n -> out.add(n.asInt()));
    return List.copyOf(out);
  }

  private static String text(JsonNode node) {
    return node.isNull() || node.isMissingNode() ? null : node.asText();
  }
}
