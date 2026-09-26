package org.crforge.core.battle.action;

import lombok.Builder;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * A damage type: switches over the modifiers an ordinary hit applies, which a typed hit runs as its
 * pipeline, and the actions a hit of that type runs on its source and on its target.
 *
 * <p>The pipeline gives nothing to a target that takes no damage, then runs four stages, each
 * behind its own switch: the level scaling, the target's protection and the source's damage
 * multiplier, each floored at zero - the multiplier skipped without a source - and the target's
 * on-hit damage, with no floor. With no buffs modelled, the protection and the multiplier only
 * floor the amount at zero and the on-hit damage adds nothing.
 *
 * @param name the row's name
 * @param enableLevelScaling whether the source's level scales the amount
 * @param enableProtection whether the target's protection lowers it
 * @param enableDamageMultiplier whether the source's multiplier scales it
 * @param enableDamageOnHit whether the target's on-hit damage adds to it
 * @param acquireDamageId whether the hit takes a damage id from the battle's counter
 * @param actionOnSource run on the source after the hit, or null
 * @param actionOnTarget run on the target after the hit, or null
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the switches and their defaults, the no-damage target, the order of the stages,"
            + " their floors and the two actions. Supplied: no buff protects, multiplies or adds on"
            + " hit. Not modelled: the level-scaling stage, which is not established and is refused,"
            + " and the damage effect a hit shows.")
@Builder(toBuilder = true)
public record DamageType(
    String name,
    boolean enableLevelScaling,
    boolean enableProtection,
    boolean enableDamageMultiplier,
    boolean enableDamageOnHit,
    boolean acquireDamageId,
    BattleAction actionOnSource,
    BattleAction actionOnTarget) {

  /** A builder with every switch on, as a row that sets none of them has them. */
  public static DamageTypeBuilder builder() {
    return new DamageTypeBuilder()
        .enableLevelScaling(true)
        .enableProtection(true)
        .enableDamageMultiplier(true)
        .enableDamageOnHit(true)
        .acquireDamageId(true);
  }

  /**
   * The amount a hit of this type deals after its pipeline.
   *
   * @param amount the amount it was dealt with
   * @param targetTakesNoDamage true when the target carries the no-damage tag
   * @param hasSource true when the hit has a source object
   */
  public int pipeline(int amount, boolean targetTakesNoDamage, boolean hasSource) {
    if (targetTakesNoDamage) {
      return 0;
    }
    if (enableLevelScaling) {
      throw new UnsupportedOperationException(
          "the level scaling of a typed hit is not established; " + name + " asks for it");
    }
    if (enableProtection) {
      amount = Math.max(amount, 0);
    }
    if (enableDamageMultiplier && hasSource) {
      amount = Math.max(amount, 0);
    }
    return amount;
  }
}
