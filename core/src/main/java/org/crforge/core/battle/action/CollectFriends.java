package org.crforge.core.battle.action;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import org.crforge.core.battle.TargetLocks;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that lasts and buffs the unit's nearest friends, one projectile each, as the Giant
 * Buffer does: its run looks for friends, asks the battle's target locks for them, waits, claims
 * the ones it was granted and fires at each, then cools down and looks again.
 *
 * <p>Each step first forgets the buffed friends no longer found. Then, by the run's state:
 *
 * <ul>
 *   <li><b>Looking.</b> The nearest friends within the search distance, up to the row's count less
 *       those already buffed, are each asked for on the collectors' lock channel, with the priority
 *       their squared distance flipped against the largest int; each accepted request is
 *       remembered. With any remembered and the row using the ability, the unit's ability is
 *       requested. The run then waits the buff delay.
 *   <li><b>Waiting.</b> The delay loses 50 a step, as long as the unit's buffs let it attack at all
 *       and it may attack. At zero the buff action is scheduled on the unit and the friends within
 *       the buff distance - the remembered ones still found offered again when there is room - are
 *       gathered; a remembered friend no longer among them is released. Each gathered friend it
 *       holds a lock on goes ahead; one it does not is asked for again under flag bit 1. With any
 *       going ahead, the run fires next; with none it cools down.
 *   <li><b>Firing.</b> At each remembered friend it holds a lock on: the target buff action on the
 *       friend and the projectile at it, and the friend counts as buffed. The run cools down.
 *   <li><b>Cooling down.</b> The cooldown loses 50 a step as the unit's buffs scale its hit speed,
 *       none while it may not attack; at zero or below it looks again.
 * </ul>
 *
 * <p>Gathering takes the objects of the query that are not the unit, not buffed already and not
 * locked by another collector, nearest first, the first of equals first. A row whose filter names a
 * row the tables do not hold has no filter: the object query of no filter lists nothing, and the
 * gathering ends there, so the run looks on every step and never finds a friend.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled line for line: the four states, the search and the buff distances, the requests"
            + " with their priorities, the claims, the releases, the launches and the hooks; held"
            + " by the reference battle cg_giantbuffer_buffs_friends. A row with no filter (a"
            + " name the tables do not hold) finds no friend. Refused: a row that unbuffs a"
            + " friend beyond a distance by ending its buff action, whose end is not modelled.")
public final class CollectFriends extends RowAction {

  /** The lock channel of the collectors. */
  public static final int CHANNEL = 1;

  /** The step every countdown loses, before any scaling. */
  private static final int STEP_MS = 50;

  /**
   * The row's own columns.
   *
   * @param cooldownMs the wait after firing before it looks again
   * @param maxFriendlyTroops how many friends it keeps buffed at most
   * @param targetFilter the filter its search asks, or null for none
   * @param distanceToGetTargets the search distance
   * @param distanceToBuff the distance a friend must stand within once the delay is up
   * @param distanceToUnbuff the distance beyond which no friend is gathered; 0 for none
   * @param useAbility true when finding friends requests the unit's ability
   * @param buffDelayMs the wait between asking for friends and claiming them
   * @param onBuffAction what it runs on the unit as the delay ends, or null
   * @param onTargetBuffAction what it runs on each friend it fires at, or null
   * @param projectile the projectile it fires at each friend
   */
  @Builder
  public record Columns(
      int cooldownMs,
      int maxFriendlyTroops,
      GameObjectFilter targetFilter,
      int distanceToGetTargets,
      int distanceToBuff,
      int distanceToUnbuff,
      boolean useAbility,
      int buffDelayMs,
      BattleAction onBuffAction,
      BattleAction onTargetBuffAction,
      ProjectileData projectile) {}

  private static final int COOLING = 0;
  private static final int LOOKING = 1;
  private static final int WAITING = 2;
  private static final int FIRING = 3;

  private final Columns columns;

  /**
   * @param row the row's shared columns
   * @param columns its own columns
   */
  public CollectFriends(ActionRow row, Columns columns) {
    super(row);
    this.columns = columns;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return new Run(this, holder.getOwner().friendCollecting());
  }

  /** One run: its state, its countdown and the friends it buffed and asked for. */
  private final class Run extends ActionInstance {

    private final FriendCollecting host;
    private int state = LOOKING;
    private int counterMs;
    private final List<Integer> buffed = new ArrayList<>();
    private final List<Integer> requested = new ArrayList<>();

    private Run(BattleAction action, FriendCollecting host) {
      super(action);
      this.host = host;
    }

    @Override
    protected void update(ActionHolder holder) {
      forgetTheLost();
      switch (state) {
        case LOOKING -> look();
        case COOLING -> {
          int rate = host.noAttack() ? 0 : STEP_MS;
          counterMs -= host.hitSpeed(rate);
          if (counterMs <= 0) {
            state = LOOKING;
          }
        }
        case WAITING -> waitAndClaim();
        case FIRING -> fire();
        default -> throw new IllegalStateException("state " + state);
      }
    }

    /**
     * The buffed friends no longer found are forgotten, the last taking a forgotten one's place.
     */
    private void forgetTheLost() {
      // The lock manager is asked for on every step, which makes it on the first.
      host.locks();
      int k = buffed.size();
      while (k > 0) {
        k--;
        if (!host.found(buffed.get(k))) {
          int last = buffed.size() - 1;
          buffed.set(k, buffed.get(last));
          buffed.remove(last);
        }
      }
    }

    private void look() {
      List<Integer> candidates = gather(columns.distanceToGetTargets(), List.of());
      if (candidates.isEmpty()) {
        return;
      }
      TargetLocks locks = host.locks();
      for (int id : candidates) {
        int priority = host.squaredDistance(id) ^ Integer.MAX_VALUE;
        if (locks.request(host.ownerId(), id, CHANNEL, priority, 0)) {
          requested.add(id);
        }
      }
      if (!requested.isEmpty() && columns.useAbility()) {
        host.requestAbility();
      }
      state = WAITING;
      counterMs = columns.buffDelayMs();
    }

    private void waitAndClaim() {
      if (host.hitSpeed(STEP_MS) >= 1 && !host.noAttack()) {
        counterMs -= STEP_MS;
      }
      if (counterMs > 0) {
        return;
      }
      TargetLocks locks = host.locks();
      if (columns.onBuffAction() != null) {
        host.schedule(host.ownerId(), columns.onBuffAction());
      }
      List<Integer> candidates = gather(columns.distanceToBuff(), requested);
      // A remembered friend no longer gathered is released, from the last down.
      int k = requested.size();
      while (k > 0) {
        k--;
        int id = requested.get(k);
        if (candidates.contains(id)) {
          continue;
        }
        locks.release(host.ownerId(), id, CHANNEL);
        requested.remove(k);
      }
      boolean going = false;
      for (int id : candidates) {
        if (locks.claim(host.ownerId(), id, CHANNEL)) {
          going = true;
          continue;
        }
        int priority = host.squaredDistance(id) ^ Integer.MAX_VALUE;
        if (locks.request(host.ownerId(), id, CHANNEL, priority, 2)) {
          requested.add(id);
          going = true;
        } else {
          requested.remove(Integer.valueOf(id));
        }
      }
      if (going) {
        state = FIRING;
      } else {
        state = COOLING;
        counterMs = columns.cooldownMs();
      }
    }

    private void fire() {
      TargetLocks locks = host.locks();
      for (int id : List.copyOf(requested)) {
        if (!locks.claim(host.ownerId(), id, CHANNEL)) {
          continue;
        }
        if (columns.onTargetBuffAction() != null) {
          host.schedule(id, columns.onTargetBuffAction());
        }
        host.launch(columns.projectile(), id);
        buffed.add(id);
      }
      requested.clear();
      state = COOLING;
      counterMs = columns.cooldownMs();
    }

    /**
     * At most the row's count less the buffed friends, nearest first: those the query answers
     * within the radius and, when that leaves room, the given ids still found and not yet taken,
     * whatever their distance.
     */
    private List<Integer> gather(int radius, List<Integer> offeredAgain) {
      List<Integer> out = new ArrayList<>();
      int room = columns.maxFriendlyTroops() - buffed.size();
      if (room < 1) {
        return out;
      }
      if (columns.targetFilter() == null) {
        // The query of no filter answers no list, and the gathering returns before it offers
        // any remembered friend again.
        return out;
      }
      offer(host.query(radius, columns.targetFilter()), out, room);
      room = columns.maxFriendlyTroops() - buffed.size();
      if (room > out.size()) {
        List<Integer> again = new ArrayList<>();
        for (int id : offeredAgain) {
          if (host.found(id) && !out.contains(id)) {
            again.add(id);
          }
        }
        offer(again, out, room);
      }
      return out;
    }

    /** Offers each object that is not the unit, not buffed and not locked by another collector. */
    private void offer(List<Integer> listed, List<Integer> out, int room) {
      for (int id : listed) {
        if (id == host.ownerId() || buffed.contains(id)) {
          continue;
        }
        if (host.locks().heldByOther(host.ownerId(), id, CHANNEL)) {
          continue;
        }
        insert(out, room, id);
      }
    }

    /**
     * Places an object before the first one strictly farther away, so the first of equals stays
     * first, refused beyond the unbuff distance when there is one; the list is cut to the room.
     */
    private void insert(List<Integer> out, int room, int id) {
      int d = host.squaredDistance(id);
      int unbuff = columns.distanceToUnbuff();
      if (unbuff != 0 && d > unbuff * unbuff) {
        return;
      }
      for (int k = 0; k < out.size(); k++) {
        if (d < host.squaredDistance(out.get(k))) {
          out.add(k, id);
          cut(out, room);
          return;
        }
      }
      if (out.size() < room) {
        out.add(id);
      }
      cut(out, room);
    }

    private static void cut(List<Integer> out, int room) {
      while (out.size() > room) {
        out.remove(out.size() - 1);
      }
    }
  }
}
