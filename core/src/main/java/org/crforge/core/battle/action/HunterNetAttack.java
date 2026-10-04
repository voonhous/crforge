package org.crforge.core.battle.action;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * The evolved Hunter's net: a starting action that, on its own cooldown and beside the owner's
 * ordinary attack, throws a projectile at the nearest enemy in its range.
 *
 * <p>The start sets the cooldown to InitialCooldown, with no cast running and no target kept. Each
 * step does nothing while the owner carries NO_ATTACK, or while the owner's buffs scale a step of
 * 100 below 1; otherwise the step is half of that scaled 100 (50 without a buff), and:
 *
 * <ul>
 *   <li>while a cast runs, the step comes off it, floored at 0; once it has run out the kept target
 *       is shot at (the finder asked again when the target has left), the cooldown set to Cooldown
 *       and the target forgotten; a shot that makes no projectile keeps them both;
 *   <li>otherwise, while the cooldown runs, the step comes off it, floored at 0; the step that
 *       empties it schedules ActionOnCooldownReady on the owner and does nothing more;
 *   <li>otherwise, with the owner's targeting component on, nothing happens while its attack has
 *       loaded for at most ForbidNetShotIfAttackedIn since its load time was last written, or while
 *       its next hit is at most ForbidNetShotIfAttackWithin away (HitSpeed less the attack time
 *       modulo HitSpeed); then the finder's target is kept and a cast of TrapCastTime (at least 1)
 *       starts.
 * </ul>
 *
 * <p>The finder asks the object query around the owner, building squares included, to Range plus
 * the owner's radius with TargetFilter; it orders the answers by their centre's squared distance
 * from the owner, the earlier of equals first, and takes the first that no other object holds a
 * target lock on in channel 0. A MinRange of 1 or more would also pass over an object closer than
 * it plus the owner's radius, edge to centre; that is refused, as no shipped row sets one.
 *
 * <p>The shot: the start is the owner's point plus the line to the target, set to the owner's
 * radius plus ProjectileStartExtraRadius, at ProjectileStartZ; the projectile is aimed at the
 * target; ActionOnShot runs on the owner when it was made. A target that leaves the battle is
 * forgotten.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the start, the NO_ATTACK and frozen gates, the halved scaled step,"
            + " the cast, the cooldown with its ready action, the two attack gates, the finder"
            + " (object query, centre-distance order, lock skip), the shot's start and the"
            + " forgotten target. Held by evo_hunter_vs_musketeer: two nets at a Musketeer, the"
            + " first decided as it comes into range and the second held back after a hit by"
            + " ForbidNetShotIfAttackedIn. Not held by a reference: ForbidNetShotIfAttackWithin"
            + " (the case passes without it), a buffed step, a held lock, a shot that makes"
            + " nothing, a target that leaves during the cast."
            + " Refused: a MinRange of 1 or more and ActionOnPrepareShot.")
public final class HunterNetAttack extends RowAction {

  /** The step the cast and the cooldown take, before halving and the owner's buffs. */
  private static final int STEP = 100;

  /** The lock channel the finder skips a held target in. */
  private static final int LOCK_CHANNEL = 0;

  /** The id the run keeps for no target. */
  public static final int NONE = -1;

  /**
   * The row's own columns.
   *
   * @param cooldownMs the wait after a shot
   * @param initialCooldownMs the wait from the start
   * @param range how far from the owner's edge the finder reaches
   * @param projectile the projectile's row
   * @param projectileStartZ the projectile's start height
   * @param projectileStartExtraRadius what the start adds to the owner's radius
   * @param targetFilter the finder's filter
   * @param trapCastTimeMs the cast between the finder and the shot
   * @param forbidIfAttackWithinMs no cast starts while the next hit is at most this away
   * @param forbidIfAttackedInMs no cast starts while the attack has loaded for at most this
   * @param onCooldownReady the action run on the owner as the cooldown runs out, or null
   * @param onShot the action run on the owner after a shot, or null
   */
  @Builder
  public record Columns(
      int cooldownMs,
      int initialCooldownMs,
      int range,
      String projectile,
      int projectileStartZ,
      int projectileStartExtraRadius,
      GameObjectFilter targetFilter,
      int trapCastTimeMs,
      int forbidIfAttackWithinMs,
      int forbidIfAttackedInMs,
      BattleAction onCooldownReady,
      BattleAction onShot) {}

  /** The row's own columns. */
  @Getter private final Columns columns;

  /**
   * @param row the row's shared columns
   * @param columns its own columns
   */
  public HunterNetAttack(ActionRow row, Columns columns) {
    super(row);
    this.columns = columns;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return new Run(this, holder.getOwner().netAttackHost());
  }

  /** One run: the cooldown, the cast and the target it keeps. */
  public static final class Run extends ActionInstance {

    private final HunterNetAttack attack;
    private final NetAttackHost host;

    /** The cooldown left. */
    @Getter private int cooldownMs;

    /** The cast left, 0 while none runs. */
    @Getter private int castMs;

    /** The kept target, {@link #NONE} for none. */
    @Getter private int targetId = NONE;

    /** The ids of the nets shot, in order. */
    private final List<Integer> shots = new ArrayList<>();

    private Run(HunterNetAttack attack, NetAttackHost host) {
      super(attack);
      this.attack = attack;
      this.host = host;
      this.cooldownMs = attack.getColumns().initialCooldownMs();
    }

    /** The ids of the nets shot so far, in order. */
    public List<Integer> shots() {
      return List.copyOf(shots);
    }

    @Override
    protected void update(ActionHolder holder) {
      if (host.noAttack()) {
        return;
      }
      int scaled = host.timeStep(STEP);
      if (scaled < 1) {
        return;
      }
      int step = scaled >> 1;
      Columns columns = attack.getColumns();
      if (castMs >= 1) {
        castMs = Math.max(0, castMs - step);
        if (castMs > 0) {
          return;
        }
        if (targetId == NONE) {
          targetId = find();
          if (targetId == NONE) {
            return;
          }
        }
        if (shoot(targetId)) {
          cooldownMs = columns.cooldownMs();
          targetId = NONE;
        }
        return;
      }
      if (cooldownMs >= 1) {
        cooldownMs = Math.max(0, cooldownMs - step);
        if (cooldownMs == 0 && columns.onCooldownReady() != null) {
          host.schedule(columns.onCooldownReady());
        }
        return;
      }
      if (host.targetingOn()) {
        if (host.loadedMs() <= columns.forbidIfAttackedInMs()) {
          return;
        }
        int hitSpeed = host.hitSpeedMs();
        int time = host.attackTimeMs();
        // As the division instruction answers, 0 for a zero divisor.
        int whole = hitSpeed == 0 ? 0 : time / hitSpeed * hitSpeed;
        if (hitSpeed + (whole - time) <= columns.forbidIfAttackWithinMs()) {
          return;
        }
      }
      targetId = find();
      if (targetId == NONE) {
        return;
      }
      castMs = Math.max(columns.trapCastTimeMs(), 1);
    }

    @Override
    protected void objectLeft(int leftId) {
      if (leftId == targetId) {
        targetId = NONE;
      }
    }

    /**
     * The finder: the object query around the owner to Range plus its radius, in the order of the
     * centre's squared distance from the owner, the earlier of equals first; the first no other
     * object holds a lock on.
     */
    private int find() {
      Columns columns = attack.getColumns();
      int x = host.ownerX();
      int y = host.ownerY();
      List<Integer> ordered = new ArrayList<>();
      List<Integer> distances = new ArrayList<>();
      for (int id : host.query(columns.range() + host.ownerRadius(), columns.targetFilter())) {
        int d = FixedMath.squaredDistance(host.x(id), host.y(id), x, y);
        int at = 0;
        while (at < distances.size() && d >= distances.get(at)) {
          at++;
        }
        ordered.add(at, id);
        distances.add(at, d);
      }
      for (int id : ordered) {
        if (!host.heldByOther(id, LOCK_CHANNEL)) {
          return id;
        }
      }
      return NONE;
    }

    /** The shot at the target; false when no projectile was made. */
    private boolean shoot(int target) {
      Columns columns = attack.getColumns();
      int[] line = {host.x(target) - host.ownerX(), host.y(target) - host.ownerY()};
      FixedMath.normalize(line, host.ownerRadius() + columns.projectileStartExtraRadius());
      int x = host.ownerX() + line[0];
      int y = host.ownerY() + line[1];
      int net = host.launch(columns.projectile(), target, x, y, columns.projectileStartZ());
      if (net == NONE) {
        return false;
      }
      shots.add(net);
      if (columns.onShot() != null) {
        host.schedule(columns.onShot());
      }
      return true;
    }
  }
}
