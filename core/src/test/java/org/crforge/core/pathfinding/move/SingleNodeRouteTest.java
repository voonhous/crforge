package org.crforge.core.pathfinding.move;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.grid.Route;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The route a jump or a dash sets: the one cell of a point, clamped into the grid. */
class SingleNodeRouteTest {

  private static final int WIDTH = 36;
  private static final int HEIGHT = 64;

  private static GridEntity ownerAt(int x, int y) {
    GridEntity owner = new GridEntity();
    owner.setX(x);
    owner.setY(y);
    return owner;
  }

  @Test
  @DisplayName("a jump's landing point becomes its one node, and a jumper's dash byte is off")
  void aJumpSetsTheLandingCell() {
    MovementState component = MovementState.forSide(0, 10092, 14092);
    component.setRoute(Route.of(1250, 1214, 1178));
    MovementConfig jumper = MovementConfig.forGroundUnit().withJump(true, 4000);

    // The Hog Rider of hog_river, jumping from (10092, 14092) to the centre of node 1250.
    SingleNodeRoute.set(component, ownerAt(10092, 14092), jumper, 13250, 17250, 1, WIDTH, HEIGHT);

    assertThat(component.getRoute()).isEqualTo(Route.of(1250));
    assertThat(component.getDashStopsInRange()).isZero();
    assertThat(component.getRouteDirX()).isEqualTo(181);
    assertThat(component.getRouteDirY()).isEqualTo(181);
  }

  @Test
  @DisplayName("a point off the grid is clamped into its edge cells, and the flag sets the byte")
  void aPointOffTheGridIsClamped() {
    MovementState component = MovementState.forSide(0, 3500, 10000);

    SingleNodeRoute.set(
        component,
        ownerAt(3500, 10000),
        MovementConfig.forGroundUnit(),
        -5,
        40000,
        1,
        WIDTH,
        HEIGHT);

    assertThat(component.getRoute()).isEqualTo(Route.of((HEIGHT - 1) * WIDTH));
    assertThat(component.getDashStopsInRange()).isEqualTo(1);
  }

  @Test
  @DisplayName("499 falls in the first cell and 500 in the second")
  void theCellEdgeIsAtFiveHundred() {
    MovementState component = MovementState.forSide(0, 3500, 10000);
    GridEntity owner = ownerAt(3500, 10000);
    MovementConfig config = MovementConfig.forGroundUnit();

    SingleNodeRoute.set(component, owner, config, 499, 500, 0, WIDTH, HEIGHT);

    assertThat(component.getRoute()).isEqualTo(Route.of(WIDTH));
    assertThat(component.getDashStopsInRange()).isZero();
  }
}
