package org.crforge.core.battle.unit;

import java.util.List;
import lombok.Builder;

/**
 * A unit's ability row as the battle reads it: how long a cast lasts and when its effect fires,
 * whether the unit keeps its target while it casts, the action it runs, the buff it gives itself,
 * the lane switch, the character it leaves behind and the area effect it creates as it fires, the
 * state it holds the unit in afterwards, the souls the unit collects for that area effect, and what
 * a champion's controller reads: its cost, its cooldown and its charges.
 *
 * <p>A request for the ability takes the unit into the casting state. The cast time and the trigger
 * delay are counted in whole ticks from there, each the column's milliseconds divided by 50; the
 * effect fires on the visit the trigger delay reaches zero, and the unit stands again on the visit
 * the cast time does.
 *
 * @param name the ability row's name
 * @param castTimeMs how long a cast lasts
 * @param triggerDelayMs how long after the start of a cast its effect fires
 * @param keepCurrentTarget true when the unit keeps its target while it casts, its targeting
 *     component switched off meanwhile
 * @param champion true for a champion's ability, which a champion's clone never casts
 * @param onActivationAction the action row the ability runs as it fires, or null
 * @param buff the buff the ability gives the unit itself as it fires, or null
 * @param buffTimeMs how long that buff lasts
 * @param manaCost the elixir a player pays to use the ability, in whole elixir
 * @param cooldownMs how long after a use its controller refuses the next
 * @param maxCharges how many uses one play of the champion allows; 0 for no limit
 * @param dashRange how far the ability's dash reaches for its first target; 0 for no dash
 * @param dashTargetFurthest true when the dash takes the furthest target in its reach rather than
 *     the nearest
 * @param pendingBuff the buff a unit waiting to cast its dash carries, or null
 * @param switchLanes true for an ability that sends the unit across the arena to the mirror of its
 *     position, routing there in the in-game pathfinding state
 * @param activationSpawnCharacter the row of the character the ability leaves on the unit's spot as
 *     it fires, or null for none
 * @param areaEffectObject the row of the area effect the ability creates at the unit as it fires,
 *     its parent the unit, or null for none
 * @param abilityStateDurationMs how long the unit stays in the ability's follow-up state after it
 *     fires; 0 for none
 * @param gameTagsWhileAbilityActive the tag bits the unit carries while it is in that state
 * @param resurrectBaseCount how many characters the ability's area effect makes with no soul
 *     collected; 0 for an ability that collects none, whose area effect keeps its row's lifetime
 * @param resurrectEnemies true when a death of the other side counts a soul
 * @param resurrectOwnTroops true when a death of the unit's own side counts a soul
 * @param spawnLimit the most characters the area effect makes, the base count and the souls
 *     together
 * @param unmodelledColumns the columns that make the ability do more than run its activation action
 *     and buff the unit itself, or keep a buff on a unit waiting to cast, which the battle does not
 *     model; a request for such an ability is refused
 */
@Builder(toBuilder = true)
public record AbilityData(
    String name,
    int castTimeMs,
    int triggerDelayMs,
    boolean keepCurrentTarget,
    boolean champion,
    String onActivationAction,
    String buff,
    int buffTimeMs,
    int manaCost,
    int cooldownMs,
    int maxCharges,
    int dashRange,
    boolean dashTargetFurthest,
    String pendingBuff,
    boolean switchLanes,
    String activationSpawnCharacter,
    String areaEffectObject,
    int abilityStateDurationMs,
    long gameTagsWhileAbilityActive,
    int resurrectBaseCount,
    boolean resurrectEnemies,
    boolean resurrectOwnTroops,
    int spawnLimit,
    List<String> unmodelledColumns) {

  public AbilityData {
    unmodelledColumns = unmodelledColumns == null ? List.of() : List.copyOf(unmodelledColumns);
  }
}
