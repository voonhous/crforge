package org.crforge.core.pathfinding.target;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * Whether a candidate is one the owner prefers.
 *
 * <p>The answer is only ever 0 or 1, and the selector settles equal priorities by distance. Only a
 * character can prefer anything: a building-only attacker prefers buildings and the troops whose
 * building-target column lets it take them at all, and an attacker that ranks buffed candidates
 * lower prefers the ones without the buff.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Agrees with the reference: a building-only attacker prefers buildings and the"
            + " troops its building-target column lets it take. The buff-deprioritising rule"
            + " is not held by any fixture, and no driven unit sets either column.")
public final class TargetPriority {

  /** The preferred answer. */
  public static final int PREFERRED = 1;

  /** The ordinary answer. */
  public static final int ORDINARY = 0;

  private TargetPriority() {
    // Utility class
  }

  /** The priority of one candidate for one owner. */
  public static int priority(TargetingState t, TargetView candidate, SelectionQueries queries) {
    if (t.getOwner().getType() != ReferenceValidator.TYPE_CHARACTER) {
      return ORDINARY;
    }
    TargetingConfig cfg = t.getConfig();
    if (cfg.targetOnlyBuildings()) {
      if (candidate.building()) {
        return PREFERRED;
      }
      TargetingConfig candidateConfig = candidate.getConfig();
      if (candidate.presenceFlag() && candidateConfig != null && candidateConfig.buildingTarget()) {
        return PREFERRED;
      }
    }
    if (!cfg.ignoreTargetsWithBuff()) {
      return ORDINARY;
    }
    if (!candidate.presenceFlag()) {
      return ORDINARY;
    }
    if (!cfg.deprioritizeTargetsWithBuff()) {
      return ORDINARY;
    }
    if (!candidate.isBuffComponentPresent()) {
      return PREFERRED;
    }
    return queries.carriesDeprioritizingBuff(candidate) ? ORDINARY : PREFERRED;
  }
}
