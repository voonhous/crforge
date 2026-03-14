package org.crforge.core.ability;

/**
 * Hiding ability data (Tesla). The building hides underground when no enemies are nearby, becoming
 * untargetable and immune to most effects. When an enemy enters sight range, the building reveals
 * (with a transition time) and can then attack. After upTime seconds with no target, it hides
 * again.
 *
 * @param hideTime seconds for the hide/reveal transition animation (hideTimeMs / 1000)
 * @param upTime seconds to stay up after losing all targets before hiding again (upTimeMs / 1000)
 */
public record HidingAbility(float hideTime, float upTime) implements AbilityData {
  @Override
  public AbilityType type() {
    return AbilityType.HIDING;
  }
}
