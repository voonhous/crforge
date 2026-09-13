package org.crforge.core.entity.projectile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.util.GameUnits.tiles;

import java.util.List;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.Test;

/**
 * Projectile flight time must not grow by a tick because positions are reported in whole game units
 * while the per-tick step is fractional.
 */
class ProjectileArrivalTimingTest {

  private static final float DT = 1.0f / 30;

  private static int ticksToHit(Projectile projectile) {
    for (int tick = 1; tick <= 200; tick++) {
      if (projectile.update(DT)) {
        return tick;
      }
    }
    return -1;
  }

  @Test
  void positionTargetedProjectile_hitsOnTheTickItsStepsCoverTheDistance() {
    // 1.25 tiles/s at 30 ticks/s = 41.667 units per tick; 24 steps cover exactly 1000 units, so
    // the 24th update is the first whose remaining distance fits within one step
    Projectile projectile =
        new Projectile(Team.BLUE, tiles(5), tiles(5), tiles(5), tiles(6), 10, 0, 1250f, List.of());

    assertThat(ticksToHit(projectile)).isEqualTo(24);
    assertThat(projectile.getPosition().getY()).isEqualTo(tiles(6));
  }

  @Test
  void diagonalProjectile_arrivalIsNotDelayedByRounding() {
    // 3-4-5 triangle: 5000 units at 5000 units/s, 1/30 s ticks -> 30 steps of 166.667 units
    Projectile projectile =
        new Projectile(
            Team.BLUE,
            tiles(2),
            tiles(2),
            tiles(2) + 3000,
            tiles(2) + 4000,
            10,
            0,
            5000f,
            List.of());

    assertThat(ticksToHit(projectile)).isEqualTo(30);
    assertThat(projectile.getPosition().getX()).isEqualTo(tiles(2) + 3000);
    assertThat(projectile.getPosition().getY()).isEqualTo(tiles(2) + 4000);
  }
}
