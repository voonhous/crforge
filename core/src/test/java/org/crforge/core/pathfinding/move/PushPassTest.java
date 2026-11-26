/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.pathfinding.move;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.pathfinding.EntityFlags;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.grid.CellGrid;
import org.crforge.core.pathfinding.grid.PathfindingGlobals;
import org.crforge.core.pathfinding.grid.TileMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Behaviour of the pass that sums the pushes a unit takes from the units standing next to it. */
class PushPassTest {

  private static final CellGrid GRID =
      new CellGrid(TileMap.standard1v1(), true, PathfindingGlobals.PATHFINDING_BUILDING_COST);

  private MovementState component;
  private GridEntity owner;
  private StubMovementQueries queries;

  private static GridEntity neighbour(
      int id, int x, int y, int side, int mass, int radius, boolean moving) {
    GridEntity entity = new GridEntity();
    entity.setId(id);
    entity.setX(x);
    entity.setY(y);
    entity.setSide(side);
    entity.setMass(mass);
    entity.setCollisionRadius(radius);
    entity.setMovementComponent(moving);
    entity.setMovementActive(moving);
    return entity;
  }

  @BeforeEach
  void setUp() {
    component = MovementState.forSide(0, 3500, 10000);
    owner = neighbour(1, 3500, 10000, 0, 6, 500, true);
    queries = new StubMovementQueries();
  }

  private void run(List<GridEntity> others) {
    MovementChain chain =
        new MovementChain(
            component,
            owner,
            GRID,
            MovementConfig.forGroundUnit(),
            MovementGlobals.forStandardArena(36),
            null,
            others,
            queries);
    PushPass.pushPass(component, owner, others, queries, chain);
  }

  @Test
  void aUnitStandingAloneIsNotPushedAtAll() {
    run(List.of());

    assertThat(component.getPushX()).isZero();
    assertThat(component.getPushY()).isZero();
    assertThat(component.getPushCount()).isZero();
  }

  @Test
  void anAllyToTheRightPushesTheUnitLeft() {
    run(List.of(neighbour(2, 3700, 10000, 0, 6, 500, true)));

    assertThat(component.getPushX()).isEqualTo(-300);
    assertThat(component.getPushY()).isZero();
    assertThat(component.getPushCount()).isEqualTo(1);
  }

  @Test
  void twoNeighboursEachAddTheirOwnPush() {
    run(
        List.of(
            neighbour(2, 3700, 10000, 0, 6, 500, true),
            neighbour(3, 3400, 10300, 1, 6, 500, true)));

    assertThat(component.getPushX()).isEqualTo(-206);
    assertThat(component.getPushY()).isEqualTo(-284);
    assertThat(component.getPushCount()).isEqualTo(2);
  }

  @Test
  void aUnitSharingAPositionWithABuildingIsPushedBackTowardItsOwnSide() {
    run(List.of(neighbour(4, 3500, 10000, 0, 12, 1000, false)));

    assertThat(component.getPushX()).isZero();
    assertThat(component.getPushY()).isEqualTo(-300);
    assertThat(component.getPushCount()).isEqualTo(1);
  }

  @Test
  void theOppositeSideIsPushedTheOtherWayOutOfACoincidentPosition() {
    owner.setSide(1);
    queries.ownerSide = 1;

    run(List.of(neighbour(4, 3500, 10000, 0, 12, 1000, false)));

    assertThat(component.getPushY()).isEqualTo(300);
  }

  /** The flag bits as the tables number the game tags. */
  private static final EntityFlags BITS = EntityFlags.of(GameData.tables());

  private static void flag(GridEntity entity, long bits) {
    entity.setFlagBits(BITS);
    entity.setFlags(bits);
  }

  @Test
  @DisplayName(
      "a partner side's neighbour is an ally, by team: a unit not pushed by allies skips it, one"
          + " not pushed by enemies takes its push")
  void aPartnerSideNeighbourIsAnAlly() {
    flag(owner, BITS.noPushedByAlly());
    GridEntity partner = neighbour(2, 3700, 10000, 2, 6, 500, true);
    partner.setFlagBits(BITS);

    run(List.of(partner));
    assertThat(component.getPushCount()).isZero();

    setUp();
    flag(owner, BITS.noPushedByEnemy());
    run(List.of(partner));
    assertThat(component.getPushX()).isEqualTo(-300);
    assertThat(component.getPushCount()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "a partner side's neighbour pushes by the ally flag: one that does not push allies holds"
          + " its ground, one that does not push enemies pushes")
  void aPartnerSideNeighbourPushesByTheAllyFlag() {
    owner.setFlagBits(BITS);
    GridEntity enemyOnly = neighbour(2, 3700, 10000, 2, 6, 500, true);
    flag(enemyOnly, BITS.noPushedByEnemy());
    run(List.of(enemyOnly));
    assertThat(component.getPushX()).isEqualTo(-300);

    setUp();
    owner.setFlagBits(BITS);
    GridEntity allyOnly = neighbour(2, 3700, 10000, 2, 6, 500, true);
    flag(allyOnly, BITS.noPushedByAlly());
    GridEntity sideZero = neighbour(2, 3700, 10000, 0, 6, 500, true);
    flag(sideZero, BITS.noPushedByAlly());
    run(List.of(allyOnly));
    int partnerPush = component.getPushX();
    setUp();
    owner.setFlagBits(BITS);
    run(List.of(sideZero));
    assertThat(partnerPush).as("as a side 0 ally's").isEqualTo(component.getPushX());
  }

  @Test
  @DisplayName("a partner side's neighbour without a no-push flag pushes as any ally does")
  void aPartnerSideNeighbourWithoutFlagsPushes() {
    owner.setFlagBits(BITS);
    GridEntity partner = neighbour(2, 3700, 10000, 2, 6, 500, true);
    partner.setFlagBits(BITS);

    run(List.of(partner));

    assertThat(component.getPushX()).isEqualTo(-300);
    assertThat(component.getPushCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("a side 2 unit sharing a position separates as side 0, both of team 0")
  void aSideTwoUnitSharingAPositionSeparatesAsSideZero() {
    owner.setSide(2);
    queries.ownerSide = 2;

    run(List.of(neighbour(4, 3500, 10000, 0, 12, 1000, false)));

    assertThat(component.getPushX()).isZero();
    assertThat(component.getPushY()).isEqualTo(-300);
  }

  @Test
  @DisplayName("a side 3 unit sharing a position separates as side 1, both of team 1")
  void aSideThreeUnitSharingAPositionSeparatesAsSideOne() {
    owner.setSide(3);
    queries.ownerSide = 3;

    run(List.of(neighbour(4, 3500, 10000, 0, 12, 1000, false)));

    assertThat(component.getPushY()).isEqualTo(300);
  }

  @Test
  void aUnitWithoutACollisionRadiusIsNeverPushed() {
    owner.setCollisionRadius(0);

    run(List.of(neighbour(2, 3700, 10000, 0, 6, 500, true)));

    assertThat(component.getPushCount()).isZero();
  }

  @Test
  @DisplayName(
      "a neighbour its own movement left in the air, as a dash cut short before it lands, is on"
          + " the air layer and does not push a unit on the ground")
  void aNeighbourLeftInTheAirByItsOwnHeightDoesNotPush() {
    GridEntity lifted = neighbour(2, 3700, 10000, 0, 6, 500, true);
    // The height its own movement writes, with no height offset: the push pass compares the live
    // height, the two together.
    lifted.setZ(500);

    run(List.of(lifted));

    assertThat(component.getPushCount()).isZero();
    assertThat(component.getPushX()).isZero();
  }

  @Test
  void aNeighbourOutOfReachAddsNothing() {
    run(List.of(neighbour(2, 5000, 10000, 0, 6, 500, true)));

    assertThat(component.getPushCount()).isZero();
  }

  @Test
  void aNeighbourWithAnInactiveMovementComponentIsNotStatic() {
    // A unit waiting to deploy keeps its movement component, switched off. The push pass tests
    // whether the component exists, so the neighbour reaches with the unit's whole radius of 700,
    // not the 500 a static neighbour is clamped to: 500 + 700 covers the 1100 between them.
    owner.setCollisionRadius(700);
    GridEntity waiting = neighbour(2, 4600, 10000, 0, 6, 500, true);
    waiting.setMovementActive(false);

    run(List.of(waiting));

    assertThat(component.getPushCount()).isEqualTo(1);
    assertThat(component.getPushX()).isNegative();
  }

  @Test
  void twoAlignedStaticNeighboursCopyASingleAxisPushOntoTheOtherAxis() {
    // Two buildings of mass 0 on the unit's x each push it 1 unit away along the length; with a
    // second static entity on its x the aligned check answers 1 and the push is copied across.
    run(
        List.of(
            neighbour(2, 3500, 10900, 0, 0, 500, false),
            neighbour(3, 3500, 11000, 0, 0, 500, false)));

    assertThat(component.getPushY()).isEqualTo(-2);
    assertThat(component.getPushX()).isEqualTo(-2);
    assertThat(component.getPushCount()).isEqualTo(2);
  }

  @Test
  void oneStaticNeighbourLeavesASingleAxisPushAlone() {
    run(List.of(neighbour(2, 3500, 10900, 0, 0, 500, false)));

    assertThat(component.getPushY()).isEqualTo(-1);
    assertThat(component.getPushX()).isZero();
  }
}
