package org.crforge.core.battle.unit;

import java.util.List;
import lombok.Builder;

/**
 * A unit's ability row as the battle reads it: how long a cast lasts and when its effect fires,
 * whether the unit keeps its target while it casts, and the action it runs as it fires.
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
 * @param unmodelledColumns the columns that make the ability do more than run its activation
 *     action, or keep a buff on a unit waiting to cast, which the battle does not model; a request
 *     for such an ability is refused
 */
@Builder(toBuilder = true)
public record AbilityData(
    String name,
    int castTimeMs,
    int triggerDelayMs,
    boolean keepCurrentTarget,
    boolean champion,
    String onActivationAction,
    List<String> unmodelledColumns) {

  public AbilityData {
    unmodelledColumns = unmodelledColumns == null ? List.of() : List.copyOf(unmodelledColumns);
  }
}
