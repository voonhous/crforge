package org.crforge.core.pathfinding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Default values and the 64-bit flag word of {@link GridEntity}. */
class GridEntityTest {

  @Test
  void aNewEntityIsAliveOfTypeFiveAndPushEnabled() {
    GridEntity entity = new GridEntity();
    assertThat(entity.isAlive()).isTrue();
    assertThat(entity.getType()).isEqualTo(5);
    assertThat(entity.isPushEnabled()).isTrue();
    assertThat(entity.getState()).isEqualTo(GridEntityState.STANDING);
    assertThat(entity.getFlags()).isZero();
    assertThat(entity.getPendingFlags()).isZero();
  }

  @Test
  void flagsHoldBitsAboveThirtyTwo() {
    GridEntity entity = new GridEntity();
    long noMoveAllowAttract = 1L << 58;
    long noPushedByAlly = 1L << 53;

    entity.setFlags(noMoveAllowAttract | noPushedByAlly);

    assertThat(entity.getFlags() & noMoveAllowAttract).isNotZero();
    assertThat(entity.getFlags() & noPushedByAlly).isNotZero();
    assertThat(entity.getFlags() & (1L << 52)).isZero();
  }

  @Test
  void pendingFlagsAreSeparateFromFlags() {
    GridEntity entity = new GridEntity();
    entity.setPendingFlags(1L << 51);
    assertThat(entity.getFlags()).isZero();
    assertThat(entity.getPendingFlags()).isEqualTo(1L << 51);
  }

  @Test
  void positionAndFacingRoundTrip() {
    GridEntity entity = new GridEntity();
    entity.setId(7);
    entity.setName("Knight");
    entity.setSide(0);
    entity.setX(3500);
    entity.setY(10000);
    entity.setZ(0);
    entity.setZTotal(0);
    entity.setDirX(81);
    entity.setDirY(243);
    entity.setCollisionRadius(500);
    entity.setMass(6);
    entity.setLane(1);
    entity.setDeployCountdown(1000);
    entity.setMovementActive(true);

    assertThat(entity.getName()).isEqualTo("Knight");
    assertThat(entity.getX()).isEqualTo(3500);
    assertThat(entity.getY()).isEqualTo(10000);
    assertThat(entity.getDirX()).isEqualTo(81);
    assertThat(entity.getDirY()).isEqualTo(243);
    assertThat(entity.getCollisionRadius()).isEqualTo(500);
    assertThat(entity.getLane()).isEqualTo(1);
    assertThat(entity.getDeployCountdown()).isEqualTo(1000);
    assertThat(entity.isMovementActive()).isTrue();
  }

  @Test
  void aTowerIsABuildingThatOccludesAndDoesNotMove() {
    GridEntity tower = new GridEntity();
    tower.setBuilding(true);
    tower.setOccludes(true);
    tower.setKing(true);
    tower.setCollisionRadius(1400);

    assertThat(tower.isBuilding()).isTrue();
    assertThat(tower.isOccludes()).isTrue();
    assertThat(tower.isKing()).isTrue();
    assertThat(tower.isAir()).isFalse();
    assertThat(tower.isMovementActive()).isFalse();
  }
}
