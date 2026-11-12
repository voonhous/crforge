/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;

class DeepCopyTest {

  /** A mutable object of this code base, with a cycle through its peer. */
  static final class Node {
    final String name;
    int value;
    Node peer;
    final List<Node> children = new ArrayList<>();
    final int[] counts = {1, 2, 3};

    Node(String name) {
      this.name = name;
    }

    /** A capturing lambda: it reads this node. */
    IntSupplier reader() {
      return () -> value;
    }

    /** An inner class instance, holding this node as its enclosing instance. */
    Counter counter() {
      return new Counter();
    }

    final class Counter {
      int next() {
        return ++value;
      }
    }
  }

  record Pair(Node left, List<Node> right) {}

  /** A record of values only, by its components' types; it may hold another of its kind. */
  record Amount(int count, String unit, Amount next) {}

  /** A record that may hold anything. */
  record Tagged(Object subject, List<String> tags, Map<String, Integer> counts) {}

  /** A plain class that hashes by its fields. */
  static final class Valued {
    int value;

    @Override
    public boolean equals(Object other) {
      return other instanceof Valued v && v.value == value;
    }

    @Override
    public int hashCode() {
      return value;
    }
  }

  static final class Holder {
    Object held;
  }

  @Test
  void aCopyKeepsTheGraphsSharingAndCyclesAndChangesApartFromIt() {
    Node a = new Node("a");
    Node b = new Node("b");
    a.peer = b;
    b.peer = a;
    a.children.add(b);
    a.children.add(b);
    Node copy = DeepCopy.of(a, o -> false);
    assertThat(copy).isNotSameAs(a);
    assertThat(copy.peer).isNotSameAs(b);
    assertThat(copy.peer.peer).isSameAs(copy);
    assertThat(copy.children).containsExactly(copy.peer, copy.peer);
    assertThat(copy.name).isEqualTo("a");
    assertThat(copy.counts).containsExactly(1, 2, 3).isNotSameAs(a.counts);
    copy.value = 9;
    copy.counts[0] = 7;
    copy.children.clear();
    assertThat(a.value).isZero();
    assertThat(a.counts[0]).isEqualTo(1);
    assertThat(a.children).hasSize(2);
  }

  @Test
  void aCopiedLambdaOrInnerObjectHoldsTheCopiesItCaptured() {
    Node a = new Node("a");
    Holder holder = new Holder();
    Node.Counter counter = a.counter();
    holder.held = List.of(a, a.reader(), counter);
    Holder copy = DeepCopy.of(holder, o -> false);
    List<?> held = (List<?>) copy.held;
    Node node = (Node) held.get(0);
    IntSupplier reader = (IntSupplier) held.get(1);
    Node.Counter copiedCounter = (Node.Counter) held.get(2);
    assertThat(node).isNotSameAs(a);
    assertThat(copiedCounter.next()).isEqualTo(1);
    assertThat(reader.getAsInt()).isEqualTo(1);
    assertThat(a.value).isZero();
    // A lambda that captures nothing is kept.
    Runnable none = () -> {};
    holder.held = none;
    assertThat(DeepCopy.of(holder, o -> false).held).isSameAs(none);
  }

  @Test
  @SuppressWarnings("unchecked")
  void aRecordAndTheJdksCollectionsAreRebuiltOnTheCopies() {
    Node a = new Node("a");
    Map<String, Node> byName = new HashMap<>(Map.of("a", a));
    Map<Node, Integer> byNode = new IdentityHashMap<>(Map.of(a, 1));
    Set<Node> nodes = new HashSet<>(Set.of(a));
    Holder holder = new Holder();
    holder.held = List.of(new Pair(a, List.of(a)), byName, byNode, nodes, Set.of(a), Map.of(a, a));
    List<?> held = (List<?>) DeepCopy.of(holder, o -> false).held;
    Pair pair = (Pair) held.get(0);
    Node node = pair.left();
    assertThat(node).isNotSameAs(a);
    assertThat(pair.right()).containsExactly(node);
    assertThat(((Map<?, ?>) held.get(1)).get("a")).isSameAs(node);
    assertThat(((Map<?, ?>) held.get(2)).get(node)).isEqualTo(1);
    assertThat((Set<Object>) held.get(3)).containsExactly(node);
    assertThat((Set<Object>) held.get(4)).containsExactly(node);
    assertThat(((Map<?, ?>) held.get(5)).get(node)).isSameAs(node);
  }

  @Test
  void aSharedObjectIsKeptWithAllItHolds() {
    Node data = new Node("data");
    Node a = new Node("a");
    a.peer = data;
    Node copy = DeepCopy.of(a, o -> o == data);
    assertThat(copy.peer).isSameAs(data);
    assertThat(DeepCopy.reachable(a)).contains(a, data, a.children, a.counts);
  }

  @Test
  void whatHoldsNothingThatChangesIsKept() {
    Node data = new Node("data");
    // Values only by its components' types, values and shared data by what it holds, and a lambda
    // that captures shared data.
    Amount amount = new Amount(7, "seven", new Amount(1, "one", null));
    Tagged tagged = new Tagged(data, List.of("x", "y"), Map.of("k", 300));
    IntSupplier reader = data.reader();
    Holder holder = new Holder();
    holder.held = new ArrayList<>(List.of(amount, tagged, reader, List.of(tagged), Set.of(1)));
    List<?> held = (List<?>) DeepCopy.of(holder, o -> o == data).held;
    assertThat(held).isNotSameAs(holder.held);
    for (int i = 0; i < held.size(); i++) {
      assertThat(held.get(i)).isSameAs(((List<?>) holder.held).get(i));
    }
  }

  @Test
  void whatHoldsAnObjectThatChangesIsBuiltAgainAtAnyDepth() {
    Node a = new Node("a");
    // The node is two records and a list down.
    Tagged tagged = new Tagged(List.of(new Tagged(a, List.of(), Map.of())), List.of(), Map.of());
    Holder holder = new Holder();
    holder.held = tagged;
    Tagged copy = (Tagged) DeepCopy.of(holder, o -> false).held;
    assertThat(copy).isNotSameAs(tagged);
    Tagged inner = (Tagged) ((List<?>) copy.subject()).get(0);
    assertThat(inner.subject()).isInstanceOf(Node.class).isNotSameAs(a);
    // A mutable list is copied even when all it holds is kept, and so is the record holding it.
    Tagged listed = new Tagged("s", new ArrayList<>(List.of("x")), Map.of());
    holder.held = listed;
    Tagged listedCopy = (Tagged) DeepCopy.of(holder, o -> false).held;
    assertThat(listedCopy).isNotSameAs(listed).isEqualTo(listed);
    assertThat(listedCopy.tags()).isNotSameAs(listed.tags());
  }

  @Test
  @SuppressWarnings("unchecked")
  void aMemoKeepsWhatAnEarlierCopyFoundUnchangingAndCopiesTheSame() {
    Node data = new Node("data");
    Node a = new Node("a");
    Tagged unchanging = new Tagged(data, List.of("x"), Map.of());
    Tagged changing = new Tagged(a, List.of("x"), Map.of());
    Holder holder = new Holder();
    holder.held = new ArrayList<>(List.of(a, unchanging, changing));
    DeepCopy.Memo memo = new DeepCopy.Memo();
    for (int round = 0; round < 3; round++) {
      List<?> held = (List<?>) DeepCopy.of(holder, o -> o == data, memo).held;
      Node node = (Node) held.get(0);
      assertThat(node).isNotSameAs(a);
      assertThat(held.get(1)).isSameAs(unchanging);
      assertThat(held.get(2)).isNotSameAs(changing);
      assertThat(((Tagged) held.get(2)).subject()).isSameAs(node);
      // The record, both records' lists of tags and the one empty map they share: found by the
      // first copy, and not added again.
      assertThat(memo.size()).isEqualTo(4);
    }
    // What a later copy first reaches is added.
    Tagged later = new Tagged("s", List.of(), Map.of());
    ((List<Object>) holder.held).add(later);
    assertThat(((List<?>) DeepCopy.of(holder, o -> o == data, memo).held).get(3)).isSameAs(later);
    // The record and its empty list.
    assertThat(memo.size()).isEqualTo(6);
  }

  @Test
  void aSortedCollectionIsFilledOnceItsElementsAre() {
    Holder holder = new Holder();
    TreeSet<Node> sorted = new TreeSet<>((x, y) -> Integer.compare(x.value, y.value));
    for (int v : new int[] {3, 1, 2}) {
      Node n = new Node("n" + v);
      n.value = v;
      sorted.add(n);
    }
    holder.held = sorted;
    TreeSet<?> copy = (TreeSet<?>) DeepCopy.of(holder, o -> false).held;
    assertThat(copy).extracting(n -> ((Node) n).value).containsExactly(1, 2, 3);
  }

  @Test
  void whatCannotBeCopiedFaithfullyIsRefused() {
    Holder holder = new Holder();
    holder.held = new Random(1);
    assertThatThrownBy(() -> DeepCopy.of(holder, o -> false))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("java.util.Random");
    holder.held = Collections.unmodifiableList(new ArrayList<>());
    assertThatThrownBy(() -> DeepCopy.of(holder, o -> false))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("view");
    holder.held = new HashSet<>(Set.of(new Valued()));
    assertThatThrownBy(() -> DeepCopy.of(holder, o -> false))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("hash code");
  }
}
