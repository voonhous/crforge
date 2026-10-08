package org.crforge.core.battle.action;

import java.util.List;
import org.crforge.core.battle.filter.GameObjectFilter;

/**
 * What a chain projectile attack's run asks of the battle around the character that runs it.
 * Objects are named by their ids, as the run keeps them.
 */
public interface ChainAttackHost {

  /** Where the owner stands along the width. */
  int ownerX();

  /** Where the owner stands along the length. */
  int ownerY();

  /** The owner's height. */
  int ownerZ();

  /** The owner's row's collision radius. */
  int ownerRadius();

  /**
   * True when the owner's tag word holds ATTACKING or NO_ATTACK: the first a hit of its own raised
   * on the step before, the second a stun or a hold.
   */
  boolean attackingOrNoAttack();

  /** True while the owner's targeting component is on. */
  boolean targetingOn();

  /** A time step as the owner's buffs scale its hit speed. */
  int timeStep(int stepMs);

  /** Whether an object with the id is in the battle's live list. */
  boolean live(int id);

  /** Where the object stands along the width. */
  int x(int id);

  /** Where the object stands along the length. */
  int y(int id);

  /** The object's height. */
  int z(int id);

  /**
   * The point a live projectile flies to: where its target stands now, at the target's height, for
   * a homing projectile that has one; otherwise its stored aim and aim height.
   *
   * @param id the projectile's id
   * @return {x, y, z}
   */
  int[] projectileAim(int id);

  /**
   * The objects the centre query around a point answers: the index's buckets over the circle,
   * walked in their order, each object once, accepted by the filter, asked for the owner's team and
   * row name, and with its centre strictly within the radius; no collision radius is added and no
   * building is tested by its square.
   *
   * @param x the circle's centre along the width
   * @param y the circle's centre along the length
   * @param radius the circle's radius
   * @param filter the filter row
   * @return the ids, in the query's order
   */
  List<Integer> centreQuery(int x, int y, int radius, GameObjectFilter filter);

  /** What a selection takes off the object's squared distance: its const-priority offset. */
  int priority(int id);

  /**
   * Launches the first hop from the owner: its start radius along the line to the target where it
   * stands now, shifted by its lengthwise offset and its start height, aimed at the target, the
   * owner as launcher and owner, at its level, handed to the holder.
   *
   * @param projectile the projectile's row
   * @param targetId the target's id
   * @return the projectile's id
   */
  int launchFromOwner(String projectile, int targetId);

  /**
   * Launches a later hop from a point, aimed at the target where it stands now, the owner as
   * launcher and owner, at its level, handed to the holder.
   *
   * @param projectile the projectile's row
   * @param targetId the target's id
   * @param x the start along the width
   * @param y the start along the length
   * @param z the start's height
   * @return the projectile's id
   */
  int launchFrom(String projectile, int targetId, int x, int y, int z);

  /** Tells the owner's listening actions that an attack ended, as a landed hit's end does. */
  void attackEnded();
}
