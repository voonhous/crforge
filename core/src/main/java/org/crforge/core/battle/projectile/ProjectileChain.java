/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.projectile;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The state one wave of a chain of projectiles shares - Arrows' wave of ten: the circle every
 * victim of the chain must stand in, whether its projectiles land on their ring points, and the ids
 * of the entities the chain has hit, so none is hit twice by it.
 */
@Getter
@RequiredArgsConstructor
public final class ProjectileChain {

  /** The chain's circle: the spell's placed point and radius. */
  private final int x;

  private final int y;
  private final int radius;

  /** True once a projectile of the chain was given its ring point. */
  private boolean ringPoints;

  /** The ids of the entities the chain has hit, in the order they were hit. */
  private final List<Integer> hitIds = new ArrayList<>();

  void markRingPoints() {
    ringPoints = true;
  }
}
