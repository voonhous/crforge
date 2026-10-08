package org.crforge.core.pathfinding.move;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.grid.Route;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * One visit of an entity's movement component: the entry point the tick driver calls once per tick,
 * after targeting and before the entity's state visit.
 *
 * <p>The visit picks one of five paths and never more:
 *
 * <ol>
 *   <li>an attached entity is placed on a circle around whatever it is attached to and does not
 *       route at all;
 *   <li>a morphing entity does nothing;
 *   <li>an entity whose movement countdown is running replays the last displacement and counts the
 *       countdown down;
 *   <li>an entity with a pushback in flight runs the pushback visit;
 *   <li>otherwise it prepares a route when its state asks for one and then follows it.
 * </ol>
 *
 * <p>The visit itself performs almost nothing: it announces route preparation, the follower, the
 * push pass and each displacement, and {@link MovementChain} runs them where they are announced.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Agrees with the reference line for line. Held: the ordinary visit of a walking ground"
            + " unit, by the walks of the reference battles; the pushback visit of a unit's own"
            + " recoil, by the reference battles zap_machine_launch_recoil and"
            + " zap_machine_fireballed_on_launch, its relocation off the river not checked"
            + " against a recorded battle; the attached placement of a rider without a rotation"
            + " limit, by card_GoblinGiant. Not held by any fixture: the limited rotation of an"
            + " attacking rider, the pushback visit's end action, the block countdown. Collision"
            + " checks are always on. The route dropped at a flight's end, which the battle core"
            + " always sets, is held by the reference battles of a Monk's push on a Giant, the"
            + " Zap Machine's recoil and two death explosions' pushes.")
public final class MovementVisit {

  /** The x value that marks an entity's position as never having been written. */
  public static final int UNSET_POSITION = Integer.MAX_VALUE;

  /** How far a pushback's budget falls per visit, in game units. */
  private static final int PUSHBACK_DECAY = 25;

  /** Full turn in degrees, which the attachment share is taken out of. */
  private static final int FULL_TURN = 360;

  /** Scale the sine helper answers in. */
  private static final int SINE_SCALE = 1024;

  private MovementVisit() {
    // Utility class
  }

  /**
   * Runs one movement visit.
   *
   * @param component the entity's movement component
   * @param owner the entity being moved
   * @param parent what the entity is attached to, or null when it moves on its own
   * @param config the entity's movement configuration columns
   * @param parentConfig the configuration of the entity it is attached to, or null
   * @param queries the answers the movement pass pulls from the rest of the simulation
   * @param noCheckCollisions true when the entity ignores collisions this visit, which skips the
   *     push pass and the relocation of an in-flight pushback
   * @param chain the chain that runs the passes the visit reaches and records the rest
   */
  public static void movementVisit(
      MovementState component,
      GridEntity owner,
      AttachedParent parent,
      MovementConfig config,
      MovementConfig parentConfig,
      MovementQueries queries,
      boolean noCheckCollisions,
      MovementChain chain) {
    if (parent != null) {
      attachedPlacement(owner, parent, config, parentConfig, chain);
      return;
    }
    if (owner.getState() == GridEntityState.MORPHING) {
      return;
    }
    if (component.getBlockCountdown() >= 1) {
      if (component.getPushbackBudget() >= 1) {
        chain.pushPass();
      }
      chain.displace(
          component.getTargetX(), component.getTargetY(), component.getPushbackBudget(), 0, 0);
      component.setBlockCountdown(component.getBlockCountdown() - 1);
      if (component.getBlockCountdown() == 0) {
        component.setPushbackBudget(0);
      }
      return;
    }
    if (component.getPushbackInFlight() != 0) {
      pushbackVisit(component, owner, config, queries, noCheckCollisions, chain);
      return;
    }
    chain.mark("route_query");
    if ((queries.routeRequest() & 1) != 0) {
      chain.prepareRoute();
    }
    chain.follow();
  }

  /**
   * Places an entity that is attached to another on a circle around it and matches its facing.
   *
   * <p>The attached entity sits at the parent's position offset by the configured spawn radius
   * along its own share of the spawn arc. Outside an attack it simply copies the parent's facing;
   * while attacking it turns toward the parent's facing by at most the configured rotation per
   * visit, taking whichever of the three unwrapped candidate angles is closest.
   *
   * <p>Held by the reference battle card_GoblinGiant, whose riders have no rotation limit; the
   * limited rotation is not held.
   */
  static void attachedPlacement(
      GridEntity owner,
      AttachedParent parent,
      MovementConfig config,
      MovementConfig parentConfig,
      MovementChain chain) {
    GridEntity parentEntity = parent.entity();
    int parentHeading = FixedMath.angleOfVector(parentEntity.getDirX(), parentEntity.getDirY());
    int share = FixedMath.div(parent.shareAngle() * config.spawnMaxAngle(), FULL_TURN);
    int base = parentHeading + config.spawnAngleShift() + share;
    int radius = parentConfig.spawnRadius();
    int offsetX = FixedMath.div(FixedMath.sine1024(base + 270) * radius, SINE_SCALE);
    int offsetY = FixedMath.div(FixedMath.sine1024(base + 180) * radius, SINE_SCALE);
    setPosition(
        owner, parentEntity.getX() + offsetX, parentEntity.getY() + offsetY, config.flyingHeight());
    chain.mark("position_changed");
    if (owner.getState() != GridEntityState.ATTACKING) {
      owner.setDirX(parentEntity.getDirX());
      owner.setDirY(parentEntity.getDirY());
      return;
    }
    int limit = config.spawnAttachMaxRotation();
    if (limit == 0) {
      return;
    }
    int parentAngle = FixedMath.angleOfVector(parentEntity.getDirX(), parentEntity.getDirY());
    int ownerAngle = FixedMath.angleOfVector(owner.getDirX(), owner.getDirY());
    int distance = Math.abs(ownerAngle - parentAngle);
    int candidate = ownerAngle;
    if (Math.abs(ownerAngle - FULL_TURN - parentAngle) < distance) {
      candidate = ownerAngle - FULL_TURN;
    }
    if (Math.abs(ownerAngle + FULL_TURN - parentAngle) < distance) {
      candidate = ownerAngle + FULL_TURN;
    }
    int delta = candidate - parentAngle;
    int sign = delta >= 1 ? 1 : (delta < 0 ? -1 : 0);
    int rotation = Math.min(limit, distance);
    int heading = parentAngle + sign * rotation;
    owner.setDirX(FixedMath.div(FixedMath.sine1024(heading + 90), 4));
    owner.setDirY(FixedMath.div(FixedMath.sine1024(heading), 4));
  }

  /**
   * One visit of an in-flight pushback: the entity is pushed, moved off an unusable cell, then
   * displaced toward the pushback's target with a budget that falls 25 per visit. The visit whose
   * budget falls below 0 ends the flight; when the match-wide settings say so, that end also drops
   * the route the entity held.
   *
   * <p>Held by the reference battle zap_machine_launch_recoil: the Sparky's recoil after each
   * launch flies here. The relocation off the river before the displacement, on a tick the recoil
   * leaves it on the river, is not checked against a recorded battle.
   */
  static void pushbackVisit(
      MovementState component,
      GridEntity owner,
      MovementConfig config,
      MovementQueries queries,
      boolean noCheckCollisions,
      MovementChain chain) {
    if (!noCheckCollisions && component.getPushbackBudget() >= 1) {
      chain.pushPass();
      chain.mark("cell_test");
      if ((queries.cellTest(owner.getX(), owner.getY()) & 1) != 0) {
        chain.mark("air");
        if ((queries.air() & 1) == 0) {
          chain.mark("hovering");
          if ((queries.hovering() & 1) == 0) {
            chain.mark("relocate");
            int packed = queries.relocate(owner.getX(), owner.getY());
            setPosition(owner, packed & 0xffff, packed >> 16, owner.getZ());
          }
        }
      }
    }
    component.setPushbackBudget(component.getPushbackBudget() - PUSHBACK_DECAY);
    int budget = component.getPushbackBudget();
    int wasInFlight = component.getPushbackInFlight();
    chain.displace(
        component.getTargetX(), component.getTargetY(), budget, 1, component.getAttackPushback());
    component.setPushbackInFlight(budget >= 0 ? 1 : 0);
    if (wasInFlight == 0 || budget >= 0) {
      return;
    }
    if (component.getAttackPushback() != 0) {
      chain.mark("attack_pushback_end");
      if (config.attackPushbackEndAction() != null) {
        chain.mark(config.attackPushbackEndAction());
      }
    }
    // When the globals ask for it, as the battle core's always do, the flight's end, an attack
    // pushback's after its end action, drops the route and its leads-away bit: the next visit that
    // prepares a route then
    // searches one from where the pushback left the entity, instead of walking back toward the
    // waypoint it held before the push.
    if (chain.globals().pushbackEndDropsRoute()) {
      component.setRoute(new Route());
      component.setRouteLeadsAway(0);
    }
  }

  /**
   * Writes an entity's position, seeding its previous position as well the first time it is ever
   * written.
   */
  static void setPosition(GridEntity entity, int x, int y, int z) {
    if (entity.getX() == UNSET_POSITION) {
      entity.setPrevX(x);
      entity.setPrevY(y);
      entity.setPrevZ(z);
    }
    entity.setX(x);
    entity.setY(y);
    entity.setZ(z);
  }
}
