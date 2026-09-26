package org.crforge.core.battle.unit;

import java.util.List;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.combat.AreaDamage;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.core.pathfinding.move.MovementState;

/**
 * Something that watches one battle's arena from outside the tick: it is told where the tick's
 * entity visits begin and end, about every hit that lands, about every projectile launched and
 * every projectile that arrives, about every arena entity that leaves, and about each step of a
 * king tower's activation, and takes no part in any of it.
 *
 * <p>Both tick calls hand over the tick's arena entities in ascending id, the list the visits ran
 * over. An entity that became removable during the tick is still in it at the end, because the
 * closing cleanup runs after the last call.
 */
public interface WorldObserver {

  /**
   * After the tick's pre-pass: everything the visits query is built, and nothing has been visited.
   */
  default void afterPrePass(int tick, List<WorldEntity> present) {}

  /**
   * After every entity's post-hook and before the closing cleanup: every position and state of the
   * tick is final, an entity that died this tick is still present, and every projectile the tick
   * visited has taken its step.
   *
   * @param tick the tick
   * @param present the tick's arena entities in ascending id
   * @param projectiles the projectiles the tick visited in ascending id, those that arrived
   *     included; the ones launched during the tick are not among them
   */
  default void afterPostHooks(
      int tick, List<WorldEntity> present, List<ProjectileEntity> projectiles) {}

  /**
   * The damage of one direct hit was dealt to an entity.
   *
   * @param tick the tick the hit landed in
   * @param target the entity the damage was dealt to
   * @param damage hit points the hit dealt, before the target's guards and the clamp to zero
   * @param result what the damage did to the target
   */
  default void damageDealt(int tick, WorldEntity target, int damage, DamageResult result) {}

  /**
   * A projectile was launched and handed to the holder; it has its id and its start and aim, and
   * first flies on the next tick.
   */
  default void projectileLaunched(int tick, ProjectileEntity projectile) {}

  /**
   * A projectile arrived and dealt its damage to its target.
   *
   * @param tick the tick of the arrival
   * @param projectile the projectile, standing at its aim
   * @param target the entity the damage was dealt to
   * @param damage hit points the impact dealt, before the target's guards and the clamp to zero
   * @param result what the damage did to the target
   */
  default void projectileImpacted(
      int tick, ProjectileEntity projectile, WorldEntity target, int damage, DamageResult result) {}

  /**
   * An arena entity left the battle. Every remaining entity has been told of it, so what the
   * removal did to another entity's reference is already in place.
   *
   * @param tick the tick last run: the one whose closing cleanup removed the entity, which is where
   *     an entity that dies during a tick leaves
   * @param removed the entity that left
   */
  default void entityRemoved(int tick, WorldEntity removed) {}

  /**
   * A step of a king tower's activation happened: the condition in the run pass, the activating
   * run's start and the effect in a pending pass, its end and its removal in later run passes.
   */
  default void activation(int tick, TowerEntity king, ActivationEvent event) {}

  /**
   * One victim of the area of an entity's hit took its share.
   *
   * @param tick the tick of the hit
   * @param attacker the entity whose hit made the area
   * @param victim the entity the area collected
   * @param damage hit points dealt, before the victim's guards and the clamp to zero
   * @param hitId the id the hit carries
   * @param result what the damage did to the victim
   */
  default void areaHit(
      int tick,
      WorldEntity attacker,
      WorldEntity victim,
      int damage,
      int hitId,
      DamageResult result) {}

  /**
   * The area of an entity's hit is done: who stood in it, whom the validator accepted, and whom it
   * damaged. Told after every victim's own hit.
   */
  default void areaDamaged(
      int tick, WorldEntity owner, AreaDamage.Area area, AreaDamage.Outcome outcome) {}

  /**
   * A unit asked to push itself back after a launch, away from the launch's aim.
   *
   * @param tick the battle tick
   * @param unit the unit
   * @param started true when the request aimed a pushback now in flight
   * @param fromX the point pushed away from
   * @param fromY the point pushed away from
   * @param pushback the unit's movement component after the request
   */
  default void pushbackRequested(
      int tick, WorldEntity unit, boolean started, int fromX, int fromY, MovementState pushback) {}

  /**
   * A unit on a cell it may not stand on was moved to the nearest one it may, by its pushback
   * visit.
   *
   * @param tick the battle tick
   * @param unit the unit
   * @param x where it stood
   * @param y where it stood
   * @param toX where it was moved to
   * @param toY where it was moved to
   */
  default void relocated(int tick, WorldEntity unit, int x, int y, int toX, int toY) {}
}
