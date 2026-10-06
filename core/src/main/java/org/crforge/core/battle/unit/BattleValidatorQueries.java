package org.crforge.core.battle.unit;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.crforge.core.pathfinding.combat.LevelScaling;
import org.crforge.core.pathfinding.combat.ScalingGlobals;
import org.crforge.core.pathfinding.target.TargetView;
import org.crforge.core.pathfinding.target.ValidatorQueries;

/**
 * The validator's questions about a target that only the battle can answer: the four the
 * pending-damage rule asks of a target with damage on its way. Every other question keeps the
 * standard answer of a one-against-one battle.
 *
 * <p>The rule refuses a projectile attacker a target the damage on its way will kill: the lethal
 * test must hold, and the damage must land soon enough. It keeps the target all the same when the
 * target will dash out of the damage's way in time, or is being healed while its row's full hit
 * points at its level exceed the damage.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the lethal test with its shield and untouchable gates, the dash test, the"
            + " healing test and the full hit points at the target's level. Held by the"
            + " references' selections and drops, and the shield gate by pending_shield_guards;"
            + " the dash and healing tests by no run; the damage reduction of the target's buffs"
            + " on the amount by BattleMonkTest alone, since no damage on its way to a Monk"
            + " under its ability is lethal in the references. An unkillable target spared the"
            + " rule: translated, held by no run.")
final class BattleValidatorQueries implements ValidatorQueries {

  private final BattleWorld world;

  BattleValidatorQueries(BattleWorld world) {
    this.world = world;
  }

  /**
   * The lethal test: nothing is lethal while the target's shield is up or while it is untouchable;
   * otherwise the damage, through the target's damage reduction and at least 1, is lethal when it
   * reaches the hit points it has left.
   */
  @Override
  public boolean pendingDamageAccepted(TargetView target, int amount) {
    WorldEntity entity = world.entityOf(target.getEntity());
    HitPoints hitPoints = entity.getHitPoints();
    if (hitPoints.getShield() > 0) {
      return false;
    }
    if (entity.untouchable()) {
      return false;
    }
    return Math.max(entity.getBuffs().damageReduction(amount), 1) >= hitPoints.getHitPoints();
  }

  /**
   * The dash test: the target's targeting component is on, its dash wind-up is running, its row is
   * immune to damage while it dashes, and the wind-up ends no later than the damage lands, so the
   * dash starts before the shot does.
   */
  @Override
  public boolean pendingDamageIsRecent(TargetView target, int duration) {
    WorldEntity entity = world.entityOf(target.getEntity());
    if (!entity.isActive(CharacterEntity.TARGETING_SLOT)) {
      return false;
    }
    int windup = entity.getTargeting().getDashWindupMs();
    return windup > 0 && entity.getData().dashImmuneToDamageTimeMs() >= 1 && windup <= duration;
  }

  /** The target's tag word holds UNKILLABLE, as a buff of the Berserker hero form sets it. */
  @Override
  public boolean unkillable(TargetView target) {
    return world.entityOf(target.getEntity()).unkillable();
  }

  /** The healing test: a buff the target carries heals at its first level. */
  @Override
  public boolean pendingDamageBuffHolds(TargetView target) {
    WorldEntity entity = world.entityOf(target.getEntity());
    for (BuffInstance instance : entity.getBuffs().items()) {
      if (instance.getBuff().healPerSecond() >= 1) {
        return true;
      }
    }
    return false;
  }

  /** The target row's full hit points at the level the validator hands over, the target's own. */
  @Override
  public int committedDamage(TargetView target, int key) {
    UnitData data = world.entityOf(target.getEntity()).getData();
    return LevelScaling.hitpoints(
        ScalingGlobals.standard(),
        data.hitpoints(),
        key,
        data.rarity(),
        data.king(),
        data.summonerTower());
  }
}
