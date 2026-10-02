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

  /**
   * This configuration with the morph a unit takes as it arrives at its spawn destination.
   *
   * @param morph true for a unit that morphs as it surfaces
   */
  public StateVisitConfig withSpawnPathfindMorph(boolean morph) {
    return new StateVisitConfig(
        deployTimeMs,
        dashLandingTimeMs,
        dashImmuneToDamageTimeMs,
        flyingHeight,
        ingamePathfindEndsInDeploy,
        morph,
        onIngamePathfindStopAction,
        kamikaze,
        kingTowerMiddle,
        neutralObject,
        hideBeforeFirstHit,
        hidesWhenNotAttacking,
        deployTimeAffectedByCharacterSpeed,
        abilityPresent,
        abilityHoldsState,
        abilityStateFlags);
  }

  /**
   * This configuration with what a unit does as it arrives at a point its ability sent it to.
   *
   * @param endsInDeploy true for a unit that deploys again on arrival rather than walking on
   */
  public StateVisitConfig withIngamePathfindArrival(boolean endsInDeploy) {
    return new StateVisitConfig(
        deployTimeMs,
        dashLandingTimeMs,
        dashImmuneToDamageTimeMs,
        flyingHeight,
        endsInDeploy,
        spawnPathfindMorph,
        onIngamePathfindStopAction,
        kamikaze,
        kingTowerMiddle,
        neutralObject,
        hideBeforeFirstHit,
        hidesWhenNotAttacking,
        deployTimeAffectedByCharacterSpeed,
        abilityPresent,
        abilityHoldsState,
        abilityStateFlags);
  }

  /**
   * This configuration with the row's hiding while it does not attack, whose deploy end runs its
   * targeting visit and whose every visit from then on runs its hide handler.
   *
   * @param hides true for a row that hides while it does not attack
   */
  public StateVisitConfig withHidesWhenNotAttacking(boolean hides) {
    return new StateVisitConfig(
        deployTimeMs,
        dashLandingTimeMs,
        dashImmuneToDamageTimeMs,
        flyingHeight,
        ingamePathfindEndsInDeploy,
        spawnPathfindMorph,
        onIngamePathfindStopAction,
        kamikaze,
        kingTowerMiddle,
        neutralObject,
        hideBeforeFirstHit,
        hides,
        deployTimeAffectedByCharacterSpeed,
        abilityPresent,
        abilityHoldsState,
        abilityStateFlags);
  }

  /**
   * This configuration with a dash: the time the entity is held after its dash lands, and how long
   * nothing can hurt it after the dash.
   *
   * @param landingTimeMs the hold after the landing; 0 to walk on at once
   * @param immuneTimeMs the immunity after the dash; 0 for none
   */
  public StateVisitConfig withDash(int landingTimeMs, int immuneTimeMs) {
    return new StateVisitConfig(
        deployTimeMs,
        landingTimeMs,
        immuneTimeMs,
        flyingHeight,
        ingamePathfindEndsInDeploy,
        spawnPathfindMorph,
        onIngamePathfindStopAction,
        kamikaze,
        kingTowerMiddle,
        neutralObject,
        hideBeforeFirstHit,
        hidesWhenNotAttacking,
        deployTimeAffectedByCharacterSpeed,
        abilityPresent,
        abilityHoldsState,
        abilityStateFlags);
  }
}
