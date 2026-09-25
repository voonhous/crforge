package org.crforge.core.battle.deploy;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.unit.UnitData;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.grid.LaneAssignment;
import org.crforge.core.pathfinding.grid.TileMap;

/**
 * One card play worked out before anything is created: whether it is refused, where it lands, and
 * where, in which lane and in which state each of its units starts.
 *
 * <p>In the order the play runs: the map check on the raw point; the placement search over the
 * deploy mask; the column the placed point's units may stand in along the arena's length, from the
 * same mask built as if the card could not be placed on buildings; then, unit by unit in creation
 * order, the formation offset around the placed point, the length clamped into the column unless
 * the unit flies, the creation inset of 250 from every edge, the lane of the unit's own position
 * with the placed point as the reference, and whether it starts deploying or waits its turn.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the order of the steps, the column interval and its two parities, the formation"
            + " radius chosen from the card, then the unit's spawn radius, then its collision"
            + " radius, the clamp into the column, the creation inset, the unit's lane from its own"
            + " position with the placed point as the reference, and its start. Not modelled: the"
            + " elixir and the other gates before the map check, spell and building cards.")
public final class CardPlacement {

  /** How far from every edge of the arena a unit is created. */
  private static final int CREATION_INSET = 250;

  private CardPlacement() {
    // Utility class
  }

  /**
   * One unit of a placed card.
   *
   * @param index its place in the formation
   * @param unit its columns
   * @param dx its formation offset along the width
   * @param dy its formation offset along the length
   * @param x where it is created
   * @param y where it is created
   * @param lane its lane
   * @param start whether it deploys at once or waits, and how long
   */
  public record Unit(
      int index, UnitData unit, int dx, int dy, int x, int y, int lane, InitialDelay start) {}

  /**
   * What a play came to.
   *
   * @param code 0 when placed, otherwise the command's refusal code
   * @param x the placed point, when placed
   * @param y the placed point, when placed
   * @param interval the column along the length the units are clamped into, or null for none
   * @param originLane the lane of the placed point
   * @param units the units in creation order; empty when refused
   */
  public record Result(int code, int x, int y, int[] interval, int originLane, List<Unit> units) {

    public boolean placed() {
      return code == 0;
    }
  }

  /** The command's code when no legal tile is found. */
  public static final int NO_POSITION = 0x17;

  /**
   * Works a play out.
   *
   * @param tileMap the arena's map
   * @param card the card
   * @param x requested x
   * @param y requested y
   * @param side the placing side
   * @param entities the battle's characters, the live list then the queued ones
   * @param symmetricalSnap whether the symmetrical snap applies
   * @param laneSequence whether the formation's lane sequence applies
   */
  public static Result place(
      TileMap tileMap,
      DeployCard card,
      int x,
      int y,
      int side,
      List<MaskEntity> entities,
      boolean symmetricalSnap,
      boolean laneSequence) {
    int code = MapCheck.check(tileMap, card, x, y);
    if (code != MapCheck.OK) {
      return new Result(code, 0, 0, null, 0, List.of());
    }
    boolean[] mask = PlacementSearch.mask(tileMap, card, side, entities);
    int[] placed = PlacementSearch.find(tileMap, card, x, y, side, mask, symmetricalSnap);
    if (placed == null) {
      return new Result(NO_POSITION, 0, 0, null, 0, List.of());
    }
    int px = placed[0];
    int py = placed[1];
    int w = tileMap.width();
    int h = tileMap.height();
    // The column is read from the mask built as if the card could not be placed on buildings.
    DeployCard offBuildings =
        new DeployCard(
            card.name(),
            card.unit(),
            card.count(),
            card.secondary(),
            card.secondaryCount(),
            card.summonRadius(),
            card.summonWidth(),
            card.summonDeployDelayMs(),
            card.summonDeployDelaySecondMs(),
            card.canDeployOnEnemySide(),
            false,
            card.canPlaceOnWater(),
            card.fullLaneDeploy(),
            card.touchdownLimitedDeploy(),
            card.deployWTileMargin(),
            0,
            0);
    int[] interval =
        columnInterval(
            PlacementSearch.mask(tileMap, offBuildings, side, entities),
            w >> 1,
            h >> 1,
            px,
            side,
            h);
    int originLane = LaneAssignment.lane(w, h, w, px, py, -1, 0, tileMap::bits);
    int secondaryCount = card.secondary() == null ? 0 : card.secondaryCount();
    boolean firstIsBuilding = card.unit().building();
    List<Unit> units = new ArrayList<>();
    for (int k = 0; k < card.total(); k++) {
      UnitData unit = card.unitAt(k);
      int radius =
          card.summonRadius() != 0
              ? card.summonRadius()
              : unit.spawnRadius() != 0 ? unit.spawnRadius() : unit.collisionRadius();
      int[] offset =
          Formation.offset(
              k,
              card.count(),
              radius,
              card.summonWidth(),
              (side & 1) == 0 ? 1 : 0,
              originLane,
              unit.spawnAngleShift(),
              secondaryCount,
              true,
              laneSequence);
      int ux = px + offset[0];
      int uy = py + offset[1];
      if (interval != null && unit.flyingHeight() <= 0) {
        uy = uy > interval[0] ? Math.min(uy, interval[1]) : interval[0];
      }
      int cx = Math.min(Math.max(ux, CREATION_INSET), w * 500 - CREATION_INSET);
      int cy = Math.min(Math.max(uy, CREATION_INSET), h * 500 - CREATION_INSET);
      int lane =
          LaneAssignment.lane(w, h, w, cx, cy, px, card.fullLaneDeploy() ? 1 : 0, tileMap::bits);
      if (lane <= 0) {
        lane = originLane;
      }
      InitialDelay start =
          InitialDelay.select(
              k,
              card.count(),
              unit.deployTimeMs(),
              card.summonDeployDelayMs(),
              card.summonDeployDelaySecondMs(),
              firstIsBuilding);
      units.add(new Unit(k, unit, offset[0], offset[1], cx, cy, lane, start));
    }
    return new Result(0, px, py, interval, originLane, units);
  }

  /**
   * The column along the length the placed point's units may stand in: over the open rows of the
   * point's tile column, from the lowest to the highest, the top side's shifted half a tile. None
   * when no row is open or the span is a quarter of the map's cells or more.
   */
  static int[] columnInterval(
      boolean[] mask, int stride, int rows, int x, int side, int heightCells) {
    int col = (x / 500) >> 1;
    boolean odd = (side & 1) != 0;
    int lo = Integer.MAX_VALUE;
    int hi = Integer.MIN_VALUE;
    boolean found = false;
    for (int r = 0; r < rows; r++) {
      if (mask[r * stride + col]) {
        lo = Math.min(lo, r * 1000 + (odd ? 500 : 0));
        hi = Math.max(hi, r * 1000 + (odd ? 0 : 500));
        found = true;
      }
    }
    if (odd) {
      lo -= 1;
    }
    if (!found || hi - lo >= heightCells * 250) {
      return null;
    }
    return new int[] {lo, hi};
  }
}
