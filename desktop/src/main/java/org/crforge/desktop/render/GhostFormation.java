package org.crforge.desktop.render;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.card.Card;
import org.crforge.core.card.TroopStats;
import org.crforge.core.player.Team;
import org.crforge.core.util.FormationLayout;

/**
 * Shared utility for computing multi-unit formation positions. Used by both ArenaRenderer (hover
 * ghost preview) and DeployOverlayRenderer (pending deployment ghosts).
 */
final class GhostFormation {

  private GhostFormation() {}

  /**
   * Computes formation positions for a card's units. Returns a list of [offsetX, offsetY,
   * visualRadius] arrays in game units relative to the deployment center (convert with {@link
   * RenderConstants#unitsToPixels}). Offsets are flipped for RED team, matching the simulation's
   * TroopFactory.
   *
   * @param card the card being deployed
   * @param totalUnits total number of units to place
   * @param startIdx first unit index to include (0 for all, >0 to skip already-spawned)
   * @param team the deploying team (RED offsets are negated)
   * @param defaultRadius fallback visual radius in game units when stats have none
   */
  static List<float[]> computePositions(
      Card card, int totalUnits, int startIdx, Team team, float defaultRadius) {
    TroopStats primaryStats = card.getUnitStats();

    // Single-unit or non-troop cards: single ghost at center
    if (totalUnits <= 1 || primaryStats == null) {
      return List.of(new float[] {0f, 0f, defaultRadius});
    }

    List<float[]> positions = new ArrayList<>();
    int primaryCount = card.getUnitCount();
    TroopStats secondaryStats = card.getSecondaryUnitStats();
    List<int[]> formationOffsets = card.getFormationOffsets();
    float summonRadius = card.getSummonRadius();

    for (int idx = startIdx; idx < totalUnits; idx++) {
      boolean isSecondary = idx >= primaryCount;
      TroopStats stats = isSecondary ? secondaryStats : primaryStats;
      if (stats == null) continue;

      float visRadius = stats.getVisualRadius() > 0 ? stats.getVisualRadius() : defaultRadius;
      float offsetX = 0f;
      float offsetY = 0f;

      if (formationOffsets != null && idx < formationOffsets.size()) {
        int[] offset = formationOffsets.get(idx);
        offsetX = offset[0];
        offsetY = offset[1];
      } else if (totalUnits > 1 && summonRadius > 0) {
        FormationLayout.Offset offset =
            FormationLayout.calculateDeployOffset(
                idx, totalUnits, summonRadius, stats.getCollisionRadius());
        offsetX = offset.x();
        offsetY = offset.y();
      }

      if (team == Team.RED) {
        offsetX = -offsetX;
        offsetY = -offsetY;
      }

      positions.add(new float[] {offsetX, offsetY, visRadius});
    }

    return positions;
  }
}
