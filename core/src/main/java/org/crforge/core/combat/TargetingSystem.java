package org.crforge.core.combat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;
import lombok.Setter;
import org.crforge.core.component.Combat;
import org.crforge.core.effect.AppliedEffect;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.crforge.core.util.GameUnits;

public class TargetingSystem {

  // How far beyond sight range a target remains valid before being dropped
  private static final float TARGET_RETENTION_RANGE_MULTIPLIER = 1.5f;

  // Radius used by CROWDEST algorithm to count nearby enemies around each candidate (2 tiles)
  private static final int CROWDEST_NEIGHBOR_RADIUS = 2 * GameUnits.UNITS_PER_TILE;

  private final Random rng;

  /**
   * Troops whose targets are chosen elsewhere. A troop this answers true for is skipped entirely,
   * so nothing here writes its combat target. The default answers false for every troop, which is
   * the behaviour of a match that has no other target source.
   */
  @Setter private Predicate<Troop> externallyManaged = troop -> false;

  public TargetingSystem() {
    this(42);
  }

  public TargetingSystem(long seed) {
    this.rng = new Random(seed);
  }

  public void updateTargets(Collection<Entity> entities) {
    for (Entity entity : entities) {
      if (!entity.isAlive()) {
        continue;
      }

      // Inactive towers do not target
      if (entity instanceof Tower tower && !tower.isActive()) {
        continue;
      }

      Combat combat = entity.getCombat();

      // Entity must have combat capabilities to target anything
      if (combat == null) {
        continue;
      }

      // Troops cannot target while deploying or tunneling underground, and a troop whose target
      // is chosen elsewhere is left alone entirely.
      if (entity instanceof Troop troop
          && (troop.isDeploying() || troop.isTunneling() || externallyManaged.test(troop))) {
        continue;
      }
      // Buildings cannot target while deploying
      if (entity instanceof Building building && building.isDeploying()) {
        continue;
      }

      updateEntityTarget(entity, combat, entities);
    }
  }

  private void updateEntityTarget(Entity attacker, Combat combat, Collection<Entity> entities) {
    Entity currentTarget = combat.getCurrentTarget();

    // Building-targeting troops always retarget to the closest building.
    // This enables the "building pull" mechanic where defensive buildings
    // divert troops away from towers (e.g. Tesla revealing, Cannon placed in path).
    if (combat.isTargetOnlyBuildings()) {
      Entity best = findBestTarget(attacker, combat, entities);
      combat.setCurrentTarget(best);
      return;
    }

    if (combat.isTargetLocked()) {
      // Locked phase (in attack range, fighting): keep current target if still valid.
      // If target dies, leaves retention range, or becomes invalid, unlock and rescan.
      if (isValidTarget(attacker, combat, currentTarget)) {
        return;
      }
      combat.setTargetLocked(false);
    }

    // Unlocked phase (moving toward target): always rescan for the closest enemy.
    // This enables "pull" mechanics where placing a unit/building closer diverts the troop.
    // The identity guard in setCurrentTarget prevents state reset when rescan returns the same
    // target.
    Entity newTarget = findBestTarget(attacker, combat, entities);
    combat.setCurrentTarget(newTarget);
  }

  private Entity findBestTarget(Entity attacker, Combat combat, Collection<Entity> entities) {
    List<Entity> candidates = findCandidates(attacker, combat, entities);
    if (candidates.isEmpty()) {
      return null;
    }

    return switch (combat.getTargetSelectAlgorithm()) {
      case NEAREST -> selectNearest(attacker, candidates);
      case FARTHEST -> selectFarthest(attacker, candidates);
      case LOWEST_HP -> selectByHp(candidates, true);
      case HIGHEST_HP -> selectByHp(candidates, false);
      case LOWEST_AD -> selectByDamage(candidates, true);
      case HIGHEST_AD -> selectByDamage(candidates, false);
      case LOWEST_HP_RATIO -> selectByHpRatio(candidates, true);
      case HIGHEST_HP_RATIO -> selectByHpRatio(candidates, false);
      case RANDOM -> candidates.get(rng.nextInt(candidates.size()));
      case CROWDEST -> selectCrowdest(attacker, candidates);
      case FARTHEST_IN_ABILITY_RANGE -> selectFarthestInRange(attacker, combat, candidates);
      // Stubbed: Entity lacks star level and elixir cost fields today
      case HIGHEST_STAR, LOWEST_STAR, HIGHEST_COST, LOWEST_COST ->
          selectNearest(attacker, candidates);
    };
  }

  /**
   * Collects all valid targeting candidates within sight range. Applies team filtering, visibility
   * checks, target type compatibility, and minimum range exclusion. Shared by all algorithms.
   */
  private List<Entity> findCandidates(Entity attacker, Combat combat, Collection<Entity> entities) {
    Team enemyTeam = attacker.getTeam().opposite();
    List<Entity> candidates = new ArrayList<>();

    for (Entity e : entities) {
      if (e.getTeam() != enemyTeam || !e.isTargetable()) {
        continue;
      }
      // Invisible troops cannot be targeted by units (but can still be hit by AOE spells)
      if (e instanceof Troop t && t.isInvisible()) {
        continue;
      }
      if (!canTarget(combat, e)) {
        continue;
      }
      long distSq = getDistanceSq(attacker, e);
      // Skip candidates within the minimum range blind spot (e.g. Mortar)
      if (combat.getMinimumRange() > 0) {
        long effectiveMinRange = edgeRange(combat.getMinimumRange(), attacker, e);
        if (GameUnits.insideRadius(distSq, effectiveMinRange)) {
          continue;
        }
      }
      // Use edge-to-edge distance for sight range, matching how CombatSystem checks attack range
      long effectiveSightRange = edgeRange(combat.getSightRange(), attacker, e);
      if (GameUnits.withinRadius(distSq, effectiveSightRange)) {
        candidates.add(e);
      }
    }
    return candidates;
  }

  // -- Selection strategy methods --

  private Entity selectNearest(Entity attacker, List<Entity> candidates) {
    Entity best = null;
    long bestDistSq = Long.MAX_VALUE;
    for (Entity e : candidates) {
      long distSq = getDistanceSq(attacker, e);
      if (distSq < bestDistSq) {
        bestDistSq = distSq;
        best = e;
      }
    }
    return best;
  }

  private Entity selectFarthest(Entity attacker, List<Entity> candidates) {
    Entity best = null;
    long bestDistSq = -1L;
    for (Entity e : candidates) {
      long distSq = getDistanceSq(attacker, e);
      if (distSq > bestDistSq) {
        bestDistSq = distSq;
        best = e;
      }
    }
    return best;
  }

  private Entity selectByHp(List<Entity> candidates, boolean lowest) {
    Entity best = null;
    int bestHp = lowest ? Integer.MAX_VALUE : Integer.MIN_VALUE;
    for (Entity e : candidates) {
      int hp = e.getHealth().getCurrent();
      if ((lowest && hp < bestHp) || (!lowest && hp > bestHp)) {
        bestHp = hp;
        best = e;
      }
    }
    return best;
  }

  private Entity selectByDamage(List<Entity> candidates, boolean lowest) {
    Entity best = null;
    int bestDmg = lowest ? Integer.MAX_VALUE : Integer.MIN_VALUE;
    for (Entity e : candidates) {
      // Entities without combat (e.g. passive buildings) have 0 damage
      int dmg = e.getCombat() != null ? e.getCombat().getDamage() : 0;
      if ((lowest && dmg < bestDmg) || (!lowest && dmg > bestDmg)) {
        bestDmg = dmg;
        best = e;
      }
    }
    return best;
  }

  private Entity selectByHpRatio(List<Entity> candidates, boolean lowest) {
    Entity best = null;
    float bestRatio = lowest ? Float.MAX_VALUE : -1f;
    for (Entity e : candidates) {
      float ratio = e.getHealth().percentage();
      if ((lowest && ratio < bestRatio) || (!lowest && ratio > bestRatio)) {
        bestRatio = ratio;
        best = e;
      }
    }
    return best;
  }

  /**
   * Selects the candidate with the most other candidates nearby. Counts neighbors within a fixed
   * radius around each candidate, then picks the one in the densest cluster.
   */
  private Entity selectCrowdest(Entity attacker, List<Entity> candidates) {
    Entity best = null;
    int bestCount = -1;
    long bestDistSq = Long.MAX_VALUE;

    for (Entity e : candidates) {
      int count = 0;
      for (Entity other : candidates) {
        if (other != e
            && GameUnits.withinRadius(
                e.getPosition().distanceSquaredTo(other.getPosition()), CROWDEST_NEIGHBOR_RADIUS)) {
          count++;
        }
      }
      // Tie-break by distance to attacker (closer wins)
      long distSq = getDistanceSq(attacker, e);
      if (count > bestCount || (count == bestCount && distSq < bestDistSq)) {
        bestCount = count;
        bestDistSq = distSq;
        best = e;
      }
    }
    return best;
  }

  /**
   * Selects the farthest candidate that is still within the attacker's attack range (not sight
   * range). Useful for units that want to maximize distance while still being able to hit.
   */
  private Entity selectFarthestInRange(Entity attacker, Combat combat, List<Entity> candidates) {
    Entity best = null;
    long bestDistSq = -1L;

    for (Entity e : candidates) {
      long distSq = getDistanceSq(attacker, e);
      long effectiveRange = edgeRange(combat.getRange(), attacker, e);
      if (GameUnits.withinRadius(distSq, effectiveRange) && distSq > bestDistSq) {
        bestDistSq = distSq;
        best = e;
      }
    }
    // If no candidate is within attack range, fall back to nearest
    return best != null ? best : selectNearest(attacker, candidates);
  }

  private boolean isValidTarget(Entity attacker, Combat combat, Entity target) {
    if (target == null || !target.isTargetable()) {
      return false;
    }
    // Drop target that went invisible (forces retarget, which will also skip invisible)
    if (target instanceof Troop t && t.isInvisible()) {
      return false;
    }

    // Check if target is still in range (with leeway), using squared distance to avoid sqrt.
    // The retention range is sightRange * 1.5 rounded to the nearest game unit.
    long retentionRange =
        edgeRange(
            GameUnits.round(combat.getSightRange() * (double) TARGET_RETENTION_RANGE_MULTIPLIER),
            attacker,
            target);
    long distanceSq = getDistanceSq(attacker, target);
    if (!GameUnits.withinRadius(distanceSq, retentionRange)) {
      return false;
    }

    // Drop target that entered the minimum range blind spot (e.g. Mortar)
    if (combat.getMinimumRange() > 0) {
      long effectiveMinRange = edgeRange(combat.getMinimumRange(), attacker, target);
      if (GameUnits.insideRadius(distanceSq, effectiveMinRange)) {
        return false;
      }
    }

    return canTarget(combat, target);
  }

  private boolean canTarget(Combat attackerCombat, Entity target) {
    // targetOnlyBuildings: unit ignores all non-building/tower entities (e.g. Giant, Hog Rider)
    if (attackerCombat.isTargetOnlyBuildings() && !(target instanceof Building)) {
      return false;
    }

    // targetOnlyTroops: unit ignores buildings (e.g. Ram Rider's bola targets troops only)
    if (attackerCombat.isTargetOnlyTroops() && target instanceof Building) {
      return false;
    }

    // ignoreTargetsWithBuff: skip targets that already have this buff applied
    // (e.g. Ram Rider skips targets already snared by BolaSnare)
    if (attackerCombat.getIgnoreTargetsWithBuff() != null) {
      for (AppliedEffect effect : target.getAppliedEffects()) {
        if (attackerCombat.getIgnoreTargetsWithBuff().equals(effect.getBuffName())) {
          return false;
        }
      }
    }

    TargetType attackerTargetType = attackerCombat.getTargetType();
    MovementType targetMovement = target.getMovementType();

    return switch (attackerTargetType) {
      case ALL -> true;
      case GROUND ->
          targetMovement == MovementType.GROUND || targetMovement == MovementType.BUILDING;
      case AIR -> targetMovement == MovementType.AIR;
      case BUILDINGS -> targetMovement == MovementType.BUILDING;
    };
  }

  /**
   * Checks if the given target type can target the given movement type. Public for reuse by
   * CombatSystem (multiple targets logic).
   */
  public static boolean canTargetMovementType(
      TargetType attackerType, MovementType targetMovement) {
    return switch (attackerType) {
      case ALL -> true;
      case GROUND ->
          targetMovement == MovementType.GROUND || targetMovement == MovementType.BUILDING;
      case AIR -> targetMovement == MovementType.AIR;
      case BUILDINGS -> targetMovement == MovementType.BUILDING;
    };
  }

  private long getDistanceSq(Entity a, Entity b) {
    return a.getPosition().distanceSquaredTo(b.getPosition());
  }

  /**
   * Converts an edge-to-edge range into a center-to-center range in game units by adding both
   * collision radii.
   */
  private static long edgeRange(int range, Entity attacker, Entity target) {
    return (long) range + attacker.getCollisionRadius() + target.getCollisionRadius();
  }
}
