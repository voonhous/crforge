package org.crforge.core.battle.action;

import lombok.Builder;
import lombok.Getter;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * The evolved Royal Ghost's starting part: a run on the Ghost that, on its first hit after each
 * time it was invisible, makes two summon areas beside what it hit, each of which spawns a summon
 * the next tick, and a damage area at that point a little later.
 *
 * <p><b>The run on the Ghost.</b> It starts with its latch set. Each step, from the run pass, it
 * first takes 50 ms off the damage area's countdown while that is 1 or more and, landing on exactly
 * 0, makes the damage area at the summon point; then it sets the latch to whether the Ghost is
 * invisible. The run never finishes by itself.
 *
 * <p><b>The hit.</b> The notice that one of the Ghost's attacks ended with a landed hit does
 * nothing unless the latch is set: the latch the last run pass left, so only the first hit after an
 * invisibility summons. With it set, the run keeps the Ghost's reference and takes its position as
 * the summon point; then it makes the two summon areas SummonDistance to either side of the point,
 * across the line from the Ghost to it - the left one at the point less the offset, the right one
 * at the point plus it - each made by the Ghost for its side, at its level, the Ghost its parent,
 * and on each area's holder a summon run is listed, its countdown the summon delay, the point to
 * face and the reference to hand over; last the damage area's countdown is set, and a countdown of
 * 0 makes the damage area at once.
 *
 * <p><b>The summon run.</b> Each step, from its area's run pass, takes 50 ms off its countdown; at
 * or below 0 it spawns its side's summon on the area's point and finishes, and the run pass after
 * removes it. The area's run pass first comes on the tick after the hit, as the area is admitted at
 * the hit tick's closing cleanup.
 *
 * <p>An object that leaves is forgotten as the reference of the run and of every summon run.
 *
 * <p>Refused as the row is built: tags, a singleton, a next action, the gates, a phase, a delay and
 * a speed byte, none of which the shipped rows set, and a summon row that hits at once, runs an
 * action on its summons or spawns them without their deploy. As it runs: an owner other than a
 * character, and a hit with no reference, whose summon point reads a slot not established. A summon
 * row scheduled as an action of its own is refused.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the latch set at the start and from the Ghost's invisibility in"
            + " every step, after the damage area's countdown, which makes the area only on"
            + " landing on 0; the hit notice gated by the latch, the reference and its position"
            + " kept, the two areas across the line to the point, the summon runs listed on"
            + " them, the countdown and an area made at once for a delay of 0; the summon run's"
            + " countdown, spawn and finish; and the reference forgotten as it leaves. Held by"
            + " buff_after_hits_ghost_evo. Refused: the shared columns no row sets, a summon"
            + " row's instant hit, its action on summons and a spawn without deploy, a hit with"
            + " no reference and an owner other than a character.")
public final class GhostEvo extends RowAction {

  /** The step every countdown takes, in milliseconds. */
  private static final int STEP_MS = 50;

  /**
   * The row's own columns.
   *
   * @param summonDistance how far to either side of the point the summon areas stand
   * @param damageArea the area effect made at the point after the delay, or null for none
   * @param damageAreaDelayMs the delay after the hit before the damage area is made
   * @param leftArea the area effect made on the left of the point
   * @param rightArea the area effect made on the right of the point
   * @param summon the row each summon area's run follows
   */
  @Builder
  public record Columns(
      int summonDistance,
      String damageArea,
      int damageAreaDelayMs,
      String leftArea,
      String rightArea,
      Summon summon) {}

  /** What the run asks of the battle about the Ghost it runs on. */
  public interface Host {

    /**
     * The run's start, told to the battle's observers.
     *
     * @param phase the pending pass it started in, or 0 outside every pass
     */
    void started(int phase);

    /** Whether the Ghost is invisible: an instance of a buff that makes it so is listed. */
    boolean invisible();

    /** The Ghost's reference, or null for none. */
    BattleEntity reference();

    /** A position along the width of an object of the battle. */
    int x(BattleEntity entity);

    /** A position along the length of an object of the battle. */
    int y(BattleEntity entity);

    /** The Ghost's position along the width. */
    int x();

    /** The Ghost's position along the length. */
    int y();

    /**
     * Makes an area effect at a point, by the Ghost for its side and at its level, the Ghost its
     * parent, handed to the holder; it is admitted at the tick's closing cleanup.
     *
     * @param row the area effect's row
     * @param x its point along the width
     * @param y its point along the length
     * @param target the object it would follow under a follow-target row, or null
     * @return the area effect's action holder, which lists a summon run
     */
    ActionHolder makeArea(String row, int x, int y, BattleEntity target);

    /**
     * A hit that summoned, told to the battle's observers.
     *
     * @param reference the reference kept
     * @param x the summon point along the width
     * @param y the summon point along the length
     * @param countdownMs the damage area's countdown after it
     */
    void summoned(BattleEntity reference, int x, int y, int countdownMs);
  }

  /** What a summon run asks of the battle about the area it runs on. */
  public interface SummonHost {

    /**
     * Spawns one summon on the area's point, for its side and at its level, deploying, hands it the
     * reference when it takes it, faces it toward the point and queues it.
     *
     * @param row the summon's row
     * @param reference the reference to hand over, or null for none
     * @param x the point it faces along the width
     * @param y the point it faces along the length
     */
    void spawnSummon(String row, BattleEntity reference, int x, int y);
  }

  /** The row's own columns. */
  @Getter private final Columns columns;

  /**
   * @param row the row's shared columns
   * @param columns its own columns
   */
  public GhostEvo(ActionRow row, Columns columns) {
    super(row);
    this.columns = columns;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    Host host = holder.getOwner().ghostEvoHost(this);
    host.started(holder.passPhase());
    return new Run(this, host);
  }

  /** The run on the Ghost. */
  public static final class Run extends ActionInstance {

    private final Host host;
    private final Columns columns;

    /** Whether the Ghost was invisible at the last step: set from the start. */
    @Getter private boolean latch = true;

    /** The reference kept at the last hit that summoned, or null. */
    private BattleEntity reference;

    /** The summon point along the width. */
    private int pointX;

    /** The summon point along the length. */
    private int pointY;

    /** The damage area's countdown in milliseconds. */
    @Getter private int countdownMs;

    private Run(GhostEvo action, Host host) {
      super(action);
      this.host = host;
      this.columns = action.columns;
    }

    @Override
    protected void update(ActionHolder holder) {
      if (countdownMs >= 1) {
        countdownMs -= STEP_MS;
        if (countdownMs == 0 && columns.damageArea() != null) {
          host.makeArea(columns.damageArea(), pointX, pointY, reference);
        }
      }
      latch = host.invisible();
    }

    @Override
    protected void attackEnded(ActionHolder holder) {
      if (!latch) {
        return;
      }
      reference = host.reference();
      if (reference == null) {
        throw new UnsupportedOperationException(
            getAction().name()
                + " summons from a hit with no reference, whose point is not established");
      }
      pointX = host.x(reference);
      pointY = host.y(reference);
      int dx = pointX - host.x();
      int dy = host.y() - pointY;
      int distance = FixedMath.isqrt(dx * dx + dy * dy);
      int a = FixedMath.divOrZero(dy * columns.summonDistance(), distance);
      int b = FixedMath.divOrZero(dx * columns.summonDistance(), distance);
      Summon summon = columns.summon();
      ActionHolder left = host.makeArea(columns.leftArea(), pointX - a, pointY - b, null);
      left.list(summon.run(left.getOwner(), true, pointX, pointY, reference));
      ActionHolder right = host.makeArea(columns.rightArea(), pointX + a, pointY + b, null);
      right.list(summon.run(right.getOwner(), false, pointX, pointY, reference));
      countdownMs = columns.damageAreaDelayMs();
      host.summoned(reference, pointX, pointY, countdownMs);
      if (countdownMs == 0 && columns.damageArea() != null) {
        host.makeArea(columns.damageArea(), pointX, pointY, reference);
      }
    }

    @Override
    protected void objectLeft(int leftId) {
      if (reference != null && reference.getId() == leftId) {
        reference = null;
      }
    }
  }

  /**
   * The row a summon area's run follows: which summon each side spawns, after how long and whether
   * with its deploy. It is never scheduled itself.
   */
  public static final class Summon extends RowAction {

    private final String leftUnit;
    private final String rightUnit;
    private final int delayMs;

    /**
     * @param row the row's shared columns
     * @param leftUnit the left area's summon
     * @param rightUnit the right area's summon
     * @param delayMs the delay before the summon, in milliseconds
     */
    public Summon(ActionRow row, String leftUnit, String rightUnit, int delayMs) {
      super(row);
      this.leftUnit = leftUnit;
      this.rightUnit = rightUnit;
      this.delayMs = delayMs;
    }

    /**
     * The same row with the delay its Ghost's row gives every summon run it makes.
     *
     * @param delayMs the delay, in milliseconds
     */
    public Summon withDelay(int delayMs) {
      return new Summon(getRow(), leftUnit, rightUnit, delayMs);
    }

    @Override
    public ActionInstance start(ActionHolder holder) {
      throw new UnsupportedOperationException(
          name() + " is scheduled as an action of its own, which is not modelled");
    }

    private SummonRun run(ActionOwner area, boolean left, int x, int y, BattleEntity reference) {
      return new SummonRun(
          this, area.ghostSummonHost(), left ? leftUnit : rightUnit, x, y, reference);
    }
  }

  /** The run on a summon area. */
  private static final class SummonRun extends ActionInstance {

    private final SummonHost host;
    private final String unit;
    private final int pointX;
    private final int pointY;
    private BattleEntity reference;
    private int countdownMs;

    private SummonRun(
        Summon action, SummonHost host, String unit, int x, int y, BattleEntity reference) {
      super(action);
      this.host = host;
      this.unit = unit;
      this.pointX = x;
      this.pointY = y;
      this.reference = reference;
      this.countdownMs = action.delayMs;
    }

    @Override
    protected void update(ActionHolder holder) {
      countdownMs -= STEP_MS;
      if (countdownMs > 0) {
        return;
      }
      host.spawnSummon(unit, reference, pointX, pointY);
      finish();
    }

    @Override
    protected void objectLeft(int leftId) {
      if (reference != null && reference.getId() == leftId) {
        reference = null;
      }
    }
  }
}
