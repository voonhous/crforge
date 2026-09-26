package org.crforge.core.battle.spawn;

import java.util.function.IntSupplier;
import lombok.Builder;
import org.crforge.core.battle.action.ActionRow;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.unit.UnitData;

/**
 * The columns of a character spawn row, of either class: a spawn at its source, or a spawn to a
 * location worked out from the source by the location columns. The columns every action row shares
 * live in its {@link ActionRow}.
 *
 * <p>Three columns of the class are left out because the character spawn never reads them: the two
 * offsets, which only an area-effect spawn adds, and the spawn time, which only a buff spawn uses.
 *
 * @param toLocation true for a spawn to a location, false for a spawn at the source
 * @param spawnData the unit the row spawns
 * @param spawnDataClone the clone row of that unit, which a clone spawn uses instead; null for none
 * @param count how many children
 * @param spawnRadius the ring's radius, or 0 to place every child in front of the point
 * @param deployTimeMs a deploy time of the row's own, or 0 for the unit's
 * @param spawnLevelIndex the children's level, or -1 for the source's
 * @param useDeploy true to start every child deploying
 * @param isEnemy true to spawn for the other side
 * @param isDeathSpawn true to make every child untargetable at first; on unless the row says not
 * @param isSpawnConstPriority true for the ring's lane mirror and a fixed priority per child
 * @param spawnPushback true to push the children out from the point
 * @param ignoreEffects true to create the children ignoring effects
 * @param useMorph true to morph the source instead of creating children
 * @param addToSourceGroup true to link each child into its source's group
 * @param spawnAsClone true to spawn clones
 * @param inheritPrestigeFromParent true to give the children the owner's prestige
 * @param parentGoAsSource true to spawn from the owner of the action instead of its cause
 * @param shareContext true to hand the children the action's target
 * @param validatePlacementAsBuilding true to search for a place as a building would
 * @param actionToRunOnSpawned the action each child runs as it is spawned, or null
 * @param absoluteX the location along the width in half tiles from the arena's edge, or 0
 * @param absoluteY the location along the length in half tiles, or 0
 * @param relativeX the location along the width in half tiles from the source, or 0
 * @param relativeY the location along the length in half tiles from the source, or 0
 * @param mirroredX the location along the width in half tiles from the source, never flipped
 * @param mirroredY the location along the length in half tiles from the source, flipped
 * @param xPositionExpression the location along the width as an expression, or null
 * @param yPositionExpression the location along the length as an expression, or null
 */
@Builder(toBuilder = true)
public record SpawnRow(
    boolean toLocation,
    UnitData spawnData,
    UnitData spawnDataClone,
    int count,
    int spawnRadius,
    int deployTimeMs,
    int spawnLevelIndex,
    boolean useDeploy,
    boolean isEnemy,
    boolean isDeathSpawn,
    boolean isSpawnConstPriority,
    boolean spawnPushback,
    boolean ignoreEffects,
    boolean useMorph,
    boolean addToSourceGroup,
    boolean spawnAsClone,
    boolean inheritPrestigeFromParent,
    boolean parentGoAsSource,
    boolean shareContext,
    boolean validatePlacementAsBuilding,
    BattleAction actionToRunOnSpawned,
    int absoluteX,
    int absoluteY,
    int relativeX,
    int relativeY,
    int mirroredX,
    int mirroredY,
    IntSupplier xPositionExpression,
    IntSupplier yPositionExpression) {

  /** The level column's value for a spawn at the source's level. */
  public static final int SOURCE_LEVEL = -1;

  /**
   * A builder with the columns a row leaves out at their defaults: one child, the source's level, a
   * death spawn.
   */
  public static SpawnRowBuilder builder() {
    return new SpawnRowBuilder().count(1).spawnLevelIndex(SOURCE_LEVEL).isDeathSpawn(true);
  }
}
