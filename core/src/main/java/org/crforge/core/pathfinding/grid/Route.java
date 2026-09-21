package org.crforge.core.pathfinding.grid;

import java.util.Arrays;

/**
 * A grid route as a growable list of row-major cell ids, stored <b>destination first</b>.
 *
 * <p>Element 0 is the goal cell and the <b>last</b> element is the next waypoint the unit walks
 * toward. Following a route therefore consumes it from the back: {@link #pop()} removes the next
 * waypoint in constant time, and the route is exhausted once it is empty. Nothing inverts or
 * normalizes the order; code that wants the goal reads {@code get(0)} and code that wants the next
 * waypoint reads {@link #last()}.
 *
 * <p>A cell id is {@code row * gridWidth + column}, so a route is meaningful only together with the
 * width of the grid it was searched on.
 *
 * <p>The class is a plain mutable container with no routing logic of its own. It is not thread
 * safe.
 */
public final class Route {

  private static final int DEFAULT_CAPACITY = 16;

  private int[] nodes;
  private int size;

  /** Creates an empty route with a small default capacity. */
  public Route() {
    this(DEFAULT_CAPACITY);
  }

  /**
   * Creates an empty route with room for {@code initialCapacity} nodes before the backing array has
   * to grow.
   */
  public Route(int initialCapacity) {
    if (initialCapacity < 0) {
      throw new IllegalArgumentException("Negative capacity: " + initialCapacity);
    }
    this.nodes = new int[Math.max(initialCapacity, 1)];
    this.size = 0;
  }

  /** Creates a route holding the given cell ids, goal first. */
  public static Route of(int... goalFirstNodes) {
    Route route = new Route(Math.max(goalFirstNodes.length, 1));
    for (int node : goalFirstNodes) {
      route.add(node);
    }
    return route;
  }

  /** Number of cells still on the route. */
  public int size() {
    return size;
  }

  /** True when no cells are left, which is how "no route" is represented. */
  public boolean isEmpty() {
    return size == 0;
  }

  /**
   * Returns the cell id at the given position, counted from the goal.
   *
   * @throws IndexOutOfBoundsException if {@code index} is outside {@code 0..size-1}
   */
  public int get(int index) {
    checkIndex(index);
    return nodes[index];
  }

  /**
   * Returns the next waypoint, which is the element furthest from the goal.
   *
   * @throws IllegalStateException if the route is empty
   */
  public int last() {
    if (size == 0) {
      throw new IllegalStateException("Route is empty");
    }
    return nodes[size - 1];
  }

  /**
   * Overwrites the cell id at the given position.
   *
   * @throws IndexOutOfBoundsException if {@code index} is outside {@code 0..size-1}
   */
  public void set(int index, int node) {
    checkIndex(index);
    nodes[index] = node;
  }

  /**
   * Appends a cell id. Callers build a route goal first, so the first call supplies the goal and
   * each later call supplies a cell one step closer to the start.
   */
  public void add(int node) {
    if (size == nodes.length) {
      nodes = Arrays.copyOf(nodes, nodes.length * 2);
    }
    nodes[size++] = node;
  }

  /**
   * Removes and returns the next waypoint, the element furthest from the goal. This is the arrival
   * step of route following and runs in constant time.
   *
   * @throws IllegalStateException if the route is empty
   */
  public int pop() {
    if (size == 0) {
      throw new IllegalStateException("Route is empty");
    }
    return nodes[--size];
  }

  /** Drops every cell, leaving the route empty. */
  public void clear() {
    size = 0;
  }

  /** Returns an independent copy, used where a route is kept aside and possibly restored. */
  public Route copy() {
    Route copy = new Route(Math.max(size, 1));
    System.arraycopy(nodes, 0, copy.nodes, 0, size);
    copy.size = size;
    return copy;
  }

  /** Replaces this route's contents with another route's, leaving the other one untouched. */
  public void copyFrom(Route other) {
    if (other.size > nodes.length) {
      nodes = new int[other.size];
    }
    System.arraycopy(other.nodes, 0, nodes, 0, other.size);
    size = other.size;
  }

  /** Returns the cell ids as a fresh array, goal first. */
  public int[] toArray() {
    return Arrays.copyOf(nodes, size);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof Route)) {
      return false;
    }
    Route that = (Route) other;
    if (size != that.size) {
      return false;
    }
    for (int i = 0; i < size; i++) {
      if (nodes[i] != that.nodes[i]) {
        return false;
      }
    }
    return true;
  }

  @Override
  public int hashCode() {
    int hash = 1;
    for (int i = 0; i < size; i++) {
      hash = 31 * hash + nodes[i];
    }
    return hash;
  }

  @Override
  public String toString() {
    return "Route(goalFirst=" + Arrays.toString(toArray()) + ")";
  }

  private void checkIndex(int index) {
    if (index < 0 || index >= size) {
      throw new IndexOutOfBoundsException("Index " + index + " out of bounds for size " + size);
    }
  }
}
