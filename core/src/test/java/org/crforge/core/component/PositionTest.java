package org.crforge.core.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.util.GameUnits.tiles;

import org.crforge.core.engine.GameEngine;
import org.junit.jupiter.api.Test;

class PositionTest {

  @Test
  void constructor_storesWholeGameUnits() {
    Position pos = new Position(tiles(9.5), tiles(-0.25));

    assertThat(pos.getX()).isEqualTo(9500);
    assertThat(pos.getY()).isEqualTo(-250);
  }

  @Test
  void move_accumulatesFractionalSteps_withoutSpeedLoss() {
    // Raw speed 45 = 750 game units/s = 37.5 units per 50 ms tick. Truncating each step would move
    // only 37 units per tick (740 units/s, a 1.3% slowdown).
    Position pos = new Position(0, 0);
    float step = 750f * GameEngine.DELTA_TIME;

    for (int tick = 0; tick < GameEngine.TICKS_PER_SECOND; tick++) {
      pos.move(step, 0f);
    }

    assertThat(pos.getX()).isEqualTo(750);
  }

  @Test
  void move_accumulatesOverManySeconds_withoutDrift() {
    // Raw speed 650 (tunnel) = 10833.33 units/s: 541.67 units per tick
    Position pos = new Position(0, 0);
    float speed = 10833.333f;
    int ticks = GameEngine.TICKS_PER_SECOND * 60;

    for (int tick = 0; tick < ticks; tick++) {
      pos.move(0f, speed * GameEngine.DELTA_TIME);
    }

    // 60 seconds at 10833.333 units/s; per-step fixed-point rounding is below 1/65536 unit
    assertThat(pos.getY()).isBetween(649_999, 650_001);
  }

  @Test
  void move_isSymmetricForNegativeDirections() {
    Position right = new Position(0, 0);
    Position left = new Position(0, 0);

    for (int tick = 0; tick < 3; tick++) {
      right.move(37.5f, 0f);
      left.move(-37.5f, 0f);
    }

    // 112.5 units: both round to the nearest unit, ties toward positive infinity
    assertThat(right.getX()).isEqualTo(113);
    assertThat(left.getX()).isEqualTo(-112);

    right.move(37.5f, 0f);
    left.move(-37.5f, 0f);
    assertThat(right.getX()).isEqualTo(150);
    assertThat(left.getX()).isEqualTo(-150);
  }

  @Test
  void move_subUnitStepsEventuallyAdvanceWholeUnits() {
    Position pos = new Position(100, 100);

    pos.move(0.25f, -0.25f);
    assertThat(pos.getX()).isEqualTo(100);
    assertThat(pos.getY()).isEqualTo(100);

    pos.move(0.25f, -0.25f);
    // 100.5 rounds up; 99.5 rounds up to 100
    assertThat(pos.getX()).isEqualTo(101);
    assertThat(pos.getY()).isEqualTo(100);

    pos.move(0.25f, -0.25f);
    assertThat(pos.getX()).isEqualTo(101);
    assertThat(pos.getY()).isEqualTo(99);
  }

  @Test
  void set_discardsSubUnitRemainder() {
    Position pos = new Position(0, 0);
    pos.move(0.4f, 0.4f);

    pos.set(10, 10);
    pos.move(0.4f, 0.4f);

    // Without the reset the accumulated 0.8 would round up to 11
    assertThat(pos.getX()).isEqualTo(10);
    assertThat(pos.getY()).isEqualTo(10);
  }

  @Test
  void add_preservesSubUnitRemainder() {
    Position pos = new Position(0, 0);
    pos.move(0.4f, 0f);

    pos.add(5, 0);
    pos.move(0.4f, 0f);

    assertThat(pos.getX()).isEqualTo(6);
  }

  @Test
  void clamp_onlyTouchesOutOfBoundsAxes() {
    Position pos = new Position(-20, 500);
    pos.move(0f, 0.4f);

    pos.clamp(0, 1000, 0, 1000);
    // Y was in bounds: its remainder survives, so a further 0.4 crosses the rounding boundary
    pos.move(0f, 0.4f);

    assertThat(pos.getX()).isZero();
    assertThat(pos.getY()).isEqualTo(501);
  }

  @Test
  void clamp_landsExactlyOnBound() {
    Position pos = new Position(1500, -1);

    pos.clamp(0, 1000, 0, 1000);

    assertThat(pos.getX()).isEqualTo(1000);
    assertThat(pos.getY()).isZero();
  }

  @Test
  void distanceSquaredTo_isOverflowSafe() {
    Position a = new Position(-1_000_000, -1_000_000);
    Position b = new Position(1_000_000, 1_000_000);

    // 2 * (2,000,000)^2 = 8e12, far beyond int range
    assertThat(a.distanceSquaredTo(b)).isEqualTo(8_000_000_000_000L);
    assertThat(a.distance(b)).isEqualTo((float) Math.sqrt(8e12));
  }

  @Test
  void copy_preservesCoordinatesRemainderAndRotation() {
    Position pos = new Position(10, 20, 1.5f);
    pos.move(0.4f, 0f);

    Position copy = pos.copy();
    copy.move(0.4f, 0f);

    assertThat(copy.getX()).isEqualTo(11);
    assertThat(copy.getY()).isEqualTo(20);
    assertThat(copy.getRotation()).isEqualTo(1.5f);
    // Original is independent
    assertThat(pos.getX()).isEqualTo(10);
  }
}
