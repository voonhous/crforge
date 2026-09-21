package org.crforge.core.pathfinding.move;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.grid.Route;
import org.junit.jupiter.api.Test;

/**
 * Behaviour of the route direction pair and of the predicate that says whether a route leads away
 * from the reference.
 */
class DirectionInitializerTest {

  private static final int WIDTH = 36;

  /**
   * The route a Knight deployed on the left of the arena searches on its first moving tick: the
   * goal cell at column 6 row 48, then column 7 from row 47 back up to row 21.
   */
  private static Route leftLaneRoute() {
    Route route = new Route();
    route.add(48 * WIDTH + 6);
    for (int row = 47; row >= 21; row--) {
      route.add(row * WIDTH + 7);
    }
    return route;
  }

  private static GridEntity ownerAt(int x, int y) {
    GridEntity owner = new GridEntity();
    owner.setX(x);
    owner.setY(y);
    return owner;
  }

  @Test
  void theDirectionPointsAtTheCentreOfTheNextWaypoint() {
    MovementState component = MovementState.forSide(0, 3500, 10000);
    component.setRoute(leftLaneRoute());
    assertThat(component.getRoute().size()).isEqualTo(28);

    DirectionInitializer.directionInit(component, ownerAt(3500, 10000), WIDTH);

    assertThat(component.getRouteDirX()).isEqualTo(81);
    assertThat(component.getRouteDirY()).isEqualTo(243);
  }

  @Test
  void anEmptyRouteClearsTheDirection() {
    MovementState component = MovementState.forSide(0, 3500, 10000);
    component.setRouteDirX(81);
    component.setRouteDirY(243);

    DirectionInitializer.directionInit(component, ownerAt(3500, 10000), WIDTH);

    assertThat(component.getRouteDirX()).isZero();
    assertThat(component.getRouteDirY()).isZero();
  }

  @Test
  void aRouteThatWalksStraightAtTheTowerNeverLeadsAway() {
    MovementState component = MovementState.forSide(0, 3500, 10000);
    component.setRoute(leftLaneRoute());

    int answer =
        RouteBeyondReference.routeBeyondReference(
            component, ownerAt(3500, 10000), new ReferencePoint(3500, 25500), WIDTH);

    assertThat(answer).isZero();
  }

  @Test
  void aRouteOfFewerThanTwoNodesNeverLeadsAway() {
    MovementState component = MovementState.forSide(0, 3500, 10000);
    component.setRoute(Route.of(48 * WIDTH + 6));

    int answer =
        RouteBeyondReference.routeBeyondReference(
            component, ownerAt(3500, 10000), new ReferencePoint(3500, 25500), WIDTH);

    assertThat(answer).isZero();
  }

  @Test
  void aRouteLeadingPastTheReferenceAnswersOneAndLeavesItsVectorBehind() {
    MovementState component = MovementState.forSide(0, 3500, 10000);
    // The unit stands one cell from the reference while the route still runs far up the arena.
    component.setRoute(Route.of(48 * WIDTH + 6, 21 * WIDTH + 7));

    int answer =
        RouteBeyondReference.routeBeyondReference(
            component, ownerAt(3500, 25000), new ReferencePoint(3500, 25500), WIDTH);

    assertThat(answer).isEqualTo(1);
    assertThat(component.getWorkVector()).containsExactly(3750 - 3500, 10750 - 25500);
  }
}
