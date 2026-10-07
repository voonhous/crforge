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
 * An attack that chains projectiles from target to target, as the evolved Electro Dragon's does: an
 * attack sequence entry schedules it with the hit's target as its cause, in place of the launch and
 * the direct hit, and each run launches its first hop from the owner and then hops on.
 *
 * <p>The start keeps the owner's point and height as the run's point, no hop yet, and the cause's
 * id. The first step finds the cause by its id among the live objects (the run finishes without
 * it), launches the first hop at it and adds the step to the run's time. Every later step:
 *
 * <ul>
 *   <li>finishes the run, and tells the owner's listening actions an attack ended, when the owner's
 *       tag word holds ATTACKING (a hit of its own on the step before) or NO_ATTACK, when its
 *       targeting component is off, when MaxChainLength hops have been launched or when the run's
 *       time has reached MaxTime (-1 for no limit on either);
 *   <li>follows the last hop's projectile while it is live, the run's point becoming the point it
 *       flies to, and once it is gone forgets it and restarts the hop timer at 0;
 *   <li>runs the next-target search once the hop timer has reached the hop's ChainDelays entry (the
 *       last for later hops, 0 without any) and stops the timer at -1; otherwise a running timer
 *       gains the step the owner's hit speed makes of 50;
 *   <li>adds 50 to the run's time.
 * </ul>
 *
 * <p>A hop launch takes the hop's projectile from Projectiles (the last for later hops): the first
 * from the owner as its attack's start, every later one from the run's point; it keeps the
 * projectile's id, moves the run's point to where the target stands, lists the target as remembered
 * (the oldest dropped past MaximumTargetsToRememberForRepeatChecks) and as the only previous
 * target, and counts the hop. Since the hop timer stays where it was, the first search runs on the
 * step after the first hop, while its projectile still flies, and every later one once the last
 * hop's projectile has gone.
 *
 * <p>The search asks the ChainTargets filter of the hop (the last for later hops) in the centre
 * query around the run's point to ChainRange (plus the owner's radius before any hop), skips the
 * ids of the exclusion list and takes the least squared distance less the object's const-priority
 * offset, floored at 0, the earlier of equals. The exclusion list is the previous target alone when
 * RepeatTargets is set and DeprioritizeRepeatTargets is not, otherwise the remembered targets; with
 * DeprioritizeRepeatTargets a search that finds nobody asks again excluding only the previous
 * target. A target found is hopped to at once; nobody found finishes the run.
 *
 * <p>Refused as the row is built: no projectile or no filter.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the start, the first hop from the owner on the first step, the"
            + " ends by tag, component and limits with their notice, the projectile followed, the"
            + " hop timer and its delays, the search with its two exclusion lists and its centre"
            + " query, the hop from the run's point and the remembered targets. Held by"
            + " evo_electrodragon_vs_musketeer, where every chain ends after one hop (side 1's"
            + " princess tower stands 4001 from the hop's point, outside the centre query), and"
            + " evo_electrodragon_chain_vs_musketeer_giant, where a chain hops ten times between"
            + " a Musketeer, a Giant and a princess tower, the deprioritized search bringing it"
            + " back, and ends by the owner's next hit (whose end there changes nothing a"
            + " reference shows: the search on the next step would find nobody). On 16.402.18 the"
            + " run starts with the hop timer stopped, so the second hop waits for the first to"
            + " land: held by evo_electrodragon_chain_vs_musketeer_giant and two random battles"
            + " (a hop after a first hop that killed its target, and one after a first hop that"
            + " flew two steps). Not held by a"
            + " reference: ChainDelays, MaxChainLength, MaxTime, a stun's end and a search"
            + " before any hop."
            + " Refused: a row without a projectile or a filter.")
public final class ChainProjectileAttack extends RowAction {

  /** The step the run's time and the hop timer take, before the owner's buffs, in milliseconds. */
  private static final int STEP_MS = 50;

  /** The id the run keeps for an object it does not hold. */
  public static final int NONE = -1;

  /**
   * The row's own columns.
   *
   * @param projectiles the projectile of each hop, the last for every later hop
   * @param chainRange how far from the run's point the search reaches, centre to centre
   * @param chainTargets the filter of each hop's search, the last for every later hop
   * @param maxChainLength how many hops a run launches at most, -1 for no limit
   * @param repeatTargets true when a target hopped to before may be hopped to again
   * @param deprioritizeRepeatTargets true when the search first skips every remembered target and
   *     only then any but the previous one
   * @param maxRemembered how many targets the run remembers, -1 for all
   * @param maxTimeMs how long a run lasts at most, -1 for no limit
   * @param chainDelaysMs how long after the last hop's projectile has gone each search waits, the
   *     last for every later hop; empty for none
   */
  @Builder
  public record Columns(
      List<String> projectiles,
      int chainRange,
      List<GameObjectFilter> chainTargets,
      int maxChainLength,
      boolean repeatTargets,
      boolean deprioritizeRepeatTargets,
      int maxRemembered,
      int maxTimeMs,
      List<Integer> chainDelaysMs) {

    public Columns {
      projectiles = List.copyOf(projectiles);
      chainTargets = List.copyOf(chainTargets);
      chainDelaysMs = List.copyOf(chainDelaysMs);
    }
  }

  /** The row's own columns. */
  @Getter private final Columns columns;

  /**
   * @param row the row's shared columns
   * @param columns its own columns
   */
  public ChainProjectileAttack(ActionRow row, Columns columns) {
    super(row);
    this.columns = columns;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    ChainAttackHost host = holder.getOwner().chainAttackHost();
    int cause = instigator == null ? NONE : instigator.getOwner().actionId();
    return new Run(this, host, cause);
  }

  /** One run: its point, its hops, its timers and the targets it remembers. */
  public static final class Run extends ActionInstance {

    private final ChainProjectileAttack attack;
    private final ChainAttackHost host;

    /** The id of the cause, the first hop's target. */
    private final int instigatorId;

    /** The last hop's projectile while the run follows it, else {@link #NONE}. */
    @Getter private int projectileId = NONE;

    /** The run's point along the width, along the length and its height. */
    private int x;

    private int y;
    private int z;

    /** The hops launched. */
    @Getter private int hops;

    /** The run's time. */
    @Getter private int timeMs;

    /** The hop timer, -1 while it is stopped. */
    @Getter private int hopTimerMs;

    /** The targets hopped to, oldest first. */
    private final List<Integer> remembered = new ArrayList<>();

    /** The last target hopped to, alone. */
    private final List<Integer> previous = new ArrayList<>();

    /** The id of each hop's projectile, in order. */
    private final List<Integer> launched = new ArrayList<>();

    private Run(ChainProjectileAttack attack, ChainAttackHost host, int instigatorId) {
      super(attack);
      this.attack = attack;
      this.host = host;
      this.instigatorId = instigatorId;
      this.x = host.ownerX();
      this.y = host.ownerY();
      this.z = host.ownerZ();
      // A stopped timer makes the first search wait, as every later one does, until the first
      // hop's projectile has gone; a timer at 0 runs it on the step after the first hop.
      this.hopTimerMs = host.firstSearchWaitsForHop() ? -1 : 0;
    }

    /** The ids of every hop's projectile so far, in order. */
    public List<Integer> launched() {
      return List.copyOf(launched);
    }

    /** The targets the run remembers, oldest first. */
    public List<Integer> remembered() {
      return List.copyOf(remembered);
    }

    @Override
    protected void update(ActionHolder holder) {
      Columns columns = attack.getColumns();
      if (hops == 0) {
        if (instigatorId == NONE || !host.live(instigatorId)) {
          finish();
          return;
        }
        hop(instigatorId);
        timeMs += STEP_MS;
        return;
      }
      if (host.attackingOrNoAttack()
          || !host.targetingOn()
          || (columns.maxChainLength() != -1 && hops >= columns.maxChainLength())
          || (columns.maxTimeMs() != -1 && timeMs >= columns.maxTimeMs())) {
        finish();
        host.attackEnded();
        return;
      }
      if (projectileId != NONE) {
        if (!host.live(projectileId)) {
          hopTimerMs = 0;
          projectileId = NONE;
        } else {
          int[] aim = host.projectileAim(projectileId);
          x = aim[0];
          y = aim[1];
          z = aim[2];
        }
      }
      List<Integer> delays = columns.chainDelaysMs();
      int delay = delays.isEmpty() ? 0 : delays.get(Math.min(hops, delays.size()) - 1);
      if (hopTimerMs >= delay) {
        search();
        hopTimerMs = -1;
      } else if (hopTimerMs >= 0) {
        hopTimerMs += host.timeStep(STEP_MS);
      }
      timeMs += STEP_MS;
    }

    /** Launches a hop at the target and lists it. */
    private void hop(int target) {
      List<String> projectiles = attack.getColumns().projectiles();
      int count = projectiles.size();
      if (hops == 0) {
        projectileId = host.launchFromOwner(projectiles.get(0), target);
      } else {
        String row = projectiles.get(hops < count ? hops : count - 1);
        projectileId = host.launchFrom(row, target, x, y, z);
      }
      launched.add(projectileId);
      x = host.x(target);
      y = host.y(target);
      z = host.z(target);
      remembered.add(target);
      int max = attack.getColumns().maxRemembered();
      if (max != -1 && remembered.size() > max) {
        remembered.remove(0);
      }
      previous.clear();
      previous.add(target);
      hops++;
    }

    /** The next-target search: a hop to the target it finds, or the run's end. */
    private void search() {
      Columns columns = attack.getColumns();
      int reach = columns.chainRange() + (hops != 0 ? 0 : host.ownerRadius());
      List<GameObjectFilter> filters = columns.chainTargets();
      GameObjectFilter filter = filters.get(Math.min(hops, filters.size()) - 1);
      boolean previousOnly = columns.repeatTargets() && !columns.deprioritizeRepeatTargets();
      int found = find(reach, filter, previousOnly ? previous : remembered);
      if (found == NONE && columns.deprioritizeRepeatTargets()) {
        found = find(reach, filter, previous);
      }
      if (found != NONE) {
        hop(found);
        return;
      }
      finish();
    }

    /**
     * The finder: the centre query around the run's point, the excluded ids skipped, then the least
     * squared distance less the object's const-priority offset, floored at 0, strictly less; the
     * earlier of equals.
     */
    private int find(int reach, GameObjectFilter filter, List<Integer> excluded) {
      int best = NONE;
      int bestDistance = Integer.MAX_VALUE;
      for (int id : host.centreQuery(x, y, reach, filter)) {
        if (excluded.contains(id)) {
          continue;
        }
        int d =
            Math.max(
                FixedMath.squaredDistance(host.x(id), host.y(id), x, y) - host.priority(id), 0);
        if (d < bestDistance) {
          best = id;
          bestDistance = d;
        }
      }
      return best;
    }
  }
}
