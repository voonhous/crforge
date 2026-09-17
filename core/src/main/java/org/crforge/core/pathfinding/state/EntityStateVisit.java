package org.crforge.core.pathfinding.state;

import java.util.List;
import org.crforge.core.pathfinding.EntityFlags;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.math.FixedMath;
import org.crforge.core.pathfinding.move.MovementState;

/**
 * The per-entity visit that runs once per tick after every component pass: the countdowns that live
 * on the entity rather than on one of its components.
 *
 * <p>The blocks below run in a fixed order and several of them return early, so the order is part
 * of the behaviour:
 *
 * <ol>
 *   <li>a unit that has run out of route while pathfinding arrives at its destination;
 *   <li>the elapsed-time accumulators advance, except in the three states that hold their own;
 *   <li>a staggered unit counts its stagger down and nothing else happens this visit;
 *   <li>a landed dash releases the unit back into movement;
 *   <li>the pending-damage duration counts down and a unit with no hit points asks to be removed;
 *   <li>the dash immunity is either topped up or counted down;
 *   <li>a requested ability starts;
 *   <li>the casting and follow-up countdowns advance;
 *   <li>a unit removed from play follows whatever it was attached to, and nothing else happens;
 *   <li>the deployment countdown advances and, at zero, the unit resumes;
 *   <li>a unit that has reached its own goal row stops;
 *   <li>a morph counts down.
 * </ol>
 *
 * <p>Blocks this class does not carry, because each is gated by a configuration column that belongs
 * to a part of the simulation outside movement and routing: the buff a unit gets while it is not
 * attacking, the self-damage of a kamikaze unit, elixir generation, the hide handling, the morph
 * timer with its growth scale, and the live spawner. They sit between blocks 4 and 5 and between 11
 * and 12 in the order above and none of them changes a state or a position that routing reads.
 *
 * <p>Two consequences of leaving them out, which matter to anyone extending this class rather than
 * to a plain ground troop:
 *
 * <ul>
 *   <li><b>Reachability.</b> The omitted growth and morph block ends the visit early for an entity
 *       with a running timer, so the blocks after it - the spawner, the morph countdown and the
 *       tail - run here where the standard game would already have returned.
 *   <li><b>The removal request.</b> The lifetime block below is the only ported writer of it. The
 *       original also raises it for a kamikaze unit with no hit-point object, for a morph timer
 *       reaching zero and for a spawner past its limit, all inside omitted blocks, so none of those
 *       three ever asks to be removed here.
 * </ul>
 *
 * <p>Two further gaps inside ported blocks: the dash-landing block announces the landing without
 * running the targeting work the standard game does alongside it, and the ability block starts the
 * cast without removing the buff the cast leaves behind.
 */
public final class EntityStateVisit {

  /** Milliseconds one tick advances every countdown by. */
  private static final int TICK_MS = 50;

  /** The x value that marks an entity's position as never having been written. */
  private static final int UNSET_POSITION = Integer.MAX_VALUE;

  private EntityStateVisit() {
    // Utility class
  }

  /**
   * Runs one entity state visit.
   *
   * @param entity the entity being visited
   * @param timers the entity's state-visit countdowns and flags
   * @param movement the entity's movement component, or null when it has none
   * @param config the entity's configuration columns
   * @param globals the match-wide settings
   * @param queries the answers the visit pulls from the rest of the simulation
   * @param chain the list that records what the visit announced
   * @param setter how a requested state change is applied
   */
  public static void stateVisit(
      GridEntity entity,
      StateTimers timers,
      MovementState movement,
      StateVisitConfig config,
      StateVisitGlobals globals,
      StateQueries queries,
      List<String> chain,
      StateSetter setter) {
    // 1. A unit that ran out of route while pathfinding has arrived.
    int word = entity.getState();
    if ((entity.getState() & ~1) == GridEntityState.SPAWN_PATHFIND) {
      MovementState component = entity.isMovementActive() ? movement : null;
      entity.setPendingFlags(entity.getPendingFlags() | EntityFlags.DISABLE_PHYSICAL);
      if (component != null
          && component.getRoute().isEmpty()
          && (entity.getFlags() & (EntityFlags.NO_MOVE_ALLOW_ATTRACT | EntityFlags.NO_MOVE)) == 0
          && (entity.getFlags() & EntityFlags.CAPTURED) == 0) {
        word = arrival(entity, component, config, queries, chain, setter);
      } else {
        word = entity.getState();
      }
    }

    // 2. Elapsed time. Deploying, spawn pathfinding and waiting to deploy hold their own timers.
    if (GridEntityState.inMask(word, GridEntityState.DELAY_SKIP_MASK)) {
      if (timers.isAttackFinishing()) {
        accumulate(timers, globals);
      }
    } else {
      entity.setDelay(entity.getDelay() + TICK_MS);
      if (timers.isAttackFinishing()) {
        accumulate(timers, globals);
      }
    }

    // 3. A staggered unit counts down and nothing else happens this visit.
    if (entity.getState() == GridEntityState.WAITING_TO_DEPLOY) {
      entity.setDelay(Math.max(entity.getDelay(), TICK_MS) - TICK_MS);
      if (entity.getDelay() == 0) {
        setter.setState(entity, GridEntityState.DEPLOYING);
      }
      return;
    }

    // 4. A landed dash holds the unit for its landing time and then releases it.
    if (entity.getBlockCountdownMs() >= 1) {
      entity.setBlockCountdownMs(entity.getBlockCountdownMs() + TICK_MS);
      if (entity.getBlockCountdownMs() >= config.dashLandingTimeMs()) {
        entity.setBlockCountdownMs(0);
        chain.add("dash_landed");
        setter.setState(entity, GridEntityState.MOVING);
      }
    }

    // 5. Pending damage, and removal of a unit that has no hit points left.
    timers.setPendingDamageDurationMs(
        Math.max(timers.getPendingDamageDurationMs(), TICK_MS) - TICK_MS);
    chain.add("has_hit_points");
    if (!queries.hasHitPoints()
        && !config.kingTowerMiddle()
        && entity.getDeployCountdown() == 0
        && !config.neutralObject()) {
      if (config.kamikaze()) {
        chain.add("remove");
      }
      timers.setRemovalRequested(true);
    }

    // 6. Dash immunity is topped up while the unit is protected and counted down otherwise.
    boolean topUp;
    if (queries.protectedFromDamage() && queries.protectionApplies()) {
      topUp = true;
    } else if (timers.isAttached()) {
      topUp = true;
    } else if (entity.getState() == GridEntityState.DASHING
        && config.dashImmuneToDamageTimeMs() > 0) {
      topUp = true;
    } else {
      topUp = timers.isStopsAtGoalRow();
    }
    if (topUp) {
      timers.setDashImmunityRemainingMs(config.dashImmuneToDamageTimeMs());
    } else {
      timers.setDashImmunityRemainingMs(timers.getDashImmunityRemainingMs() - TICK_MS);
    }

    // 7. A requested ability starts, or its cooldown is held.
    if (timers.isAbilityReady()) {
      chain.add("ability_trigger_ready");
      if (queries.abilityTriggerReady()) {
        timers.setAbilityReady(false);
        setter.setState(entity, GridEntityState.CASTING);
      } else {
        entity.setPendingFlags(entity.getPendingFlags() | EntityFlags.ABILITY_COOLDOWN_PAUSED);
      }
    }

    // 8. The casting and follow-up countdowns.
    if (entity.getState() == GridEntityState.CASTING) {
      entity.setPendingFlags(entity.getPendingFlags() | EntityFlags.CASTING_ABILITY);
      chain.add("ability_cast_active");
      if (queries.abilityCastActive() && config.abilityPresent() && config.abilityHoldsState()) {
        ResumeHelper.resume(entity, config, queries, chain, setter);
      } else {
        timers.setAbilityWarningCountdown(timers.getAbilityWarningCountdown() - 1);
        timers.setAbilityCountdown(timers.getAbilityCountdown() - 1);
        if (timers.getAbilityWarningCountdown() == 0) {
          chain.add("ability_warning");
        }
        if (timers.getAbilityCountdown() <= 0 && entity.getState() == GridEntityState.CASTING) {
          setter.setState(entity, GridEntityState.STANDING);
        }
      }
    }
    boolean holdingFollowUp = false;
    if (entity.getState() == GridEntityState.ABILITY_FOLLOW_UP) {
      timers.setAbilityCountdown(timers.getAbilityCountdown() - 1);
      if (timers.getAbilityCountdown() > 0) {
        if (config.abilityPresent()) {
          entity.setPendingFlags(entity.getPendingFlags() | config.abilityStateFlags());
        }
        word = GridEntityState.ABILITY_FOLLOW_UP;
        holdingFollowUp = true;
      } else {
        setter.setState(entity, GridEntityState.STANDING);
      }
    }

    // 9. A unit removed from play follows whatever it was attached to.
    if (!holdingFollowUp) {
      if (entity.getState() == GridEntityState.FOLLOWING_REMOVED_BUILDING
          && timers.getFollowTarget() == null) {
        setter.setState(entity, GridEntityState.STANDING);
      }
      if (entity.getState() == GridEntityState.FOLLOWING_REMOVED) {
        if (timers.getFollowTarget() != null) {
          int copyX = entity.getPrevX();
          int copyY = entity.getPrevY();
          setPosition(entity, timers.getFollowTarget().getX(), timers.getFollowTarget().getY());
          if (entity.getX() != copyX || entity.getY() != copyY) {
            entity.setDirX(entity.getX() - copyX);
            entity.setDirY(entity.getY() - copyY);
          }
        } else {
          setter.setState(entity, GridEntityState.STANDING);
        }
        chain.add("visit_tail");
        return;
      }
      word = entity.getState();
    }

    // 10. The deployment countdown, and the resume that ends it.
    if (word == GridEntityState.CLONE_SETUP || entity.getDeployCountdown() >= 1) {
      int remaining;
      if (config.deployTimeAffectedByCharacterSpeed()) {
        chain.add("scaled_deploy_step");
        remaining = entity.getDeployCountdown() - queries.scaledDeployStepMs();
      } else {
        remaining = entity.getDeployCountdown() - TICK_MS;
      }
      entity.setDeployCountdown(Math.max(remaining, 0));
      if (remaining > 0) {
        return;
      }
      if (entity.getState() == GridEntityState.CLONE_SETUP) {
        return;
      }
      ResumeHelper.resume(entity, config, queries, chain, setter);
      if (config.hideBeforeFirstHit() || config.hidesWhenNotAttacking()) {
        chain.add("visit_tail");
        chain.add("targeting_visit");
      }
    }

    // 11. A unit that has walked to its own goal row stops there.
    if (entity.getState() == GridEntityState.MOVING && timers.isStopsAtGoalRow()) {
      chain.add("goal_row");
      if (queries.goalRow() == FixedMath.divOrZero(entity.getY(), 500)) {
        setter.setState(entity, GridEntityState.STANDING);
      }
    }

    // 12. A morph counts down and the unit stands when it ends.
    if (entity.getState() == GridEntityState.MORPHING) {
      timers.setMorphCountdownMs(Math.max(timers.getMorphCountdownMs(), TICK_MS) - TICK_MS);
      if (timers.getMorphCountdownMs() == 0) {
        setter.setState(entity, GridEntityState.STANDING);
      }
    }
    chain.add("visit_tail");
  }

  /**
   * A unit that has run out of route while pathfinding stops where it was heading.
   *
   * <p>With an explicit destination set the unit is placed on it. A unit routing to a spawn
   * destination then starts its deploy countdown, facing down the arena for side 0 and up it for
   * side 1, or moves straight away when it has no deploy time; a unit routing to a mid-match
   * destination moves, or starts a deploy countdown when its configuration says so.
   *
   * @return the state word the rest of the visit should use
   */
  static int arrival(
      GridEntity entity,
      MovementState component,
      StateVisitConfig config,
      StateQueries queries,
      List<String> chain,
      StateSetter setter) {
    int explicitX = component.getExplicitX();
    int explicitY = component.getExplicitY();
    if (explicitX >= 0 && explicitY >= 0) {
      setPosition(entity, explicitX, explicitY);
      entity.setZ(config.flyingHeight());
    }
    int state = entity.getState();
    if (state == GridEntityState.INGAME_PATHFIND) {
      component.setExplicitX(-1);
      component.setExplicitY(-1);
      chain.add("pathfind_arrival");
      if (config.ingamePathfindEndsInDeploy()) {
        setter.setState(entity, GridEntityState.DEPLOYING);
        chain.add("pathfind_arrival_deploy");
      } else {
        setter.setState(entity, GridEntityState.MOVING);
      }
      if (config.onIngamePathfindStopAction() != null) {
        chain.add(config.onIngamePathfindStopAction());
      }
      return entity.getState();
    }
    if (state == GridEntityState.SPAWN_PATHFIND) {
      component.setExplicitX(-1);
      component.setExplicitY(-1);
      if (config.deployTimeMs() != 0) {
        setter.setState(entity, GridEntityState.DEPLOYING);
        entity.setDirX(0);
        entity.setDirY(queries.team() == 0 ? 256 : -256);
      } else {
        setter.setState(entity, GridEntityState.MOVING);
      }
      chain.add("spawn_arrival");
      if (config.spawnPathfindMorph()) {
        chain.add("spawn_pathfind_morph");
      }
      return entity.getState();
    }
    return state;
  }

  /** Advances the attack-finish timer and clears the flag once it passes the configured limit. */
  static void accumulate(StateTimers timers, StateVisitGlobals globals) {
    timers.setAttackFinishElapsedMs(timers.getAttackFinishElapsedMs() + TICK_MS);
    if (timers.getAttackFinishElapsedMs() > globals.attackFinishTimeMs()) {
      timers.setAttackFinishing(false);
    }
  }

  /**
   * Writes an entity's position, seeding its previous position as well the first time it is ever
   * written.
   */
  private static void setPosition(GridEntity entity, int x, int y) {
    if (entity.getX() == UNSET_POSITION) {
      entity.setPrevX(x);
      entity.setPrevY(y);
    }
    entity.setX(x);
    entity.setY(y);
  }
}
