/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.util;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import sun.misc.Unsafe;

/**
 * A deep copy of an object graph: every object reachable from the root is copied once, so the
 * copy's objects refer to each other as the originals do (shared and cyclic references included),
 * and nothing the copy holds is an original, except what the caller names as shared.
 *
 * <p>How each object is copied:
 *
 * <ul>
 *   <li>A shared object (the caller's predicate), a {@code String}, a boxed primitive, an enum
 *       constant, a {@code Class}, a plain {@code Object} and a lambda that captures nothing are
 *       the originals.
 *   <li>An array is a new array of the same type with every element copied.
 *   <li>An object of a class of this code base (not of the JDK) is allocated without a constructor
 *       and every instance field of it and of its superclasses copied; an anonymous or inner class
 *       copies its enclosing instance as a field.
 *   <li>A record is built by its canonical constructor from its copied components, and a lambda
 *       that captures values by its own constructor from its copied captures. Neither can change
 *       what it holds, so one whose values are all kept as they are (leaves, shared objects and
 *       others so kept) is the original, as is a record whose components can only be values (by
 *       their types: primitives, strings, boxed primitives, enums and such records).
 *   <li>A collection or map of the JDK is rebuilt as one of its own class holding the copied
 *       elements, in the original's order of iteration; an immutable one ({@code List.of} and the
 *       like) is rebuilt immutable, or is the original when its elements are all kept as they are.
 * </ul>
 *
 * <p>A JDK class not listed here (other than the shared kinds above) is refused, naming it: its
 * private state cannot be copied faithfully from outside the JDK. A view of another collection
 * ({@code Collections.unmodifiableList} and the like) is refused for the same reason.
 *
 * <p>A plain object's fields are filled in after it is made, so a graph of any depth is copied
 * without deep recursion; a collection is filled as it is copied, its elements possibly not filled
 * in yet. So an element hashed into a set or map must hash by identity, by value or by shared data:
 * a plain object whose class defines its own hash code is refused there. A sorted collection is
 * filled once every plain object is, as its comparator reads its elements. A collection keyed by
 * identity (an {@link IdentityHashMap}) iterates in an order of the copy's identity hashes, not the
 * original's.
 *
 * <p>A plain object's fields are read and written through {@code sun.misc.Unsafe} at their offsets:
 * the reflective accessors cost several times as much. Its memory access methods are deprecated for
 * removal from JDK 23; the build is on JDK 21.
 *
 * <p>TODO: replace this with copy code generated at build time: an annotation processor writing a
 * {@code copyInto(CopyContext)} for each class of battle state, with the lambdas that capture state
 * (about 25 fields, most in {@code GridStateSetter} and {@code SelectionChain}) made named classes.
 * That drops {@code Unsafe}, the reflection and the reliance on the JDK's lambda layout (captures
 * in fields {@code arg$1}, {@code arg$2}... in its constructor's order), and copies faster. Keep
 * this class as the test oracle then: a copy must reach no original but the shared data. Do it once
 * a profile of the search shows the copy costs enough to matter (docs/environment.md, "What is not
 * offered yet").
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class DeepCopy {

  private static final Unsafe UNSAFE = unsafe();

  /** How objects of each class are copied, worked out once per class. */
  private static final ClassValue<Plan> PLANS =
      new ClassValue<>() {
        @Override
        protected Plan computeValue(Class<?> type) {
          return Plan.of(type);
        }
      };

  private final Predicate<Object> shared;

  /** What earlier copies found unchanging, as this copy began; empty without a memo. */
  private final IdentityHashMap<Object, Object> unchanging;

  /** What this copy finds unchanging, to add to its memo; null without one. */
  private final List<Object> found;

  /**
   * How many objects the last copy made, in any thread: the next copy's map of copies is made for
   * as many and a quarter more, as a map grown from empty is rehashed several times over a battle's
   * few thousand objects. Only a hint: a graph of another size is copied as well.
   */
  private static volatile int lastCopies = 256;

  private final IdentityHashMap<Object, Object> copies =
      new IdentityHashMap<>(lastCopies + lastCopies / 4);

  /** Plain objects allocated and not yet filled in: the original then the copy. */
  private final ArrayDeque<Object> unfilled = new ArrayDeque<>();

  /** Sorted collections and maps made empty: the copy, then its copied elements to insert. */
  private final ArrayDeque<Object> sorted = new ArrayDeque<>();

  /**
   * A deep copy of a graph.
   *
   * @param root the graph's root
   * @param shared the objects the copy keeps as they are, with everything they hold: data that
   *     never changes, such as the game tables
   * @return the root's copy
   * @throws UnsupportedOperationException naming a class the graph holds that cannot be copied
   */
  public static <T> T of(T root, Predicate<Object> shared) {
    return of(root, shared, null);
  }

  /**
   * As {@link #of(Object, Predicate)}, remembering between copies what never changes.
   *
   * @param root the graph's root
   * @param shared the objects the copy keeps as they are; it must answer the same for an object at
   *     every copy that uses the memo
   * @param memo what the copies of these graphs found unchanging so far, added to by this one, or
   *     null for none
   * @return the root's copy
   * @throws UnsupportedOperationException naming a class the graph holds that cannot be copied
   */
  public static <T> T of(T root, Predicate<Object> shared, Memo memo) {
    DeepCopy copy =
        new DeepCopy(
            shared,
            memo == null ? new IdentityHashMap<>(0) : memo.unchanging,
            memo == null ? null : new ArrayList<>());
    @SuppressWarnings("unchecked")
    T result = (T) copy.copy(root);
    copy.drain();
    lastCopies = copy.copies.size();
    if (memo != null && !copy.found.isEmpty()) {
      memo.add(copy.found);
    }
    return result;
  }

  /**
   * The records, capturing lambdas and immutable collections that copies found to hold nothing a
   * copy changes: only leaves, shared objects and others of their kind. Such an object can never
   * change, so every later copy keeps it too, and a copy given the memo keeps it without reading it
   * again. A battle's units each hold records of some hundred values read from the tables, which
   * cost a copy more to read than the battle's own state.
   *
   * <p>One memo serves the copies of graphs that share objects, such as a battle and its forks; a
   * graph is copied the same with it as without. It holds the objects strongly, so it should live
   * no longer than the graphs do. Several threads may copy with one memo at once: a copy reads the
   * objects known as it began, and adds the ones it found as a new map when it ends, which costs a
   * pass over the memo only when it found some.
   */
  public static final class Memo {
    private volatile IdentityHashMap<Object, Object> unchanging = new IdentityHashMap<>(0);

    /** How many objects the memo holds. */
    public int size() {
      return unchanging.size();
    }

    private synchronized void add(List<Object> found) {
      IdentityHashMap<Object, Object> next =
          new IdentityHashMap<>(unchanging.size() + found.size());
      next.putAll(unchanging);
      for (Object object : found) {
        next.put(object, object);
      }
      unchanging = next;
    }
  }

  /**
   * Every object reachable from a root, by identity, walked as {@link #of} would copy them: for a
   * predicate of shared objects that keeps a whole graph, such as the game tables and all they
   * hold.
   *
   * @param root the graph's root
   * @return a set of the objects, compared by identity
   */
  public static Set<Object> reachable(Object root) {
    Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    // A stack of the objects still to visit, nulls left in and skipped.
    List<Object> open = new ArrayList<>();
    open.add(root);
    while (!open.isEmpty()) {
      Object object = open.remove(open.size() - 1);
      if (object == null || !seen.add(object)) {
        continue;
      }
      Class<?> type = object.getClass();
      if (type.isArray()) {
        if (!type.getComponentType().isPrimitive()) {
          for (Object element : (Object[]) object) {
            open.add(element);
          }
        }
        continue;
      }
      Plan plan = PLANS.get(type);
      switch (plan.kind) {
        case LEAF -> {}
        case COLLECTION, IMMUTABLE_LIST, IMMUTABLE_SET -> open.addAll((Collection<?>) object);
        case MAP, IMMUTABLE_MAP -> {
          for (Map.Entry<?, ?> entry : ((Map<?, ?>) object).entrySet()) {
            open.add(entry.getKey());
            open.add(entry.getValue());
          }
        }
        case OPTIONAL -> ((Optional<?>) object).ifPresent(open::add);
        case BITSET -> {}
        case PLAIN, RECORD, LAMBDA -> {
          for (Field field : plan.fields) {
            if (!field.getType().isPrimitive()) {
              open.add(read(field, object));
            }
          }
        }
        case REFUSED -> {
          // A view, such as an unmodifiable map, is walked as the collection it shows.
          if (object instanceof Collection<?> collection) {
            open.addAll(collection);
          } else if (object instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
              open.add(entry.getKey());
              open.add(entry.getValue());
            }
          } else {
            throw refused(type);
          }
        }
      }
    }
    return seen;
  }

  /** The copy of an object, made now or before; a plain object's fields may be filled in later. */
  private Object copy(Object object) {
    if (object == null) {
      return null;
    }
    Class<?> type = object.getClass();
    if (type.isArray()) {
      Object made = copies.get(object);
      return made != null ? made : copyArray(object, type);
    }
    Plan plan = PLANS.get(type);
    // A leaf is never in the copies: asked first, as boxed numbers, strings and enum constants are
    // the most of what a battle's collections hold, and the look up costs more than the answer.
    if (plan.kind == Kind.LEAF) {
      return object;
    }
    Object made = copies.get(object);
    if (made != null) {
      return made;
    }
    // A plain object is never unchanging: only what cannot change what it holds is.
    if (plan.kind != Kind.PLAIN && unchanging.containsKey(object) || shared.test(object)) {
      return object;
    }
    switch (plan.kind) {
      case PLAIN -> {
        made = allocate(type);
        copies.put(object, made);
        unfilled.add(object);
        unfilled.add(made);
        return made;
      }
      case RECORD, LAMBDA -> {
        Object[] values = new Object[plan.fields.length];
        boolean same = true;
        for (int i = 0; i < values.length; i++) {
          Object value = read(plan.fields[i], object);
          values[i] = copy(value);
          same &= values[i] == value;
        }
        // Neither can change what it holds, so one whose values are all kept as they are (leaves
        // and shared data) is itself kept: a new one would differ in nothing but its identity.
        made = same ? unchanging(object) : construct(plan.constructor, values);
        copies.put(object, made);
        return made;
      }
      case COLLECTION, MAP -> {
        made = emptyLike(object);
        copies.put(object, made);
        if (made instanceof TreeSet<?>
            || made instanceof PriorityQueue<?>
            || made instanceof TreeMap<?, ?>) {
          // A comparator reads its elements: they are copied now and inserted once every plain
          // object is filled in.
          sorted.add(made);
          sorted.add(copiedElements(object));
        } else {
          fill(object, made);
        }
        return made;
      }
      case IMMUTABLE_LIST -> {
        List<Object> elements = new ArrayList<>();
        boolean same = true;
        for (Object element : (List<?>) object) {
          Object copied = copy(element);
          elements.add(copied);
          same &= copied == element;
        }
        // An immutable collection whose elements are all kept as they are is itself kept, as a
        // record is: so a record of the tables' values and lists of them is not built again.
        made = same ? unchanging(object) : List.copyOf(elements);
        copies.put(object, made);
        return made;
      }
      case IMMUTABLE_SET -> {
        List<Object> elements = new ArrayList<>();
        boolean same = true;
        for (Object element : (Set<?>) object) {
          Object copied = hashed(copy(element));
          elements.add(copied);
          same &= copied == element;
        }
        made = same ? unchanging(object) : Set.copyOf(elements);
        copies.put(object, made);
        return made;
      }
      case IMMUTABLE_MAP -> {
        Map<Object, Object> entries = new LinkedHashMap<>();
        boolean same = true;
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) object).entrySet()) {
          Object key = hashed(copy(entry.getKey()));
          Object value = copy(entry.getValue());
          entries.put(key, value);
          same &= key == entry.getKey() && value == entry.getValue();
        }
        made = same ? unchanging(object) : Map.copyOf(entries);
        copies.put(object, made);
        return made;
      }
      case OPTIONAL -> {
        made = ((Optional<?>) object).map(this::copy);
        copies.put(object, made);
        return made;
      }
      case BITSET -> {
        made = ((BitSet) object).clone();
        copies.put(object, made);
        return made;
      }
      default -> throw refused(type);
    }
  }

  /** An object kept because nothing it holds changes, noted for the memo. */
  private Object unchanging(Object object) {
    if (found != null) {
      found.add(object);
    }
    return object;
  }

  private Object copyArray(Object array, Class<?> type) {
    int length = Array.getLength(array);
    if (type.getComponentType().isPrimitive()) {
      Object made = Array.newInstance(type.getComponentType(), length);
      System.arraycopy(array, 0, made, 0, length);
      copies.put(array, made);
      return made;
    }
    Object[] from = (Object[]) array;
    Object[] made = (Object[]) Array.newInstance(type.getComponentType(), length);
    copies.put(array, made);
    for (int i = 0; i < length; i++) {
      made[i] = copy(from[i]);
    }
    return made;
  }

  /**
   * Fills in every plain object, then inserts every sorted collection's elements: inserting makes
   * no object, so nothing is left unfilled after.
   */
  private void drain() {
    while (!unfilled.isEmpty()) {
      Object original = unfilled.poll();
      Object made = unfilled.poll();
      Plan plan = PLANS.get(original.getClass());
      long[] offsets = plan.offsets;
      char[] types = plan.types;
      for (int i = 0; i < offsets.length; i++) {
        long at = offsets[i];
        switch (types[i]) {
          case 'L' -> UNSAFE.putObject(made, at, copy(UNSAFE.getObject(original, at)));
          case 'I' -> UNSAFE.putInt(made, at, UNSAFE.getInt(original, at));
          case 'Z' -> UNSAFE.putBoolean(made, at, UNSAFE.getBoolean(original, at));
          case 'J' -> UNSAFE.putLong(made, at, UNSAFE.getLong(original, at));
          case 'D' -> UNSAFE.putDouble(made, at, UNSAFE.getDouble(original, at));
          case 'F' -> UNSAFE.putFloat(made, at, UNSAFE.getFloat(original, at));
          case 'S' -> UNSAFE.putShort(made, at, UNSAFE.getShort(original, at));
          case 'B' -> UNSAFE.putByte(made, at, UNSAFE.getByte(original, at));
          default -> UNSAFE.putChar(made, at, UNSAFE.getChar(original, at));
        }
      }
    }
    while (!sorted.isEmpty()) {
      insert(sorted.poll(), (List<?>) sorted.poll());
    }
  }

  /** A collection's elements copied, or a map's keys and values in turn, in its order. */
  private List<Object> copiedElements(Object original) {
    List<Object> elements = new ArrayList<>();
    if (original instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        elements.add(copy(entry.getKey()));
        elements.add(copy(entry.getValue()));
      }
    } else {
      for (Object element : (Collection<?>) original) {
        elements.add(copy(element));
      }
    }
    return elements;
  }

  /** Inserts copied elements, or keys and values in turn, into a sorted collection or map. */
  @SuppressWarnings("unchecked")
  private static void insert(Object made, List<?> elements) {
    if (made instanceof Map<?, ?> map) {
      for (int i = 0; i < elements.size(); i += 2) {
        ((Map<Object, Object>) map).put(elements.get(i), elements.get(i + 1));
      }
    } else {
      ((Collection<Object>) made).addAll(elements);
    }
  }

  /** Inserts the copied elements into a collection or map made empty, in the original's order. */
  @SuppressWarnings("unchecked")
  private void fill(Object original, Object made) {
    boolean hashing = made instanceof HashMap<?, ?> || made instanceof HashSet<?>;
    if (original instanceof Map<?, ?> map) {
      Map<Object, Object> target = (Map<Object, Object>) made;
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        Object key = copy(entry.getKey());
        target.put(hashing ? hashed(key) : key, copy(entry.getValue()));
      }
      return;
    }
    Collection<Object> target = (Collection<Object>) made;
    for (Object element : (Collection<?>) original) {
      Object copied = copy(element);
      target.add(hashing ? hashed(copied) : copied);
    }
  }

  /**
   * A copy about to be hashed into a set or map's buckets, refused when its hash code could read
   * state not yet copied: a plain object (or a record holding one) whose class defines its own hash
   * code. Any other hashes by identity, by value or by shared data, which is already final.
   */
  private Object hashed(Object copied) {
    if (copied != null && hashReadsState(copied)) {
      throw new UnsupportedOperationException(
          "a deep copy does not hash a "
              + copied.getClass().getName()
              + " into a set or map: its hash code reads its fields, which may not be copied yet");
    }
    return copied;
  }

  private boolean hashReadsState(Object copied) {
    Class<?> type = copied.getClass();
    if (type.isArray() || shared.test(copied)) {
      return false;
    }
    Plan plan = PLANS.get(type);
    if (plan.kind == Kind.PLAIN) {
      return plan.ownHashCode;
    }
    if (plan.kind == Kind.RECORD) {
      for (Field field : plan.fields) {
        Object value = read(field, copied);
        if (value != null && !field.getType().isPrimitive() && hashReadsState(value)) {
          return true;
        }
      }
    }
    return false;
  }

  /** A collection or map of the original's class, empty, keeping its comparator or key type. */
  private Object emptyLike(Object object) {
    if (object instanceof ArrayList<?> list) {
      return new ArrayList<>(list.size());
    }
    if (object instanceof LinkedList<?>) {
      return new LinkedList<>();
    }
    if (object instanceof ArrayDeque<?>) {
      return new ArrayDeque<>();
    }
    if (object instanceof LinkedHashSet<?>) {
      return new LinkedHashSet<>();
    }
    if (object instanceof HashSet<?>) {
      return new HashSet<>();
    }
    if (object instanceof TreeSet<?> set) {
      return new TreeSet<>((Comparator<Object>) copy(set.comparator()));
    }
    if (object instanceof PriorityQueue<?> queue) {
      return new PriorityQueue<>((Comparator<Object>) copy(queue.comparator()));
    }
    if (object instanceof EnumSet<?> set) {
      return EnumSet.copyOf(set);
    }
    if (object instanceof LinkedHashMap<?, ?>) {
      return new LinkedHashMap<>();
    }
    if (object instanceof HashMap<?, ?>) {
      return new HashMap<>();
    }
    if (object instanceof TreeMap<?, ?> map) {
      return new TreeMap<>((Comparator<Object>) copy(map.comparator()));
    }
    if (object instanceof IdentityHashMap<?, ?>) {
      return new IdentityHashMap<>();
    }
    if (object instanceof EnumMap<?, ?> map) {
      EnumMap<?, ?> made = new EnumMap<>(map);
      made.clear();
      return made;
    }
    throw refused(object.getClass());
  }

  private static UnsupportedOperationException refused(Class<?> type) {
    return new UnsupportedOperationException(
        "a deep copy does not copy a "
            + type.getName()
            + ": a JDK class it has no rule for, or a view of another collection");
  }

  // -- plans -------------------------------------------------------------------------------------

  private enum Kind {
    LEAF,
    PLAIN,
    RECORD,
    LAMBDA,
    COLLECTION,
    MAP,
    IMMUTABLE_LIST,
    IMMUTABLE_SET,
    IMMUTABLE_MAP,
    OPTIONAL,
    BITSET,
    REFUSED
  }

  /** How objects of one class are copied: their kind, fields and constructor. */
  private static final class Plan {
    final Kind kind;
    final Field[] fields;
    final Constructor<?> constructor;

    /** Whether a plain class, or a superclass of it, defines its own hash code. */
    final boolean ownHashCode;

    /**
     * A plain class's fields as offsets into its objects, in the order of {@link #fields}, which a
     * copy reads and writes directly: the reflective accessors cost several times as much.
     */
    final long[] offsets;

    /**
     * The type of each of {@link #offsets}: a primitive's descriptor letter, or L for a reference.
     */
    final char[] types;

    private Plan(Kind kind, Field[] fields, Constructor<?> constructor) {
      this(kind, fields, constructor, false);
    }

    private Plan(Kind kind, Field[] fields, Constructor<?> constructor, boolean ownHashCode) {
      this.kind = kind;
      this.fields = fields;
      this.constructor = constructor;
      this.ownHashCode = ownHashCode;
      this.offsets = new long[kind == Kind.PLAIN ? fields.length : 0];
      this.types = new char[offsets.length];
      for (int i = 0; i < offsets.length; i++) {
        offsets[i] = UNSAFE.objectFieldOffset(fields[i]);
        Class<?> type = fields[i].getType();
        types[i] = type.isPrimitive() ? type.descriptorString().charAt(0) : 'L';
      }
    }

    static Plan of(Class<?> type) {
      if (type == String.class
          || type == Object.class
          || type == Class.class
          || type == Integer.class
          || type == Long.class
          || type == Short.class
          || type == Byte.class
          || type == Character.class
          || type == Boolean.class
          || type == Double.class
          || type == Float.class
          || Enum.class.isAssignableFrom(type)) {
        return new Plan(Kind.LEAF, new Field[0], null);
      }
      if (type.isHidden()) {
        Field[] captures = instanceFields(type);
        if (captures.length == 0) {
          return new Plan(Kind.LEAF, captures, null);
        }
        // A lambda's constructor takes its captures in the order of their fields arg$1, arg$2...
        Arrays.sort(
            captures,
            Comparator.comparingInt(f -> Integer.parseInt(f.getName().replace("arg$", ""))));
        Constructor<?> constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return new Plan(Kind.LAMBDA, captures, constructor);
      }
      if (type.isRecord()) {
        RecordComponent[] components = type.getRecordComponents();
        Field[] fields = new Field[components.length];
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
          fields[i] = field(type, components[i].getName());
          types[i] = components[i].getType();
        }
        try {
          Constructor<?> constructor = type.getDeclaredConstructor(types);
          constructor.setAccessible(true);
          // A record of values only is kept as it is, without reading it.
          return new Plan(valuesOnly(type) ? Kind.LEAF : Kind.RECORD, fields, constructor);
        } catch (NoSuchMethodException e) {
          throw new IllegalStateException(type + " has no canonical constructor", e);
        }
      }
      String name = type.getName();
      if (name.startsWith("java.util.ImmutableCollections$List")
          || name.startsWith("java.util.ImmutableCollections$SubList")) {
        return new Plan(Kind.IMMUTABLE_LIST, new Field[0], null);
      }
      if (name.startsWith("java.util.ImmutableCollections$Set")) {
        return new Plan(Kind.IMMUTABLE_SET, new Field[0], null);
      }
      if (name.startsWith("java.util.ImmutableCollections$Map")) {
        return new Plan(Kind.IMMUTABLE_MAP, new Field[0], null);
      }
      if (name.equals("java.util.Collections$EmptyList")
          || name.equals("java.util.Collections$EmptySet")
          || name.equals("java.util.Collections$EmptyMap")) {
        return new Plan(Kind.LEAF, new Field[0], null);
      }
      if (type == Optional.class) {
        return new Plan(Kind.OPTIONAL, new Field[0], null);
      }
      if (type == BitSet.class) {
        return new Plan(Kind.BITSET, new Field[0], null);
      }
      if (type == ArrayList.class
          || type == LinkedList.class
          || type == ArrayDeque.class
          || type == HashSet.class
          || type == LinkedHashSet.class
          || type == TreeSet.class
          || type == PriorityQueue.class
          || EnumSet.class.isAssignableFrom(type)) {
        return new Plan(Kind.COLLECTION, new Field[0], null);
      }
      if (type == HashMap.class
          || type == LinkedHashMap.class
          || type == TreeMap.class
          || type == IdentityHashMap.class
          || type == EnumMap.class) {
        return new Plan(Kind.MAP, new Field[0], null);
      }
      if (name.startsWith("java.") || name.startsWith("jdk.") || name.startsWith("sun.")) {
        return new Plan(Kind.REFUSED, new Field[0], null);
      }
      return new Plan(Kind.PLAIN, instanceFields(type), null, definesHashCode(type));
    }

    /**
     * Whether a record can hold nothing a battle changes, by its components' declared types: each a
     * primitive, a {@code String}, a boxed primitive, an enum or another such record.
     */
    private static boolean valuesOnly(Class<?> record) {
      return valuesOnly(record, new HashSet<>());
    }

    /** As above, with the records being asked about: one that holds itself is not asked again. */
    private static boolean valuesOnly(Class<?> record, Set<Class<?>> asking) {
      if (!asking.add(record)) {
        return true;
      }
      for (RecordComponent component : record.getRecordComponents()) {
        Class<?> type = component.getType();
        boolean value =
            type.isPrimitive()
                || type == String.class
                || type == Integer.class
                || type == Long.class
                || type == Short.class
                || type == Byte.class
                || type == Character.class
                || type == Boolean.class
                || type == Double.class
                || type == Float.class
                || type.isEnum()
                || type.isRecord() && valuesOnly(type, asking);
        if (!value) {
          return false;
        }
      }
      return true;
    }

    private static boolean definesHashCode(Class<?> type) {
      try {
        return type.getMethod("hashCode").getDeclaringClass() != Object.class;
      } catch (NoSuchMethodException e) {
        throw new IllegalStateException(e);
      }
    }

    /** The instance fields of a class and its superclasses, each made accessible. */
    private static Field[] instanceFields(Class<?> type) {
      List<Field> fields = new ArrayList<>();
      for (Class<?> at = type; at != null && at != Object.class; at = at.getSuperclass()) {
        if (at.getName().startsWith("java.")) {
          throw new UnsupportedOperationException(
              "a deep copy does not copy " + type.getName() + ", a subclass of " + at.getName());
        }
        for (Field field : at.getDeclaredFields()) {
          if (!Modifier.isStatic(field.getModifiers())) {
            field.setAccessible(true);
            fields.add(field);
          }
        }
      }
      return fields.toArray(new Field[0]);
    }

    private static Field field(Class<?> type, String name) {
      try {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
      } catch (NoSuchFieldException e) {
        throw new IllegalStateException(type + " has no field " + name, e);
      }
    }
  }

  // -- reflection --------------------------------------------------------------------------------

  private static Object read(Field field, Object object) {
    try {
      return field.get(object);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Object allocate(Class<?> type) {
    try {
      return UNSAFE.allocateInstance(type);
    } catch (InstantiationException e) {
      throw new IllegalStateException("cannot allocate a " + type.getName(), e);
    }
  }

  private static Object construct(Constructor<?> constructor, Object[] values) {
    try {
      return constructor.newInstance(values);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("cannot build a " + constructor.getDeclaringClass(), e);
    }
  }

  private static Unsafe unsafe() {
    try {
      Field field = Unsafe.class.getDeclaredField("theUnsafe");
      field.setAccessible(true);
      return (Unsafe) field.get(null);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }
}
