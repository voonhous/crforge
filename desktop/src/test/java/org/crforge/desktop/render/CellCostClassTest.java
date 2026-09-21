package org.crforge.desktop.render;

import static org.assertj.core.api.Assertions.assertThat;

import com.badlogic.gdx.graphics.Color;
import java.util.HashSet;
import java.util.Set;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.grid.CellCostField;
import org.crforge.core.pathfinding.grid.CellCosts;
import org.crforge.core.pathfinding.grid.TileMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cell-cost overlay paints one colour per reason a cell costs what it costs. The cost alone
 * cannot tell water apart from a building footprint - both charge the blocked weight - so the
 * classification reads the cell's flags and the overlay as well, in the same order the cost rule
 * does. These tests pin that order and the cost each class corresponds to.
 */
class CellCostClassTest {

  private static final CellCosts COSTS = CellCosts.standard();

  /** The unit the overlay prices cells for: moving, on lane 1, not allowed into water. */
  private static final int STATE = GridEntityState.MOVING;

  private static final int LANE = 1;

  private static final int BUILDING_COST = COSTS.buildingCost();

  @Test
  @DisplayName("a cell outside the arena is its own class")
  void outsideTheArena() {
    assertThat(CellCostClass.classify(false, 0, false, 0, BUILDING_COST))
        .isEqualTo(CellCostClass.OUT_OF_ARENA);
  }

  @Test
  @DisplayName("water wins over the road the cell also carries")
  void waterBeatsRoad() {
    int bits = TileMap.WATER_BIT | 1;
    assertThat(CellCostClass.classify(true, bits, false, 0, BUILDING_COST))
        .isEqualTo(CellCostClass.WATER);
    assertThat(cost(bits, false, 0)).isEqualTo(COSTS.blockedCost());
  }

  @Test
  @DisplayName("a blocked cell is its own class")
  void blocked() {
    int bits = TileMap.BLOCKED_BIT;
    assertThat(CellCostClass.classify(true, bits, false, 0, BUILDING_COST))
        .isEqualTo(CellCostClass.BLOCKED);
    assertThat(cost(bits, false, 0)).isEqualTo(COSTS.blockedCost());
  }

  @Test
  @DisplayName("a footprint stamped over an ordinary cell makes it a building cell")
  void buildingOverlay() {
    assertThat(CellCostClass.classify(true, 0, true, BUILDING_COST, BUILDING_COST))
        .isEqualTo(CellCostClass.BUILDING);
    assertThat(cost(0, true, BUILDING_COST)).isEqualTo(BUILDING_COST);
  }

  @Test
  @DisplayName("a footprint stamped over a road cell also makes it a building cell")
  void buildingOverlayBeatsRoad() {
    assertThat(CellCostClass.classify(true, LANE, true, BUILDING_COST, BUILDING_COST))
        .isEqualTo(CellCostClass.BUILDING);
    assertThat(cost(LANE, true, BUILDING_COST)).isEqualTo(BUILDING_COST);
  }

  @Test
  @DisplayName("an overlay value that is not in force leaves the cell alone")
  void inactiveOverlayIsIgnored() {
    assertThat(CellCostClass.classify(true, LANE, false, BUILDING_COST, BUILDING_COST))
        .isEqualTo(CellCostClass.ROAD);
    assertThat(cost(LANE, false, BUILDING_COST)).isEqualTo(COSTS.matchingRoadCost());
  }

  @Test
  @DisplayName("a road cell costs the road weight whether or not it is the unit's own lane")
  void road() {
    assertThat(CellCostClass.classify(true, LANE, true, 0, BUILDING_COST))
        .isEqualTo(CellCostClass.ROAD);
    assertThat(cost(LANE, true, 0)).isEqualTo(COSTS.matchingRoadCost());
    assertThat(cost(2, true, 0)).isEqualTo(COSTS.roadCost());
  }

  @Test
  @DisplayName("a cell with no road and no footprint costs the default weight")
  void plainGround() {
    assertThat(CellCostClass.classify(true, 0, true, 0, BUILDING_COST))
        .isEqualTo(CellCostClass.DEFAULT);
    assertThat(cost(0, true, 0)).isEqualTo(COSTS.defaultCost());
  }

  @Test
  @DisplayName("every class paints a different colour")
  void coloursAreDistinct() {
    Set<Color> colours = new HashSet<>();
    for (CellCostClass value : CellCostClass.values()) {
      assertThat(value.color()).as("%s has a colour", value).isNotNull();
      colours.add(value.color());
    }
    assertThat(colours).hasSize(CellCostClass.values().length);
  }

  /** The cost the overlay shows, for the unit the overlay prices cells for. */
  private static int cost(int tileBits, boolean overlayActive, int overlayCost) {
    return CellCostField.cellCost(
        36, 64, 0, 0, tileBits, COSTS, true, false, false, STATE, LANE, overlayActive, overlayCost);
  }
}
