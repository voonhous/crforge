package org.crforge.core.pathfinding.target;

/**
 * What applying a hit needs from outside the targeting component: the damage of one hit at the
 * owner's level, the battle's hit ids, and where the damage of a hit that lands is sent.
 *
 * <p>The two damages are asked for rather than carried because the standard game reads them from
 * the owner's data row at the owner's level at the moment of the hit.
 */
public interface HitQueries {

  /** Damage of one ordinary hit, at the owner's level. */
  int damage();

  /**
   * Damage of one special hit, at the owner's level; the ordinary damage for a unit without one.
   */
  default int specialDamage() {
    return damage();
  }

  /** True when the owner may not attack at all, which discards the hit before any other step. */
  default boolean attackForbidden() {
    return false;
  }

  /**
   * The id of the hit about to be dealt, counted by the battle. One hit carries one id through
   * every target it reaches, which is how a target tells two hits apart.
   */
  default int nextHitId() {
    return 0;
  }

  /**
   * Deals the damage of a landed hit to one target.
   *
   * @param target what the hit landed on
   * @param damage hit points to take, already the crown-tower damage where the target asks for it
   * @param hitId the id this hit carries
   * @param directionX direction of the hit along the arena's width
   * @param directionY direction of the hit along the arena's length
   */
  void dealDamage(TargetView target, int damage, int hitId, int directionX, int directionY);
}
