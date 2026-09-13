package org.crforge.core.ability;

/**
 * Hook ability data (Fisherman). Troop throws a hook to pull the target toward itself, then drags
 * itself toward the target.
 *
 * @param hookRange maximum edge-to-edge range for hook to trigger, in game units
 * @param hookMinimumRange minimum edge-to-edge range for hook to trigger, in game units
 * @param hookLoadTime wind-up time before the hook fires
 * @param hookDragBackSpeed speed at which the target is pulled (raw units, divided by 60 at
 *     runtime)
 * @param hookDragSelfSpeed speed at which the fisherman pulls itself toward the target
 */
public record HookAbility(
    int hookRange,
    int hookMinimumRange,
    float hookLoadTime,
    float hookDragBackSpeed,
    float hookDragSelfSpeed)
    implements AbilityData {
  @Override
  public AbilityType type() {
    return AbilityType.HOOK;
  }
}
