package org.crforge.core.battle.projectile;

import java.util.List;
import lombok.Builder;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.combat.PackedLevel;
import org.crforge.core.pathfinding.combat.RarityTable;
import org.crforge.core.pathfinding.combat.ScalingMode;

/**
 * The published columns of one projectile row that the battle reads, unconverted: distances in game
 * units (1000 per tile), times in whole milliseconds, speed in game units per 50 ms step.
 *
 * @param name the row's name, which is also the identity of the projectile's configuration
 * @param rarity the row's own rarity; the launcher's level is re-based on it when the projectile is
 *     launched, and the damage is scaled at that level
 * @param speed game units flown per step
 * @param gravity the parabola the flight's height follows; 0 for a straight flight
 * @param homing true when the projectile follows its target: the aim is re-pinned onto the target
 *     every step, and a target that leaves the battle leaves the aim where it last stood
 * @param homingTimeMs how long a projectile that homes for a limited time keeps re-aiming; 0 for
 *     one that never does
 * @param homingMinDistance the least distance from the start to the target for that limited homing
 *     to be taken up at all
 * @param damage damage at the first level
 * @param crownTowerDamagePercent difference, in percent, between what the projectile deals to a
 *     crown tower and to anything else; 0 for one that hits both alike
 * @param damageMode which scaling rule the damage follows: an ordinary row scales as a card, a few
 *     tower rows as a king or princess tower
 * @param radius the radius of the area the impact damages; 0 for a projectile that hits its one
 *     target
 * @param aoeToAir whether the area the impact damages reaches air units
 * @param aoeToGround whether the area the impact damages reaches ground units and buildings
 * @param onlyEnemies true when the area spares the launcher's own side; without it the launcher's
 *     own units in the area are damaged too
 * @param projectileRadius the radius of the flying body of a projectile that hits what it passes; 0
 *     for one that only hits at its aim
 * @param projectileRange how far a projectile without a target flies from its launcher; 0 for one
 *     that flies to its target
 * @param checkCollisions true when the projectile stops at the first entity its body touches
 * @param considerZDistance true when the flight counts the height above the aim into the distance
 *     left and steps its height straight toward the aim's, as the evolved Cannon's bomb falls
 * @param alwaysApplyPushback true when the area of the impact pushes its victims even without
 *     damage
 * @param minDistance the least distance from the start the aim is pushed out to; 0 for none
 * @param circleScatter true for a row whose scatter pattern is the circle, which counts it among
 *     the projectiles that fly to a point rather than to a target
 * @param lineScatter true for a row whose scatter pattern is the line: as an attack's first
 *     projectile, it lays the attack's further ones out in a fan about the line to the target
 * @param pushback how far the area of the impact pushes its victims from the impact point; 0 for
 *     none
 * @param spawnCharacter the character the impact spawns around the impact point, or null
 * @param spawnCharacterCount how many of it: at least one when the row names one, else 0
 * @param spawnCharacterDeployTimeMs the deploy time the impact gives its children; 0 for none
 * @param spawnConstPriority true when the impact gives its k-th child a fixed priority, (20k)^2 off
 *     its squared distance as a candidate
 * @param radiusY the half height of the area of the impact, which makes it a box; 0 for a circle
 * @param projectileRadiusY the half height of a flying body's box; 0 for a circle
 * @param projectileStartExtraRadius how much wider a flying body's first pass is, at its
 *     registration
 * @param pushbackAll true when the push of a hit along a flying body's way lifts the gates that
 *     would refuse it
 * @param spawnProjectile the projectile the impact launches beyond the aim, or null
 * @param onStartingAction the action row the projectile runs on itself as it is admitted, or null
 * @param spawnChain how many links of spawned projectiles are left: at least one when the row names
 *     a spawned projectile, else 0
 * @param chainIsNewProjectile true when the projectiles its impact spawns do not share its group
 *     id, each taking a group of its own as it is launched
 * @param constantHeight the height the projectile starts at and aims at, in place of its
 *     launcher's, and lands at; 0 for none
 * @param targetBuff the buff its impact applies to what its area holds, or to its one target, or
 *     null
 * @param applyBuffBeforeDamage true when the impact applies its target buff before its damage
 * @param applyBuffEvenIfImmuneToDamage true when the buff reaches a target that is untouchable at
 *     the moment
 * @param buffTimeMs how long that buff lasts at the first level
 * @param buffTimeIncreasePerLevel how much longer it lasts for each step of the projectile's level
 * @param maximumTargets the most entities its area buff reaches: 1000 for an empty column, as the
 *     loader stores
 * @param onlyOwnTroops true when its area buff reaches only the launcher's own side
 * @param spawnCount how many of this row another projectile's impact spawns, in a fan; 0 and 1 both
 *     mean one
 * @param spawnRadius the angle, in degrees, of the fan's outermost step once divided by the count
 * @param chainedHitRadius how far from where it lands it hops on to its next target; 0 for a
 *     projectile that does not hop
 * @param chainedHitCount how many targets a hopping projectile hits in all, its first included
 * @param pingpongVisualTimeMs how long a projectile that sweeps out to its aim and back takes for
 *     the whole sweep; 0 for one that flies once to its aim
 * @param randomDelayMs the bound of the random wait a unit's launch gives the projectile before it
 *     flies; 0 for none
 * @param onHitTargetAction the action row its impact schedules on its target, with the projectile
 *     as the cause, before the hit lands; null for none
 * @param spawnAreaEffectObject the area effect its impact makes at the impact point, after its hit
 *     and its spawned characters; null for none
 * @param ignoreReflectedAttack true for a projectile whose hits a reflecting unit does not reflect
 * @param dragBackSpeed how far a hooking projectile flies back each step once it has hooked its
 *     target; 0 for a projectile that does not hook
 * @param dragSelfSpeed how far a hook on a building drags its owner each step, under the attractor
 *     column
 * @param dragMargin how much short of its owner a pulled target is aimed, beyond both radii
 * @param dragBackAsAttractor true for a hook whose speeds follow what it hooked: the target's own
 *     speed for a pulled troop, the self-drag speed for a building
 * @param allowResetTarget true for a projectile that loses its target when the target goes into a
 *     pathfinding state: true for an empty column, as the loader stores it
 * @param deflectBehaviour how a deflecting area effect treats it, as bits: {@link #NO_DEFLECT},
 *     {@link #INVERT_DIRECTION}, {@link #CHECK_ONLY_TARGET_POSITION}, {@link
 *     #USE_SPELLS_TOWER_DAMAGE_MUL} and {@link #IGNORE_HEIGHT}
 * @param deflectRadius the radius a deflecting area effect measures it by in place of its own; 0
 *     for its own
 * @param actionOnDeflector the action a deflection runs on the deflector, or null
 * @param customDeflectAction the action a deflection runs on the projectile in place of turning it,
 *     or null
 * @param useCustomMovement true for a projectile whose flight visit moves it not at all, which a
 *     run on it moves instead, as the evolved Snowball's rolling snowball rolls
 * @param spawnAxisY true for a projectile whose impact aims the projectile it spawns straight
 *     forward for its side, its minimum distance beyond the aim, in place of the fan
 * @param unmodelledColumns the columns its row sets that the impact does not model, which refuse it
 *     as a spell casts it
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the columns carried and the homing-like test that tells a projectile flying to a"
            + " point from one flying to a target. Not carried yet: the far"
            + " distance clamp, the random angle and distance, the angular delay, the"
            + " pingpong death effect, which is presentation, the deflect behaviour, the chained hit's end effect, the target buff of a"
            + " projectile that flies to a point, and a spawned area effect that follows; the impact's pushback, its spawned characters"
            + " and its spawned area effect are carried.")
@Builder(toBuilder = true)
public record ProjectileData(
    String name,
    RarityTable rarity,
    int speed,
    int gravity,
    boolean homing,
    int homingTimeMs,
    int homingMinDistance,
    int damage,
    int crownTowerDamagePercent,
    ScalingMode damageMode,
    int radius,
    boolean aoeToAir,
    boolean aoeToGround,
    boolean onlyEnemies,
    int projectileRadius,
    int projectileRange,
    boolean checkCollisions,
    boolean considerZDistance,
    boolean alwaysApplyPushback,
    int minDistance,
    boolean circleScatter,
    boolean lineScatter,
    int pushback,
    String spawnCharacter,
    int spawnCharacterCount,
    int spawnCharacterDeployTimeMs,
    boolean spawnConstPriority,
    int radiusY,
    int projectileRadiusY,
    int projectileStartExtraRadius,
    boolean pushbackAll,
    String spawnProjectile,
    String onStartingAction,
    int spawnChain,
    boolean chainIsNewProjectile,
    int constantHeight,
    String targetBuff,
    boolean applyBuffBeforeDamage,
    boolean applyBuffEvenIfImmuneToDamage,
    int buffTimeMs,
    int buffTimeIncreasePerLevel,
    int maximumTargets,
    boolean onlyOwnTroops,
    int spawnCount,
    int spawnRadius,
    int chainedHitRadius,
    int chainedHitCount,
    int pingpongVisualTimeMs,
    int randomDelayMs,
    String onHitTargetAction,
    String spawnAreaEffectObject,
    boolean ignoreReflectedAttack,
    int dragBackSpeed,
    int dragSelfSpeed,
    int dragMargin,
    boolean dragBackAsAttractor,
    boolean allowResetTarget,
    int deflectBehaviour,
    int deflectRadius,
    String actionOnDeflector,
    String customDeflectAction,
    boolean useCustomMovement,
    boolean spawnAxisY,
    List<String> unmodelledColumns) {

  /** The deflection bit of a projectile no deflecting area effect turns around. */
  public static final int NO_DEFLECT = 1;

  /** The deflection bit of a projectile sent on along its path rather than back at its source. */
  public static final int INVERT_DIRECTION = 2;

  /** The deflection bit of a projectile measured only at its target's point. */
  public static final int CHECK_ONLY_TARGET_POSITION = 4;

  /** The deflection bit of a projectile that takes a spell's share of a crown tower deflected. */
  public static final int USE_SPELLS_TOWER_DAMAGE_MUL = 8;

  /** The deflection bit of a projectile measured on the ground plane, its height left out. */
  public static final int IGNORE_HEIGHT = 0x10;

  public ProjectileData {
    if (damageMode == null) {
      damageMode = ScalingMode.CARD_DAMAGE;
    }
    unmodelledColumns = unmodelledColumns == null ? List.of() : List.copyOf(unmodelledColumns);
  }

  /**
   * True for a projectile that flies to a point and hits what it passes on the way, rather than to
   * a target: one with a flying body that either has a range without homing, stops at collisions or
   * scatters in a circle. Such a projectile keeps its start height as its aim height.
   */
  public boolean homingLike() {
    if (projectileRadius < 1) {
      return false;
    }
    if (!homing && projectileRange > 0) {
      return true;
    }
    return checkCollisions || circleScatter;
  }

  /**
   * How long the target buff lasts for a projectile at a level: the first level's time plus the
   * increase for each step, the steps being the packed level's low byte.
   *
   * @param packedLevel the projectile's level, packed
   */
  public int buffTime(int packedLevel) {
    return buffTimeIncreasePerLevel * PackedLevel.steps(packedLevel) + buffTimeMs;
  }
}
