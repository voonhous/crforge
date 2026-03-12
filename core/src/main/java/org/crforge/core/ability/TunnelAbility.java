package org.crforge.core.ability;

/**
 * Ability data for units that tunnel underground to their deploy target (e.g. Miner). The tunnel
 * speed is in tiles/sec, converted from spawnPathfindSpeed / 60 at load time.
 */
public record TunnelAbility(float tunnelSpeed) implements AbilityData {

  @Override
  public AbilityType type() {
    return AbilityType.TUNNEL;
  }
}
