/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A unit whose targeting waits for its pingpong projectile to come back does not walk: the speed
 * budget is 0 while the targeting component's resume delay, which the launch sets to the sweep's
 * time, is running, whatever the unit's state. It matters when something puts the unit into the
 * moving state while its axe is out, as the tiebreaker's clearing does: it resumes every unit it
 * kills, and the unit lives through the update the clearing runs until that update's damage drain.
 *
 * <p>The scene: the top side's Executioner stands in reach of the bottom side's left princess tower
 * and throws its axe at it. While the axe is out, the clearing's kill reaches the Executioner: it
 * is resumed into the moving state at once and its kill waits for the drain of the next update.
 */
class BattlePingpongResumeStandTest {

  /** The level of the towers and the Executioner. */
  private static final int LEVEL = 1;

  /** Where the Executioner stands, in reach of the bottom side's left princess tower. */
  private static final int X = 3500;

  private static final int Y = 11000;

  /** Long enough for the deploy and the first throw. */
  private static final int TICKS = 400;

  @Test
  @DisplayName(
      "an Executioner resumed into the moving state while its axe is out stands still through"
          + " the update: its resume delay holds its speed at 0")
  void theResumeDelayHoldsTheSpeed() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, true);
    List<ProjectileEntity> axes = new ArrayList<>();
    List<String> kills = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void projectileLaunched(int tick, ProjectileEntity projectile) {
                if (projectile.getData().pingpongVisualTimeMs() >= 1) {
                  axes.add(projectile);
                }
              }

              @Override
              public void clearingKilled(int tick, WorldEntity target) {
                kills.add(
                    target.name() + " " + target.getView().getX() + "," + target.getView().getY());
              }
            });
    CharacterEntity executioner =
        match.deploy(0, match.getWorld().getRecords().unit("AxeMan"), LEVEL, 1, X, Y, "Axe");
    for (int step = 0; step < TICKS && axes.isEmpty(); step++) {
      match.getBattle().step();
    }
    assertThat(axes).as("the axe is out").hasSize(1);
    ProjectileEntity axe = axes.get(0);
    // A few steps into the sweep, the axe still on its way and the resume delay running.
    match.getBattle().step();
    match.getBattle().step();
    assertThat(axe.isReleased()).isFalse();
    assertThat(executioner.getTargeting().getResumeDelayMs()).isPositive();
    assertThat(executioner.getTargeting().getResumeDelayElapsedMs())
        .isLessThan(executioner.getTargeting().getResumeDelayMs() - 50);
    int x = executioner.getView().getX();
    int y = executioner.getView().getY();

    // The clearing's kill: the resume at once, the kill queued for the drain.
    match.getWorld().clearingKill(executioner);
    assertThat(executioner.getView().getState()).isEqualTo(GridEntityState.MOVING);
    match.getBattle().step();

    // It took the update in the moving state without a step, and died where it stood.
    assertThat(kills).containsExactly("Axe " + x + "," + y);
    assertThat(executioner.getHitPoints().getHitPoints()).isZero();
  }
}
