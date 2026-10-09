package org.crforge.tables;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.crforge.tables.TableResolver.Row;

/**
 * Writes the resolved rows as the game tables: one document per exported table, each a header and
 * its rows by name in creation order with their index, class, global id where the table has one,
 * and typed columns sorted by name; and the action graph, every action row by name with its class,
 * its ClassType and its fields, and every reference to an action from any row.
 */
final class TableExport {

  /** The fixed global ids, in the logic files of a set. */
  static final String GLOBAL_IDS = "csv_logic/global_ids_oldformat.csv";

  /** A column name compared as its UTF-8 bytes. */
  private static final Comparator<String> BY_BYTES =
      (a, b) ->
          Arrays.compareUnsigned(
              a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));

  /** A resolved row as written: its key, origin, class short name and columns sorted by name. */
  private record Written(
      String table, String name, String origin, String cls, Map<String, Object> columns) {}

  private final ClientSchema schema;
  private final GameFiles files;
  private final List<Written> rows = new ArrayList<>();
  private Map<List<String>, Long> fixedIds;

  TableExport(ClientSchema schema, GameFiles files, TableResolver resolver) {
    this.schema = schema;
    this.files = files;
    for (Row row : resolver.rows.values()) {
      Map<String, Object> columns = new TreeMap<>(BY_BYTES);
      columns.putAll(resolver.columns(row));
      rows.add(
          new Written(row.table, row.name, row.origin, shortName(resolver.rowClass(row)), columns));
    }
  }

  static String shortName(String cls) {
    return cls == null ? null : cls.substring(cls.lastIndexOf('.') + 1);
  }

  /** Every document, by file name without the extension. */
  Map<String, Object> documents() {
    Map<String, Object> out = new LinkedHashMap<>();
    schema.exportTables().forEach((name, table) -> out.put(name, table(table, name)));
    out.put("actions", actions());
    return out;
  }

  private Map<String, Object> header(String name, String table) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("table", name);
    out.put("id", LoadModel.isDigits(table) ? (Object) Long.valueOf(table) : table);
    out.put("version", files.dataVersion());
    out.put("content_sha", files.contentSha());
    return out;
  }

  private Map<String, Object> table(String table, String name) {
    Map<String, Object> rowsOut = new LinkedHashMap<>();
    String idType = schema.globalIdTypes().get(name);
    int index = 0;
    for (Written w : rows) {
      if (!w.table().equals(table)) {
        continue;
      }
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("index", (long) index++);
      row.put("class", w.cls());
      if (idType != null) {
        row.put("global_id", globalId(idType, w.name()));
      }
      row.put("columns", w.columns());
      rowsOut.put(w.name(), row);
    }
    Map<String, Object> out = header(name, table);
    out.put("rows", rowsOut);
    return out;
  }

  /** The id the client gives a row: its fixed id, else FNV-1a 32 of the type and row names. */
  private long globalId(String type, String name) {
    if (fixedIds == null) {
      String text = files.text(GLOBAL_IDS);
      if (text == null) {
        throw new IllegalStateException("the global ids need " + GLOBAL_IDS);
      }
      fixedIds = new HashMap<>();
      List<List<String>> records = ClientCsv.splitLineRecords(text);
      for (List<String> r : records.subList(Math.min(2, records.size()), records.size())) {
        fixedIds.put(List.of(r.get(2), r.get(1)), new BigInteger(r.get(0).strip()).longValue());
      }
    }
    Long fixed = fixedIds.get(List.of(type, name));
    return fixed != null ? fixed : fnv1a32(type + name);
  }

  /** FNV-1a 32 of the UTF-8 bytes of text, as a signed 32-bit integer. */
  static long fnv1a32(String text) {
    int h = 0x811c9dc5;
    for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
      h = (h ^ (b & 0xff)) * 0x01000193;
    }
    return h;
  }

  // --- the action graph --------------------------------------------------------------------------

  private Map<String, Object> actions() {
    String actionTable = String.valueOf(schema.typeTokens().get("ACTION"));
    Set<String> actionClasses = TableResolver.actionReferenceClasses(schema);
    Map<String, String> full = new HashMap<>();
    schema.classes().keySet().forEach(k -> full.put(shortName(k), k));
    Set<String> allActionColumns = new HashSet<>();
    schema
        .classes()
        .values()
        .forEach(
            c ->
                c.refs()
                    .forEach(
                        (f, target) -> {
                          if (actionClasses.contains(target)) {
                            allActionColumns.add(f);
                          }
                        }));
    Set<String> actionNames = new HashSet<>();
    for (Written w : rows) {
      if (w.table().equals(actionTable)) {
        actionNames.add(w.name());
      }
    }
    Map<String, Set<String>> columnsOf = new HashMap<>();
    List<Object> references = new ArrayList<>();
    Map<String, Object> actions = new LinkedHashMap<>();
    for (Written w : rows) {
      String cls = w.cls() == null ? null : full.get(w.cls());
      Set<String> columns =
          cls == null
              ? allActionColumns
              : columnsOf.computeIfAbsent(cls, c -> actionColumns(c, actionClasses));
      Map<String, Object> fields = new LinkedHashMap<>();
      for (Map.Entry<String, Object> e : w.columns().entrySet()) {
        String column = e.getKey();
        Object v = e.getValue();
        fields.put(
            column,
            columns.contains(column)
                ? resolveValue(w.table(), w.name(), column, v, actionNames, references)
                : v);
      }
      if (w.table().equals(actionTable)) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("class", w.cls());
        action.put("ClassType", fields.get("ClassType"));
        action.put("fields", fields);
        actions.put(w.name(), action);
      }
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("table", "actions");
    out.put("version", files.dataVersion());
    out.put("content_sha", files.contentSha());
    out.put("actions", actions);
    out.put("references", references);
    return out;
  }

  /** The action columns a class or one of its parents declares. */
  private Set<String> actionColumns(String cls, Set<String> actionClasses) {
    Set<String> out = new HashSet<>();
    String c = cls;
    while (c != null && schema.classes().containsKey(c)) {
      ClientSchema.DataClass d = schema.classes().get(c);
      d.refs()
          .forEach(
              (f, target) -> {
                if (actionClasses.contains(target)) {
                  out.add(f);
                }
              });
      c = d.parent();
    }
    return out;
  }

  private static Object resolveValue(
      String table,
      String row,
      String column,
      Object value,
      Set<String> actionNames,
      List<Object> references) {
    if (value instanceof List<?> list) {
      List<Object> out = new ArrayList<>();
      for (int i = 0; i < list.size(); i++) {
        String name = target(table, row, column, (long) i, list.get(i), actionNames, references);
        if (name != null) {
          out.add(Map.of("action", name));
        }
      }
      return out;
    }
    String name = target(table, row, column, null, value, actionNames, references);
    return name != null ? Map.of("action", name) : null;
  }

  /** The action a value names, or null; records the reference. */
  private static String target(
      String table,
      String row,
      String column,
      Long index,
      Object value,
      Set<String> actionNames,
      List<Object> references) {
    String name;
    String written;
    if (value instanceof Map<?, ?> inline) {
      Object named = inline.get("Name");
      name =
          TableResolver.truthy(named)
              ? String.valueOf(named)
              : row + "_" + column + (index == null ? "" : index);
      written = "inline";
    } else if (value instanceof String s) {
      name = s;
      written = "name";
    } else {
      return null; // not a name or a table: dropped
    }
    if (name.isEmpty() || !actionNames.contains(name)) {
      return null; // no action
    }
    List<Object> reference = new ArrayList<>();
    reference.add(table);
    reference.add(row);
    reference.add(column);
    reference.add(index);
    reference.add(name);
    reference.add(written);
    references.add(reference);
    return name;
  }
}
