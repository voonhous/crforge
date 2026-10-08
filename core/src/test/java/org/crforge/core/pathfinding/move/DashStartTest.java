package org.crforge.core.pathfinding.move;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.pathfinding.EntityFlags;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.grid.Route;
import org.crforge.core.pathfinding.state.StateSetter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Where a dash is aimed, which way the dasher faces, and the state it asks for. */
class DashStartTest {

  /** Which bit of a tag word each flag is, as the configured tables number the game tags. */
  private static final EntityFlags BITS = EntityFlags.of(GameData.tables());

  private static final int WIDTH = 36;
  private static final int HEIGHT = 64;

  /** The states asked for, in order. */
  private final List<Integer> asked = new ArrayList<>();

  private final StateSetter setter =
      (entity, state) -> {
        asked.add(state);
        entity.setState(state);
      };

  /** A Bandit-sized dasher, radius 600, facing up the arena. */
  private static GridEntity dasherAt(int x, int y) {
    GridEntity owner = new GridEntity();
    owner.setFlagBits(BITS);
    owner.setX(x);
    owner.setY(y);
    owner.setCollisionRadius(600);
    owner.setDirX(0);
    owner.setDirY(256);
    owner.setState(GridEntityState.MOVING);
    return owner;
  }

  @Test
  @DisplayName("the Bandit dashes to the cell short of the Knight by both radii, facing it")
  void theBanditStopsShortOfTheKnight() {
    // A Bandit dashing at a Knight at (3687, 16486), radius 500: the point pulled back 1100 toward
    // the Bandit is (3683, 15386), in node 1087. The Bandit's dash is held by the reference battles
    // grid_clone_over_bandit_dash and grid_log_over_bandit_dash_end.
    GridEntity owner = dasherAt(3665, 10965);
    MovementState movement = MovementState.forSide(0, 3665, 10965);

    DashStart.start(
        owner,
        movement,
        MovementConfig.forGroundUnit(),
        3687,
        16486,
        500,
        1,
        WIDTH,
        HEIGHT,
        setter);

    assertThat(movement.getRoute()).isEqualTo(Route.of(1087));
    assertThat(movement.getDashStopsInRange()).isEqualTo(1);
    assertThat(owner.getDirX()).isEqualTo(1);
    assertThat(owner.getDirY()).isEqualTo(256);
    assertThat(asked).containsExactly(GridEntityState.DASHING);
    assertThat(owner.getPendingFlags() & BITS.dashing()).isNotZero();
    assertThat(owner.getX()).as("the start moves nothing").isEqualTo(3665);
  }

  @Test
  @DisplayName("a dasher already closer than both radii dashes to its own cell")
  void aCloseDasherDashesWhereItStands() {
    GridEntity owner = dasherAt(3750, 15250);
    MovementState movement = MovementState.forSide(0, 3750, 15250);

    DashStart.start(
        owner,
        movement,
        MovementConfig.forGroundUnit(),
        3900,
        15700,
        500,
        1,
        WIDTH,
        HEIGHT,
        setter);

    assertThat(movement.getRoute()).isEqualTo(Route.of(30 * WIDTH + 7));
  }

  @Test
  @DisplayName("a dasher on the point's column keeps its facing, and a jumper's dash byte is off")
  void aDasherOnTheColumnKeepsItsFacing() {
    GridEntity owner = dasherAt(3500, 10000);
    owner.setDirX(181);
    owner.setDirY(181);
    MovementState movement = MovementState.forSide(0, 3500, 10000);

    DashStart.start(
        owner,
        movement,
        MovementConfig.forGroundUnit().withJump(false, 3000),
        3500,
        14000,
        500,
        1,
        WIDTH,
        HEIGHT,
        setter);

    assertThat(owner.getDirX()).isEqualTo(181);
    assertThat(owner.getDirY()).isEqualTo(181);
    assertThat(movement.getDashStopsInRange()).isZero();
  }

  @Test
  @DisplayName("a dasher under the no-dash flag does nothing at all")
  void noDashStopsEverything() {
    GridEntity owner = dasherAt(3665, 10965);
    owner.setFlags(BITS.noDash());
    MovementState movement = MovementState.forSide(0, 3665, 10965);
    movement.setRoute(Route.of(1159, 1123));

    DashStart.start(
        owner,
        movement,
        MovementConfig.forGroundUnit(),
        3687,
        16486,
        500,
        1,
        WIDTH,
        HEIGHT,
        setter);

    assertThat(movement.getRoute()).isEqualTo(Route.of(1159, 1123));
    assertThat(asked).isEmpty();
    assertThat(owner.getState()).isEqualTo(GridEntityState.MOVING);
  }
}
