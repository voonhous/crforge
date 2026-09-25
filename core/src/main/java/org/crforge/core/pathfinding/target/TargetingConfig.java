package org.crforge.core.pathfinding.target;

import java.util.List;
import lombok.Builder;
import org.crforge.core.pathfinding.grid.PathfindingGlobals;

/**
 * The per-character columns the targeting code reads about one entity.
 *
 * <p>Every unit and every building carries one of these. The names follow the published data
 * columns wherever a column has one; the handful of flags whose published name is not documented
 * carry a behavioural name and say so in their Javadoc.
 *
 * <p>Distances and radii are integer game units (1000 per arena tile); times are milliseconds. A
 * value left unset by the builder is zero, false or null, which is what a character without that
 * column behaves like.
 *
 * @param configKey identity of the configuration row, compared by the validator against the
 *     excluded row of the game mode
 * @param range attack range measured from the unit's centre to the target's edge
 * @param minimumRange closest distance at which the unit may still attack, 0 for none
 * @param specialRange attack range of the special attack, 0 when there is none
 * @param collisionRadius the unit's own radius, added to the attack and sight radii when {@link
 *     PathfindingGlobals#ADD_CHARACTER_RANGE_TO_RADIUS} holds
 * @param sightRange distance at which the unit notices a candidate
 * @param sightRangeForCharacters distance at which characters notice this entity, used by
 *     tower-like candidates; 0 for an ordinary candidate
 * @param sightClip depth behind the unit beyond which a candidate is ignored, 0 for no clipping
 * @param sightClipSide width to the side beyond which a candidate is ignored, 0 for no clipping
 * @param attackSequenceMode 0 for an ordinary attacker; a non-zero mode runs an attack sequence and
 *     makes the unit walk closer while moving
 * @param attackSequenceLength number of steps in the attack sequence
 * @param attackSequenceStepIds step ids of the attack sequence, indexed by the component's step
 *     index
 * @param attackSequenceStepBeforeStart step id read when no step is active
 * @param attackSequenceEntries the steps themselves, indexed by step id
 * @param targetLowestHp true when the unit prefers the candidate with the fewest hit points
 * @param isBuilding true for a building, which does not clip its sight and does not lose range
 *     while moving
 * @param isSummonerTower true for a tower that spawns units, which some filters treat apart
 * @param buildingTarget true when the entity may be taken by a building-only attacker although it
 *     is not itself a building; such an attacker also ranks it level with a building
 * @param targetOnlyBuildings the unit attacks buildings only
 * @param targetOnlyTroops the unit attacks troops only
 * @param targetOnlyTowers the unit attacks towers only
 * @param targetOnlyKingTower the unit attacks the king tower only
 * @param doNotTargetTowers the unit never attacks towers
 * @param attacksGround the unit can attack ground targets
 * @param attacksAir the unit can attack air targets
 * @param suckElixirSpeed non-zero for an elixir drainer, which needs summoner towers among the
 *     non-tower candidates
 * @param dashCount number of dash hits, non-zero for a dashing unit
 * @param ignoreTargetsWithBuff true when the unit skips candidates carrying a named buff
 * @param deprioritizeTargetsWithBuff true when such candidates are ranked lower instead of skipped
 * @param morphKeepTarget true when the entity keeps its attacker while morphing
 * @param lifeTime lifetime in milliseconds, non-zero for a timed entity
 * @param hasProjectile true when the unit fires a projectile rather than hitting directly
 * @param hasSpecialProjectile true when the special attack fires its own projectile
 * @param hitSpeed milliseconds between two hits
 * @param loadTime milliseconds of wind-up before the first hit
 * @param loadFirstHit true when the wind-up runs before the first hit rather than after it
 * @param loadAfterRetarget true when the wind-up runs again after the unit takes a new target
 * @param resetHitTimerWhenNoTarget true when losing the target restarts the hit timer
 * @param reselectsEveryVisit flag that makes the visit drop its reference and re-select as soon as
 *     the retarget cooldown has run out; the published name of this flag is not documented
 * @param dropsReferenceAfterAttack flag that makes the visit drop its reference at the end of an
 *     attack tick; the published name of this flag is not documented
 * @param dashCooldown milliseconds between two dashes, non-zero for a dashing unit
 * @param dashMinRange closest distance at which a dash may start
 * @param dashMaxRange furthest distance at which a dash may start
 * @param dashDistance fixed dash distance, 0 to dash up to the target
 * @param dashLandingTime milliseconds the unit holds still after a dash
 * @param dashingPushback pushback applied to entities hit during a dash
 * @param dashStopsAtContact flag that stops a dash at the two entities' touching edges rather than
 *     at the target's centre; the published name of this flag is not documented
 * @param hasDashStartEffect true when starting a dash plays an effect
 * @param specialMinRange closest distance at which the special attack may start
 * @param specialLoadTime wind-up of the special attack in milliseconds
 * @param specialChargeTime milliseconds the special attack charges for
 * @param specialIgnoreBuildings true when the special attack is not used against buildings
 * @param specialAttacksToIgnoreList true when a special attack adds its target to the hit list
 * @param burst number of hits in a burst, 0 for a single hit
 * @param burstDelay milliseconds between two hits of a burst
 * @param burstKeepTarget true when the unit keeps its reference for the whole burst
 * @param multipleTargets number of targets a single attack hits, below two for a single target
 * @param uniqueMultipleTargets true when each of those targets must be a different entity
 * @param allTargetsHit true when a missing extra target falls back to the unit's own reference
 * @param attackDashTime milliseconds added to the attack timer when timing the hit of a dash
 *     attack. The column is carried across from the character data, but the block that reads it
 *     only announces the dash hit and is not ported, so nothing reads it today
 * @param attackFinishTime milliseconds a unit keeps attacking after losing its target
 * @param overrideAttackFinishTime true when {@code attackFinishTime} replaces the global default
 * @param keepTargetWithPendingDamage true when a target that has taken damage is kept although the
 *     ordinary check rejects it
 * @param jumpHeight jump height in game units, non-zero for a jumping unit
 * @param hasOnStartingAttackAction true when the first hit of an attack runs an action
 * @param hasHitEffect true when a dash hit and a missing extra target play an effect; the effect
 *     itself is out of scope here, so only its presence is carried
 * @param hitEffectVariant value passed along with that effect; its meaning is not documented
 * @param burstAffectAnimation true when a running burst freezes the attack time, so the
 *     attack-timer advance steps only the burst timer while one runs; no published row sets it
 * @param stopTimeAfterAttack milliseconds a hit holds the unit still afterwards, stored into the
 *     attack block timer by every hit; 0 for a unit that carries on at once
 * @param crownTowerDamagePercent percentage, as a difference from the plain damage, that a hit
 *     deals to a crown tower: 0 leaves the damage alone, -70 takes 70 percent off it
 * @param areaDamageRadius for a unit without a projectile, the radius of the circle each landed hit
 *     damages instead of its target alone; 0 for one that hits its target alone
 * @param selfAsAoeCenter true when that circle is centred on the unit itself rather than on where
 *     its reference stood at the start of the visit
 */
@Builder(toBuilder = true)
public record TargetingConfig(
    String configKey,
    int range,
    int minimumRange,
    int specialRange,
    int collisionRadius,
    int sightRange,
    int sightRangeForCharacters,
    int sightClip,
    int sightClipSide,
    int attackSequenceMode,
    int attackSequenceLength,
    List<Integer> attackSequenceStepIds,
    int attackSequenceStepBeforeStart,
    List<AttackSequenceEntry> attackSequenceEntries,
    boolean targetLowestHp,
    boolean isBuilding,
    boolean isSummonerTower,
    boolean buildingTarget,
    boolean targetOnlyBuildings,
    boolean targetOnlyTroops,
    boolean targetOnlyTowers,
    boolean targetOnlyKingTower,
    boolean doNotTargetTowers,
    boolean attacksGround,
    boolean attacksAir,
    int suckElixirSpeed,
    int dashCount,
    boolean ignoreTargetsWithBuff,
    boolean deprioritizeTargetsWithBuff,
    boolean morphKeepTarget,
    int lifeTime,
    boolean hasProjectile,
    boolean hasSpecialProjectile,
    int hitSpeed,
    int loadTime,
    boolean loadFirstHit,
    boolean loadAfterRetarget,
    boolean resetHitTimerWhenNoTarget,
    boolean reselectsEveryVisit,
    boolean dropsReferenceAfterAttack,
    int dashCooldown,
    int dashMinRange,
    int dashMaxRange,
    int dashDistance,
    int dashLandingTime,
    int dashingPushback,
    boolean dashStopsAtContact,
    boolean hasDashStartEffect,
    int specialMinRange,
    int specialLoadTime,
    int specialChargeTime,
    boolean specialIgnoreBuildings,
    boolean specialAttacksToIgnoreList,
    int burst,
    int burstDelay,
    boolean burstKeepTarget,
    int multipleTargets,
    boolean uniqueMultipleTargets,
    boolean allTargetsHit,
    int attackDashTime,
    int attackFinishTime,
    boolean overrideAttackFinishTime,
    boolean keepTargetWithPendingDamage,
    int jumpHeight,
    boolean hasOnStartingAttackAction,
    boolean hasHitEffect,
    int hitEffectVariant,
    boolean burstAffectAnimation,
    int stopTimeAfterAttack,
    int crownTowerDamagePercent,
    int areaDamageRadius,
    boolean selfAsAoeCenter) {

  /** Sight clip depth every ordinary character carries in the standard mode. */
  public static final int STANDARD_SIGHT_CLIP = 1000;

  /**
   * The configuration of an ordinary attacking troop: one attack sequence step with no overrides,
   * the standard sight clip, no dash, no burst and a single target.
   *
   * @param rawRange attack range as published, in game units
   * @param sightRange sight range as published, in game units
   * @param collisionRadius the unit's own collision radius, in game units
   * @param hitSpeedMs milliseconds between two hits
   * @param loadTimeMs milliseconds of wind-up
   * @param attacksGround whether the unit can hit ground targets
   * @param attacksAir whether the unit can hit air targets
   */
  public static TargetingConfig forUnit(
      int rawRange,
      int sightRange,
      int collisionRadius,
      int hitSpeedMs,
      int loadTimeMs,
      boolean attacksGround,
      boolean attacksAir) {
    return TargetingConfig.builder()
        .configKey("unit")
        .range(rawRange)
        .sightRange(sightRange)
        .collisionRadius(collisionRadius)
        .hitSpeed(hitSpeedMs)
        .loadTime(loadTimeMs)
        .attacksGround(attacksGround)
        .attacksAir(attacksAir)
        .sightClip(STANDARD_SIGHT_CLIP)
        .attackSequenceLength(1)
        .attackSequenceStepIds(List.of(0))
        .attackSequenceEntries(List.of(AttackSequenceEntry.none()))
        .build();
  }

  /**
   * The configuration of a crown tower: a building that attacks both layers and that characters
   * notice from its own sight range.
   *
   * @param configKey identity of the tower's configuration row
   * @param rawRange attack range as published, in game units
   * @param sightRange sight range as published, in game units
   * @param collisionRadius the tower's collision radius, in game units
   * @param hitSpeedMs milliseconds between two hits
   * @param loadTimeMs milliseconds of wind-up
   * @param summonerTower whether the tower spawns units
   */
  public static TargetingConfig tower(
      String configKey,
      int rawRange,
      int sightRange,
      int collisionRadius,
      int hitSpeedMs,
      int loadTimeMs,
      boolean summonerTower) {
    return TargetingConfig.builder()
        .configKey(configKey)
        .range(rawRange)
        .sightRange(sightRange)
        .collisionRadius(collisionRadius)
        .hitSpeed(hitSpeedMs)
        .loadTime(loadTimeMs)
        .attacksGround(true)
        .attacksAir(true)
        .isBuilding(true)
        .isSummonerTower(summonerTower)
        .sightClip(STANDARD_SIGHT_CLIP)
        .attackSequenceLength(1)
        .attackSequenceStepIds(List.of(0))
        .attackSequenceEntries(List.of(AttackSequenceEntry.none()))
        .build();
  }

  /** The attack sequence step with the given id, or null when there is no such step. */
  public AttackSequenceEntry entry(int stepId) {
    if (attackSequenceEntries == null || stepId < 0 || stepId >= attackSequenceEntries.size()) {
      return null;
    }
    return attackSequenceEntries.get(stepId);
  }

  /** The step id stored at the given index of the attack sequence. */
  public int stepId(int index) {
    if (attackSequenceStepIds == null || index < 0 || index >= attackSequenceStepIds.size()) {
      return attackSequenceStepBeforeStart;
    }
    return attackSequenceStepIds.get(index);
  }
}
