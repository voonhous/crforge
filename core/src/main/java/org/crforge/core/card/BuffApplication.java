package org.crforge.core.card;

/**
 * Associates a buff name with its duration and optional CURSE death-spawn stats. Multiple
 * BuffApplications can be attached to a single {@link AreaEffectStats} to support multi-buff area
 * effects (e.g. GoblinCurse applies both a CURSE and a DPS buff per tick).
 */
public record BuffApplication(String buffName, float duration, TroopStats curseSpawnStats) {

  /** Convenience factory for buffs without CURSE death-spawn semantics. */
  public static BuffApplication of(String buffName, float duration) {
    return new BuffApplication(buffName, duration, null);
  }
}
