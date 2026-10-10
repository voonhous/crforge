/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.ActionOwner;
import org.crforge.core.battle.action.BlowdartController;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * One run of the evolved Dart Goblin's poison controller on the object its darts hit: the darts it
 * has counted, its stack, the time it has left, the timer to its next area and the areas it has
 * dropped that are still in the battle. See {@link BlowdartController} for the rules.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled line for line from the class's start, re-trigger, step, stack step, area drop,"
            + " match and object-left notice; held by evo_blowdartgoblin_vs_musketeer. Refused: a"
            + " cause other than a character or a projectile, and a stack whose area row index"
            + " falls below 0.")
public final class BlowdartControllerRun extends ActionInstance {

  /** The step the battle takes, in milliseconds, as the run counts its time. */
  private static final int STEP_MS = 50;

  private final BlowdartController controller;
  private final WorldEntity owner;
  private final BattleWorld world;

  /** The darts counted. */
  @Getter private int darts;

  /** The stack reached, counted from 0 before the first dart. */
  @Getter private int stack;

  /** True once the last stack is reached. */
  @Getter private boolean maxed;

  /** Milliseconds toward the next area. */
  private int timer;

  /** The id of the unit that threw the darts. */
  @Getter private int thrower;

  /** Milliseconds the run has left once active. */
  @Getter private int leftMs;

  /** True once a dart's hit has activated the run. */
  @Getter private boolean active;

  /** True for a run a dart's hit started, which counts each further dart's hit. */
  private boolean countsHits;

  /** True once the thrower has left the battle. */
  private boolean throwerGone;

  /** The ids of the areas the run dropped that are still in the battle. */
  private final List<Integer> areas = new ArrayList<>();

  /** The stack's index as the last start or re-trigger left it, which each area's copy keeps. */
  private int levelIndex;

  /** The level of the areas, the thrower's. */
  private int packedLevel;

  /** The side of the areas, the cause's team. */
  private final int side;

  BlowdartControllerRun(BlowdartController controller, WorldEntity owner, ActionHolder instigator) {
    super(controller);
    this.controller = controller;
    this.owner = owner;
    this.world = owner.world;
    ActionOwner cause = instigator == null ? null : instigator.getOwner();
    if (!(cause instanceof WorldEntity) && !(cause instanceof ProjectileEntity)) {
      throw new UnsupportedOperationException(
          controller.name() + " started by something other than a unit or a dart, not modelled");
    }
    // The start: the cause as the thrower, its team and level, the duration for the owner, and
    // the first dart counted.
    leftMs = duration();
    thrower = cause.actionId();
    packedLevel =
        cause instanceof WorldEntity unit
            ? unit.packedLevel()
            : ((ProjectileEntity) cause).packedLevel();
    // The cause's team: its side's low bit, which is its side in a battle of two sides.
    side =
        cause instanceof WorldEntity unit
            ? unit.actionTeam()
            : ((ProjectileEntity) cause).side() & 1;
    darts++;
    stackStep();
    levelIndex = (maxed ? 1 : 0) + stack - 1;
    if (cause instanceof ProjectileEntity dart) {
      // A dart's hit: the run is active at once, counts each further hit, and the thrower is the
      // dart's owner.
      active = true;
      countsHits = true;
      thrower = dart.getOwnerId();
      drop();
    }
  }

  /** Duration, or CrownTowerDuration on a crown tower when it is set. */
  private int duration() {
    BlowdartController.Columns columns = controller.getColumns();
    return owner.getTargetView().crownTower() && columns.crownTowerDurationMs() != -1
        ? columns.crownTowerDurationMs()
        : columns.durationMs();
  }

  /**
   * Whether a start caused by the given entity is this run: a dart whose owner is the thrower, or
   * the thrower itself; anything else is not.
   */
  @Override
  public boolean sameRun(ActionHolder instigator) {
    ActionOwner cause = instigator == null ? null : instigator.getOwner();
    if (cause instanceof ProjectileEntity dart) {
      return dart.getOwnerId() == thrower;
    }
    if (cause instanceof CharacterEntity unit) {
      return unit.getId() == thrower;
    }
    return false;
  }

  /**
   * True when the next dart is special: the last stack is not reached and the darts, the next one
   * counted, reach the next stack's count.
   */
  boolean nextDartSpecial() {
    return !maxed && darts + 1 >= controller.getColumns().stackAmountChecks().get(stack);
  }

  /** Counts a dart the thrower's dart choice picked, which may step the stack. */
  void countDart() {
    darts++;
    stackStep();
  }

  /**
   * The stack step after a dart is counted: nothing once the last stack is reached; otherwise, once
   * the darts reach the stack's count, the stack goes up by one, the areas take the thrower's level
   * again while it is in the battle, and a stack that reaches MaxStacks stays one below it as the
   * last one.
   */
  private void stackStep() {
    if (maxed) {
      return;
    }
    BlowdartController.Columns columns = controller.getColumns();
    if (darts < columns.stackAmountChecks().get(stack)) {
      return;
    }
    stack++;
    if (!throwerGone) {
      BattleEntity unit = world.liveObject(thrower);
      if (unit instanceof WorldEntity entity) {
        packedLevel = entity.packedLevel();
      } else if (unit instanceof ProjectileEntity dart) {
        packedLevel = dart.packedLevel();
      }
    }
    if (stack >= columns.maxStacks()) {
      stack--;
      maxed = true;
    }
  }

  /**
   * A second start of the row the same thrower caused: a dart's hit counts on a run a dart's hit
   * started and activates one the dart choice started, dropping its first area; an area is dropped
   * too when the time has run out; then the time is put back and the stack stepped.
   */
  @Override
  protected void retrigger(ActionHolder holder, ActionHolder instigator) {
    ActionOwner cause = instigator == null ? null : instigator.getOwner();
    if (cause instanceof ProjectileEntity) {
      if (countsHits) {
        darts++;
      }
      if (!active) {
        active = true;
        drop();
      }
    }
    if (leftMs <= 0) {
      drop();
    }
    leftMs = duration();
    stackStep();
    levelIndex = (maxed ? 1 : 0) + stack - 1;
  }

  @Override
  protected void update(ActionHolder holder) {
    if (!active) {
      return;
    }
    leftMs -= STEP_MS;
    if (leftMs <= 0) {
      if (areas.isEmpty()) {
        finish();
      }
      timer = 0;
      return;
    }
    timer += STEP_MS;
    int interval = controller.getColumns().spawnIntervalMs();
    if (timer - interval >= 0) {
      timer -= interval;
      drop();
    }
  }

  /** An object left the battle: the thrower is no longer looked up, and an area is unlisted. */
  @Override
  protected void objectLeft(int leftId) {
    if (leftId == thrower) {
      throwerGone = true;
    }
    areas.remove(Integer.valueOf(leftId));
  }

  /**
   * Drops the stack's area on the owner's point, for the run's side and at its level, with no
   * parent; a copy of the run is listed on it and the area is listed on the run.
   */
  private void drop() {
    List<String> rows = controller.getColumns().aeoList();
    int index = Math.min((maxed ? 1 : 0) + stack - 1, rows.size() - 1);
    if (index < 0) {
      throw new UnsupportedOperationException(
          controller.name() + " drops an area before its first stack, not modelled");
    }
    AreaEffectEntity area =
        world.createAreaEffect(
            rows.get(index),
            owner.getView().getX(),
            owner.getView().getY(),
            side,
            packedLevel,
            null,
            "action",
            controller.name());
    area.actionHolder().list(new Copy(controller, levelIndex, thrower, area.getId()));
    areas.add(area.getId());
  }

  /**
   * The copy of the run each area carries: the stack index and thrower the poison damage its hits
   * start reads. It does nothing on its steps.
   */
  public static final class Copy extends ActionInstance {

    /** The stack index the run had as it dropped the area. */
    @Getter private final int levelIndex;

    /** The id of the unit that threw the darts. */
    @Getter private final int thrower;

    /** The id of the area it is listed on. */
    @Getter private final int areaId;

    Copy(BlowdartController controller, int levelIndex, int thrower, int areaId) {
      super(controller);
      this.levelIndex = levelIndex;
      this.thrower = thrower;
      this.areaId = areaId;
    }

    @Override
    protected void update(ActionHolder holder) {
      // A copy only keeps what its area's hits read.
    }
  }
}
