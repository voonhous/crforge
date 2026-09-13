package org.crforge.core.ability;

/**
 * Ability data for units that tunnel underground to their deploy target (e.g. Miner). The tunnel
 * speed is in game units per second, converted from the raw spawnPathfindSpeed (60 = one tile per
 * second) at load time.
 */
public record TunnelAbility(float tunnelSpeed) implements AbilityData {

  @Override
  public AbilityType type() {
    return AbilityType.TUNNEL;
  }
}
