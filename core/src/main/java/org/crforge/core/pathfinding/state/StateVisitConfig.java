package org.crforge.core.pathfinding.state;

/**
 * The card configuration columns the entity state visit and its resume helper read.
 *
 * <p>Times are milliseconds and distances are game units. A plain ground unit carries only a deploy
 * time, which is what {@link #forGroundUnit(int)} returns.
 *
 * @param deployTimeMs how long the entity holds still after it is placed
 * @param dashLandingTimeMs how long the entity is held after a dash lands before it moves again
 * @param dashImmuneToDamageTimeMs how long the entity is immune after a dash
 * @param flyingHeight height the entity is placed at when it arrives at a pathfind destination
 * @param ingamePathfindEndsInDeploy whether arriving at a mid-match destination starts a deploy
 *     countdown instead of movement
 * @param spawnPathfindMorph whether arriving at a spawn destination morphs the entity
 * @param onIngamePathfindStopAction action run on arriving at a mid-match destination, or null
 * @param kamikaze whether the entity destroys itself
 * @param kingTowerMiddle whether the entity is the middle of a king tower
 * @param neutralObject whether the entity belongs to no side
 * @param hideBeforeFirstHit whether the entity is hidden until it first attacks
 * @param hidesWhenNotAttacking whether the entity hides whenever it is not attacking
 * @param deployTimeAffectedByCharacterSpeed whether the deploy countdown is scaled by the entity's
 *     speed modifiers rather than stepping a flat 50 per tick
 * @param abilityPresent whether the entity carries an ability at all
 * @param abilityHoldsState whether that ability keeps the entity in the casting state until it ends
 * @param abilityStateFlags flags the ability sets on the entity while it counts down
 */
public record StateVisitConfig(
    int deployTimeMs,
    int dashLandingTimeMs,
    int dashImmuneToDamageTimeMs,
    int flyingHeight,
    boolean ingamePathfindEndsInDeploy,
    boolean spawnPathfindMorph,
    String onIngamePathfindStopAction,
    boolean kamikaze,
    boolean kingTowerMiddle,
    boolean neutralObject,
    boolean hideBeforeFirstHit,
    boolean hidesWhenNotAttacking,
    boolean deployTimeAffectedByCharacterSpeed,
    boolean abilityPresent,
    boolean abilityHoldsState,
    long abilityStateFlags) {

  /**
   * The configuration of a plain ground unit: a deploy time and nothing else.
   *
   * @param deployTimeMs how long the entity holds still after it is placed
   */
  public static StateVisitConfig forGroundUnit(int deployTimeMs) {
    return new StateVisitConfig(
        deployTimeMs,
        0,
        0,
        0,
        false,
        false,
        null,
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        0L);
  }
}
