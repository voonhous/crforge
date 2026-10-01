package org.crforge.core.battle.unit;

import java.util.List;
import lombok.Builder;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.pathfinding.combat.RarityTable;

/**
 * The published columns of one unit or building that the battle reads, in the units the columns are
 * published in: distances in game units (1000 per tile), times in whole milliseconds, speed in game
 * units per 50 ms step.
 *
 * <p>Nothing here is converted. A speed of 60 is 60 game units per step, a hit speed of 1200 is
 * 1200 ms; the battle steps integer milliseconds, so no column ever passes through a float.
 *
 * @param name the unit's data name, which is also the identity of its configuration row
 * @param speed movement budget in game units per step; 0 for a building
 * @param range attack range
 * @param sightRange how far the unit notices targets
 * @param collisionRadius radius of the unit's collision circle
 * @param mass weight in pushes; 0 for a building
 * @param hitSpeedMs time between two hits
 * @param loadTimeMs wind-up before a hit
 * @param deployTimeMs countdown between placement and the first move
 * @param attacksGround whether the unit can hit ground targets
 * @param attacksAir whether the unit can hit air targets
 * @param air whether the unit flies
 * @param building whether the unit is a building
 * @param king whether the unit is a king tower
 * @param summonerTower whether the unit is a princess tower
 * @param hitpoints hit points at the first level
 * @param damage damage per hit at the first level
 * @param crownTowerDamagePercent difference, in percent, between what a hit deals to a crown tower
 *     and what it deals to anything else; 0 for a unit that hits both alike
 * @param rarity the rarity whose table scales the unit's stats by level: the unit's own row's,
 *     which is what the level a unit is created at is packed against
 * @param projectile the projectile the unit fires instead of hitting directly, or null for a unit
 *     that hits directly
 * @param customFirstProjectile the projectile the first of an attack's projectiles is instead, or
 *     null for none
 * @param projectileStartRadius how far along the line to the target a projectile leaves the unit
 * @param projectileStartZ how high above the unit a projectile leaves it
 * @param projectileYOffset how far along the arena's length a projectile's start is shifted; the
 *     top side's is mirrored
 * @param multipleProjectiles how many projectiles one attack launches; 0 and 1 both mean one
 * @param areaDamageRadius for a unit that hits directly, the radius of the circle each hit damages,
 *     0 for one that hits its target alone; for a unit that fires several projectiles, their spread
 * @param selfAsAoeCenter true when the circle a hit damages is centred on the unit itself rather
 *     than on its target
 * @param overrideAttackFinishTime true when the unit waits its own time, not the global one, before
 *     it takes a new target after losing one
 * @param attackFinishTimeMs that own wait
 * @param spawnRadius the radius of the formation a card places the unit in, when the card sets
 *     none; 0 for none, which falls back to the collision radius
 * @param spawnAngleShift degrees its spawner's ring is turned by, with the angle it faces; for an
 *     attached unit, degrees its place around its parent is turned by
 * @param flyingHeight how high the unit flies; 0 for a ground unit
 * @param spawnPathfindSpeed the speed of a unit that walks to its placement; 0 for one placed at
 *     once
 * @param spawnPathfindMorph the row a unit that walks to its placement morphs into as it arrives,
 *     or null for none
 * @param spawnAreaObject the area effect the unit makes each time it enters the deploying state
 *     through its setter, or null for none
 * @param spawnPushback the radius of the push the unit makes each time it enters the deploying
 *     state through its setter: the enemies standing within it are pushed away; 0 for none
 * @param spawnPushbackRadius how far that push sends each of them; 0 for none
 * @param tileSizeOverride the tiles a building's footprint spans, when not derived from its
 *     collision radius; 0 for none
 * @param noDeploySizeW the width, in tiles, of the box around a building that the other side may
 *     not place in; 0 for none
 * @param noDeploySizeH the height of that box, in tiles
 * @param attackPushBack how far the unit pushes itself back, in game units, away from the aim of
 *     each projectile it launches; 0 for none
 * @param ignorePushback true when the unit's row ignores pushback
 * @param onStartingAction the action row the unit runs when it joins the battle, or null
 * @param onDeathAction the action row the unit runs when it dies, or null
 * @param onKilledAction the action row the unit runs when it is killed, or null
 * @param deathDamage damage the unit deals around itself as it dies, at the first level; 0 for none
 * @param deathDamageRadius the radius of that damage
 * @param deathPushBack how far that damage pushes what it hits; 0 for no push
 * @param deathSpawnCharacter the row of the units the unit spawns as it dies, or null for none
 * @param deathSpawnCount how many it spawns: the column, at least one when the row spawns
 * @param deathSpawnRadius the radius of the ring they stand on; 0 for none
 * @param deathSpawnDeployTimeMs the deploy time they start with; 0 for their own row's rule
 * @param deathAreaEffect the area effect the unit leaves where it dies, or null for none
 * @param deathSpawnPushback true when the death spawn's children are put on the unit and fly back
 *     to their ring points
 * @param deathSpawnMinRadius the least radius a death spawn's child is drawn at; 0 for the ring's
 *     own radius
 * @param unmodelledDeathColumns the columns of what the unit does as it dies that its row sets and
 *     the battle does not model: a second or third death spawn, a death projectile, a starting buff
 *     taken back, a spawned area object ended, and the parts of the death spawn's placement that
 *     are not established
 * @param champion true for a champion: the unit's ability row makes it one, as an ability row does
 *     unless it says otherwise
 * @param ability the unit's ability row, or null for a unit without one
 * @param globalId the id the game gives the unit's row, which expressions compare it by
 * @param lifeTimeMs how long the unit lives before its hit points run down; 0 for no limit
 * @param targetOnlyBuildings true for a unit that attacks buildings only
 * @param attackSequence the unit's attack sequence; a row without one keeps a single element
 * @param onStartingAttackAction the action row the unit runs as it starts an attack and at each
 *     hit, or null
 * @param onAttackAction the action row the unit runs as it attacks, or null
 * @param minimumRange the closest distance to a target's edge it may attack from; 0 for none
 * @param sightClip how far short of its reach behind the unit a candidate may stand, as the loader
 *     leaves it: 1000 for a row that leaves it 0, 0 for a building
 * @param sightClipSide how far short of its reach to the unit's side a candidate may stand; 0 for
 *     no clip
 * @param loadFirstHit true when the unit winds up its load before its first hit and again after
 *     each, rather than having it credited
 * @param spawnCharacter the row of the units its spawner makes while it lives, or null for none
 * @param spawnNumber how many children one wave of its spawner makes
 * @param spawnIntervalMs the time between the children of a wave; 0 to make a wave at once
 * @param spawnPauseTimeMs the time between waves
 * @param spawnStartTimeMs the time before the first wave, counted from the end of its deploy
 * @param manaCollectAmount the whole elixir it pays its king each time its collector's timer runs
 *     out; 0 for no collector
 * @param manaGenerateTimeMs the time its collector counts between payouts
 * @param manaOnDeathForOpponent the elixir its death pays the side that killed it, in thousandths
 *     of an elixir as the row writes it; 0 for none
 * @param ignoreBuffs the buff rows it takes nothing of
 * @param shieldHitpoints its shield at the first level; 0 for none
 * @param stopMovementAfterMs how long it walks before it stops for a while; 0 for never
 * @param waitMs how long it stands each time it stops
 * @param deathInheritIgnoreList true when its death spawn joins every id list that holds it, and a
 *     rider it lets go joins every id list that holds it too
 * @param spawnAttach true when its spawner's children ride on it: made as it starts deploying and
 *     attached to it from then on
 * @param spawnMaxAngle for an attached unit, the arc its share of its parent's ring is taken from
 * @param spawnAttachMaxRotation for an attached unit, how far, in degrees, it may face away from
 *     its parent's heading while it attacks; 0 for no limit
 * @param chargeRange a tenth of the distance it must walk to be fully charged; 0 for a unit that
 *     does not charge
 * @param chargeSpeedMultiplier the percent its speed is scaled by once fully charged
 * @param damageSpecial the damage of its charged hit at the first level
 * @param keepChargingAfterAttack true when its charged hit does not reset the charge
 * @param jumpEnabled true for a unit that jumps the river: its route may cross water, and it leaps
 *     over the water in its way
 * @param jumpHeight the height of its jump arc
 * @param jumpSpeed its speed while it jumps, in game units per tick
 * @param kamikaze true for a unit whose hit destroys it
 * @param multipleTargets how many targets one hit reaches, below two for one
 * @param allTargetsHit true when an extra target the lookup does not find is its reference again
 * @param uniqueMultipleTargets true when the extra targets are drawn from one list, each hit once
 * @param buffOnDamage the buff its hit applies to what it hits, or null for none
 * @param buffOnDamageTimeMs how long that buff lasts on what it hits
 * @param groupMaxSize the most children of its row a spawn group holds; 0 for no limit
 * @param buffAfterHits the buffs the unit gives itself after so many hits; empty for none
 * @param buffAfterHitsCounts how many hits each takes
 * @param buffAfterHitsTimesMs how long each lasts
 * @param dashCooldown the wind-up before a dash, in milliseconds; 0 for a unit that does not dash
 * @param dashMinRange how far beyond its own collision radius a target must be for a dash
 * @param dashMaxRange how far a target may be for a dash
 * @param dashDamage the damage its dash deals as it lands, at the first level
 * @param dashRadius the radius that damage covers around the landing point; 0 to hit its target
 *     alone
 * @param dashPushBack how far that damage pushes what it hits
 * @param dashLandingTimeMs how long it is held after its dash lands; 0 to walk on at once
 * @param dashConstantTimeMs how long its dash flies, with a fixed height profile; 0 for a dash that
 *     flies until it reaches its target
 * @param dashImmuneToDamageTimeMs how long, after its dash, nothing can hurt it; while it dashes
 *     with one, nothing can
 * @param dashToTargetRadius true when its dash aims at its target's edge rather than its centre
 * @param targetOnlyTroops true for a unit that attacks troops only, never a building
 * @param ignoreTargetsWithBuff the buff row whose carriers it passes over as targets, or null
 * @param deprioritizeTargetsWithBuff true when it ranks such carriers lower instead of passing over
 *     them
 * @param hovering true for a unit that hovers: its route may cross water, at the water cost, and a
 *     push never moves it off water
 * @param buffWhenNotAttacking the buff row it takes while it is not attacking, or null for none
 * @param buffWhenNotAttackingTimeMs how long after its attack ends, with no reference in its attack
 *     range, it takes that buff again
 * @param buffWhenNotAttackingUseAttackRange true when a reference within its attack range holds
 *     that countdown
 * @param startWithBuffWhenNotAttacking true when it takes that buff as it is created; the loader
 *     makes it true for a row that leaves it empty
 * @param allowAreaDamageWhenInvisible true when an area's damage reaches it while it is invisible
 * @param areaEffectOnHit the area effect each of its direct hits makes where it stands, or null for
 *     none
 * @param keepTargetWithPendingDamage true when, having hit, it keeps a reference that its own shots
 *     in flight will kill rather than turning to another; the loader's default is true, and no row
 *     sets it false
 * @param hidesWhenNotAttacking true when it hides while it does not attack, by a hide counter its
 *     state visit steps from the end of its deploy
 * @param hideTimeMs the hide counter's value at which it is hidden, the time it takes to go down
 * @param upTimeMs the time it takes to come back up from hidden
 * @param unmodelledColumns the columns its row sets that the battle does not model, which refuse it
 *     as it is created: a shield, hiding before its first hit, a buff at a share of its hit points,
 *     elixir, and the parts of a spawner that are not established
 */
@Builder(toBuilder = true)
public record UnitData(
    String name,
    int speed,
    int range,
    int sightRange,
    int collisionRadius,
    int mass,
    int hitSpeedMs,
    int loadTimeMs,
    int deployTimeMs,
    boolean attacksGround,
    boolean attacksAir,
    boolean air,
    boolean building,
    boolean king,
    boolean summonerTower,
    int hitpoints,
    int damage,
    int crownTowerDamagePercent,
    RarityTable rarity,
    ProjectileData projectile,
    ProjectileData customFirstProjectile,
    int projectileStartRadius,
    int projectileStartZ,
    int projectileYOffset,
    int multipleProjectiles,
    int areaDamageRadius,
    boolean selfAsAoeCenter,
    boolean overrideAttackFinishTime,
    int attackFinishTimeMs,
    int spawnRadius,
    int spawnAngleShift,
    int flyingHeight,
    int spawnPathfindSpeed,
    String spawnPathfindMorph,
    String spawnAreaObject,
    int spawnPushback,
    int spawnPushbackRadius,
    int tileSizeOverride,
    int noDeploySizeW,
    int noDeploySizeH,
    int attackPushBack,
    boolean ignorePushback,
    String onStartingAction,
    String onDeathAction,
    String onKilledAction,
    int deathDamage,
    int deathDamageRadius,
    int deathPushBack,
    String deathSpawnCharacter,
    int deathSpawnCount,
    int deathSpawnRadius,
    int deathSpawnDeployTimeMs,
    String deathAreaEffect,
    boolean deathSpawnPushback,
    int deathSpawnMinRadius,
    List<String> unmodelledDeathColumns,
    boolean champion,
    AbilityData ability,
    int globalId,
    int lifeTimeMs,
    boolean targetOnlyBuildings,
    AttackSequence attackSequence,
    String onStartingAttackAction,
    String onAttackAction,
    int minimumRange,
    int sightClip,
    int sightClipSide,
    boolean loadFirstHit,
    String spawnCharacter,
    int spawnNumber,
    int spawnIntervalMs,
    int spawnPauseTimeMs,
    int spawnStartTimeMs,
    int manaCollectAmount,
    int manaGenerateTimeMs,
    int manaOnDeathForOpponent,
    List<String> ignoreBuffs,
    int shieldHitpoints,
    int stopMovementAfterMs,
    int waitMs,
    boolean deathInheritIgnoreList,
    boolean spawnAttach,
    int spawnMaxAngle,
    int spawnAttachMaxRotation,
    int chargeRange,
    int chargeSpeedMultiplier,
    int damageSpecial,
    boolean keepChargingAfterAttack,
    boolean jumpEnabled,
    int jumpHeight,
    int jumpSpeed,
    boolean kamikaze,
    int multipleTargets,
    boolean allTargetsHit,
    boolean uniqueMultipleTargets,
    String buffOnDamage,
    int buffOnDamageTimeMs,
    int groupMaxSize,
    List<String> buffAfterHits,
    List<Integer> buffAfterHitsCounts,
    List<Integer> buffAfterHitsTimesMs,
    int dashCooldown,
    int dashMinRange,
    int dashMaxRange,
    int dashDamage,
    int dashRadius,
    int dashPushBack,
    int dashLandingTimeMs,
    int dashConstantTimeMs,
    int dashImmuneToDamageTimeMs,
    boolean dashToTargetRadius,
    boolean targetOnlyTroops,
    String ignoreTargetsWithBuff,
    boolean deprioritizeTargetsWithBuff,
    boolean hovering,
    String buffWhenNotAttacking,
    int buffWhenNotAttackingTimeMs,
    boolean buffWhenNotAttackingUseAttackRange,
    boolean startWithBuffWhenNotAttacking,
    boolean allowAreaDamageWhenInvisible,
    String areaEffectOnHit,
    boolean keepTargetWithPendingDamage,
    boolean hidesWhenNotAttacking,
    int hideTimeMs,
    int upTimeMs,
    List<String> unmodelledColumns) {

  public UnitData {
    unmodelledColumns = unmodelledColumns == null ? List.of() : List.copyOf(unmodelledColumns);
    ignoreBuffs = ignoreBuffs == null ? List.of() : List.copyOf(ignoreBuffs);
    buffAfterHits = buffAfterHits == null ? List.of() : List.copyOf(buffAfterHits);
    buffAfterHitsCounts =
        buffAfterHitsCounts == null ? List.of() : List.copyOf(buffAfterHitsCounts);
    buffAfterHitsTimesMs =
        buffAfterHitsTimesMs == null ? List.of() : List.copyOf(buffAfterHitsTimesMs);
    unmodelledDeathColumns =
        unmodelledDeathColumns == null ? List.of() : List.copyOf(unmodelledDeathColumns);
    attackSequence = attackSequence == null ? AttackSequence.NONE : attackSequence;
  }

  /** True for a unit that fires a projectile rather than hitting its target directly. */
  public boolean hasProjectile() {
    return projectile != null;
  }

  /**
   * True for a unit that pushes the enemies around it each time it enters the deploying state
   * through its setter: one with both a push radius and a push distance.
   */
  public boolean pushesOnDeploy() {
    return spawnPushback != 0 && spawnPushbackRadius != 0;
  }
}
