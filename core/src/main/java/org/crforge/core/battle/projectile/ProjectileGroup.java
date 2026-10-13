/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.projectile;

import java.util.ArrayList;
import java.util.List;

/**
 * The group the projectiles of one volley share, made for a launcher whose row links its volley's
 * projectiles into one group (GroupProjectiles, the Hunter's pellets): the first projectile of the
 * volley makes it, and every further one joins it.
 *
 * <p>It lists the entities a projectile of the group has doomed: one whose queued damage, after the
 * projectile's travelling hit was queued for the damage drain, reached the entity's hit points and
 * shield. The group's other projectiles pass over a listed entity, neither hitting it nor listing
 * it as hit, so a volley whose first pellets will kill a unit at the drain flies on past it. A
 * deflection lists the object whose area effect turned one of the group around, after that object
 * took the projectile's damage, and the group's projectiles deal it no further deflection damage.
 *
 * <p>It also lists its projectiles in the order they joined, the first one first; a projectile that
 * leaves the battle leaves the group, so the first listed is the first of the volley still in the
 * battle. A volley whose last projectile's row deflects as a group (DeflectGroup, as the Princess's
 * arrows) is turned around as one: only the first listed may be deflected, and its deflection turns
 * every one of the group around with it.
 */
public final class ProjectileGroup {

  /** The ids of the entities the group has doomed, in the order they were listed. */
  private final List<Integer> doomed = new ArrayList<>();

  /** The group's projectiles still in the battle, in the order they joined. */
  private final List<ProjectileEntity> members = new ArrayList<>();

  /** True when a deflection turns the whole group around as one. */
  private boolean deflectsAsOne;

  /**
   * Adds a projectile of the volley, after those launched before it.
   *
   * @param projectile the projectile joining
   */
  void join(ProjectileEntity projectile) {
    members.add(projectile);
  }

  /**
   * Takes a projectile that has left the battle out of the group.
   *
   * @param projectile the projectile leaving
   */
  void leave(ProjectileEntity projectile) {
    members.remove(projectile);
  }

  /** The group's projectiles still in the battle, the first launched first; a copy. */
  public List<ProjectileEntity> members() {
    return List.copyOf(members);
  }

  /** The first of the group's projectiles still in the battle, or null when none is left. */
  public ProjectileEntity first() {
    return members.isEmpty() ? null : members.get(0);
  }

  /**
   * Marks the group as turned around as one by a deflection, as the launch does when the volley's
   * last projectile's row deflects as a group.
   */
  public void deflectAsOne() {
    deflectsAsOne = true;
  }

  /** Whether a deflection turns the whole group around as one. */
  public boolean deflectsAsOne() {
    return deflectsAsOne;
  }

  /**
   * Lists an entity as doomed, once.
   *
   * @param id the entity's id
   */
  public void doom(int id) {
    if (!doomed.contains(id)) {
      doomed.add(id);
    }
  }

  /**
   * Whether an entity is listed as doomed.
   *
   * @param id the entity's id
   */
  public boolean dooms(int id) {
    return doomed.contains(id);
  }
}
