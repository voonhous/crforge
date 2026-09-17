package org.crforge.core.pathfinding.grid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Destination-first ordering and the growth behaviour of {@link Route}. */
class RouteTest {

  @Test
  void aNewRouteIsEmpty() {
    Route route = new Route();
    assertThat(route.isEmpty()).isTrue();
    assertThat(route.size()).isZero();
    assertThat(route.toArray()).isEmpty();
  }

  @Test
  void elementZeroIsTheGoalAndTheLastElementIsTheNextWaypoint() {
    Route route = Route.of(1734, 727, 691, 655);
    assertThat(route.get(0)).isEqualTo(1734);
    assertThat(route.last()).isEqualTo(655);
    assertThat(route.size()).isEqualTo(4);
  }

  @Test
  void popRemovesTheNextWaypointAndLeavesTheGoalInPlace() {
    Route route = Route.of(1734, 727, 691, 655);

    assertThat(route.pop()).isEqualTo(655);
    assertThat(route.size()).isEqualTo(3);
    assertThat(route.last()).isEqualTo(691);
    assertThat(route.get(0)).isEqualTo(1734);

    assertThat(route.pop()).isEqualTo(691);
    assertThat(route.pop()).isEqualTo(727);
    assertThat(route.pop()).isEqualTo(1734);
    assertThat(route.isEmpty()).isTrue();
  }

  @Test
  void popAndLastFailOnAnEmptyRoute() {
    Route route = new Route();
    assertThatThrownBy(route::pop).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(route::last).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void setOverwritesInPlace() {
    Route route = Route.of(10, 20, 30);
    route.set(1, 99);
    assertThat(route.toArray()).containsExactly(10, 99, 30);
  }

  @Test
  void getAndSetRejectPositionsOutsideTheRoute() {
    Route route = Route.of(10, 20);
    assertThatThrownBy(() -> route.get(2)).isInstanceOf(IndexOutOfBoundsException.class);
    assertThatThrownBy(() -> route.get(-1)).isInstanceOf(IndexOutOfBoundsException.class);
    assertThatThrownBy(() -> route.set(2, 0)).isInstanceOf(IndexOutOfBoundsException.class);
  }

  @Test
  void clearDropsEveryCell() {
    Route route = Route.of(1, 2, 3);
    route.clear();
    assertThat(route.isEmpty()).isTrue();
    assertThat(route.toArray()).isEmpty();
  }

  @Test
  void growsBeyondItsInitialCapacity() {
    Route route = new Route(2);
    for (int i = 0; i < 1000; i++) {
      route.add(i);
    }
    assertThat(route.size()).isEqualTo(1000);
    assertThat(route.get(0)).isZero();
    assertThat(route.last()).isEqualTo(999);
  }

  @Test
  void copyIsIndependentOfTheOriginal() {
    Route route = Route.of(5, 6, 7);
    Route copy = route.copy();
    copy.pop();
    copy.add(42);

    assertThat(route.toArray()).containsExactly(5, 6, 7);
    assertThat(copy.toArray()).containsExactly(5, 6, 42);
  }

  @Test
  void copyFromReplacesTheContentsWithoutSharingStorage() {
    Route target = Route.of(1, 2, 3, 4, 5);
    Route source = Route.of(9, 8);

    target.copyFrom(source);
    assertThat(target.toArray()).containsExactly(9, 8);

    source.add(7);
    assertThat(target.toArray()).containsExactly(9, 8);
  }

  @Test
  void copyFromGrowsTheTargetWhenTheSourceIsLonger() {
    Route target = new Route(1);
    Route source = Route.of(1, 2, 3, 4, 5, 6, 7, 8);
    target.copyFrom(source);
    assertThat(target.toArray()).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
  }

  @Test
  void equalRoutesCompareEqual() {
    assertThat(Route.of(1, 2, 3)).isEqualTo(Route.of(1, 2, 3)).hasSameHashCodeAs(Route.of(1, 2, 3));
    assertThat(Route.of(1, 2, 3)).isNotEqualTo(Route.of(3, 2, 1));
    assertThat(Route.of(1, 2)).isNotEqualTo(Route.of(1, 2, 3));
  }

  @Test
  void rejectsANegativeCapacity() {
    assertThatThrownBy(() -> new Route(-1)).isInstanceOf(IllegalArgumentException.class);
  }
}
