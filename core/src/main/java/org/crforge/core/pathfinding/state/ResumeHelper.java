package org.crforge.core.pathfinding.state;

import java.util.List;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;

/**
 * What an entity does once something that was holding it ends: a deployment countdown running out,
 * a cast finishing, or the reference it was attacking being dropped.
 *
 * <p>The answer is the first of three that applies: move, when the entity can follow a route and
 * the arena will give it one; stand, when it still has hit points; or nothing at all, unless it is
 * one of the three kinds of entity that are removed instead.
 *
 * <p>Morphing, waiting to deploy and the two removed-and-following states are never resumed. An
 * entity in the casting state is resumed only while the cast is still active and its ability holds
 * the state.
 */
public final class ResumeHelper {

  private ResumeHelper() {
    // Utility class
  }

  /**
   * Requests the state the entity should be in now that whatever held it has ended.
   *
   * @param entity the entity being resumed
   * @param config the entity's configuration columns
   * @param queries the answers the resume pulls from the rest of the simulation
   * @param chain the list that records what the resume announced
   * @param setter how a requested state change is applied
   */
  public static void resume(
      GridEntity entity,
      StateVisitConfig config,
      StateQueries queries,
      List<String> chain,
      StateSetter setter) {
    int state = entity.getState();
    if (state == GridEntityState.MORPHING
        || state == GridEntityState.WAITING_TO_DEPLOY
        || state == GridEntityState.FOLLOWING_REMOVED
        || state == GridEntityState.FOLLOWING_REMOVED_BUILDING) {
      return;
    }
    if (state == GridEntityState.CASTING) {
      chain.add("ability_cast_active");
      if (!queries.abilityCastActive()) {
        return;
      }
      if (!(config.abilityPresent() && config.abilityHoldsState())) {
        return;
      }
    }
    chain.add("may_hold_route");
    if (queries.mayHoldRoute()) {
      chain.add("grid_route_flag");
      boolean routeFlag = queries.gridRouteFlag();
      chain.add("grid_allows_route");
      if (queries.gridAllowsRoute(routeFlag)) {
        setter.setState(entity, GridEntityState.MOVING);
        return;
      }
    }
    chain.add("has_hit_points");
    if (queries.hasHitPoints()) {
      setter.setState(entity, GridEntityState.STANDING);
      return;
    }
    if (config.kamikaze() || config.kingTowerMiddle() || config.neutralObject()) {
      return;
    }
    chain.add("remove");
  }
}
