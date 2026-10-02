package org.crforge.core.pathfinding;

import static org.crforge.core.util.ValidationUtils.checkArgument;

import java.util.function.Supplier;
import lombok.Setter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.move.MovementChain;
import org.crforge.core.pathfinding.move.MovementConfig;
import org.crforge.core.pathfinding.move.MovementState;
import org.crforge.core.pathfinding.state.StateSetter;
import org.crforge.core.pathfinding.state.StateTimers;
import org.crforge.core.pathfinding.target.TargetingState;

/**
 * The state setter of one grid-driven unit: the interrupt guard, and the actions a state change
 * runs on the unit's movement and targeting components.
 *
 * <p>A state change is more than a store. Leaving the attacking state clears the targeting
 * component's target-lost timer and starts a row's not-attacking countdown; leaving the deploying
 * or either pathfinding state clears the deploy countdown, unless the unit is leaving for clone
 * setup. Entering the standing, attacking, clone-setup or casting state empties the route and
 * clears the route-leads-away bit, so a unit that stops holds no route; entering clone setup also
 * switches the movement component on, and leaving it empties the route and clears the movement
 * component's destination. Entering the moving state prepares a route at once, over whatever
 * reference the targeting component holds at that moment, rather than waiting for the next movement
 * visit. The actions run in that order: exit actions, the store, entry actions.
 *
 * <p>The guard comes first: while the deploy countdown is running, only clone setup and the two
 * removed-and-following states may be set, so nothing pulls a unit that is still being placed into
 * attacking or moving. A request for the state the unit is already in does nothing at all.
 *
 * <p>The route preparation needs the movement pass's answers over the unit's <b>current</b>
 * reference, which is why the setter is given a supplier of chains rather than a chain: each
 * preparation builds a fresh one.
 *
 * <p>A unit without a movement component skips every route action, and one without a targeting
 * component skips the target-lost clear; either may be null.
 *
 * <p>A setter given the unit's deploy time seeds the deploy countdown on entering the deploying
 * state with the larger of the countdown and the deploy time, which is how a unit that waited its
 * turn starts deploying.
 *
 * <p>Entering the dashing state raises the dashing flag and, for a unit with a movement component,
 * resets its charge - to 0 for a row with a charge range, to none without, with the targeting
 * component's strike-now byte cleared - loads its dash timer with the row's constant dash time and
 * clears its landing hold.
 *
 * <p>Entering either pathfinding state drops the damage pending on the unit, as the unit's own
 * entry hook does; the pending duration is kept.
 *
 * <p>Entering the casting state, for a unit given its casting, raises the casting flag and seeds
 * the ability's two countdowns in whole ticks, and empties the route. Entering the ability's
 * follow-up state seeds the cast's countdown with the follow-up's duration in whole ticks. Leaving
 * it before the effect fired leaves the ability pending again, and a unit with a movement component
 * has its charge reset. A change into or out of the casting state ends with the unit's combat gate.
 *
 * <p><b>Not carried here.</b> The standard game also switches components on and off as states
 * change, seeds the morph countdown on entering the morphing state, chains a further dash on
 * leaving the dashing state and runs the row's closing action, resets the charge on leaving the
 * follow-up states, relocates a unit leaving a following state to a free cell, makes a building
 * asked to follow stand instead, and ends every other change with two notifications. None of that
 * is reachable from a plain ground unit, which is all the grid drives.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the interrupt guard; the route emptied on entering the standing, attacking,"
            + " clone-setup and casting states; the route prepared on entering the moving"
            + " state; the target-lost timer cleared, and the not-attacking countdown started,"
            + " on leaving the attacking state, the second held by ghost_river_wizard_tower; the"
            + " deploy countdown cleared on leaving the deploying and pathfinding states and"
            + " raised to the unit's deploy time on entering the deploying state; the dashing"
            + " state's entry, held by bandit_knight; the casting state's entry and exit"
            + " with the combat gate after them, held by giant_buffer_knights; the pending"
            + " damage dropped, and every projectile aimed at the unit losing it, on entering"
            + " either pathfinding state, held by mighty_miner_ability_tower, whose tower arrow is"
            + " in flight as the Mighty Miner switches lanes; the combat gate on leaving the"
            + " in-game pathfinding state, held by both Mighty Miner ability runs; the follow-up"
            + " state's countdown seeded on its entry, held by both Monk ability runs; the clone"
            + " setup's entry"
            + " and exit, held by clone_golem_group. Held by the 53"
            + " reference walks, whose route empties at the lock, and the staggered placements."
            + " Not modelled: switching components, the countdown seeded on entering the morphing"
            + " state, the chained dash and closing action on leaving the dashing state, whose"
            + " columns are refused, the following-state rewrites and the two notifications every"
            + " change ends with. A cast with no countdowns at all is refused.")
public final class GridStateSetter implements StateSetter {

  /** Milliseconds per tick, which the casting countdowns are counted in. */
  private static final int TICK_MS = 50;

  private final GridEntity owner;
  private final MovementState movement;
  private final TargetingState targeting;
  private final Supplier<MovementChain> chains;

  /** The deploy countdown entering the deploying state seeds; -1 for a setter that seeds none. */
  private final int deployTimeMs;

  /** The unit's movement columns, which the dashing state's entry reads; null for none. */
  private final MovementConfig movementConfig;

  /**
   * What entering and leaving the casting state needs: the unit's countdowns, its ability's cast
   * time and trigger delay, whether it is a champion's clone, the combat gate the change ends with,
   * and how long the ability's follow-up state lasts.
   *
   * @param timers the unit's state-visit countdowns, which the entry seeds
   * @param castTimeMs the ability's cast time
   * @param triggerDelayMs the ability's trigger delay
   * @param championClone true for a clone of a champion, whose cast is never left pending
   * @param combatGate the combat gate, run at the end of a change into or out of the casting state
   * @param abilityStateDurationMs how long the follow-up state lasts, which its entry seeds the
   *     cast's countdown with in whole ticks; 0 for an ability without one
   */
  public record Casting(
      StateTimers timers,
      int castTimeMs,
      int triggerDelayMs,
      boolean championClone,
      Runnable combatGate,
      int abilityStateDurationMs) {

    /** A casting whose ability has no follow-up state. */
    public Casting(
        StateTimers timers,
        int castTimeMs,
        int triggerDelayMs,
        boolean championClone,
        Runnable combatGate) {
      this(timers, castTimeMs, triggerDelayMs, championClone, combatGate, 0);
    }
  }

  /**
   * What the hook's states do beyond the setter's own fields: the unit's component switches, the
   * reference check, the standing test with its relocation, and the combat gate every change into
   * or out of one of them ends with.
   */
  public interface Following {

    /**
     * Switches both components, the targeting and the movement one, on or off.
     *
     * @param on true to switch them on
     */
    void components(boolean on);

    /** Switches the movement component back on, as leaving the held state to attack does. */
    void movementOn();

    /** Drops the targeting reference when it no longer passes the attack range test. */
    void dropReferenceOutOfRange();

    /** Moves the unit off a cell the standing test refuses, as leaving a pulled state does. */
    void standOrRelocate();

    /** The combat gate a change into or out of the hook's states ends with. */
    void tailGate();
  }

  /** The unit's hook states, or null for a unit the setter refuses to move into them. */
  @Setter private Following following;

  /** The unit's casting, or null for a unit without an ability, which never casts. */
  @Setter private Casting casting;

  /**
   * What entering the deploying state makes besides its countdown - a row's area object, made and
   * updated at once - or null for nothing.
   */
  @Setter private Runnable deployingEntry;

  /**
   * What leaving the attacking state does besides clearing the target-lost timer - a row with a
   * buff while it is not attacking starts that countdown - or null for nothing.
   */
  @Setter private Runnable attackingExit;

  /**
   * What entering either pathfinding state does besides dropping the pending damage - the
   * projectiles aimed at the unit lose it as their target - or null for nothing.
   */
  @Setter private Runnable pathfindEntry;

  /**
   * The combat gate a change out of the in-game pathfinding state ends with, on the targeting
   * component's own switch, or null for a unit that never enters that state.
   */
  @Setter private Runnable ingamePathfindExitGate;

  /**
   * Creates the setter of one unit.
   *
   * @param owner the unit whose state this sets
   * @param movement the unit's movement component, or null when it has none
   * @param targeting the unit's targeting component, or null when it has none
   * @param chains builds a movement chain over the unit's current reference, for the route
   *     preparation that entering the moving state runs
   */
  public GridStateSetter(
      GridEntity owner,
      MovementState movement,
      TargetingState targeting,
      Supplier<MovementChain> chains) {
    this(owner, movement, targeting, chains, -1);
  }

  /**
   * Creates the setter of one unit that seeds its deploy countdown on entering the deploying state.
   *
   * @param owner the unit whose state this sets
   * @param movement the unit's movement component, or null when it has none
   * @param targeting the unit's targeting component, or null when it has none
   * @param chains builds a movement chain over the unit's current reference
   * @param deployTimeMs the unit's deploy time, which entering the deploying state seeds the
   *     countdown with; -1 to seed nothing
   */
  public GridStateSetter(
      GridEntity owner,
      MovementState movement,
      TargetingState targeting,
      Supplier<MovementChain> chains,
      int deployTimeMs) {
    this(owner, movement, targeting, chains, deployTimeMs, null);
  }

  /**
   * Creates the setter of one unit that seeds its deploy countdown and can enter the dashing state.
   *
   * @param owner the unit whose state this sets
   * @param movement the unit's movement component, or null when it has none
   * @param targeting the unit's targeting component, or null when it has none
   * @param chains builds a movement chain over the unit's current reference
   * @param deployTimeMs the unit's deploy time, or -1 to seed nothing
   * @param movementConfig the unit's movement columns, whose charge range and constant dash time
   *     the dashing state's entry reads; null for a unit that never dashes
   */
  public GridStateSetter(
      GridEntity owner,
      MovementState movement,
      TargetingState targeting,
      Supplier<MovementChain> chains,
      int deployTimeMs,
      MovementConfig movementConfig) {
    this.owner = owner;
    this.movement = movement;
    this.targeting = targeting;
    this.chains = chains;
    this.deployTimeMs = deployTimeMs;
    this.movementConfig = movementConfig;
  }

  /**
   * What leaving the dashing state does for a unit whose dashes chain: the chain's next dash, which
   * keeps the unit dashing, or the dash's end.
   */
  public interface DashExit {

    /**
     * Starts the chain's next dash as the unit is asked to leave for a state, when it may.
     *
     * @param newState the state asked for
     * @return true when a next dash started: the unit stays dashing, and nothing else is done
     */
    boolean chain(int newState);

    /** The dash's end, as the unit leaves the dashing state with no next dash. */
    void end();
  }

  /** What leaving the dashing state does, for a unit whose dashes chain; null for any other. */
  private DashExit dashExit;

  /**
   * Gives the setter what leaving the dashing state does for a unit whose dashes chain.
   *
   * @param dashExit the chain and the dash's end
   */
  public void setDashExit(DashExit dashExit) {
    this.dashExit = dashExit;
  }

  @Override
  public void setState(GridEntity entity, int newState) {
    checkArgument(
        entity == owner,
        () -> "the setter of " + owner.getName() + " was asked about " + entity.getName());
    int oldState = owner.getState();
    if (oldState == newState) {
      return;
    }
    if (owner.getDeployCountdown() >= 1 && !interruptsDeployment(newState)) {
      return;
    }
    boolean hook = hookState(oldState) || hookState(newState);
    if (hook && following == null) {
      throw new UnsupportedOperationException(
          owner.getName() + " is asked into or out of a hook's state, not modelled for it");
    }
    // Leaving the dashing state, a chain's next dash keeps the unit dashing: nothing is stored.
    if (oldState == GridEntityState.DASHING && dashExit != null && dashExit.chain(newState)) {
      return;
    }
    exit(oldState, newState);
    owner.setState(newState);
    enter(oldState, newState);
    // A change into or out of the casting state, or the hook's states, ends with the combat gate;
    // every other change leaves the gate to the state visit's tail.
    if ((oldState == GridEntityState.CASTING || newState == GridEntityState.CASTING)
        && casting != null) {
      casting.combatGate().run();
    }
    if (hook) {
      following.tailGate();
    }
    // An arrival out of the in-game pathfinding state ends with the combat gate too: a unit that
    // deploys again there drops its reference.
    if (oldState == GridEntityState.INGAME_PATHFIND && ingamePathfindExitGate != null) {
      ingamePathfindExitGate.run();
    }
  }

  /** The two pulled states and the held state a hook sets. */
  private static boolean hookState(int state) {
    return state == GridEntityState.FOLLOWING_REMOVED
        || state == GridEntityState.FOLLOWING_REMOVED_BUILDING
        || state == GridEntityState.COMPONENTS_DISABLED;
  }

  /** The three states that may be set while the deploy countdown is still running. */
  private static boolean interruptsDeployment(int state) {
    return state == GridEntityState.CLONE_SETUP
        || state == GridEntityState.FOLLOWING_REMOVED
        || state == GridEntityState.FOLLOWING_REMOVED_BUILDING;
  }

  /** The actions keyed by the state being left. */
  private void exit(int oldState, int newState) {
    switch (oldState) {
      case GridEntityState.ATTACKING -> {
        if (targeting != null) {
          targeting.setTargetLostTimerMs(0);
        }
        if (attackingExit != null) {
          attackingExit.run();
        }
      }
      case GridEntityState.DEPLOYING,
          GridEntityState.SPAWN_PATHFIND,
          GridEntityState.INGAME_PATHFIND -> {
        if (newState != GridEntityState.CLONE_SETUP) {
          owner.setDeployCountdown(0);
        }
      }
      case GridEntityState.CASTING -> exitCasting();
      case GridEntityState.DASHING -> {
        if (dashExit != null) {
          dashExit.end();
        }
      }
      case GridEntityState.FOLLOWING_REMOVED, GridEntityState.FOLLOWING_REMOVED_BUILDING ->
          exitPulled(newState);
      // Leaving a clone's setup empties the route and forgets the point the move aimed at.
      case GridEntityState.CLONE_SETUP -> {
        if (movement != null) {
          resetRoute();
          movement.setExplicitX(-1);
          movement.setExplicitY(-1);
        }
      }
      default -> {
        // No ported action.
      }
    }
  }

  /**
   * Leaving a pulled state: both components switched on and, with a movement component, the point
   * the unit aimed at, the charge and the pushback's bytes reset and a reference out of range
   * dropped; then the standing test on the unit's cell, which moves it off a cell it may not stand
   * on. The route the standard game empties here too is emptied, or prepared afresh, by the entry
   * of the standing or the moving state, the only two a pulled unit is asked into.
   */
  private void exitPulled(int newState) {
    following.components(true);
    if (movement != null) {
      movement.setExplicitX(-1);
      movement.setExplicitY(-1);
      resetCharge();
      movement.setPushbackInFlight(0);
      movement.setAttackPushback(0);
      following.dropReferenceOutOfRange();
    }
    if (newState == GridEntityState.CLONE_SETUP) {
      // Cloned while pulled, the unit would go straight into the clone's setup.
      throw new UnsupportedOperationException(
          owner.getName() + " leaves a pulled state for a clone's setup, not modelled");
    }
    following.standOrRelocate();
  }

  /**
   * Leaving the casting state: a cast left before its effect fired is pending again, unless the
   * unit is a champion's clone, and a unit with a movement component has its charge reset.
   */
  private void exitCasting() {
    if (casting != null
        && casting.timers().getAbilityWarningCountdown() >= 1
        && !casting.championClone()) {
      owner.setPendingFlags(owner.getPendingFlags() | EntityFlags.ABILITY_COOLDOWN_PAUSED);
      casting.timers().setAbilityReady(true);
    }
    if (movement != null) {
      resetCharge();
    }
  }

  /**
   * Entering the casting state: the casting flag raised and the countdowns seeded in whole ticks,
   * the cast time's and the trigger delay's. A cast with neither, whose effect fires in the entry
   * itself, is refused.
   */
  private void enterCasting() {
    if (casting == null) {
      throw new UnsupportedOperationException(
          owner.getName() + " enters the casting state without an ability");
    }
    owner.setPendingFlags(owner.getPendingFlags() | EntityFlags.CASTING_ABILITY);
    int cast = casting.castTimeMs() / TICK_MS;
    int trigger = casting.triggerDelayMs() / TICK_MS;
    if ((cast | trigger) == 0) {
      throw new UnsupportedOperationException(
          owner.getName() + " casts with no cast time and no trigger delay, not modelled");
    }
    casting.timers().setAbilityCountdown(cast);
    casting.timers().setAbilityWarningCountdown(trigger);
  }

  /**
   * The charge reset: to 0 for a row with a charge range, to none without, with the targeting
   * component's strike-now byte cleared.
   */
  private void resetCharge() {
    checkArgument(
        movementConfig != null,
        () -> "the setter of " + owner.getName() + " has no movement columns to reset a charge by");
    movement.setChargeProgress(
        movementConfig.chargeRange() != 0 ? 0 : MovementState.CHARGE_INACTIVE);
    if (targeting != null) {
      targeting.setChargeStrike(false);
    }
  }

  /** The actions keyed by the state being entered, run after it is stored. */
  private void enter(int oldState, int newState) {
    switch (newState) {
      case GridEntityState.STANDING -> resetRoute();
      case GridEntityState.ATTACKING -> {
        if (oldState == GridEntityState.COMPONENTS_DISABLED) {
          following.movementOn();
        }
        resetRoute();
      }
      // Pulled: a reference out of range is dropped, and both components are switched off; held:
      // both are switched off. The combat gate after it switches the targeting one back on in all
      // but the troop's pulled state.
      // A building, which the standard game makes stand instead, is never asked: a hook on one
      // drags its owner.
      case GridEntityState.FOLLOWING_REMOVED, GridEntityState.FOLLOWING_REMOVED_BUILDING -> {
        following.dropReferenceOutOfRange();
        following.components(false);
      }
      case GridEntityState.COMPONENTS_DISABLED -> following.components(false);
      // A clone's setup switches the movement component on and empties the route; the charge is
      // kept, the standard game not resetting it there.
      case GridEntityState.CLONE_SETUP -> {
        if (movement != null && owner.isMovementComponent()) {
          owner.setMovementActive(true);
        }
        resetRoute();
      }
      case GridEntityState.CASTING -> {
        enterCasting();
        resetRoute();
      }
      case GridEntityState.MOVING -> prepareRoute();
      case GridEntityState.DASHING -> enterDash();
      // The ability's follow-up state counts its duration down on the cast's countdown, in whole
      // ticks; the state visit takes it from there.
      case GridEntityState.ABILITY_FOLLOW_UP -> enterFollowUp();
      // The entity's own entry hook: a unit that goes underground or pathfinds in the battle drops
      // the damage pending on it, whose duration it keeps, and the projectiles aimed at it lose it.
      case GridEntityState.SPAWN_PATHFIND, GridEntityState.INGAME_PATHFIND -> {
        owner.setPendingDamageAmount(0);
        if (pathfindEntry != null) {
          pathfindEntry.run();
        }
      }
      case GridEntityState.DEPLOYING -> {
        // Entering the deploying state switches the movement component on, which a unit that
        // waited its turn had off; a building has none to switch.
        if (movement != null && owner.isMovementComponent()) {
          owner.setMovementActive(true);
        }
        if (deployTimeMs >= 0) {
          // The entry keeps the larger of the running countdown and the deploy time. The guard
          // refuses this state while the countdown is 1 or more, so here it is the deploy time.
          owner.setDeployCountdown(Math.max(owner.getDeployCountdown(), deployTimeMs));
        }
        if (deployingEntry != null) {
          deployingEntry.run();
        }
      }
      default -> {
        // No ported action.
      }
    }
  }

  /**
   * The follow-up state's entry: the cast's countdown seeded with the ability's follow-up duration
   * in whole ticks.
   */
  private void enterFollowUp() {
    if (casting == null) {
      throw new UnsupportedOperationException(
          owner.getName() + " enters the ability's follow-up state without an ability");
    }
    casting.timers().setAbilityCountdown(casting.abilityStateDurationMs() / TICK_MS);
  }

  /**
   * The dashing state's entry: the dashing flag raised and, with a movement component, the charge
   * reset, the dash timer loaded and the landing hold cleared.
   */
  private void enterDash() {
    owner.setPendingFlags(owner.getPendingFlags() | EntityFlags.DASHING);
    if (movement == null) {
      return;
    }
    resetCharge();
    // The dash timer: the constant dash time, when the row has one; a fixed dash distance, which
    // times the dash by its length instead, is refused with its row.
    if (movementConfig.dashConstantTime() >= 1) {
      movement.setDashTimeMs(movementConfig.dashConstantTime());
    }
    owner.setBlockCountdownMs(0);
  }

  /** Empties the route and clears the route-leads-away bit. */
  private void resetRoute() {
    if (movement == null) {
      return;
    }
    movement.getRoute().clear();
    movement.setRouteLeadsAway(0);
  }

  /**
   * Prepares a route over the unit's current reference, as entering the moving state does. Storing
   * a reference asks for the same preparation, so a targeting component's owner points its route
   * request here.
   */
  public void prepareRoute() {
    if (movement == null) {
      return;
    }
    chains.get().prepareRoute();
  }
}
