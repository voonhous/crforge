/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import java.util.List;
import org.crforge.core.battle.filter.GameObjectFilter;

/**
 * What a Goblin Hut's life state asks of the battle around the building that runs it. Objects are
 * named by their ids, as the run keeps them.
 */
public interface GoblinHutLife {

  /** Where the owner stands along the width. */
  int ownerX();

  /** Where the owner stands along the length. */
  int ownerY();

  /** The owner's reach: its collision radius plus its row's range. */
  int reach();

  /**
   * The objects the object query around the owner answers, testing a building by its square: the
   * index's buckets over the circle, walked in their order, each object once, a troop accepted
   * strictly within the radius plus its own collision radius, and each by the filter, asked for the
   * owner's team.
   *
   * @param radius the circle's radius
   * @param filter the filter row
   * @return the ids, in the query's order
   */
  List<Integer> query(int radius, GameObjectFilter filter);

  /** Whether an object with the id is still in the battle's live list. */
  boolean live(int id);

  /** Where the object stands along the width. */
  int x(int id);

  /** Where the object stands along the length. */
  int y(int id);

  /** The object's collision radius. */
  int radius(int id);

  /**
   * Whether the owner's validator accepts the object, as a target the owner may take: alive, on the
   * other side, of a layer it attacks, and a building when the owner takes buildings only.
   */
  boolean valid(int id);

  /** True while the owner's targeting component is on; a stun switches it off. */
  boolean active();

  /** A time step as the owner's buffs scale its spawn speed. */
  int spawnSpeed(int stepMs);

  /**
   * Schedules an action on the owner, with the owner as its cause: inside a pending pass one with
   * no delay starts at once, from the run pass it waits for the next pending pass.
   *
   * @param action the action, or null for none
   */
  void schedule(BattleAction action);

  /** The arena's width, in cells. */
  int arenaWidth();

  /** The arena's length, in cells. */
  int arenaHeight();

  /**
   * Where a point off water lands: the point itself on land, else the nearest land the relocation
   * finds, as {x, y}.
   */
  int[] relocated(int x, int y);

  /**
   * Makes one child at a point, as the one-child positional spawner does: on the point unless the
   * in-front test refuses it, at the owner's level and on its side, deploying for its row's deploy
   * time, untargetable at first, queued with no registration visit.
   *
   * @param row the child's row
   * @param x the point along the width
   * @param y the point along the length
   */
  void spawn(String row, int x, int y);

  /**
   * Tells the battle what the run did, for its observers.
   *
   * @param event what happened, with its details
   */
  void log(GoblinHutLifeState.Event event);
}
