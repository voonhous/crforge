package org.crforge.core.battle.spawn;

import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.unit.UnitData;

/**
 * What a character spawn row hands the spawner.
 *
 * @param configuration the unit to spawn
 * @param x the point along the width the children are placed around
 * @param y the point along the length
 * @param count how many children
 * @param source the object the children are spawned from, which gives their level and side
 * @param noOffset true for a single child, which stands on the point itself
 * @param radius the ring's radius, or 0 to place in front of the point
 * @param deployTimeMs the row's own deploy time, or 0 for the unit's
 * @param morph true to morph the source instead of creating children
 * @param constPriority true for the ring's lane mirror and a fixed priority per child
 * @param deathSpawn true to make every child untargetable at first
 * @param ignoreEffects true to create the children ignoring effects
 * @param enemy true to spawn for the other side
 * @param level the children's level, or {@link SpawnRow#SOURCE_LEVEL} for the source's
 * @param useDeploy true to start every child deploying
 * @param action the action each child runs as it is spawned, or null
 * @param spawnPushback true to push the children out from the point
 * @param prestige the prestige the children take, or 0
 */
public record SpawnArguments(
    UnitData configuration,
    int x,
    int y,
    int count,
    SpawnObject source,
    boolean noOffset,
    int radius,
    int deployTimeMs,
    boolean morph,
    boolean constPriority,
    boolean deathSpawn,
    boolean ignoreEffects,
    boolean enemy,
    int level,
    boolean useDeploy,
    BattleAction action,
    boolean spawnPushback,
    int prestige) {}
