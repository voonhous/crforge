package org.crforge.core.pathfinding.grid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The hand-written heap the route search orders its open nodes with. */
class RouteSearchHeapTest {

  @Test
  void insertionStopsAtTheFirstParentThatIsNotDearer() {
    int[] priority = {10, 10, 10, 5};
    RouteSearchHeap heap = new RouteSearchHeap(priority, 4);
    heap.add(0);
    heap.add(1);
    heap.add(2);

    // Equal priorities never swap, so the order of arrival is kept.
    assertThat(heap.nodes()).containsExactly(0, 1, 2);

    heap.add(3);
    assertThat(heap.nodes()).containsExactly(3, 0, 2, 1);
  }

  @Test
  void removalPromotesTheRightChildWhenBothChildrenTie() {
    int[] priority = new int[7];
    priority[0] = 20;
    priority[1] = 10;
    priority[2] = 10;
    priority[3] = 30;
    priority[4] = 30;
    priority[5] = 30;
    priority[6] = 30;
    RouteSearchHeap heap = new RouteSearchHeap(priority, 7);
    for (int node = 0; node < 7; node++) {
      heap.append(node);
    }

    assertThat(heap.pop()).isZero();
    // The last node replaces the root and sinks: the right child of two equal children wins.
    assertThat(heap.nodes()).containsExactly(2, 1, 6, 3, 4, 5);
  }

  @Test
  void findsANodeByLinearScan() {
    int[] priority = {5, 6, 7};
    RouteSearchHeap heap = new RouteSearchHeap(priority, 3);
    heap.add(0);
    heap.add(1);
    heap.add(2);

    assertThat(heap.indexOf(2)).isEqualTo(2);
    assertThat(heap.indexOf(0)).isZero();
  }

  @Test
  void askingForANodeThatIsNotThereIsRefused() {
    int[] priority = {5, 6, 7};
    RouteSearchHeap heap = new RouteSearchHeap(priority, 3);
    heap.add(0);

    assertThatThrownBy(() -> heap.indexOf(2))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not in the heap");
  }

  @Test
  void aSingleNodeHeapEmptiesOnOnePop() {
    RouteSearchHeap heap = new RouteSearchHeap(new int[] {1}, 1);
    heap.add(0);

    assertThat(heap.pop()).isZero();
    assertThat(heap.isEmpty()).isTrue();
    assertThat(heap.size()).isZero();
  }

  @Test
  void tracksThePeakSizeAsNodesArriveAndLeave() {
    int[] priority = {3, 2, 1};
    RouteSearchHeap heap = new RouteSearchHeap(priority, 3);
    heap.add(0);
    heap.add(1);
    heap.add(2);
    assertThat(heap.size()).isEqualTo(3);

    assertThat(heap.pop()).isEqualTo(2);
    assertThat(heap.pop()).isEqualTo(1);
    assertThat(heap.pop()).isZero();
    assertThat(heap.isEmpty()).isTrue();
  }
}
