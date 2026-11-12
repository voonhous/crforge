/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.Buffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.util.DeepCopy;

/**
 * What a fork of a Ladder battle copies ({@link LadderEnv#fork}): the objects an environment holds
 * that are not the game tables', counted by kind and by class, for the battles {@link
 * ForkBenchmark} forks. A profile shows where a fork's time goes; this shows what there is to copy.
 *
 * <p>Run by {@code ./gradlew :env:forkCensus}, with the ticks to count at as arguments (500, 1500
 * and 3000 without). The count leaves out the environment's own buffers, which a fork allocates
 * anew, and counts a boxed number or a string a fork would keep as it is.
 */
public final class ForkCensus {

  private ForkCensus() {}

  /** How many classes of each kind the per-class table lists. */
  private static final int LISTED = 25;

  public static void main(String[] args) {
    GameTables tables = GameTables.loadConfigured();
    Set<Object> shared = DeepCopy.reachable(tables);
    int[] ticks = args.length == 0 ? new int[] {500, 1500, 3000} : parse(args);
    for (int tick : ticks) {
      try (VectorEnv vector = ForkBenchmark.playedTo(tables, tick)) {
        report(tick, (LadderEnv) vector.env(0), shared);
      }
    }
  }

  private static int[] parse(String[] args) {
    int[] ticks = new int[args.length];
    for (int i = 0; i < args.length; i++) {
      ticks[i] = Integer.parseInt(args[i]);
    }
    return ticks;
  }

  /** One class's share of the count. */
  private static final class Row {
    int objects;
    long elements;
  }

  private static void report(int tick, LadderEnv env, Set<Object> shared) {
    Set<Object> own = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Object root : roots(env)) {
      for (Object object : DeepCopy.reachable(root)) {
        if (!shared.contains(object)) {
          own.add(object);
        }
      }
    }
    Map<String, Row> kinds = new TreeMap<>();
    Map<String, Row> classes = new TreeMap<>();
    for (Object object : own) {
      Class<?> type = object.getClass();
      long elements = type.isArray() ? Array.getLength(object) : 0;
      add(kinds, kind(type), elements);
      add(classes, kind(type) + "  " + name(type), elements);
    }
    System.out.printf("%n== tick %d: %d objects a fork walks ==%n", env.tick(), own.size());
    print(kinds, Integer.MAX_VALUE);
    System.out.printf("%n-- the %d classes with the most objects --%n", LISTED);
    print(classes, LISTED);
  }

  /** What the environment holds besides its buffers: the values of its fields. */
  private static List<Object> roots(LadderEnv env) {
    List<Object> roots = new ArrayList<>();
    for (Field field : LadderEnv.class.getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
        continue;
      }
      field.setAccessible(true);
      try {
        Object value = field.get(env);
        if (value != null && !(value instanceof Buffer)) {
          roots.add(value);
        }
      } catch (IllegalAccessException e) {
        throw new IllegalStateException(e);
      }
    }
    return roots;
  }

  private static String kind(Class<?> type) {
    if (type.isArray()) {
      return type.getComponentType().isPrimitive() ? "primitive array" : "object array";
    }
    if (type.isHidden()) {
      return "lambda";
    }
    if (type.isRecord()) {
      return "record";
    }
    if (type.isEnum() || type.getSuperclass() != null && type.getSuperclass().isEnum()) {
      return "kept: enum";
    }
    String name = type.getName();
    if (name.startsWith("java.lang.")) {
      return "kept: string or boxed";
    }
    if (name.startsWith("java.")) {
      return "JDK collection";
    }
    return "plain object";
  }

  private static String name(Class<?> type) {
    String name = type.getTypeName();
    // A lambda's class is named after the class that makes it, then a number of its own.
    int lambda = name.indexOf("$$Lambda");
    return lambda < 0 ? name : name.substring(0, lambda) + " (lambda)";
  }

  private static void add(Map<String, Row> rows, String key, long elements) {
    Row row = rows.computeIfAbsent(key, k -> new Row());
    row.objects++;
    row.elements += elements;
  }

  private static void print(Map<String, Row> rows, int limit) {
    rows.entrySet().stream()
        .sorted(Comparator.comparingInt((Map.Entry<String, Row> e) -> -e.getValue().objects))
        .limit(limit)
        .forEach(
            e ->
                System.out.printf(
                    "%7d objects %9s  %s%n",
                    e.getValue().objects,
                    e.getValue().elements == 0 ? "" : e.getValue().elements + " el",
                    e.getKey()));
  }
}
