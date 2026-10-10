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
 * it as hit, so a volley whose first pellets will kill a unit at the drain flies on past it.
 */
public final class ProjectileGroup {

  /** The ids of the entities the group has doomed, in the order they were listed. */
  private final List<Integer> doomed = new ArrayList<>();

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
