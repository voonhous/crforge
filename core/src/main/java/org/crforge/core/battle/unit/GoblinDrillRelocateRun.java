package org.crforge.core.battle.unit;

import java.util.List;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.GoblinDrillEvoRelocate;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.EntityFlags;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.HitPoints;

/**
 * One run of the evolved Goblin Drill's relocation on its building: its phase, its hide timer, the
 * ring point it will come up at with that point's index, the walking direction and whether the move
 * to the point is still to be made.
 *
 * <p>The phase counts from 1: odd while the building stands and waits for its next threshold, even
 * from the step its share reaches the threshold until it has come up again. Positions are kept in
 * half tiles (500 game units), as the ring is laid out.
 *
 * <p>Refused as it starts rather than guessed: a building on no ring point of an enemy tower, a
 * ring around a tower already destroyed, a walk backward along the ring and the quarter rule of a
 * row without distance-based positioning, none of which a reference holds.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the start's first-appear action and ring index, each update's"
            + " threshold test, hide, timer, spawner hold, move two steps before the end and"
            + " reappear. Held by evo_goblindrill_vs_musketeer. Refused: no ring point, a"
            + " destroyed tower's ring, the backward walk and the quarter rule.")
final class GoblinDrillRelocateRun extends ActionInstance {

  /** Game units in one half tile, the ring's unit. */
  static final int HALF_TILE = 500;

  /** Milliseconds one update takes off the hide timer. */
  private static final int STEP_MS = 50;

  /** The move is made once the hide timer is at or below this. */
  private static final int MOVE_AT_MS = 100;

  /** The ring's points, in order, each relative to its tower's half tile. */
  private static final int[][] RING = {
    {-5, 5}, {-5, 3}, {-5, 1}, {-5, -1}, {-5, -3}, {-5, -5}, {-3, -5}, {-1, -5}, {1, -5}, {3, -5},
    {5, -5}, {5, -3}, {5, -1}, {5, 1}, {5, 3}, {5, 5}, {3, 5}, {1, 5}, {-1, 5}, {-3, 5}
  };

  /** The step forward from each ring point, along the width. */
  private static final int[] STEP_X = {
    0, 0, 0, 0, 0, 2, 2, 2, 2, 2, 0, 0, 0, 0, 0, -2, -2, -2, -2, -2
  };

  /** The step forward from each ring point, along the length. */
  private static final int[] STEP_Y = {
    -2, -2, -2, -2, -2, 0, 0, 0, 0, 0, 2, 2, 2, 2, 2, 0, 0, 0, 0, 0
  };

  /** The last ring index; the walk wraps past it. */
  private static final int LAST = RING.length - 1;

  private final GoblinDrillEvoRelocate.Columns columns;
  private final CharacterEntity unit;

  /** The phase: odd while standing, even from the hide until it has come up. */
  private int phase = 1;

  /** What is left of the hide, in milliseconds; 0 when no hide is running. */
  private int timerMs;

  /** The point it comes up at, in half tiles. */
  private int x;

  private int y;

  /** The index of that point on the ring. */
  private int index;

  /** True when the move to the point is still to be made. */
  private boolean moving;

  /**
   * The start: the first-appear action scheduled on the building, the building as its cause; then
   * its own point in half tiles, that point's index on the ring of the enemy tower it stands by,
   * and the walking direction.
   *
   * @param row the relocation
   * @param unit the building it runs on
   * @param holder the building's holder
   */
  GoblinDrillRelocateRun(GoblinDrillEvoRelocate row, CharacterEntity unit, ActionHolder holder) {
    super(row);
    this.columns = row.getColumns();
    this.unit = unit;
    if (columns.firstAppearAction() != null) {
      holder.schedule(columns.firstAppearAction(), ActionHolder.OWN_DELAY, false, holder);
    }
    GridEntity view = unit.getView();
    x = view.getX() / HALF_TILE;
    y = view.getY() / HALF_TILE;
    int enemy = 1 - (unit.side() & 1);
    index = ringIndex(unit.world().towers(enemy), x, y);
    if (index < 0) {
      throw new UnsupportedOperationException(
          row.name()
              + " starts on "
              + unit.name()
              + " standing on no ring point of an enemy tower, which is not modelled");
    }
    if (backward(unit.world().kingTower(enemy))) {
      throw new UnsupportedOperationException(
          row.name() + " walks " + unit.name() + " backward along the ring, which is not modelled");
    }
  }

  /**
   * The index of a point on the ring of the first tower of the list whose ring holds it, or -1.
   * Each tower's ring is a square of ten half tiles a side around its own half tile.
   *
   * @param towers the enemy side's towers, in the holder's order
   * @param x the point's half tile along the width
   * @param y the point's half tile along the length
   */
  private int ringIndex(List<TowerEntity> towers, int x, int y) {
    for (TowerEntity tower : towers) {
      int tx = tower.getView().getX() / HALF_TILE;
      int ty = tower.getView().getY() / HALF_TILE;
      for (int i = 0; i < RING.length; i++) {
        if (tx + RING[i][0] == x && ty + RING[i][1] == y) {
          return i;
        }
      }
    }
    // A destroyed tower stays on its side's list natively, where the lookup would still find its
    // ring; the holder here no longer lists it.
    if (towers.size() < 3) {
      throw new UnsupportedOperationException(
          getAction().name() + " looks for a ring around a destroyed tower, which is not modelled");
    }
    return -1;
  }

  /**
   * Whether the walk goes backward: with distance-based positioning, when the point the steps reach
   * backward lies strictly farther from the enemy king than the one they reach forward, the squared
   * distances taken in half tiles.
   *
   * @param king the enemy king
   */
  private boolean backward(TowerEntity king) {
    if (!columns.distanceBased()) {
      throw new UnsupportedOperationException(
          getAction().name()
              + " chooses its direction by the arena's quarter, which is not modelled");
    }
    if (king == null) {
      throw new UnsupportedOperationException(
          getAction().name() + " chooses its direction with no enemy king, which is not modelled");
    }
    int kx = king.getView().getX() / HALF_TILE;
    int ky = king.getView().getY() / HALF_TILE;
    int fx = x;
    int fy = y;
    int fi = index;
    int bx = x;
    int by = y;
    int bi = index;
    for (int step = 0; step < columns.stepsToMove(); step++) {
      fx += STEP_X[fi];
      fy += STEP_Y[fi];
      fi = fi > LAST - 1 ? fi - LAST : fi + 1;
      bi = bi < 1 ? LAST : bi - 1;
      bx -= STEP_X[bi];
      by -= STEP_Y[bi];
    }
    int forward = Math.max(0, squared(fx - kx, fy - ky));
    return squared(bx - kx, by - ky) > forward;
  }

  private static int squared(int dx, int dy) {
    return dx * dx + dy * dy;
  }

  @Override
  protected void update(ActionHolder holder) {
    GridEntity view = unit.getView();
    if (phase > 0 && (phase & 1) == 1) {
      HitPoints hp = unit.getHitPoints();
      int share = hp.getHitPoints() * 100 / hp.getMaximum();
      int threshold = thresholdOf((phase - 1) / 2);
      if (share > threshold) {
        // Still above it, but the damage on its way would take it there: untargetable a tick.
        int left = (hp.getHitPoints() - view.getPendingDamageAmount()) * 100 / hp.getMaximum();
        if (left <= threshold) {
          unit.startSpawnImmunity();
        }
        return;
      }
      phase++;
      timerMs = 0;
    }
    boolean hidden = view.getState() == GridEntityState.WAITING_TO_DEPLOY || unit.hidden();
    if (hidden) {
      timerMs -= STEP_MS;
      view.setPendingFlags(view.getPendingFlags() | EntityFlags.NO_SUMMON);
      if (moving && timerMs <= MOVE_AT_MS) {
        // One position write, the height set to 0, which a standing building already has.
        if (view.getZ() != 0) {
          throw new UnsupportedOperationException(
              getAction().name()
                  + " moves "
                  + unit.name()
                  + " off the ground, which is not modelled");
        }
        unit.warpTo(x * HALF_TILE, y * HALF_TILE);
        moving = false;
      }
      if (timerMs > 0) {
        return;
      }
      phase++;
      int reappear = (phase - 2) / 2;
      if (reappear < columns.reappearActions().size()) {
        holder.schedule(
            columns.reappearActions().get(reappear), ActionHolder.OWN_DELAY, false, holder);
      }
      unit.requestState(GridEntityState.DEPLOYING);
      return;
    }
    if (timerMs != 0) {
      return;
    }
    unit.requestState(GridEntityState.SPAWN_PATHFIND);
    unit.startSpawnImmunity();
    // A deploy countdown still running refuses the state: the hide waits for the next update.
    if (view.getState() != GridEntityState.SPAWN_PATHFIND) {
      return;
    }
    timerMs = columns.hideTimeMs();
    int hide = (phase - 1) / 2;
    if (hide < columns.hideActions().size()) {
      holder.schedule(columns.hideActions().get(hide), ActionHolder.OWN_DELAY, false, holder);
    }
    for (int step = 0; step < columns.stepsToMove(); step++) {
      x += STEP_X[index];
      y += STEP_Y[index];
      index = index > LAST - 1 ? index - LAST : index + 1;
    }
    moving = true;
  }

  /** The threshold at an index, or -1 past the last, which no share reaches. */
  private int thresholdOf(int at) {
    List<Integer> thresholds = columns.hideHpThresholds();
    return at < thresholds.size() ? thresholds.get(at) : -1;
  }
}
