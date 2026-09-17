package org.crforge.core.pathfinding.grid;

import java.util.Arrays;

/**
 * The heap of open nodes used by {@link RouteSearch}, ordered by an external priority array.
 *
 * <p>It is written out by hand rather than taken from the standard library because the standard
 * game's heap breaks ties in a particular way and the route a search returns depends on it:
 *
 * <ul>
 *   <li><b>Insertion</b> swaps a node past its parent only when its priority is <b>strictly</b>
 *       lower, so nodes that arrive with equal priorities keep the order they arrived in.
 *   <li><b>Removal</b> looks at the <b>right</b> child first and the left child second, each
 *       replacing the current best only on a strict improvement. When both children tie at a lower
 *       priority than the parent, the right one is promoted. A conventional heap - {@code
 *       java.util.PriorityQueue} among them - prefers the left child here and produces a different
 *       route on the same cost field.
 * </ul>
 *
 * <p>Priorities live in an array the search owns and updates while nodes sit in the heap; the heap
 * reads that array by node id on every comparison rather than storing a key of its own. After the
 * search lowers a node's priority it calls {@link #siftUp(int)} on the position {@link
 * #indexOf(int)} found for it.
 *
 * <p>The class is package private: it exists for the route search, not as a general container.
 */
final class RouteSearchHeap {

  private final int[] priority;
  private int[] nodes;
  private int size;

  /**
   * Creates an empty heap.
   *
   * @param priority the search's priority array, indexed by node id and read live
   * @param capacity room for this many nodes before the backing array has to grow
   */
  RouteSearchHeap(int[] priority, int capacity) {
    this.priority = priority;
    this.nodes = new int[Math.max(capacity, 1)];
    this.size = 0;
  }

  /** Number of nodes currently in the heap. */
  int size() {
    return size;
  }

  /** True when no node is left. */
  boolean isEmpty() {
    return size == 0;
  }

  /** The node with the lowest priority, without removing it. */
  int peek() {
    return nodes[0];
  }

  /** Appends a node at the end without reordering; the caller sifts it up. */
  void append(int node) {
    if (size == nodes.length) {
      nodes = Arrays.copyOf(nodes, nodes.length * 2);
    }
    nodes[size++] = node;
  }

  /** Appends a node and moves it up to its place. */
  void add(int node) {
    append(node);
    siftUp(size - 1);
  }

  /**
   * Moves the node at the given position up while it is strictly cheaper than its parent. Equal
   * priorities do not swap, which is what keeps the arrival order of ties.
   */
  void siftUp(int index) {
    int i = index;
    while (i > 0) {
      int parent = (i - 1) / 2;
      if (priority[nodes[i]] >= priority[nodes[parent]]) {
        break;
      }
      int swap = nodes[parent];
      nodes[parent] = nodes[i];
      nodes[i] = swap;
      i = parent;
    }
  }

  /**
   * Position of a node in the heap, found by scanning from the front.
   *
   * <p>The search only ever asks about a node it has already opened, and an open node is in the
   * heap exactly once, so a node that is not there is a broken invariant rather than an ordinary
   * answer and is refused outright.
   *
   * @throws IllegalStateException when the node is not in the heap
   */
  int indexOf(int node) {
    for (int i = 0; i < size; i++) {
      if (nodes[i] == node) {
        return i;
      }
    }
    throw new IllegalStateException("Node " + node + " is not in the heap");
  }

  /**
   * Removes and returns the node with the lowest priority.
   *
   * <p>The last node is taken off the end and, if the heap is not empty afterwards, put at the root
   * and sunk back down, examining the right child before the left one.
   */
  int pop() {
    int top = nodes[0];
    int last = nodes[--size];
    if (size > 0) {
      nodes[0] = last;
      siftDown();
    }
    return top;
  }

  /**
   * Sinks the root until neither child is strictly cheaper, preferring the right child on a tie.
   */
  private void siftDown() {
    int i = 0;
    while (true) {
      int best = i;
      int right = 2 * i + 2;
      int left = 2 * i + 1;
      if (right < size && priority[nodes[best]] > priority[nodes[right]]) {
        best = right;
      }
      if (left < size && priority[nodes[best]] > priority[nodes[left]]) {
        best = left;
      }
      if (best == i) {
        return;
      }
      int swap = nodes[i];
      nodes[i] = nodes[best];
      nodes[best] = swap;
      i = best;
    }
  }

  /** The node ids in heap order, as a fresh array; for tests and diagnostics. */
  int[] nodes() {
    return Arrays.copyOf(nodes, size);
  }
}
