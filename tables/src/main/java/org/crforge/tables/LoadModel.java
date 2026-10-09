package org.crforge.tables;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every row of a data set as the client's loaders stack it, before any rule is applied: a row is a
 * stack of layers, one per source that touches it, in the order the client loads them - the table's
 * CSV row, its registered TOML section, then every manifest section with its name, in manifest
 * order. Nothing is merged, inherited, patched or resolved here (that is {@link TableResolver}).
 *
 * <p>A CSV layer holds every column of its file, each as the column's cells down the row's lines
 * (null where a line is shorter); a TOML layer holds the keys its section sets, as the client's
 * parser holds them: floats as float32, the string {@code scNull} as null.
 */
final class LoadModel {

  /**
   * The section type of an inheriting row: {@code [EXT.Name]} with {@code Base = "TOKEN.Other"}.
   */
  static final String EXT = "EXT";

  /** One source's layer of a row. */
  static final class Layer {
    /** The load order of the layer among all layers (a later repeat of a file sits between). */
    final double seq;

    /** csv, registered toml, patch, manifest, ext or embedded. */
    final String source;

    final String file;
    final String section;
    final Map<String, Object> values;

    /** Operator values resolved at link time, by column. */
    final Map<String, Object> resolved = new LinkedHashMap<>();

    Layer(double seq, String source, String file, String section, Map<String, Object> values) {
      this.seq = seq;
      this.source = source;
      this.file = file;
      this.section = section;
      this.values = values;
    }
  }

  /** A row of the model: its table key, name, the source that made it and its layers. */
  record ModelRow(String table, String name, String createdBy, List<Layer> layers) {}

  private final ClientSchema schema;
  private final GameFiles files;
  private final Map<String, Integer> tokens;
  private final Set<Integer> spellTables;
  private final Set<Integer> patchTables;

  /** The rows by table key and name, in creation order. */
  final Map<List<String>, ModelRow> rows = new LinkedHashMap<>();

  /** Each loaded CSV file's columns in order, as (name, declared type or null). */
  final Map<String, List<String[]>> csvColumns = new LinkedHashMap<>();

  /** The table key each loaded CSV file was registered to, in load order. */
  final Map<String, String> csvTables = new LinkedHashMap<>();

  /** data_manifest.toml as read. */
  final Map<String, Object> manifest;

  private int layerCount;

  LoadModel(ClientSchema schema, GameFiles files) {
    this.schema = schema;
    this.files = files;
    this.tokens = schema.typeTokens();
    this.spellTables = schema.spellTables();
    this.patchTables = schema.patchTargets().keySet();
    this.manifest = ClientToml.read(files.text(GameFiles.MANIFEST));
  }

  /** Builds the model: the registered files in registration order, then the manifest's files. */
  LoadModel build() {
    registered();
    manifestFiles();
    return this;
  }

  private void registered() {
    Set<List<String>> loaded = new HashSet<>();
    for (ClientSchema.Registration entry : schema.registrations()) {
      if (entry.kind().equals("manifest files")) {
        continue;
      }
      String key = entry.tableKey();
      for (String file : entry.files()) {
        // a file the client registers twice is loaded once
        if (!loaded.add(List.of(file, key))) {
          continue;
        }
        String text = files.text(file);
        if (text == null) {
          continue; // registered, not in the set
        }
        if (file.endsWith(".csv")) {
          csvFile(file, key, text);
        } else if (file.endsWith(".toml")) {
          tableToml(file, key, text);
        }
      }
    }
  }

  private void csvFile(String file, String key, String text) {
    List<List<String>> lines = ClientCsv.records(text);
    List<String> header = lines.size() > 0 ? lines.get(0) : List.of();
    List<String> types = lines.size() > 1 ? lines.get(1) : List.of();
    csvTables.put(file, key);
    List<String[]> columns = new ArrayList<>();
    for (int i = 0; i < header.size(); i++) {
      columns.add(new String[] {header.get(i), i < types.size() ? types.get(i) : null});
    }
    csvColumns.put(file, columns);
    // a row starts at a line whose first cell is set; the lines after it are its own
    List<String> names = new ArrayList<>();
    List<List<List<String>>> rowLines = new ArrayList<>();
    List<Integer> lineNumbers = new ArrayList<>();
    for (int n = 2; n < lines.size(); n++) {
      List<String> cells = lines.get(n);
      if (!cells.isEmpty() && !cells.get(0).isEmpty()) {
        names.add(cells.get(0));
        lineNumbers.add(n + 1);
        List<List<String>> own = new ArrayList<>();
        own.add(cells);
        rowLines.add(own);
      } else if (!rowLines.isEmpty()) {
        rowLines.get(rowLines.size() - 1).add(cells);
      }
    }
    for (int r = 0; r < names.size(); r++) {
      List<List<String>> own = rowLines.get(r);
      int width = header.size();
      for (List<String> c : own) {
        width = Math.max(width, c.size());
      }
      Map<String, Object> values = new LinkedHashMap<>();
      for (int i = 0; i < width; i++) {
        List<String> cells = new ArrayList<>();
        for (List<String> c : own) {
          cells.add(i < c.size() ? c.get(i) : null);
        }
        values.put(i < header.size() ? header.get(i) : "#" + i, cells);
      }
      layer(key, names.get(r), file, "line " + lineNumbers.get(r), "csv", values);
    }
  }

  @SuppressWarnings("unchecked")
  private void tableToml(String file, String key, String text) {
    Map<String, Object> doc = clientValues(ClientToml.read(text));
    Integer table = isDigits(key) ? Integer.valueOf(key) : null;
    for (Map.Entry<String, Object> e : doc.entrySet()) {
      String name = e.getKey();
      Map<String, Object> body = (Map<String, Object>) e.getValue();
      if (tokens.containsKey(name) || name.equals(EXT)) {
        typed(file, name, body, "registered toml");
      } else if (table != null && patchTables.contains(table)) {
        layer(key, name, file, name, "patch", body);
      } else {
        layer(key, name, file, name, "registered toml", body);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private void manifestFiles() {
    List<String> order = new ArrayList<>();
    for (Object entry : manifest.values()) {
      String path = (String) ((Map<String, Object>) entry).get("Path");
      if (!order.contains(path)) {
        order.add(path);
      }
    }
    for (String file : order) {
      String text = files.text(file);
      if (text == null) {
        throw new IllegalStateException("the manifest lists " + file + ", which the set has not");
      }
      Map<String, Object> doc = clientValues(ClientToml.read(text));
      for (Map.Entry<String, Object> e : doc.entrySet()) {
        String token = e.getKey();
        if (tokens.containsKey(token) || token.equals(EXT)) {
          typed(file, token, (Map<String, Object>) e.getValue(), "manifest");
        }
        // any other token: the client faults on the section, which makes no row
      }
    }
  }

  @SuppressWarnings("unchecked")
  private void typed(String file, String token, Map<String, Object> sections, String source) {
    for (Map.Entry<String, Object> e : sections.entrySet()) {
      String name = e.getKey();
      Map<String, Object> body = (Map<String, Object>) e.getValue();
      String section = token + "." + name;
      if (token.equals(EXT)) {
        Object base = body.get("Base");
        String tok = null;
        if (base instanceof String s && s.contains(".")) {
          tok = s.substring(0, s.indexOf('.'));
        }
        if (tok == null || !tokens.containsKey(tok)) {
          continue; // a Base naming no type token: dropped without a message
        }
        layer(String.valueOf(tokens.get(tok)), name, file, section, "ext", body);
        continue;
      }
      int table = tokens.get(token);
      if (spellTables.contains(table) && !rows.containsKey(List.of(String.valueOf(table), name))) {
        continue; // a spell section needs its row in the spells CSV
      }
      layer(String.valueOf(table), name, file, section, source, body);
    }
  }

  private void layer(
      String table,
      String name,
      String file,
      String section,
      String source,
      Map<String, Object> v) {
    ModelRow row =
        rows.computeIfAbsent(
            List.of(table, name), k -> new ModelRow(table, name, source, new ArrayList<>()));
    row.layers().add(new Layer(++layerCount, source, file, section, v));
  }

  /** A TOML value as the client's parser holds it: floats as float32, scNull as null. */
  @SuppressWarnings("unchecked")
  static <T> T clientValues(T value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<?, ?> e : map.entrySet()) {
        out.put((String) e.getKey(), clientValues(e.getValue()));
      }
      return (T) out;
    }
    if (value instanceof List<?> list) {
      List<Object> out = new ArrayList<>();
      for (Object x : list) {
        out.add(clientValues(x));
      }
      return (T) out;
    }
    if (value instanceof Double d) {
      return (T) Double.valueOf((float) d.doubleValue());
    }
    if ("scNull".equals(value)) {
      return null;
    }
    return value;
  }

  /** Python's str.isdigit for the table keys: one or more decimal digits. */
  static boolean isDigits(String s) {
    if (s.isEmpty()) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      if (!Character.isDigit(s.charAt(i))) {
        return false;
      }
    }
    return true;
  }
}
