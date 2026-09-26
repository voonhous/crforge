package org.crforge.core.pathfinding.move;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
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
        "Agrees with the reference line for line. Held: the ordinary visit of a walking"
            + " ground unit, and the pushback visit of a unit's own recoil with its relocation off"
            + " the river, by the Sparky run. Not held by any fixture: attached placement and its"
            + " limited rotation, the pushback visit's end action, the block countdown. Both callers fix the parent to none and collision checks to on.")
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
   * <p>Not exercised by tests: no attached entity appears in the recorded trajectories.
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
   * displaced toward the pushback's target with a budget that falls 25 per visit.
   *
   * <p>Held by the Sparky run: its recoil after each launch flies here, and on the tick it stands
   * on the river the relocation moves it off before the displacement.
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
    if (wasInFlight != 0 && budget < 0 && component.getAttackPushback() != 0) {
      chain.mark("attack_pushback_end");
      if (config.attackPushbackEndAction() != null) {
        chain.mark(config.attackPushbackEndAction());
      }
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
