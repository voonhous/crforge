package org.crforge.core.ability;

/**
 * Hook ability data (Fisherman). Troop throws a hook to pull the target toward itself, then drags
 * itself toward the target.
 *
 * @param hookRange maximum range for hook to trigger
 * @param hookMinimumRange minimum range for hook to trigger
 * @param hookLoadTime wind-up time before the hook fires
 * @param hookDragBackSpeed speed at which the target is pulled (raw units, divided by 60 at
 *     runtime)
 * @param hookDragSelfSpeed speed at which the fisherman pulls itself toward the target
 */
public record HookAbility(
    float hookRange,
    float hookMinimumRange,
    float hookLoadTime,
    float hookDragBackSpeed,
    float hookDragSelfSpeed)
    implements AbilityData {
  @Override
  public AbilityType type() {
    return AbilityType.HOOK;
  }
}
