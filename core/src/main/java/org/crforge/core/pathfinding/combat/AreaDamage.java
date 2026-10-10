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
 * one is dealt. An area that pushes then pushes each such victim away from the push point, right
 * after its damage and before the next victim's, as far as the push says, when the battle finds the
 * victim may be pushed.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the collection in id order through the shared validator, the air and ground"
            + " gates, the building-aware circle test, one tower-slot entity per area, the limit,"
            + " the even split rounded up, the crown-tower damage for a crown tower and the floor"
            + " of one, with a projectile or a character as the owner. Held by the areas of the"
            + " reference battles card_Wizard, card_Valkyrie, troops_knight_vs_valkyrie and"
            + " troops_left_bridge_crowd. Supplied by the caller: what passes a victim by. A"
            + " projectile's area passes by a hidden victim and one its state gate refuses as it"
            + " collects (the untouchable test with the immunity left after a dash counted), held"
            + " by tv_replay_005. Not modelled: that gate in the other areas, which pass by a"
            + " hidden victim only and leave the rest to the damage drain's refusals. The"
            + " second circle and the id list of a chain of projectiles are held by card_Arrows,"
            + " arrows_s0_tower and spell_arrows_into_push, not the partner of a 2v2 tower. Not"
            + " modelled: the area objects of their own kind, the heal of the owner's side, the"
            + " push's floor and visuals, and the death presentation. The push is held by the"
            + " death damage of golem_death_pushes_minipekka_pekka.")
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
   * @param push how far each victim is pushed; 0 for no push
   * @param pushX the point victims are pushed away from, along the width
   * @param pushY the point victims are pushed away from, along the length
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
      boolean split,
      int push,
      int pushX,
      int pushY) {

    /** An area that pushes nothing. */
    public Area(
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
        boolean split) {
      this(
          x,
          y,
          radius,
          damage,
          towerDamage,
          hitId,
          limit,
          ownSide,
          hitsAir,
          hitsGround,
          split,
          0,
          0,
          0);
    }
  }

  /**
   * What one area did.
   *
   * @param inCircle every entity whose shape lies in the circle, in id order, whether or not it may
   *     be hit
   * @param validated those of them the validator accepts
   * @param victims the entities the collection kept
   * @param damaged the victims that were dealt an amount
   * @param pushed the victims that were pushed
   */
  public record Outcome(
      List<TargetView> inCircle,
      List<TargetView> validated,
      List<TargetView> victims,
      List<TargetView> damaged,
      List<TargetView> pushed) {}

  /**
   * What a chain of projectiles shares with each of its areas: a second circle every victim must
   * also stand in, and the ids the chain has hit, which no area of it hits again.
   *
   * @param x the second circle's centre along the width
   * @param y the second circle's centre along the length
   * @param radius its radius; 0 for none
   * @param hitIds the ids the chain has hit, which this area adds to
   */
  public record Chain(int x, int y, int radius, List<Integer> hitIds) {}

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

    /**
     * Pushes one victim away from a point, after its damage: the battle decides whether it can be
     * pushed - it has a movement component, its row does not ignore pushback, and it is still alive
     * - and asks for its pushback.
     *
     * @param victim the victim
     * @param x the point it is pushed away from, along the width
     * @param y the point it is pushed away from, along the length
     * @param distance how far
     * @return true when it was pushed
     */
    default boolean push(TargetView victim, int x, int y, int distance) {
      throw new UnsupportedOperationException("this area's battle does not push its victims");
    }
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
   * @return what the area did
   */
  public static Outcome damage(
      TargetingState owner,
      List<TargetView> entities,
      Area area,
      ValidatorQueries validatorQueries,
      Queries queries) {
    return damage(owner, entities, area, null, validatorQueries, queries);
  }

  /**
   * Damages the area of one projectile of a chain: as an ordinary area, with the chain's circle as
   * a second circle and its id list, which an entity in both circles is checked against and then
   * added to, before the one-per-area tower rule.
   *
   * @param chain what the chain shares, or null for an ordinary area
   */
  public static Outcome damage(
      TargetingState owner,
      List<TargetView> entities,
      Area area,
      Chain chain,
      ValidatorQueries validatorQueries,
      Queries queries) {
    List<TargetView> victims = new ArrayList<>();
    List<TargetView> inCircle = new ArrayList<>();
    List<TargetView> validated = new ArrayList<>();
    boolean towerSlotTaken = false;
    for (TargetView entity : entities) {
      boolean accepted =
          ReferenceValidator.sharedValidate(
              owner, entity, area.ownSide(), false, true, false, validatorQueries);
      // Who stood in the circle and what the validator made of each, for the report only: the
      // collection below tests the circle after the validator and the gates, as the rule runs.
      if (ShapeTests.withinCircleShape(entity.getEntity(), area.x(), area.y(), area.radius())) {
        inCircle.add(entity);
        if (accepted) {
          validated.add(entity);
        }
      }
      if (!accepted) {
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
      if (chain != null) {
        if (chain.radius() >= 1
            && !ShapeTests.withinCircleShape(
                entity.getEntity(), chain.x(), chain.y(), chain.radius())) {
          continue;
        }
        if (chain.hitIds().contains(entity.id())) {
          continue;
        }
        chain.hitIds().add(entity.id());
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
    List<TargetView> damaged = new ArrayList<>();
    List<TargetView> pushed = new ArrayList<>();
    for (TargetView victim : victims) {
      if (!victim.isHitPointsPresent()) {
        continue;
      }
      int dealt = victim.isCrownTowerTarget() ? towerDamage : damage;
      if (dealt >= 1) {
        queries.damage(victim, dealt, area.hitId());
        damaged.add(victim);
      }
      if (area.push() >= 1 && queries.push(victim, area.pushX(), area.pushY(), area.push())) {
        pushed.add(victim);
      }
    }
    return new Outcome(inCircle, validated, victims, damaged, pushed);
  }
}
