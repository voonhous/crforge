package org.crforge.core.battle.unit;

import java.util.List;
import lombok.Builder;
import org.crforge.core.pathfinding.combat.RarityTable;

/**
 * The published columns of one area effect row that the battle reads, in the columns' own units.
 *
 * @param name the row's name
 * @param rarity the rarity its level is packed against
 * @param lifeDurationMs how long it lasts
 * @param radius the radius of its hits
 * @param maxRadius the radius it starts from and shrinks toward its radius over its life; 0 for
 *     none
 * @param hitSpeedMs the time between its hits; 0 for one hit on its first update, below 0 for none
 * @param hitSpeedOffsetMs how far into its life its hit schedule is moved
 * @param damage the damage of one hit at the first level
 * @param crownTowerDamagePercent how much more or less a crown tower takes, in percent
 * @param hitsAir whether its hits reach air units
 * @param hitsGround whether its hits reach ground units and buildings
 * @param onlyEnemies true when its hits spare its own side
 * @param ignoreBuildings true when its hits spare buildings
 * @param pushback how far a hit pushes its victims; 0 for none
 * @param maximumTargets the most victims a hit takes; 0 for no limit
 * @param sharedDamage true when a hit's damage is shared out among its victims
 * @param onStartingAction the action it runs as it joins the battle, or null
 * @param onLifeTimeEndAction the action it runs when its life ends, or null
 * @param buff the buff each of its hits applies, or null for none
 * @param buffTimeMs how long the buff it applies lasts
 * @param capBuffTimeToAreaEffectTime true when the buff lasts no longer than its own life and one
 *     hit speed more
 * @param onlyOwnTroops true when its buff reaches only its own side
 * @param spawnAreaEffectObject the area effect it creates at its point on its first update, or null
 * @param projectile the projectile it launches on each update whose hit count rose, or null for
 *     none
 * @param hitBiggestTargets true when it drops each projectile onto the enemy in its circle with the
 *     most hit points and shield it has not struck yet; false to drop it onto its own point
 * @param projectileStartHeight the height its projectiles start at
 * @param affectsHidden true when it reaches a hidden unit, which nothing else does
 * @param controlsBuff true when its slot that removes the instances it is the parent of acts; no
 *     path the battle models reaches that slot, and a parent's instances go as the parent leaves
 * @param cloning true for a Clone: its on-hit action passes a clone, a unit a Clone passes by and
 *     one tagged against clones by
 * @param onHitAction the action each of its hits schedules on every unit in its circle it reaches,
 *     or null for none; only a Clone's, a group of buff spawns and a taunt are modelled
 * @param oneHitPerTarget true when its hit action reaches each object once in its life
 * @param followsParent true when it moves with the object it follows, its parent, standing on that
 *     object's point at each update
 * @param deflectsProjectiles true when the enemy projectiles that fly within its radius are sent
 *     back at their source, its parent taking their damage
 * @param spawnCharacter the row of the characters it makes about its point over its life, or null
 *     for none
 * @param spawnIntervalMs the time between two of them
 * @param spawnInitialDelayMs how far into its life the first comes, less one interval
 * @param spawnTimeMs how long each deploys
 * @param spawnMaxCount the most it makes; 0 for no limit
 * @param spawnMinRadius the least distance from its point at which one is placed
 * @param spawnRandomizeSequence true when the directions they are placed in are shuffled once by
 *     the battle's random source; false to turn them by a fixed step, which is not modelled
 * @param spawnClones true when each it makes is a clone
 * @param stayAfterParentDies true when it stays, standing on its last point, as the object it
 *     follows leaves; false to end with it
 * @param unmodelledColumns the columns its row sets that the battle does not model
 */
@Builder(toBuilder = true)
public record AreaEffectData(
    String name,
    RarityTable rarity,
    int lifeDurationMs,
    int radius,
    int maxRadius,
    int hitSpeedMs,
    int hitSpeedOffsetMs,
    int damage,
    int crownTowerDamagePercent,
    boolean hitsAir,
    boolean hitsGround,
    boolean onlyEnemies,
    boolean ignoreBuildings,
    int pushback,
    int maximumTargets,
    boolean sharedDamage,
    String onStartingAction,
    String onLifeTimeEndAction,
    String buff,
    int buffTimeMs,
    boolean capBuffTimeToAreaEffectTime,
    boolean onlyOwnTroops,
    String spawnAreaEffectObject,
    String projectile,
    boolean hitBiggestTargets,
    int projectileStartHeight,
    boolean affectsHidden,
    boolean controlsBuff,
    boolean cloning,
    String onHitAction,
    boolean oneHitPerTarget,
    boolean followsParent,
    boolean deflectsProjectiles,
    String spawnCharacter,
    int spawnIntervalMs,
    int spawnInitialDelayMs,
    int spawnTimeMs,
    int spawnMaxCount,
    int spawnMinRadius,
    boolean spawnRandomizeSequence,
    boolean spawnClones,
    boolean stayAfterParentDies,
    List<String> unmodelledColumns) {

  public AreaEffectData {
    unmodelledColumns = unmodelledColumns == null ? List.of() : List.copyOf(unmodelledColumns);
  }
}
