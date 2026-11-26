/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import java.util.List;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.battle.projectile.ProjectileData;

/**
 * What a Royal Chef's cooking run asks of the battle around the king tower that runs it. Objects
 * are named by their ids, as the run keeps them.
 */
public interface CookingHost {

  /**
   * The king's side's princess-slot towers, in the order the holder lists them. A destroyed tower
   * is listed until the cleanup that removes it from the battle, and not after.
   */
  List<Integer> towers();

  /** Whether a tower is in the attacking state. */
  boolean attacking(int towerId);

  /** A tower's contribution as its buffs scale its hit speed: the value itself with no buff. */
  int scaled(int towerId, int contribution);

  /**
   * The live list's objects the filter lets through, asked for the king's team and row name, in the
   * holder's order.
   *
   * @param filter the filter row
   * @return their ids
   */
  List<Integer> candidates(GameObjectFilter filter);

  /** Whether an object with the id is still in the battle's live list. */
  boolean found(int id);

  /** An object's state, as its state field holds it. */
  int state(int id);

  /** Whether an object has a hit-points component. */
  boolean hasHitPoints(int id);

  /** An object's maximum hit points, its shield's maximum added. */
  int maximum(int id);

  /** An object's hit points, its shield's added. */
  int current(int id);

  /** A threshold scaled as card hit points at an object's level and rarity. */
  int scaledThreshold(int id, int base);

  /** How many instances of the named buff an object carries; 0 for an object with no buffs. */
  int buffCount(int id, String buff);

  /**
   * The squared distance from a tower to an object, the largest int when it would not fit, as the
   * choice of the throwing tower compares them.
   */
  int squaredDistance(int towerId, int id);

  /**
   * Whether a tower's attack would let a throw go now: true when it is not attacking with its
   * targeting component on, and otherwise only when more than the wait after its load and more than
   * the threshold before its next hit are left.
   *
   * @param towerId the tower
   * @param waitAfterAttackMs how long after a hit's load the throw waits
   * @param thresholdMs how close to its next hit the throw waits
   */
  boolean throwAllowed(int towerId, int waitAfterAttackMs, int thresholdMs);

  /** Turns a tower toward an object, as a throw does. */
  void turn(int towerId, int id);

  /**
   * Throws a projectile at an object from a point a distance from a tower toward it, at the king's
   * height, launched and owned by the king at its level and for its side, and hands it to the
   * holder.
   *
   * @param projectile the projectile row
   * @param towerId the tower it is thrown from
   * @param targetId the object it is thrown at
   * @param startOffset how far toward the object from the tower it starts
   */
  void throwAt(ProjectileData projectile, int towerId, int targetId, int startOffset);
}
