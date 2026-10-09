package org.crforge.tables;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.crforge.tables.LoadModel.Layer;
import org.crforge.tables.LoadModel.ModelRow;

/**
 * Resolves the load model the way the client does after loading, into each row's typed columns:
 *
 * <ul>
 *   <li>a column is read from a row's layers top down; the first layer that has it answers. A CSV
 *       layer has every column of its file, an empty cell included; a TOML layer has the keys it
 *       sets;
 *   <li>{@code [EXT.Name]} with {@code Base = "TOKEN.Other"} stacks the row's own layers on its
 *       base's layers as they stood when the EXT was linked ({@link ClientSchema.ExtLink}); a base
 *       named as a character or a building is looked up in the characters, then the buildings;
 *   <li>a two-element array with an operator ({@code = + - * / %}) in an inherited row resolves
 *       against the base's value, once, in 32-bit integers with truncating division;
 *   <li>the patch tables apply last, row by row: the target table's row of the patch's name gets
 *       the base row's layers and the patch body on top, operators resolved against the base;
 *   <li>an inline table in a reference column becomes an embedded row of the target table, named by
 *       its Name key or {@code <row>_<column>} ({@code <row>_<column><index>} for a list element),
 *       replacing a row of that name; the rule composes;
 *   <li>a column is typed by its reader: CSV cells by the column's declared type, TOML values by
 *       the kind the class's loader reads the column with.
 * </ul>
 */
final class TableResolver {

  /** The class an action reference names, besides the classes that declare the action table. */
  static final String ACTION_CLASS = "logic.data.LogicActionData";

  private static final Set<String> OPERATORS = Set.of("=", "+", "-", "*", "/", "%");

  /** An empty int cell, and the TOML int 2147483647: the reader's default, read as absent. */
  static final long INT_DEFAULT = 0x7fffffffL;

  private static final BigInteger TWO_32 = BigInteger.ONE.shiftLeft(32);

  /** A resolved row: its table key, name, origin and class, and the stack it reads from. */
  static final class Row {
    final String table;
    final String name;
    final String origin;
    final List<Layer> own;
    List<Layer> inherited = new ArrayList<>();
    Row base;

    /** Null until linked; a load position, or the name of how it was linked. */
    Object link;

    Row(String table, String name, List<Layer> own, String origin) {
      this.table = table;
      this.name = name;
      this.own = own;
      this.origin = origin;
    }

    /**
     * The layers a reader sees, bottom first; upto limits the own layers to those loaded by then.
     */
    List<Layer> stack(Double upto) {
      List<Layer> out = new ArrayList<>(inherited);
      for (Layer l : own) {
        if (upto == null || l.seq <= upto) {
          out.add(l);
        }
      }
      return out;
    }

    List<Layer> stack() {
      return stack(null);
    }
  }

  private final ClientSchema schema;
  private final LoadModel model;
  private final Map<String, Integer> tokens;
  private final List<String> combined;
  private final Map<String, String> referenceTables = new HashMap<>();
  private final Set<List<String>> arrayReadColumns = new HashSet<>();
  private final String actionTable;
  private final Set<String> actionColumns = new HashSet<>();
  private final Map<String, String> byClassType = new HashMap<>();

  /** The rows by table key and name, in creation order. */
  final Map<List<String>, Row> rows = new LinkedHashMap<>();

  /** Per loaded CSV file, its column names. */
  private final Map<String, Set<String>> csvHeaders = new HashMap<>();

  /** Per table key, per column, the type its CSV declares (the first file and position). */
  private final Map<String, Map<String, String>> csvTypes = new HashMap<>();

  /** Per file, per column, the declared type of its first position. */
  private final Map<String, Map<String, String>> csvFileTypes = new HashMap<>();

  TableResolver(ClientSchema schema, LoadModel model) {
    this.schema = schema;
    this.model = model;
    this.tokens = schema.typeTokens();
    this.combined = schema.combinedCharacters().stream().map(String::valueOf).toList();
    schema.referenceTables().forEach((c, t) -> referenceTables.put(c, String.valueOf(t)));
    schema
        .arrayReadCsvColumns()
        .forEach((t, cs) -> cs.forEach(c -> arrayReadColumns.add(List.of(t, c))));
    this.actionTable = String.valueOf(tokens.get("ACTION"));
    Set<String> actionClasses = actionReferenceClasses(schema);
    schema
        .classes()
        .values()
        .forEach(
            c ->
                c.refs()
                    .forEach(
                        (field, target) -> {
                          if (actionClasses.contains(target)) {
                            actionColumns.add(field);
                          }
                        }));
    schema
        .classes()
        .forEach(
            (name, c) -> {
              if (c.classType() != null) {
                byClassType.put(c.classType(), name);
              }
            });
    model.csvColumns.forEach(
        (file, columns) -> {
          Set<String> names = new HashSet<>();
          Map<String, String> types = new HashMap<>();
          for (String[] column : columns) {
            names.add(column[0]);
            if (!types.containsKey(column[0])) {
              types.put(column[0], column[1]); // the first position's type, even a missing one
            }
          }
          csvHeaders.put(file, names);
          csvFileTypes.put(file, types);
        });
    model.csvTables.forEach(
        (file, table) -> {
          Map<String, String> types = csvTypes.computeIfAbsent(table, k -> new HashMap<>());
          for (String[] column : model.csvColumns.get(file)) {
            if (!types.containsKey(column[0])) {
              types.put(column[0], column[1]); // the first file's and position's type
            }
          }
        });
  }

  /**
   * The classes a reference to an action row names: the action class and every class that declares
   * the action table.
   */
  static Set<String> actionReferenceClasses(ClientSchema schema) {
    int actionTable = schema.typeTokens().get("ACTION");
    Set<String> out = new HashSet<>();
    out.add(ACTION_CLASS);
    schema
        .classes()
        .forEach(
            (name, c) -> {
              if (c.tables().contains(actionTable)) {
                out.add(name);
              }
            });
    return out;
  }

  /** Resolves every row: links, patches, embedded rows. */
  TableResolver resolve() {
    for (ModelRow m : model.rows.values()) {
      rows.put(
          List.of(m.table(), m.name()), new Row(m.table(), m.name(), m.layers(), m.createdBy()));
    }
    if (schema.extLink() == ClientSchema.ExtLink.DEFERRED) {
      linkDeferred();
    } else {
      for (Row row : new ArrayList<>(rows.values())) {
        linkExt(row, new ArrayList<>());
      }
    }
    rows.values().removeIf(r -> "dropped".equals(r.link));
    applyPatches();
    for (Row row : new ArrayList<>(rows.values())) {
      if (!row.origin.equals("embedded")) {
        embed(row);
      }
    }
    return this;
  }

  // --- reading -----------------------------------------------------------------------------------

  /** The row's columns, each typed as its reader gets it, in first-seen order down the stack. */
  Map<String, Object> columns(Row row) {
    Set<String> seen = new LinkedHashSet<>();
    for (Layer l : row.stack()) {
      seen.addAll(l.values.keySet());
    }
    Map<String, Object> out = new LinkedHashMap<>();
    for (String column : seen) {
      Layer[] from = new Layer[1];
      Object value = read(row, column, from);
      if (from[0] != null) {
        out.put(column, value);
      }
    }
    return out;
  }

  /** The class of a row: the ClassType a TOML layer names, else its table's one class. */
  String rowClass(Row row) {
    List<Layer> stack = row.stack();
    for (int i = stack.size() - 1; i >= 0; i--) {
      Object ct = stack.get(i).values.get("ClassType");
      if (ct instanceof String s && byClassType.containsKey(s)) {
        return byClassType.get(s);
      }
    }
    Set<String> classes =
        LoadModel.isDigits(row.table) ? schema.tableClasses().get(row.table) : null;
    return classes != null && classes.size() == 1 ? classes.iterator().next() : null;
  }

  private boolean layerHas(Layer layer, String column) {
    if (layer.source.equals("csv")) {
      Set<String> header = csvHeaders.get(layer.file);
      return header != null && header.contains(column) && layer.values.containsKey(column);
    }
    return layer.values.containsKey(column);
  }

  /** The layer of the first layer from the top that has the column, or null. */
  private Layer rawLayer(List<Layer> stack, String column) {
    for (int i = stack.size() - 1; i >= 0; i--) {
      if (layerHas(stack.get(i), column)) {
        return stack.get(i);
      }
    }
    return null;
  }

  private static Object stored(Layer layer, String column) {
    return layer.resolved.containsKey(column)
        ? layer.resolved.get(column)
        : layer.values.get(column);
  }

  @SuppressWarnings("unchecked")
  private Object read(Row row, String column, Layer[] from) {
    Layer layer = rawLayer(row.stack(), column);
    from[0] = layer;
    if (layer == null) {
      return null;
    }
    Object v = stored(layer, column);
    if (layer.source.equals("csv")) {
      String declared = csvFileTypes.getOrDefault(layer.file, Map.of()).get(column);
      if (arrayReadColumns.contains(List.of(row.table, column)) && !isList(declared)) {
        declared = (declared == null ? "" : declared) + "Array";
      }
      return csvValue(declared, (List<String>) v);
    }
    Reader reader = columnType(row, column);
    return tomlValue(reader.kind(), reader.list(), v);
  }

  /**
   * The reader of a column a TOML layer sets.
   *
   * @param kind int, bool, float, string, or null when not known
   * @param list whether it reads a list
   */
  private record Reader(String kind, boolean list) {}

  /**
   * The reader of a TOML-set column: the type the table's CSV declares for it (a list for an
   * array-read column), else the kind the row class's loader reads it with, never a list.
   */
  private Reader columnType(Row row, String column) {
    String declared = csvTypes.getOrDefault(row.table, Map.of()).get(column);
    if (declared != null && !declared.isEmpty()) {
      boolean list = isList(declared) || arrayReadColumns.contains(List.of(row.table, column));
      return new Reader(baseKind(declared), list);
    }
    String cls = rowClass(row);
    String kind = null;
    if (cls != null) {
      kind =
          schema
              .loaderKinds()
              .getOrDefault(cls, Map.of())
              .getOrDefault(row.table, Map.of())
              .get(column);
    }
    if (!"int".equals(kind)
        && !"bool".equals(kind)
        && !"float".equals(kind)
        && !"string".equals(kind)) {
      kind = null;
    }
    return new Reader(kind, false);
  }

  // --- typed reads -------------------------------------------------------------------------------

  private static boolean isList(String declared) {
    return declared != null && declared.toLowerCase(Locale.ROOT).endsWith("array");
  }

  /** int, bool, string, float or null, from a CSV type ('int', 'Int', 'StringArray', ...). */
  static String baseKind(String declared) {
    String t = declared == null ? "" : declared.toLowerCase(Locale.ROOT);
    if (t.endsWith("array")) {
      t = t.substring(0, t.length() - 5);
    }
    return switch (t) {
      case "int" -> "int";
      case "boolean", "bool" -> "bool";
      case "string" -> "string";
      case "float" -> "float";
      default -> null;
    };
  }

  static long wrap32(long n) {
    return (int) n;
  }

  static long wrap32(BigInteger n) {
    return n.mod(TWO_32).intValue();
  }

  /**
   * A CSV int cell: 1 to 11 characters, an optional '-', digits only, else 0; 32-bit. Empty, or the
   * default value: null.
   */
  static Long csvInt(String text) {
    if (text == null || text.isEmpty()) {
      return null;
    }
    String body = text.startsWith("-") ? text.substring(1) : text;
    if (text.length() > 11 || !LoadModel.isDigits(body)) {
      return 0L;
    }
    long n = wrap32(new BigInteger(digitsOf(text)));
    return n == INT_DEFAULT ? null : n;
  }

  private static String digitsOf(String text) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      out.append(c == '-' ? '-' : Character.forDigit(Character.digit(c, 10), 10));
    }
    return out.toString();
  }

  /** Python's float() grammar, after the surrounding whitespace is stripped. */
  private static final Pattern PY_FLOAT =
      Pattern.compile(
          "[+-]?(?:(?:\\d(?:_?\\d)*(?:\\.(?:\\d(?:_?\\d)*)?)?|\\.\\d(?:_?\\d)*)"
              + "(?:[eE][+-]?\\d(?:_?\\d)*)?|inf|infinity|nan)",
          Pattern.CASE_INSENSITIVE);

  /** float(text) as Python reads it, or null where Python refuses the text. */
  static Double pythonFloat(String text) {
    String t = text.strip();
    if (!PY_FLOAT.matcher(t).matches()) {
      return null;
    }
    String unsigned = t.toLowerCase(Locale.ROOT).replaceFirst("^[+-]", "");
    boolean negative = t.startsWith("-");
    if (unsigned.startsWith("inf")) {
      return negative ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
    }
    if (unsigned.equals("nan")) {
      return Double.NaN;
    }
    return Double.parseDouble(t.replace("_", ""));
  }

  static Object csvCell(String kind, String text) {
    if ("int".equals(kind)) {
      return csvInt(text);
    }
    if ("bool".equals(kind)) {
      return (text == null ? "" : text).toLowerCase(Locale.ROOT).equals("true");
    }
    if ("float".equals(kind)) {
      if (text == null || text.isEmpty()) {
        return 0.0;
      }
      Double d = pythonFloat(text);
      return d == null ? 0.0 : (double) (float) d.doubleValue();
    }
    return text == null ? "" : text;
  }

  static Object csvValue(String declared, List<String> cells) {
    String kind = baseKind(declared);
    if (!isList(declared)) {
      return csvCell(kind, cells.isEmpty() ? "" : cells.get(0));
    }
    List<String> kept = new ArrayList<>(cells);
    while (!kept.isEmpty()
        && (kept.get(kept.size() - 1) == null || kept.get(kept.size() - 1).isEmpty())) {
      kept.remove(kept.size() - 1); // trailing empties do not count
    }
    List<Object> out = new ArrayList<>();
    for (String c : kept) {
      if ("int".equals(kind)) {
        if (c == null || c.isEmpty()) {
          out.add(0L);
        } else {
          Long n = csvInt(c);
          out.add(n != null ? n : INT_DEFAULT);
        }
      } else {
        out.add(csvCell(kind, c == null ? "" : c));
      }
    }
    return out;
  }

  /**
   * A TOML value as a reader of this kind gets it: 32-bit ints, a list column written as a scalar
   * reads empty, an int reader truncates a float. A value that does not fit the kind is kept.
   */
  static Object tomlValue(String kind, boolean isList, Object v) {
    if (isList) {
      if (!(v instanceof List<?> list)) {
        return new ArrayList<>();
      }
      List<Object> out = new ArrayList<>();
      for (Object x : list) {
        out.add(tomlValue(kind, false, x));
      }
      return out;
    }
    if (v instanceof List<?> list) {
      List<Object> out = new ArrayList<>();
      for (Object x : list) {
        out.add(tomlValue(kind, false, x));
      }
      return out;
    }
    if (v instanceof Boolean) {
      return v;
    }
    if (v instanceof Long || v instanceof BigInteger) {
      long n = v instanceof Long l ? wrap32(l) : wrap32((BigInteger) v);
      if ("float".equals(kind)) {
        return (double) (float) n;
      }
      if ("int".equals(kind) && n == INT_DEFAULT) {
        return null;
      }
      return n;
    }
    if (v instanceof Double d) {
      if ("int".equals(kind)) {
        return wrap32(truncate(d));
      }
      return d;
    }
    return v;
  }

  private static BigInteger truncate(double d) {
    return new BigDecimal(d).toBigInteger();
  }

  // --- operators ---------------------------------------------------------------------------------

  static boolean isOperator(Object v) {
    return v instanceof List<?> list
        && list.size() == 2
        && list.get(0) instanceof String op
        && OPERATORS.contains(op);
  }

  /** The operator value against the base's value, 32-bit, truncated. */
  static long applyOperator(String op, Object operand, long base) {
    base = wrap32(base);
    long n = wrap32(asInteger(operand));
    return switch (op) {
      case "=" -> n;
      case "+" -> wrap32(base + n);
      case "-" -> wrap32(base - n);
      case "*" -> wrap32(base * n);
      case "/" -> n == 0 ? 0 : wrap32(base / n);
      case "%" -> wrap32(wrap32(base * n) / 100);
      default -> throw new IllegalArgumentException(op);
    };
  }

  /** Python's int() of an operand: a float truncated, a bool as 0 or 1, a string parsed. */
  private static BigInteger asInteger(Object operand) {
    if (operand instanceof Long l) {
      return BigInteger.valueOf(l);
    }
    if (operand instanceof BigInteger b) {
      return b;
    }
    if (operand instanceof Double d) {
      return truncate(d);
    }
    if (operand instanceof Boolean b) {
      return b ? BigInteger.ONE : BigInteger.ZERO;
    }
    if (operand instanceof String s) {
      return new BigInteger(s.strip().replace("_", ""));
    }
    throw new IllegalArgumentException("an operator operand that is no number: " + operand);
  }

  /** A base value for an operator: read as an integer through the layers; a missing column is 0. */
  private long intOf(List<Layer> stack, String column) {
    Layer layer = rawLayer(stack, column);
    if (layer == null) {
      return 0;
    }
    Object v = stored(layer, column);
    if (layer.source.equals("csv")) {
      List<?> cells = (List<?>) v;
      Long n = csvInt(cells.isEmpty() ? "" : (String) cells.get(0));
      return n != null ? n : 0;
    }
    if (v instanceof List<?> list) {
      v = list.isEmpty() ? 0L : list.get(0);
    }
    if (v instanceof Long l) {
      return wrap32(l);
    }
    if (v instanceof BigInteger b) {
      return wrap32(b);
    }
    if (v instanceof Double d) {
      return wrap32(truncate(d));
    }
    return 0;
  }

  private void resolveOperators(Layer l, List<Layer> inherited) {
    for (Map.Entry<String, Object> e : l.values.entrySet()) {
      if (isOperator(e.getValue())) {
        List<?> op = (List<?>) e.getValue();
        l.resolved.put(
            e.getKey(), applyOperator((String) op.get(0), op.get(1), intOf(inherited, e.getKey())));
      }
    }
  }

  // --- inheritance -------------------------------------------------------------------------------

  private Row findBase(String token, String name) {
    String table = String.valueOf(tokens.get(token));
    Row found = rows.get(List.of(table, name));
    if (found != null) {
      return found;
    }
    if (combined.contains(table)) {
      String other = table.equals(combined.get(0)) ? combined.get(1) : combined.get(0);
      return rows.get(List.of(other, name));
    }
    return null;
  }

  private static String baseOf(Layer ext) {
    Object base = ext.values.get("Base");
    return base instanceof String s ? s : "";
  }

  private static String before(String s, char c) {
    int i = s.indexOf(c);
    return i < 0 ? s : s.substring(0, i);
  }

  private static String after(String s, char c) {
    int i = s.indexOf(c);
    return i < 0 ? "" : s.substring(i + 1);
  }

  /** The at-creation rule: linked when made if its base exists, else when its base is made. */
  private void linkExt(Row row, List<Row> seen) {
    if (row.link != null || !row.origin.equals("ext")) {
      return;
    }
    if (seen.contains(row)) {
      return; // an EXT cycle
    }
    Layer ext = row.own.get(0);
    String base = baseOf(ext);
    Row target = findBase(before(base, '.'), after(base, '.'));
    if (target == null) {
      row.link = "dropped";
      return;
    }
    List<Row> deeper = new ArrayList<>(seen);
    deeper.add(row);
    linkExt(target, deeper);
    double created = target.own.isEmpty() ? 0 : target.own.get(0).seq;
    double at = created < ext.seq ? ext.seq : created;
    row.base = target;
    row.link = at;
    row.inherited = target.stack(at);
    for (Layer l : row.own) {
      resolveOperators(l, row.inherited);
    }
  }

  /** One event of the load in order: a layer added, or a repeated manifest file loaded again. */
  private record Event(double at, Row row, Layer layer) {}

  /**
   * The deferred rule, replayed over the layers in load order: an EXT section records its row as
   * pending; any other section adds its layer and links the pending EXT rows of its table whose
   * base is this row, and theirs in turn; after every file, each table's pending EXT rows are
   * linked against their bases' final layers until a pass links none.
   */
  private void linkDeferred() {
    List<Event> events = new ArrayList<>();
    for (Row row : rows.values()) {
      for (Layer l : row.own) {
        events.add(new Event(l.seq, row, l));
      }
    }
    events.sort(Comparator.comparingDouble(Event::at));
    events.addAll(manifestRepeats());
    events.sort(Comparator.comparingDouble(Event::at));
    Map<String, LinkedHashMap<String, String>> pending = new LinkedHashMap<>();
    for (Event e : events) {
      if (e.layer() == null || !e.layer().source.equals("ext")) {
        cascade(e.row(), e.at(), pending);
      } else {
        pending
            .computeIfAbsent(e.row().table, k -> new LinkedHashMap<>())
            .put(e.row().name, after(baseOf(e.layer()), '.'));
      }
    }
    List<String> tables = new ArrayList<>(pending.keySet());
    tables.sort(
        Comparator.<String, Boolean>comparing(t -> !LoadModel.isDigits(t))
            .thenComparing(
                (a, b) ->
                    LoadModel.isDigits(a) && LoadModel.isDigits(b)
                        ? new BigInteger(a).compareTo(new BigInteger(b))
                        : a.compareTo(b)));
    for (String table : tables) {
      LinkedHashMap<String, String> waiting = pending.get(table);
      while (!waiting.isEmpty()) {
        boolean linked = false;
        List<String> names = new ArrayList<>(waiting.keySet());
        for (int i = names.size() - 1; i >= 0; i--) {
          if (waiting.containsKey(names.get(i))) {
            linked |= linkPending(rows.get(List.of(table, names.get(i))), null, pending);
          }
        }
        if (!linked) {
          break;
        }
      }
    }
    pending.forEach(
        (table, waiting) ->
            waiting.keySet().forEach(name -> rows.get(List.of(table, name)).link = "unlinked"));
  }

  /**
   * A file the manifest lists more than once is loaded again at every later entry; each row it
   * touches gets the cascade again there, after every earlier entry's layers.
   */
  @SuppressWarnings("unchecked")
  private List<Event> manifestRepeats() {
    List<String> order = new ArrayList<>();
    for (Object entry : model.manifest.values()) {
      if (entry instanceof Map<?, ?> m && m.containsKey("Path")) {
        order.add((String) ((Map<String, Object>) m).get("Path"));
      }
    }
    Map<String, Integer> first = new HashMap<>();
    for (int i = 0; i < order.size(); i++) {
      first.putIfAbsent(order.get(i), i);
    }
    Map<String, List<Event>> byFile = new LinkedHashMap<>();
    for (Row row : rows.values()) {
      for (Layer l : row.own) {
        if (first.containsKey(l.file)) {
          byFile.computeIfAbsent(l.file, k -> new ArrayList<>()).add(new Event(l.seq, row, l));
        }
      }
    }
    List<Event> out = new ArrayList<>();
    for (int position = 0; position < order.size(); position++) {
      String file = order.get(position);
      if (first.get(file) == position) {
        continue;
      }
      double before = 0;
      boolean any = false;
      for (Map.Entry<String, List<Event>> f : byFile.entrySet()) {
        if (first.get(f.getKey()) < position) {
          for (Event e : f.getValue()) {
            before = any ? Math.max(before, e.at()) : e.at();
            any = true;
          }
        }
      }
      double at = before + 0.5;
      for (Event e : byFile.getOrDefault(file, List.of())) {
        if (e.layer().source.equals("ext")) {
          continue;
        }
        out.add(new Event(at, e.row(), null));
      }
    }
    return out;
  }

  private void cascade(Row row, double seq, Map<String, LinkedHashMap<String, String>> pending) {
    for (Map.Entry<String, String> e :
        new ArrayList<>(pending.getOrDefault(row.table, new LinkedHashMap<>()).entrySet())) {
      if (e.getValue().equals(row.name)
          && pending.getOrDefault(row.table, new LinkedHashMap<>()).containsKey(e.getKey())) {
        Row ext = rows.get(List.of(row.table, e.getKey()));
        linkPending(ext, seq, pending);
        cascade(ext, seq, pending);
      }
    }
  }

  /** Links a pending row against its base's layers as of seq (null: the final layers). */
  private boolean linkPending(
      Row row, Double seq, Map<String, LinkedHashMap<String, String>> pending) {
    LinkedHashMap<String, String> waiting = pending.getOrDefault(row.table, new LinkedHashMap<>());
    String baseName = waiting.get(row.name);
    if (baseName == null || baseName.isEmpty() || waiting.containsKey(baseName)) {
      return false;
    }
    boolean anyOwn = false;
    Layer ext = null;
    for (Layer l : row.own) {
      if (seq == null || l.seq <= seq) {
        anyOwn = true;
        if (l.source.equals("ext")) {
          ext = l;
        }
      }
    }
    if (!anyOwn) {
      return false;
    }
    Row target = findBase(before(baseOf(ext), '.'), baseName);
    List<Layer> stack = target != null ? target.stack(seq) : List.of();
    if (stack.isEmpty()) {
      return false;
    }
    List<Layer> inherited = new ArrayList<>();
    for (Layer l : stack) {
      // a base layer with a Base key of its own is skipped, unless it is an EXT layer
      if (l.source.equals("ext") || !l.values.containsKey("Base")) {
        inherited.add(l);
      }
    }
    row.base = target;
    row.link = seq != null ? seq : "end of load";
    row.inherited = inherited;
    for (Layer l : row.own) {
      if (seq != null && l.seq > seq) {
        continue; // added after the link, on top, as it is
      }
      resolveOperators(l, row.inherited);
    }
    waiting.remove(row.name);
    return true;
  }

  // --- patch tables ------------------------------------------------------------------------------

  private void applyPatches() {
    List<Integer> patchTables = new ArrayList<>(schema.patchTargets().keySet());
    patchTables.sort(null);
    for (int patchTable : patchTables) {
      String target = String.valueOf(schema.patchTargets().get(patchTable));
      for (Row prow : new ArrayList<>(rows.values())) {
        if (!prow.table.equals(String.valueOf(patchTable))) {
          continue;
        }
        Layer body = prow.own.get(0);
        Object baseName = body.values.get("Base");
        Map<String, Object> values = new LinkedHashMap<>(body.values);
        values.remove("Base");
        Layer layer = new Layer(body.seq, "patch", body.file, body.section, values);
        if (!truthy(baseName)) {
          Row row = new Row(target, prow.name, new ArrayList<>(List.of(layer)), "patch");
          row.link = "finalize";
          rows.put(List.of(target, prow.name), row);
          continue;
        }
        Row base = rows.get(List.of(target, String.valueOf(baseName)));
        if (base == null || base.stack().isEmpty()) {
          continue; // the patch does nothing
        }
        List<Layer> stack = base.stack();
        resolveOperators(layer, stack);
        Row row = new Row(target, prow.name, new ArrayList<>(List.of(layer)), "patch");
        row.inherited = stack;
        row.base = base;
        row.link = "finalize";
        rows.put(List.of(target, prow.name), row);
      }
    }
  }

  // --- embedded rows -----------------------------------------------------------------------------

  private String referenceTarget(String column) {
    if (referenceTables.containsKey(column)) {
      return referenceTables.get(column);
    }
    return actionColumns.contains(column) ? actionTable : null;
  }

  @SuppressWarnings("unchecked")
  private void embed(Row row) {
    for (Layer l : row.stack()) {
      for (Map.Entry<String, Object> e : new ArrayList<>(l.values.entrySet())) {
        String column = e.getKey();
        String target = referenceTarget(column);
        if (target == null) {
          continue;
        }
        Object v = e.getValue();
        List<Object[]> items = new ArrayList<>();
        if (v instanceof Map<?, ?>) {
          items.add(new Object[] {null, v});
        } else if (v instanceof List<?> list) {
          for (int i = 0; i < list.size(); i++) {
            items.add(new Object[] {i, list.get(i)});
          }
        }
        for (Object[] item : items) {
          if (!(item[1] instanceof Map<?, ?>)) {
            continue;
          }
          Map<String, Object> body = (Map<String, Object>) item[1];
          Object named = body.get("Name");
          String name =
              truthy(named)
                  ? String.valueOf(named)
                  : row.name + "_" + column + (item[0] == null ? "" : item[0]);
          Layer layer = new Layer(l.seq, "embedded", l.file, l.section + " " + column, body);
          Row embedded = new Row(target, name, new ArrayList<>(List.of(layer)), "embedded");
          embedded.link = "embedded";
          rows.put(List.of(target, name), embedded);
          embed(embedded);
        }
      }
    }
  }

  /** Python's truth of a value: null, false, zero and empty are false. */
  static boolean truthy(Object v) {
    if (v == null) {
      return false;
    }
    if (v instanceof Boolean b) {
      return b;
    }
    if (v instanceof String s) {
      return !s.isEmpty();
    }
    if (v instanceof Long l) {
      return l != 0;
    }
    if (v instanceof Double d) {
      return d != 0;
    }
    if (v instanceof BigInteger b) {
      return b.signum() != 0;
    }
    if (v instanceof Map<?, ?> m) {
      return !m.isEmpty();
    }
    if (v instanceof List<?> list) {
      return !list.isEmpty();
    }
    return true;
  }
}
