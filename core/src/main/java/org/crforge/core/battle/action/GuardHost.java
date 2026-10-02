package org.crforge.core.battle.action;

import java.util.List;
import org.crforge.core.battle.filter.GameObjectFilter;

/**
 * What a guard's run asks of the battle about the guard it runs on and what stands around it.
 * Objects are named by their ids, as the run keeps them.
 */
public interface GuardHost {

  /** The guard's entity state. */
  int state();

  /** What is left of the guard's deploy countdown, in milliseconds. */
  int deployCountdownMs();

  /** Cuts the guard's deploy short: the countdown to 0, then the standing state asked for. */
  void cutDeploy();

  /** The guard's side's direction along the arena's length: +1 for side 0, -1 for side 1. */
  int teamSign();

  /** The guard's position along the arena's width. */
  int x();

  /** The guard's position along the arena's length. */
  int y();

  /**
   * The objects the object query around the guard answers: every object whose centre lies strictly
   * within the radius plus its collision radius and that passes the filter, asked for the guard's
   * team, in the query's order.
   *
   * @param radius the circle's radius
   * @param filter the filter row
   * @return the ids, in the query's order
   */
  List<Integer> query(int radius, GameObjectFilter filter);

  /**
   * Pushes an object away from the guard, as the request that skips every gate does: refused only
   * while a pushback is in flight and the longer one is not to be kept.
   *
   * @param id the object
   * @param distance how far
   * @param subtract true to take the current separation off the distance first
   * @param keepLonger true to accept the push with a pushback in flight, keeping the longer one
   * @return -1 for an object whose movement component is off or absent, which is not asked; else 1
   *     when the setter ran and 0 when it was refused
   */
  int push(int id, int distance, boolean subtract, boolean keepLonger);

  /** True for a character. */
  boolean character(int id);

  /** True while the object may not be touched, a dash's immunity included. */
  boolean untouchable(int id);

  /** True for an object with hit points. */
  boolean hasHitPoints(int id);

  /**
   * A damage value at the guard's level, by the card damage rule and the guard's rarity.
   *
   * @param base the value at the first level
   * @return the scaled value
   */
  int damageAtLevel(int base);

  /**
   * Hits an object for an amount, the guard the attacker, along the line from the guard to it.
   *
   * @param id the object
   * @param amount the damage
   */
  void hit(int id, int amount);

  /** True when the guard has a targeting component. */
  boolean hasTargeting();

  /**
   * A point clamped into the arena: each axis to the cells' extent less one, a coordinate at or
   * below 0 giving 0.
   *
   * @return the clamped point, x then y
   */
  int[] clamp(int x, int y);

  /**
   * Starts the guard's charge toward a point: a dash with no reference, stopping short by the
   * guard's collision radius alone and never on a reference coming into range.
   */
  void charge(int x, int y);

  /** The name of an object, for the battle's observers. */
  String name(int id);

  /**
   * One step of the run, told to the battle's observers.
   *
   * @param charging whether the run is charging after the step
   * @param tags the tags the run sets after the step
   * @param done whether the step finished the run
   * @param calls what the step did, in order
   */
  void stepped(boolean charging, long tags, boolean done, List<String> calls);
}
