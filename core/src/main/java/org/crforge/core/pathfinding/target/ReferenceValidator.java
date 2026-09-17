package org.crforge.core.pathfinding.target;

import org.crforge.core.pathfinding.EntityFlags;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.index.SpatialIndex;

/**
 * Whether a targeting component may keep or take a given target.
 *
 * <p>Every place that keeps, takes or rejects a target asks this. The rules are applied in the
 * order written below and the first one that refuses ends the answer; the order is part of the
 * behaviour, because several rules read the same flags and only the first refusal matters.
 *
 * <p>The mode argument is 1 when the caller is deciding whether to keep or take a target and 0 when
 * it is re-checking one it has. Only the pending-damage rule reads it.
 */
public final class ReferenceValidator {

  /** Mode used by the re-check of a target that is already held. */
  public static final int MODE_RECHECK = 0;

  /** Mode used when deciding to keep or take a target. */
  public static final int MODE_TAKE = 1;

  /** Entity type of an ordinary character or crown tower. */
  public static final int TYPE_CHARACTER = 5;

  /** Entity type fought at contact distance, which the character path accepts outright. */
  public static final int TYPE_CONTACT = 3;

  /** Avatar count at which a dead tower is only rejected when it maps to a tower slot. */
  private static final int AVATARS_WITH_TOWER_SLOTS = 4;

  /** Avatar count at which a dead tower is always rejected. */
  private static final int AVATARS_WITH_STRICT_TOWERS = 6;

  private ReferenceValidator() {
    // Utility class
  }

  /**
   * Answers whether the component may keep or take the target.
   *
   * <p>Two gates run before the shared rules: a target already on the component's hit list is
   * refused to a unit that attacks with a dash or a special attack, and unless the alive check is
   * bypassed the target must still have hit points. The shared rules then run with the owner's
   * TargetOnlyBuildings column as the building filter.
   */
  public static boolean validate(
      TargetingState t, TargetView target, int mode, ValidatorQueries queries) {
    TargetingConfig cfg = t.getConfig();
    boolean onlyBuildings = cfg.targetOnlyBuildings();
    if (target != null && t.getHitTargetIds().contains(target.id())) {
      if (cfg.specialRange() > 0 || cfg.dashCount() > 0) {
        return false;
      }
    }
    if (!t.isAliveCheckBypass()) {
      if (target == null || !target.alive()) {
        return false;
      }
    }
    return sharedValidate(t, target, false, onlyBuildings, false, (mode & 1) != 0, queries);
  }

  /**
   * The rules every attacker shares, in order: identity, team, the untargetable flag, the owner's
   * own ignore list, the type pairing, the excluded configuration row, the building and troop
   * filters, the tower filters, the air and ground pairing, the state filters, the pending-damage
   * rule for projectile attackers and finally the target's own acceptance.
   *
   * @param skipTeamCheck true to let an attacker take a target on its own team
   * @param onlyBuildings the building filter, normally the owner's TargetOnlyBuildings column
   * @param acceptanceFlag value handed to the target's own acceptance answer, which every ordinary
   *     entity ignores
   * @param mode true when the caller is deciding to keep or take the target
   */
  public static boolean sharedValidate(
      TargetingState t,
      TargetView target,
      boolean skipTeamCheck,
      boolean onlyBuildings,
      boolean acceptanceFlag,
      boolean mode,
      ValidatorQueries queries) {
    GridEntity owner = t.getOwner();
    if (target == null || target.getEntity() == owner) {
      return false;
    }
    if (!skipTeamCheck && SpatialIndex.team(target.getEntity()) == SpatialIndex.team(owner)) {
      return false;
    }
    if ((target.getEntity().getFlags() & EntityFlags.UNTARGETABLE) != 0) {
      return false;
    }
    if (queries.ownerIgnores(target.id())) {
      return false;
    }

    TargetingConfig cfg = t.getConfig();
    if (owner.getType() == TYPE_CHARACTER) {
      if (target.getEntity().getType() == TYPE_CONTACT && target.acceptsAttacker(acceptanceFlag)) {
        return true;
      }
    } else {
      if (owner.getType() == TYPE_CONTACT && !queries.nonCharacterOwnerAccepts(target)) {
        return false;
      }
      return accepted(target, acceptanceFlag);
    }

    TargetingConfig targetConfig = target.getConfig();
    if (targetConfig != null
        && targetConfig.configKey() != null
        && targetConfig.configKey().equals(queries.excludedConfigKey())) {
      return false;
    }
    if (onlyBuildings) {
      if (cfg.targetOnlyBuildings() && !target.building() && !airOnlyGate(cfg, target)) {
        return false;
      }
      if (!target.building() && !target.towerFlag()) {
        if (!target.presenceFlag()) {
          return false;
        }
        if (targetConfig == null || !targetConfig.buildingTarget()) {
          return false;
        }
      }
      if (cfg.suckElixirSpeed() >= 1 && !target.towerFlag() && !target.summonerTowerColumn()) {
        return false;
      }
    }
    if (cfg.targetOnlyTroops() && target.building()) {
      return false;
    }
    if (cfg.doNotTargetTowers()) {
      if (target.isSummonerTowerEntity() || target.towerFlag()) {
        return false;
      }
    }
    if (cfg.targetOnlyTowers()
        && !target.towerFlag()
        && !target.isSummonerTowerEntity()
        && !airOnlyGate(cfg, target)) {
      return false;
    }
    if (cfg.targetOnlyKingTower() && !target.towerFlag() && !airOnlyGate(cfg, target)) {
      return false;
    }
    if (target.air() && !cfg.attacksAir()) {
      return false;
    }
    if (target.towerFlag()) {
      if (queries.avatarCount() == AVATARS_WITH_TOWER_SLOTS) {
        if (!target.alive() && queries.mapsToTowerSlot(target)) {
          return false;
        }
      } else if (queries.avatarCount() == AVATARS_WITH_STRICT_TOWERS && !target.alive()) {
        return false;
      }
    }
    if (target.ground() && !cfg.attacksGround() && cfg.attacksAir()) {
      return false;
    }
    if (cfg.attackSequenceMode() != 0
        && target.presenceFlag()
        && queries.attackSequenceRejects(target)) {
      return false;
    }
    if (cfg.ignoreTargetsWithBuff()
        && target.presenceFlag()
        && !cfg.deprioritizeTargetsWithBuff()
        && target.isBuffComponentPresent()
        && queries.carriesIgnoredBuff(target)) {
      return false;
    }
    if (target.presenceFlag()) {
      if (target.getEntity().getState() == 5 && !cfg.attacksAir()) {
        return false;
      }
      if (target.getEntity().getState() == 7) {
        return false;
      }
    }
    if (cfg.hasProjectile() && mode) {
      return pendingDamageRule(t, target, queries, acceptanceFlag);
    }
    return accepted(target, acceptanceFlag);
  }

  /**
   * The rule that keeps a target which is about to die. Only a projectile attacker deciding to keep
   * or take a target reaches it.
   */
  private static boolean pendingDamageRule(
      TargetingState t, TargetView target, ValidatorQueries queries, boolean acceptanceFlag) {
    int amount = target.getPendingDamageAmount();
    int duration = target.getPendingDamageDuration();
    if (amount == 0) {
      return accepted(target, acceptanceFlag);
    }
    boolean hitPointsObject = target.isHitPointsPresent();
    boolean notBuffed = true;
    if (target.isBuffComponentPresent()) {
      notBuffed = !queries.pendingDamageBuffHolds(target);
    }
    boolean recent = false;
    boolean keepsAttacker = false;
    boolean alreadyCommitted = false;
    if (target.presenceFlag()) {
      TargetingConfig targetConfig = target.getConfig();
      keepsAttacker = targetConfig != null && targetConfig.morphKeepTarget();
      if (targetConfig != null && targetConfig.lifeTime() >= 1 && !target.building()) {
        keepsAttacker = true;
      }
      recent = queries.pendingDamageIsRecent(target, duration);
      alreadyCommitted = queries.committedDamage(target, target.getPendingDamageKey()) <= amount;
    }
    if (!hitPointsObject) {
      return accepted(target, acceptanceFlag);
    }
    if (!queries.pendingDamageAccepted(target, amount)) {
      return accepted(target, acceptanceFlag);
    }
    if (duration > t.getGlobals().pendingDamageIgnoreIfDurationLess()) {
      return accepted(target, acceptanceFlag);
    }
    boolean keep = recent || keepsAttacker || !(notBuffed || alreadyCommitted);
    if (!keep) {
      return false;
    }
    return accepted(target, acceptanceFlag);
  }

  /**
   * The target's own last word: it must carry hit points and accept the attacker, asked with the
   * acceptance flag the validator was called with.
   */
  private static boolean accepted(TargetView target, boolean acceptanceFlag) {
    return target.isHitPointsPresent() && target.acceptsAttacker(acceptanceFlag);
  }

  /**
   * The gate that lets an owner which attacks air and not ground keep an air target although a
   * building or tower filter has just refused it.
   */
  static boolean airOnlyGate(TargetingConfig cfg, TargetView target) {
    if (!cfg.attacksAir()) {
      return false;
    }
    if (cfg.attacksGround()) {
      return false;
    }
    if (!target.presenceFlag()) {
      return false;
    }
    return target.air();
  }
}
