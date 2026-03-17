package org.crforge.desktop.render;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.card.Card;
import org.crforge.core.card.TroopStats;
import org.crforge.core.player.Team;
import org.crforge.core.util.FormationLayout;
import org.crforge.core.util.Vector2;

/**
 * Shared utility for computing multi-unit formation positions. Used by both ArenaRenderer (hover
 * ghost preview) and DeployOverlayRenderer (pending deployment ghosts).
 */
final class GhostFormation {

  private GhostFormation() {}

  /**
   * Computes formation positions for a card's units. Returns a list of [offsetX, offsetY,
   * visualRadius] arrays in tile units relative to the deployment center. Offsets are flipped for
   * RED team.
   *
   * @param card the card being deployed
   * @param totalUnits total number of units to place
   * @param startIdx first unit index to include (0 for all, >0 to skip already-spawned)
   * @param team the deploying team (RED offsets are negated)
   * @param defaultRadius fallback visual radius when stats have none
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
    List<float[]> formationOffsets = card.getFormationOffsets();
    float summonRadius = card.getSummonRadius();

    for (int idx = startIdx; idx < totalUnits; idx++) {
      boolean isSecondary = idx >= primaryCount;
      TroopStats stats = isSecondary ? secondaryStats : primaryStats;
      if (stats == null) continue;

      float visRadius = stats.getVisualRadius() > 0 ? stats.getVisualRadius() : defaultRadius;
      float offsetX = 0f;
      float offsetY = 0f;

      if (formationOffsets != null && idx < formationOffsets.size()) {
        float[] offset = formationOffsets.get(idx);
        offsetX = offset[0];
        offsetY = offset[1];
      } else if (totalUnits > 1 && summonRadius > 0) {
        Vector2 offset =
            FormationLayout.calculateDeployOffset(
                idx, totalUnits, summonRadius, stats.getCollisionRadius());
        offsetX = offset.getX();
        offsetY = offset.getY();
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
