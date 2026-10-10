/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import org.crforge.core.battle.TargetLocks;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.BarbBarrelHeroReRoll;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.grid.CellGrid;
import org.crforge.core.pathfinding.grid.Relocation;
import org.crforge.core.pathfinding.move.MovementState;

/**
 * One run of a hero Barbarian Barrel's reroll on its barbarian: the spawn delay's counter, the
 * projectile the barbarian rolls in while it is in the battle, whether it was launched, and the
 * deflections it had at the last step.
 *
 * <p>See {@link BarbBarrelHeroReRoll} for what each start, step, leaving and finish does.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the start (lock request, counter, facing), the step (alive and"
            + " CAPTURED gates, the facing, the backing off in 32-bit arithmetic, the launch and"
            + " the start action, the follow and the rolling tags), the end as the projectile"
            + " leaves and the finish's release and relocation; held by ability_hero_barb_log."
            + " Refused: a deflection.")
final class BarbReRollRun extends ActionInstance {

  /** Milliseconds one step takes off the counter. */
  private static final int STEP_MS = 50;

  /** The lock channel the run asks on, and the priority and flags of its request. */
  private static final int CHANNEL = 0;

  private static final int PRIORITY = 1000;
  private static final int FLAGS = 2;

  private final BarbBarrelHeroReRoll row;
  private final CharacterEntity unit;

  /** What is left of the spawn delay, in milliseconds. */
  private int counter;

  /** The projectile the barbarian rolls in while it is in the battle, else null. */
  private ProjectileEntity projectile;

  /** True once the projectile was launched. */
  private boolean launched;

  /** The projectile's deflections at the last step. */
  private int deflections;

  /**
   * The start on a character: the lock of the character on itself asked for unless it holds one,
   * the spawn delay as the counter, and the character faced forward.
   */
  BarbReRollRun(BarbBarrelHeroReRoll row, CharacterEntity unit) {
    super(row);
    this.row = row;
    this.unit = unit;
    counter = row.getColumns().spawnDelayMs();
    TargetLocks locks = unit.world().locks();
    if (!locks.claim(unit.getId(), unit.getId(), CHANNEL)) {
      locks.request(unit.getId(), unit.getId(), CHANNEL, PRIORITY, FLAGS);
    }
    faceForward();
  }

  @Override
  protected void update(ActionHolder holder) {
    if (unit.getHitPoints() != null && unit.getHitPoints().getHitPoints() <= 0) {
      finishRun();
      return;
    }
    faceForward();
    if ((unit.getView().getFlags() & unit.getView().getFlagBits().captured()) != 0) {
      return;
    }
    counter = Math.max(counter, STEP_MS) - STEP_MS;
    GridEntity view = unit.getView();
    BarbBarrelHeroReRoll.Columns columns = row.getColumns();
    if (counter != 0) {
      // Backs off by the offset's share of a step, signed for the side: the offset is set for
      // side 0, toward its own side.
      int share = columns.offsetY() * STEP_MS / columns.spawnDelayMs();
      view.setY(view.getY() + sign() * share);
      return;
    }
    if (projectile == null && !launched) {
      launch(holder);
    }
    if (projectile == null) {
      finishRun();
      return;
    }
    view.setX(projectile.getX());
    view.setY(projectile.getY());
    unit.raiseWatched(columns.rollingTags());
    if (projectile.getDeflections() != deflections) {
      // A deflection re-stamps the barbarian from its side's summoner and changes its row; no
      // reference holds one.
      throw new UnsupportedOperationException(
          row.name()
              + "'s "
              + projectile.getData().name()
              + " is deflected, which is not modelled");
    }
  }

  /**
   * The roll's start: the start action scheduled on the character, its own cause, and the reroll
   * projectile launched from where it stands, at its height, aimed at the projectile's range
   * straight forward for its side.
   */
  private void launch(ActionHolder holder) {
    BarbBarrelHeroReRoll.Columns columns = row.getColumns();
    if (columns.onReRollStartAction() != null) {
      holder.schedule(columns.onReRollStartAction(), ActionHolder.OWN_DELAY, false, holder);
    }
    BattleWorld world = unit.world();
    ProjectileData data = world.reRollProjectile(row.name(), columns.reRollProjectile());
    GridEntity view = unit.getView();
    int x = view.getX();
    int y = view.getY();
    int z = view.getZ() + view.getHeightOffset();
    projectile = world.launchFromUnit(unit, data, x, y, z, x, y + sign() * data.projectileRange());
    launched = true;
  }

  /**
   * As the projectile leaves the battle: the run forgets it and the roll ends, the end action
   * scheduled on the character, its own cause, the character deploying for the deploy duration and
   * faced forward.
   */
  @Override
  protected void objectLeft(int leftId) {
    if (projectile == null || projectile.getId() != leftId) {
      return;
    }
    if (projectile.getDeflections() >= 1 && (projectile.getDeflections() & 1) != 0) {
      throw new UnsupportedOperationException(
          row.name() + " ends a deflected roll, which is not modelled");
    }
    projectile = null;
    BarbBarrelHeroReRoll.Columns columns = row.getColumns();
    ActionHolder holder = unit.actionHolder();
    if (columns.onReRollEndAction() != null) {
      holder.schedule(columns.onReRollEndAction(), ActionHolder.OWN_DELAY, false, holder);
    }
    if (columns.deployDurationMs() >= 1) {
      unit.deployFor(columns.deployDurationMs());
    } else {
      unit.getView().setDeployCountdown(columns.deployDurationMs());
    }
    faceForward();
  }

  /**
   * The finish and its hook: the lock of the character on itself released when it holds it, and the
   * character relocated off water and inside the arena.
   */
  private void finishRun() {
    finish();
    TargetLocks locks = unit.world().locks();
    if (locks.claim(unit.getId(), unit.getId(), CHANNEL)) {
      locks.release(unit.getId(), unit.getId(), CHANNEL);
    }
    GridEntity view = unit.getView();
    CellGrid grid = unit.world().getGrid();
    int packed =
        Relocation.relocate(
            grid.getWidth(), grid.getHeight(), view.getX(), view.getY(), -1, grid::water);
    view.setX(Relocation.unpackX(packed));
    view.setY(Relocation.unpackY(packed));
  }

  /** Faces the character straight forward for its side: up the length for side 0. */
  private void faceForward() {
    GridEntity view = unit.getView();
    view.setDirX(0);
    view.setDirY(sign() * MovementState.DIRECTION_SCALE);
  }

  /** The side's sign: 1 for side 0, whose forward is up the length, else -1. */
  private int sign() {
    return (unit.side() & 1) == 0 ? 1 : -1;
  }
}
