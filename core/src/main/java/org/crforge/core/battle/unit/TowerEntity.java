package org.crforge.core.battle.unit;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.target.TargetingConfig;

/**
 * A crown tower: a building that stands still, occludes the routing grid under its footprint and is
 * what a unit with nothing else to attack walks towards.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: a tower occludes routing from its collision radius, takes no part in pushes or"
            + " steering, is a default target, and stands at its hit points at its level."
            + " Supplied, not settled: the towers scale as Common. Not modelled yet: the tower's"
            + " own targeting component and its damage column, so a tower does not attack, and"
            + " king tower activation.")
public class TowerEntity extends WorldEntity {

  /**
   * @param data the tower's published columns
   * @param name the tower's unique name within the battle
   * @param side the side that owns the tower
   * @param x position in game units
   * @param y position in game units
   * @param level the tower's level, counted from 1
   */
  public TowerEntity(UnitData data, String name, int side, int x, int y, int level) {
    super(data, createView(data, name, side, x, y), targetingConfig(data), level);
  }

  private static GridEntity createView(UnitData data, String name, int side, int x, int y) {
    GridEntity view = new GridEntity();
    view.setName(name);
    view.setSide(side);
    view.setCollisionRadius(data.collisionRadius());
    view.setMass(data.mass());
    view.setBuilding(true);
    view.setOccludes(true);
    view.setMovementActive(false);
    // A building takes no part in pushing; the routing overlay is what keeps units off it.
    view.setPushEnabled(false);
    view.setKing(data.king());
    view.setKingCandidate(data.king() ? 1 : 0);
    view.setTargetable(1);
    view.setX(x);
    view.setY(y);
    return view;
  }

  private static TargetingConfig targetingConfig(UnitData data) {
    return TargetingConfig.tower(
        data.name(),
        data.range(),
        data.sightRange(),
        data.collisionRadius(),
        data.hitSpeedMs(),
        data.loadTimeMs(),
        data.summonerTower());
  }
}
