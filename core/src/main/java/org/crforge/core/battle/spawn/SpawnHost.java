package org.crforge.core.battle.spawn;

import java.util.List;
import java.util.function.IntSupplier;
import org.crforge.core.battle.action.ActionContext;
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
   * Creates an area effect an action's spawn row names, at this object's point moved by the row's
   * offsets, for the source's side and at its level, the source its parent.
   *
   * @param action the spawn row's name
   * @param areaEffect the area effect row's name
   * @param source the entity that caused the action
   * @param offsetX the row's offset along the width, added as it stands
   * @param offsetY the row's offset along the length, turned by this object's side
   * @param phase the phase of the pending pass that ran the action, or 0 outside every pass
   */
  default void spawnAreaEffect(
      String action, String areaEffect, SpawnHost source, int offsetX, int offsetY, int phase) {
    throw new UnsupportedOperationException(name() + " cannot spawn an area effect");
  }

  /**
   * Creates an area effect a spawn row names, at a point - the one a location row's two expressions
   * gave, or this object's for a plain row - moved by the row's offsets, the one along the length
   * turned by this object's side, for the source's side and at its level, the source its parent;
   * then schedules the row's action on what it spawned, built for it, with the source as its cause
   * and a context.
   *
   * @param action the spawn row's name
   * @param areaEffect the area effect row's name
   * @param source the entity that caused the action
   * @param x the point along the width, or null for this object's
   * @param y the point along the length, or null for this object's
   * @param offsetX the row's offset along the width, added as it stands
   * @param offsetY the row's offset along the length, turned by this object's side
   * @param onSpawned the name of the row scheduled on the area effect, or null for none
   * @param context the context it carries, or null for none
   * @param phase the phase of the pending pass that ran the action, or 0 outside every pass
   */
  default void spawnAreaEffect(
      String action,
      String areaEffect,
      SpawnHost source,
      Integer x,
      Integer y,
      int offsetX,
      int offsetY,
      String onSpawned,
      ActionContext context,
      int phase) {
    throw new UnsupportedOperationException(
        name() + " cannot spawn an area effect to a location or with an action on it");
  }

  /**
   * Launches a projectile an action's spawn row names from this object's point, with this object as
   * its launcher and owner, at no target. A row of the plain class aimed by neither expression
   * would launch at this object's current target, which is refused while it has one.
   *
   * @param action the spawn row's name
   * @param projectile the projectile row's name
   * @param startHeight the row's start height
   * @param aimX the aim along the arena's width, or null for this object's own coordinate
   * @param aimY the aim along the arena's length, or null for this object's own coordinate
   * @param spawnClass true for a row of the plain spawn class, which adds this object's live height
   *     to the start height and, aimed by neither expression, launches at its current target
   * @param fromContext true for a row that names its target in the context: it launches at the
   *     object {@code targetId} names, whatever its expressions, and at none for null
   * @param targetId the id the context named, or null for none or an id no object holds now
   * @param startOffset how far the start moves toward that target, 0 for not at all
   * @param phase the phase of the pending pass that ran the action, or 0 outside every pass
   */
  default void spawnProjectile(
      String action,
      String projectile,
      int startHeight,
      IntSupplier aimX,
      IntSupplier aimY,
      boolean spawnClass,
      boolean fromContext,
      Integer targetId,
      int startOffset,
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
