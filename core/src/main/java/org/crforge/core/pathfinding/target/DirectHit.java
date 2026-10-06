package org.crforge.core.pathfinding.target;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * How a landed hit without a projectile reaches its target, once the hit application has chosen the
 * damage.
 *
 * <p>Two damages are carried from here on: the plain one and the crown-tower one, which is the
 * plain damage scaled by the owner's crown-tower percentage. Which of the two the target takes is
 * the target's own answer. The hit also carries an id, counted by the battle, and the direction it
 * came from, which the target stores if it kills it.
 *
 * <p>A hit that was cancelled, or that has no target left to land on, gets as far as the hit id and
 * no further: the id is counted either way.
 *
 * <p>A unit with an area radius does not hit its target at all: every landed hit damages the circle
 * around the unit, for a unit that centres its area on itself, or around where its reference stood
 * at the start of the visit, and the target takes its share only as one of the victims.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the crown-tower damage from the plain damage, the hit id counted before the"
            + " target is read, the direction from the owner to the reference's last position, the"
            + " choice between the two damages by the target's own answer, and that a cancelled"
            + " hit touches nothing, and the area of a unit with a radius in place of the target,"
            + " centred on the unit or on the reference's last position, and the owner's recoil"
            + " by its attack pushback after the damage, away from the reference's last position."
            + " Held by every hit of the kill run, every area of the Valkyrie runs and the evolved"
            + " Battle Ram's tower hits. Not modelled: the damage"
            + " effect and the fallback, the attacker's buffs changing either"
            + " damage, the attack sequence step's pushback on a target that moves (refused),"
            + " the elixir a drainer moves and the"
            + " area-effect entity a hit may create.")
public final class DirectHit {

  private DirectHit() {
    // Utility class
  }

  /**
   * Resolves one hit that lands directly on its target.
   *
   * @param t the attacker's targeting component
   * @param target what the hit is aimed at, or null when the attacker has already given it up
   * @param damage the plain damage the hit application chose
   * @param missed true when the hit was cancelled and lands on nothing
   * @param queries where the hit id comes from and where the damage goes
   */
  public static void resolve(
      TargetingState t, TargetView target, int damage, boolean missed, HitQueries queries) {
    TargetingConfig cfg = t.getConfig();
    int crownTowerDamage = crownTowerDamage(cfg.crownTowerDamagePercent(), damage);
    // Whether the owner may recoil after the hit is decided by its movement component as it stands
    // now, before the hit id is counted and the damage dealt.
    boolean ownerMoves = queries.ownerMovementOn();
    int hitId = queries.nextHitId();
    // The owner's listening actions change both damages of a hit that lands, after the crown-tower
    // damage was worked out from the plain one.
    if (queries.hitListeners() && !missed) {
      damage = queries.listenedDamage(damage, hitId, false);
      crownTowerDamage = queries.listenedDamage(crownTowerDamage, hitId, true);
    }
    if (cfg.areaDamageRadius() >= 1 && !missed) {
      // A unit with an area damages the circle instead of its target, around itself or around
      // where its reference stood at the start of the visit, whether or not a target is left.
      int x = cfg.selfAsAoeCenter() ? t.getOwner().getX() : t.getLastReferenceX();
      int y = cfg.selfAsAoeCenter() ? t.getOwner().getY() : t.getLastReferenceY();
      queries.areaDamage(x, y, cfg.areaDamageRadius(), damage, crownTowerDamage, hitId);
      queries.stepPushback(null);
      return;
    }
    if (target == null || missed) {
      return;
    }
    // The hit comes from the owner and is aimed at where the reference stood at the start of the
    // visit, which is the direction the target stores if this hit kills it.
    int directionX = t.getLastReferenceX() - t.getOwner().getX();
    int directionY = t.getLastReferenceY() - t.getOwner().getY();
    // The step's MeleePushback reaches the direct hit ungated: the target, when it has a movement
    // component, is pushed from the owner before the damage is dealt.
    queries.stepPushback(target);
    int dealt = target.isCrownTowerTarget() ? crownTowerDamage : damage;
    queries.dealDamage(target, dealt, hitId, directionX, directionY);
    queries.directHitDealt();
    // Last, an owner with a movement component recoils by its own attack pushback, away from where
    // its reference stood at the start of the visit, as it does after each projectile it launches.
    // The area branch above never reaches it.
    if (ownerMoves) {
      queries.attackRecoil(t.getLastReferenceX(), t.getLastReferenceY());
    }
  }

  /**
   * The damage a crown tower takes: the plain damage scaled by the owner's crown-tower percentage,
   * rounded up. The percentage is a difference from the plain damage and never takes more than the
   * whole of it away.
   *
   * @param percent the owner's crown-tower percentage; 0 leaves the damage alone
   * @param damage the plain damage
   */
  public static int crownTowerDamage(int percent, int damage) {
    int scale = Math.max(percent, -100) + 100;
    return FixedMath.divOrZero(scale * damage + 99, 100);
  }
}
