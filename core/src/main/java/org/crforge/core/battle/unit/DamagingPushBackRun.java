package org.crforge.core.battle.unit;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.DamagingPushBack;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * One run of a carried push on a character, as the evolved Battle Ram's completed charge starts it:
 * the hit id it takes as it starts, and the objects it has hit.
 *
 * <p>Each update centres the object query ahead of the character, the offset times each axis of its
 * facing over the facing's length, and walks what the query found in its order. An object this run
 * has hit is left alone. One with a movement component on, not waiting to deploy, that the push
 * filter accepts for the character's team and row is pushed through the entry with the gates
 * skipped: away from the centre, or with PushToSide away from its foot on the line through the
 * centre along the facing, nudged ten units along the width when it stands on that line. Then a
 * character that may not be touched, a damage below 1 and an object without hit points are passed
 * over; anything else is listed and hit for the push damage at the character's level, handed
 * through the character's listening actions with the run's hit id first, the hidden test lifted.
 * The first hit of an update tells the listening actions an attack ended.
 *
 * <p>The update never finishes the run: the row's stop gate does, as the runtime asks it before
 * each update.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the hit id from the battle's counter as the run starts, the"
            + " centre in 32-bit arithmetic with a division by zero answering 0, the list checked"
            + " before the push, the push filter, the side point with its 64-bit division and its"
            + " tie nudged by the width in cells, the push through the entry, the damage through"
            + " the listeners, the hit with the hidden test lifted and the ended-attack notice once"
            + " per update. Held up to the ram's first hit by"
            + " battle_ram_evolved_knight_after_20, battle_ram_evolved_knight_after_50 and"
            + " battle_ram_evolved_knight_after_80, which then part over the ram's own recoil"
            + " after a direct hit. Not modelled: that recoil, the push effect and the hit's"
            + " presentation.")
final class DamagingPushBackRun extends ActionInstance {

  /** The scale the side point's position along the facing is kept in. */
  private static final int SIDE_SCALE = 10_000;

  /** How far a push from a point on the line is moved off it, along the width. */
  private static final int ON_LINE_NUDGE = 10;

  private final DamagingPushBack row;
  private final CharacterEntity unit;

  /** The hit id every hit of the run carries, the battle's counter as the run starts. */
  private final int hitId;

  /** The ids of the objects the run has hit, in order. */
  private final List<Integer> hit = new ArrayList<>();

  /**
   * The start: the run takes the battle's next hit id.
   *
   * @param row the push
   * @param unit the character it runs on
   */
  DamagingPushBackRun(DamagingPushBack row, CharacterEntity unit) {
    super(row);
    this.row = row;
    this.unit = unit;
    this.hitId = unit.world().nextHitId();
  }

  @Override
  protected void update(ActionHolder holder) {
    DamagingPushBack.Columns columns = row.getColumns();
    BattleWorld world = unit.world();
    GridEntity view = unit.getView();
    int hx = view.getDirX();
    int hy = view.getDirY();
    int length = FixedMath.isqrt(FixedMath.guardedSumOfSquares(hx, hy));
    int offset = columns.pushRadiusDirectionalOffset();
    int cx = FixedMath.divOrZero(offset * hx, length) + view.getX();
    int cy = FixedMath.divOrZero(offset * hy, length) + view.getY();
    List<WorldEntity> objects =
        world.objectQuery(unit, cx, cy, columns.pushBackRadius(), columns.gameObjectFilter());
    int damage = unit.damageAtLevel(columns.pushBackDamage());
    boolean noticed = false;
    for (WorldEntity object : objects) {
      int id = object.getId();
      if (hit.contains(id)) {
        continue;
      }
      if (object instanceof CharacterEntity pushed
          && columns
              .pushFilter()
              .matches(object.filterSubject(), unit.side() & 1, unit.getData().name())) {
        int[] from = columns.pushToSide() ? sidePoint(world, hx, hy, cx, cy, object) : null;
        pushed.pushedByGuard(
            from == null ? cx : from[0],
            from == null ? cy : from[1],
            columns.pushBackStrength(),
            columns.distanceProportionalPush(),
            columns.continuousPushBack());
      }
      if (object instanceof CharacterEntity && object.untouchable(true)) {
        continue;
      }
      if (damage < 1 || object.getHitPoints() == null) {
        continue;
      }
      hit.add(id);
      int amount = unit.listenedDamage(damage, hitId);
      world.dealCarriedPushDamage(
          unit,
          object,
          amount,
          object.getView().getX() - view.getX(),
          object.getView().getY() - view.getY());
      if (!noticed) {
        unit.actionHolder().attackEnded();
        noticed = true;
      }
    }
  }

  /**
   * The point a push to the side starts from: the object's foot on the line through the centre
   * along the facing, the position along the facing kept in ten-thousandths of its squared length
   * by a 64-bit division whose low 32 bits are kept. An object standing on the line is pushed from
   * a point ten units along the width beside it, toward the arena's left edge only when its x is
   * not beyond half the width counted in cells.
   */
  private static int[] sidePoint(
      BattleWorld world, int hx, int hy, int cx, int cy, WorldEntity object) {
    int ox = object.getView().getX();
    int oy = object.getView().getY();
    int dot = hx * (ox - cx) + hy * (oy - cy);
    long squared = Integer.toUnsignedLong(hx * hx + hy * hy);
    int t = squared == 0 ? 0 : (int) ((long) dot * SIDE_SCALE / squared);
    int px = hx * t / SIDE_SCALE + cx;
    int py = hy * t / SIDE_SCALE + cy;
    if (px == ox && py == oy) {
      int half = world.getTileMap().width() >> 1;
      px = ox <= half ? ox - ON_LINE_NUDGE : ox + ON_LINE_NUDGE;
      py = oy;
    }
    return new int[] {px, py};
  }
}
