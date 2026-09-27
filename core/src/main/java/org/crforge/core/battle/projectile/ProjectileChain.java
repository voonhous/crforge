package org.crforge.core.battle.projectile;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;

/**
 * The state one wave of a chain of projectiles shares - Arrows' wave of ten: the circle every
 * victim of the chain must stand in, whether its projectiles land on their ring points, and the ids
 * of the entities the chain has hit, so none is hit twice by it.
 */
@Getter
public final class ProjectileChain {

  /** The chain's circle: the spell's placed point and radius. */
  private final int x;

  private final int y;
  private final int radius;

  /** True once a projectile of the chain was given its ring point. */
  private boolean ringPoints;

  /** The ids of the entities the chain has hit, in the order they were hit. */
  private final List<Integer> hitIds = new ArrayList<>();

  public ProjectileChain(int x, int y, int radius) {
    this.x = x;
    this.y = y;
    this.radius = radius;
  }

  void markRingPoints() {
    ringPoints = true;
  }
}
