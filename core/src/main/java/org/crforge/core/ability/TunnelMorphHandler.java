package org.crforge.core.ability;

import org.crforge.core.entity.unit.Troop;

/**
 * Callback interface for handling tunnel morph events. When a dig troop (e.g. GoblinDrillDig)
 * arrives at its tunnel target and has a morphCard set, this handler is invoked to transform the
 * dig troop into the target building.
 */
@FunctionalInterface
public interface TunnelMorphHandler {
  void onTunnelMorph(Troop digTroop, float targetX, float targetY);
}
