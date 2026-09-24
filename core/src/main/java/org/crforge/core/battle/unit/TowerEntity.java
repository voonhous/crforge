package org.crforge.core.battle.unit;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.grid.LaneAssignment;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.pathfinding.target.TargetingConfig;

/**
 * A crown tower: a building that stands still, occludes the routing grid under its footprint and is
 * what a unit with nothing else to attack walks towards.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: a tower occludes routing from its collision radius, takes no part in pushes or"
            + " steering, is a default target, answers as a crown tower whether king or princess"
            + " tower and as the tower slot only when king, and stands at its hit points at its"
            + " level."
            + " Supplied, not settled: the towers scale as Common. Not modelled yet: the tower's"
            + " own targeting component, so a tower does not attack although its projectile and"
            + " launch columns are carried, and king tower activation.")
public class TowerEntity extends WorldEntity {

  /**
   * @param tileMap the arena, which assigns the tower its lane from the road nearest to it
   * @param data the tower's published columns
   * @param name the tower's unique name within the battle
   * @param side the side that owns the tower
   * @param x position in game units
   * @param y position in game units
   * @param level the tower's level, counted from 1
   */
  public TowerEntity(
      TileMap tileMap, UnitData data, String name, int side, int x, int y, int level) {
    super(data, createView(tileMap, data, name, side, x, y), targetingConfig(data), level);
  }

  /**
   * The tower's view at placement. Like every character it is given the lane of the road nearest to
   * its position, which the default selection compares with a unit's own lane.
   */
  private static GridEntity createView(
      TileMap tileMap, UnitData data, String name, int side, int x, int y) {
    GridEntity view = new GridEntity();
    view.setName(name);
    view.setSide(side);
    view.setLane(
        LaneAssignment.lane(
            tileMap.width(), tileMap.height(), tileMap.width(), x, y, -1, 0, tileMap::bits));
    view.setCollisionRadius(data.collisionRadius());
    view.setMass(data.mass());
    view.setBuilding(true);
    view.setOccludes(true);
    view.setMovementActive(false);
    // A building takes no part in pushing; the routing overlay is what keeps units off it.
    view.setPushEnabled(false);
    // Both tower kinds are crown towers: noticed from farther away, ordered last by the index and
    // dealt the crown-tower damage. Only the king tower fills its side's tower slot.
    view.setCrownTower(data.king() || data.summonerTower());
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
