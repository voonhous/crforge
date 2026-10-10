/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.pathfinding;

import org.crforge.core.battle.data.GameTable;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The bits of {@link GridEntity#getFlags()} that the routing, movement, push, targeting, state and
 * battle passes read, and the same bits as they are requested on {@link
 * GridEntity#getPendingFlags()}.
 *
 * <p>Every flag here is a game tag, and a game tag's bit is its row's index in the game tags table:
 * the same bit an action, a buff, a filter or a character row sets when it names the tag. So the
 * bits are read from the loaded table by name, never fixed. A table that drops a tag ahead of
 * others moves every later tag down one bit, and the code that tests a tag follows. A tag the table
 * does not list has no bit (0): no row can set it and no test of it passes.
 *
 * <p>The word is 64 bits wide, so every bit is a {@code long}. Each bit is defined exactly once, in
 * this class: the passes that read a bit share the definition rather than repeating it, so a bit
 * can never end up with two names. The names describe what each bit does where it is read. Tags
 * with no reader on the paths these packages cover are not listed.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "The flag bits the ported passes read are settled. Nothing here sets a flag, so"
            + " every flag-gated branch runs with the flag clear.")
public final class EntityFlags {

  /**
   * No tag has a bit: every test of a flag fails and raising one changes nothing. For the older
   * engine's grid adapter, which never sets a flag word, and for fixtures that set none.
   */
  public static final EntityFlags NONE = new EntityFlags(null);

  private final long dashing;
  private final long charging;
  private final long attacking;
  private final long noMove;
  private final long noDash;
  private final long noAttack;
  private final long lockTarget;
  private final long noSpawnTimer;
  private final long noCheckCollisions;
  private final long noCheckAvoidance;
  private final long noBuffs;
  private final long noPushedByEnemy;
  private final long inactive;
  private final long activating;
  private final long hasShield;
  private final long hidden;
  private final long abilityDisabled;
  private final long buildingDeathSpawnFindLocation;
  private final long abilityPostponed;
  private final long noSummon;
  private final long noSpecialAttack;
  private final long hasCapture;
  private final long combatDisabled;
  private final long noReflectedAttack;
  private final long forceIsGround;
  private final long noPushback;
  private final long noDamage;
  private final long noGiantbufferChefEnchantment;
  private final long forceIsAir;
  private final long captured;
  private final long disablePhysical;
  private final long castingAbility;
  private final long untargetable;
  private final long noPushedByAlly;
  private final long avoidanceAsObstacle;
  private final long abilityCooldownPaused;
  private final long warp;
  private final long noClone;
  private final long noMoveAllowAttract;
  private final long ignoreRangeExtensionToKeepTarget;
  private final long unkillable;

  /**
   * The bits as the given tables number the game tags.
   *
   * @param tables the game tables of one data version
   */
  public static EntityFlags of(GameTables tables) {
    return new EntityFlags(tables.table("game_tags"));
  }

  private EntityFlags(GameTable tags) {
    dashing = bit(tags, "DASHING");
    charging = bit(tags, "CHARGING");
    attacking = bit(tags, "ATTACKING");
    noMove = bit(tags, "NO_MOVE");
    noDash = bit(tags, "NO_DASH");
    noAttack = bit(tags, "NO_ATTACK");
    lockTarget = bit(tags, "LOCK_TARGET");
    noSpawnTimer = bit(tags, "NO_SPAWNTIMER");
    noCheckCollisions = bit(tags, "NO_CHECKCOLLISIONS");
    noCheckAvoidance = bit(tags, "NO_CHECKAVOIDANCE");
    noBuffs = bit(tags, "NO_BUFFS");
    noPushedByEnemy = bit(tags, "NO_PUSHED_BY_ENEMY");
    inactive = bit(tags, "INACTIVE");
    activating = bit(tags, "ACTIVATING");
    hasShield = bit(tags, "HAS_SHIELD");
    hidden = bit(tags, "HIDDEN");
    abilityDisabled = bit(tags, "ABILITY_DISABLED");
    buildingDeathSpawnFindLocation = bit(tags, "BUILDING_DEATH_SPAWN_FIND_LOCATION");
    abilityPostponed = bit(tags, "ABILITY_POSTPONED");
    noSummon = bit(tags, "NO_SUMMON");
    noSpecialAttack = bit(tags, "NO_SPECIAL_ATTACK");
    hasCapture = bit(tags, "HAS_CAPTURE");
    combatDisabled = bit(tags, "COMBAT_DISABLED");
    noReflectedAttack = bit(tags, "NO_REFLECTED_ATTACK");
    forceIsGround = bit(tags, "FORCE_IS_GROUND");
    noPushback = bit(tags, "NO_PUSHBACK");
    noDamage = bit(tags, "NO_DAMAGE");
    noGiantbufferChefEnchantment = bit(tags, "NO_GIANTBUFFER_CHEF_ENCHANTMENT");
    forceIsAir = bit(tags, "FORCE_IS_AIR");
    captured = bit(tags, "CAPTURED");
    disablePhysical = bit(tags, "DISABLE_PHYSICAL_INTERACTIONS_WITH_OBJECTS");
    castingAbility = bit(tags, "CASTING_ABILITY");
    untargetable = bit(tags, "UNTARGETABLE");
    noPushedByAlly = bit(tags, "NO_PUSHED_BY_ALLY");
    avoidanceAsObstacle = bit(tags, "AVOIDANCE_AS_OBSTACLE");
    abilityCooldownPaused = bit(tags, "ABILITY_COOLDOWN_PAUSED");
    warp = bit(tags, "WARP");
    noClone = bit(tags, "NO_CLONE");
    noMoveAllowAttract = bit(tags, "NO_MOVE_ALLOW_ATTRACT");
    ignoreRangeExtensionToKeepTarget = bit(tags, "IGNORE_RANGE_EXTENSION_TO_KEEP_TARGET");
    unkillable = bit(tags, "UNKILLABLE");
  }

  /**
   * A tag's bit: its row's index in the game tags table, or 0 for a tag the table does not list
   * (and for every tag of {@link #NONE}).
   */
  private static long bit(GameTable tags, String name) {
    if (tags == null || !tags.has(name)) {
      return 0;
    }
    return 1L << tags.row(name).index();
  }

  /**
   * The entity is running a dash. The targeting visit raises it on the pending flags in state 3.
   */
  public long dashing() {
    return dashing;
  }

  /** Set on the entity while its charge is complete. */
  public long charging() {
    return charging;
  }

  /**
   * The entity attacked on the step before: a hit not cancelled for distance raises it on the
   * pending flags, so it lasts one step.
   */
  public long attacking() {
    return attacking;
  }

  /** Movement is forbidden outright. */
  public long noMove() {
    return noMove;
  }

  /** The entity may not start a dash. */
  public long noDash() {
    return noDash;
  }

  /** The entity may not attack at all. */
  public long noAttack() {
    return noAttack;
  }

  /** The entity keeps the reference it has and the selector returns without choosing. */
  public long lockTarget() {
    return lockTarget;
  }

  /** The entity's spawner time does not step. */
  public long noSpawnTimer() {
    return noSpawnTimer;
  }

  /** Collision handling, and with it pushing, is disabled for this entity outright. */
  public long noCheckCollisions() {
    return noCheckCollisions;
  }

  /** Avoidance is disabled for this entity outright. */
  public long noCheckAvoidance() {
    return noCheckAvoidance;
  }

  /** The entity takes no buff. */
  public long noBuffs() {
    return noBuffs;
  }

  /** The entity may not be pushed by the other side. */
  public long noPushedByEnemy() {
    return noPushedByEnemy;
  }

  /** The entity takes no part in the fight: its targeting component is switched off. */
  public long inactive() {
    return inactive;
  }

  /** The entity is waking up: its targeting component is switched off until the run ends. */
  public long activating() {
    return activating;
  }

  /** The entity's shield is up: raised by its hit-points visit, read by data expressions only. */
  public long hasShield() {
    return hasShield;
  }

  /** The tags under which the combat gate keeps an entity's targeting component off. */
  public long keepsTargetingOff() {
    return inactive | activating;
  }

  /** The entity is hidden: no attacker takes it and the damage entry refuses it. */
  public long hidden() {
    return hidden;
  }

  /** The entity's ability may not be cast at all. */
  public long abilityDisabled() {
    return abilityDisabled;
  }

  /** A building the entity's death spawns looks for a free location. */
  public long buildingDeathSpawnFindLocation() {
    return buildingDeathSpawnFindLocation;
  }

  /** The entity's ability waits: a request leaves it pending. */
  public long abilityPostponed() {
    return abilityPostponed;
  }

  /**
   * The entity's spawner may not summon: raised on a unit a capture without a buff holds, and on
   * the evolved Goblin Drill while it is underground. The spawner holds its timer while it is up; a
   * firing already due still fires.
   */
  public long noSummon() {
    return noSummon;
  }

  /** The entity may not use its special attack. */
  public long noSpecialAttack() {
    return noSpecialAttack;
  }

  /**
   * The entity holds a capture: raised for one step on a capturing character as a drag completes (a
   * capturing projectile keeps no tag word).
   */
  public long hasCapture() {
    return hasCapture;
  }

  /**
   * The entity's targeting component is switched off: raised for one step by every combat gate that
   * switches it off (asleep, waiting to deploy, casting, deploying, dead or stunned), so it is seen
   * from the step after the first such gate to the step after the last.
   */
  public long combatDisabled() {
    return combatDisabled;
  }

  /** A reflecting unit does not reflect the entity's attack. */
  public long noReflectedAttack() {
    return noReflectedAttack;
  }

  /**
   * The entity is a ground unit to every reader of its layer, an air one's height pulled to 0, as
   * an air-to-ground run holds it; with FORCE_IS_AIR too, its live height decides.
   */
  public long forceIsGround() {
    return forceIsGround;
  }

  /** The entity refuses a pushback unless the request lifts the gates. */
  public long noPushback() {
    return noPushback;
  }

  /** The entity takes no damage: the damage entry and a typed hit's pipeline answer zero. */
  public long noDamage() {
    return noDamage;
  }

  /**
   * A Giant Buffer's or a Chef's friendly filter passes the entity by: no battle code reads the bit
   * itself, only a filter whose excluded tags name it.
   */
  public long noGiantbufferChefEnchantment() {
    return noGiantbufferChefEnchantment;
  }

  /**
   * The entity is an air unit to every reader of its layer, as a knock lifts it; with
   * FORCE_IS_GROUND too, its live height decides.
   */
  public long forceIsAir() {
    return forceIsAir;
  }

  /** The entity has been captured by the other side. */
  public long captured() {
    return captured;
  }

  /**
   * The entity takes no part in physical interaction with other objects: the tag
   * DISABLE_PHYSICAL_INTERACTIONS_WITH_OBJECTS.
   */
  public long disablePhysical() {
    return disablePhysical;
  }

  /** The entity is casting an ability. */
  public long castingAbility() {
    return castingAbility;
  }

  /** Nothing may take this entity as a target. */
  public long untargetable() {
    return untargetable;
  }

  /** The entity may not be pushed by its own side. */
  public long noPushedByAlly() {
    return noPushedByAlly;
  }

  /** The entity is treated as an obstacle to steer around rather than a body to push. */
  public long avoidanceAsObstacle() {
    return avoidanceAsObstacle;
  }

  /** The entity's ability cooldown is held. */
  public long abilityCooldownPaused() {
    return abilityCooldownPaused;
  }

  /** The entity is flying a warp at a speed: raised by each of the warp's steps for one step. */
  public long warp() {
    return warp;
  }

  /** A Clone passes the entity by. */
  public long noClone() {
    return noClone;
  }

  /** Movement is forbidden except when the entity is pulled by something else. */
  public long noMoveAllowAttract() {
    return noMoveAllowAttract;
  }

  /**
   * The entity keeps its reference only within its plain range, without the extension a unit is
   * otherwise allowed before it gives one up (0 in a table without the tag).
   */
  public long ignoreRangeExtensionToKeepTarget() {
    return ignoreRangeExtensionToKeepTarget;
  }

  /**
   * A hit that does not pierce immunity leaves the entity at 1 hit point at least, and a projectile
   * attacker keeps it as a target whatever damage is on its way (0 in a table without the tag).
   */
  public long unkillable() {
    return unkillable;
  }
}
