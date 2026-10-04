package org.crforge.core.battle.spawn;

import java.util.List;
import java.util.function.IntSupplier;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.unit.UnitData;

/**
 * An object of the battle a spawn can come from: it answers as a spawn object, it has an action
 * holder, and it reaches the battle's spawner.
 */
public interface SpawnHost extends SpawnObject {

  /** The object's name, which the children it spawns are named after. */
  String name();

  /** The object's action holder. */
  ActionHolder actionHolder();

  /**
   * Spawns the children the block describes, in the battle this object belongs to.
   *
   * @param arguments the block a spawn row's perform works out
   * @return the children spawned, in the order they were made
   */
  List<SpawnHost> spawnCharacters(SpawnArguments arguments);

  /**
   * Links a child this object spawned into its group, right after itself, so the group reads newest
   * first. Only a character keeps a group.
   *
   * @param child the child
   */
  default void linkIntoGroup(SpawnHost child) {
    throw new UnsupportedOperationException(name() + " keeps no group");
  }

  /**
   * Creates an area effect an action's spawn row names, at this object's point, for the source's
   * side and at its level, the source its parent.
   *
   * @param action the spawn row's name
   * @param areaEffect the area effect row's name
   * @param source the entity that caused the action
   * @param phase the phase of the pending pass that ran the action, or 0 outside every pass
   */
  default void spawnAreaEffect(String action, String areaEffect, SpawnHost source, int phase) {
    throw new UnsupportedOperationException(name() + " cannot spawn an area effect");
  }

  /**
   * Launches a projectile an action's spawn row names from this object's point, with this object as
   * its launcher and owner, at no target.
   *
   * @param action the spawn row's name
   * @param projectile the projectile row's name
   * @param startHeight the height it is launched from
   * @param aimX the aim along the arena's width, or null for this object's own coordinate
   * @param aimY the aim along the arena's length, or null for this object's own coordinate
   * @param phase the phase of the pending pass that ran the action, or 0 outside every pass
   */
  default void spawnProjectile(
      String action,
      String projectile,
      int startHeight,
      IntSupplier aimX,
      IntSupplier aimY,
      int phase) {
    throw new UnsupportedOperationException(name() + " cannot launch a projectile");
  }

  /**
   * Hands a champion this object spawned to its side's champion controllers.
   *
   * @param child the champion
   */
  default void handOverChampion(SpawnHost child) {
    throw new UnsupportedOperationException(name() + " cannot hand over a champion");
  }

  /**
   * The building placement a spawn row that validates its point as a building's asks for, for a
   * child of the given row: the point kept when it is free, else refused.
   *
   * @param child the row of the child it would place
   * @return the search
   */
  default SpawnPerform.PlacementSearch buildingPlacement(UnitData child) {
    throw new UnsupportedOperationException(name() + " cannot place a building it spawns");
  }
}
