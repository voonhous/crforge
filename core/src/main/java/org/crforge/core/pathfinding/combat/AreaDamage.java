package org.crforge.core.pathfinding.combat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.index.ShapeTests;
import org.crforge.core.pathfinding.target.ReferenceValidator;
import org.crforge.core.pathfinding.target.TargetView;
import org.crforge.core.pathfinding.target.TargetingState;
import org.crforge.core.pathfinding.target.ValidatorQueries;

/**
 * Damage that lands on an area: which entities are hit, and what each of them takes.
 *
 * <p>The victims are collected first, walking the battle's entities in id order. An entity is kept
 * when:
 *
 * <ol>
 *   <li>the shared validator accepts it as a target of the area's owner: not the owner, not on the
 *       owner's side unless the area may hit it, not untargetable, and carrying hit points;
 *   <li>it is not an air unit the area does not reach, nor a ground unit or building the area does
 *       not reach, and nothing makes it untouchable;
 *   <li>it lies in the circle: a building's square, with the centre clamped into it, must lie
 *       strictly inside the radius; anything else must have its centre closer than the radius plus
 *       its own collision radius;
 *   <li>it is not a second tower-slot entity: one area takes at most one of them;
 *   <li>the victims do not already number the limit, when a limit is given.
 * </ol>
 *
 * <p>Then every victim with hit points takes the damage, or the crown-tower damage when it is a
 * crown tower, shared out evenly and rounded up when the area splits it. Only an amount of at least
 * one is dealt.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the collection in id order through the shared validator, the air and ground"
            + " gates, the building-aware circle test, one tower-slot entity per area, the limit,"
            + " the even split rounded up, the crown-tower damage for a crown tower and the floor"
            + " of one. Held by the Wizard run's three impacts. Supplied, not settled: nothing is"
            + " untouchable. Not modelled: the second circle and the dedupe list of a chained"
            + " projectile, the area objects of their own kind, the heal of the owner's side,"
            + " the pushback and its visuals, and the death presentation.")
public final class AreaDamage {

  private AreaDamage() {
    // Utility class
  }

  /**
   * One area: where it is, how large, what it deals and whom it may reach.
   *
   * @param x centre along the arena's width
   * @param y centre along the arena's length
   * @param radius radius of the circle
   * @param damage what an ordinary victim takes
   * @param towerDamage what a crown tower takes
   * @param hitId the id of the hit the damage carries
   * @param limit the most victims the area takes; 0 or less for no limit
   * @param ownSide true when the area may hit the owner's own side
   * @param hitsAir whether the area reaches air units
   * @param hitsGround whether the area reaches ground units and buildings
   * @param split true to share the damage out evenly among the victims
   */
  public record Area(
      int x,
      int y,
      int radius,
      int damage,
      int towerDamage,
      int hitId,
      int limit,
      boolean ownSide,
      boolean hitsAir,
      boolean hitsGround,
      boolean split) {}

  /** What the area asks of the battle about a victim. */
  public interface Queries {

    /** Whether the victim may not be damaged at all right now. */
    default boolean untouchable(TargetView victim) {
      return false;
    }

    /**
     * Deals one victim its share.
     *
     * @param victim the victim
     * @param damage what it takes, before its own guards and the clamp to zero
     * @param hitId the id of the hit
     */
    DamageResult damage(TargetView victim, int damage, int hitId);
  }

  /**
   * Damages the area.
   *
   * @param owner the targeting state standing for the area's owner, whose side and identity the
   *     validator compares against
   * @param entities the battle's entities that may be hit, in id order
   * @param area the area
   * @param validatorQueries the game mode's answers to the validator
   * @param queries what the area asks of the battle
   * @return the victims, in the order they were collected
   */
  public static List<TargetView> damage(
      TargetingState owner,
      List<TargetView> entities,
      Area area,
      ValidatorQueries validatorQueries,
      Queries queries) {
    List<TargetView> victims = new ArrayList<>();
    boolean towerSlotTaken = false;
    for (TargetView entity : entities) {
      if (!ReferenceValidator.sharedValidate(
          owner, entity, area.ownSide(), false, true, false, validatorQueries)) {
        continue;
      }
      if (entity.air() && !area.hitsAir()) {
        continue;
      }
      if (entity.ground() && !area.hitsGround()) {
        continue;
      }
      if (queries.untouchable(entity)) {
        continue;
      }
      if (!ShapeTests.withinCircleShape(entity.getEntity(), area.x(), area.y(), area.radius())) {
        continue;
      }
      // One area takes at most one of the entities that fill a side's tower slot.
      if (entity.towerFlag() && towerSlotTaken) {
        continue;
      }
      victims.add(entity);
      towerSlotTaken |= entity.towerFlag();
      if (area.limit() >= 1 && victims.size() >= area.limit()) {
        break;
      }
    }

    int damage = area.damage();
    int towerDamage = area.towerDamage();
    if (area.split() && !victims.isEmpty()) {
      int n = victims.size();
      // Rounded up for a positive amount: the division truncates toward zero.
      damage = (damage + n - 1) / n;
      towerDamage = (towerDamage + n - 1) / n;
    }
    for (TargetView victim : victims) {
      if (!victim.isHitPointsPresent()) {
        continue;
      }
      int dealt = victim.isCrownTowerTarget() ? towerDamage : damage;
      if (dealt >= 1) {
        queries.damage(victim, dealt, area.hitId());
      }
    }
    return victims;
  }
}
