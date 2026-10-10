/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import lombok.Getter;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.ActionOwner;
import org.crforge.core.battle.action.BlowdartDamage;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.combat.LevelScaling;
import org.crforge.core.pathfinding.combat.ScalingGlobals;
import org.crforge.core.pathfinding.combat.ScalingMode;

/**
 * One run of the evolved Dart Goblin's poison damage on an object a poison area reached: its clock,
 * the amount it deals, the time it has left and the thrower whose areas keep it going. See {@link
 * BlowdartDamage} for the rules.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled line for line from the class's start, re-trigger, step, match and amount; held"
            + " by evo_blowdartgoblin_vs_musketeer. Supplied: the amount scaled as an area"
            + " effect's damage is, at the area's level. Refused: a cause other than a poison area"
            + " carrying its controller's copy.")
public final class BlowdartDamageRun extends ActionInstance {

  /** The step the battle takes, in milliseconds, as the run counts its time. */
  private static final int STEP_MS = 50;

  private final BlowdartDamage damage;
  private final WorldEntity owner;

  /** Milliseconds the run has counted. */
  private int clockMs;

  /** The amount each damage deals. */
  @Getter private int amount;

  /** Milliseconds the run has left. */
  @Getter private int leftMs;

  /** The id of the unit whose areas keep the run going. */
  @Getter private final int thrower;

  BlowdartDamageRun(BlowdartDamage damage, WorldEntity owner, ActionHolder instigator) {
    super(damage);
    this.damage = damage;
    this.owner = owner;
    leftMs = duration();
    BlowdartControllerRun.Copy copy = copy(instigator);
    amount = Math.max(amount, amount(instigator, copy));
    thrower = copy.getThrower();
  }

  /** Duration, or CrownTowerDuration on a crown tower when it is set. */
  private int duration() {
    BlowdartDamage.Columns columns = damage.getColumns();
    return owner.getTargetView().crownTower() && columns.crownTowerDurationMs() != -1
        ? columns.crownTowerDurationMs()
        : columns.durationMs();
  }

  /** The controller's copy on the area that caused a start, the area's own. */
  private BlowdartControllerRun.Copy copy(ActionHolder instigator) {
    ActionOwner cause = instigator == null ? null : instigator.getOwner();
    if (cause instanceof AreaEffectEntity area) {
      for (ActionInstance run : instigator.running()) {
        if (run instanceof BlowdartControllerRun.Copy copy
            && copy.getAction().name().equals(damage.getColumns().controller())
            && copy.getAreaId() == area.getId()) {
          return copy;
        }
      }
    }
    throw new UnsupportedOperationException(
        damage.name() + " started by something other than a poison area, not modelled");
  }

  /**
   * The amount for a start: the copy's stack's DamageList entry at the area's level, and on a crown
   * tower CrownDamageDamageMultiplier percent of it.
   */
  private int amount(ActionHolder instigator, BlowdartControllerRun.Copy copy) {
    AreaEffectEntity area = (AreaEffectEntity) instigator.getOwner();
    BlowdartDamage.Columns columns = damage.getColumns();
    int scaled =
        LevelScaling.scale(
            ScalingGlobals.standard(),
            columns.damageList().get(copy.getLevelIndex()),
            area.packedLevel(),
            ScalingMode.CARD_DAMAGE,
            area.getData().rarity());
    if (owner.getTargetView().crownTower() && columns.crownDamageMultiplier() >= 1) {
      scaled = scaled * columns.crownDamageMultiplier() / 100;
    }
    return scaled;
  }

  /** Whether a start caused by the given area is this run: the area's thrower is the run's. */
  @Override
  public boolean sameRun(ActionHolder instigator) {
    return copy(instigator).getThrower() == thrower;
  }

  /**
   * A further hit of an area: the time is put back and the amount raised when the new is higher.
   */
  @Override
  protected void retrigger(ActionHolder holder, ActionHolder instigator) {
    leftMs = duration();
    amount = Math.max(amount, amount(instigator, copy(instigator)));
  }

  @Override
  protected void update(ActionHolder holder) {
    clockMs += STEP_MS;
    leftMs -= STEP_MS;
    if (clockMs % damage.getColumns().hitSpeedMs() == 0 && owner.getHitPoints() != null) {
      owner.world.dealDamage(owner.getTargetView(), amount, 0, 0);
    }
    if (leftMs <= 0) {
      finish();
    }
  }
}
