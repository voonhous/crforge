package org.crforge.core.battle;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntPredicate;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The battle's target locks: which object holds a claim on which target, per channel, so two
 * collectors never work on the same friend. The first request of a battle makes it.
 *
 * <p>An object asks for a target on a channel with a priority. The request is only filed; the
 * post-pass after the phase-3 pending pass grants, per channel and target, the request with the
 * highest priority - the first of equals keeps it - and then forgets every request. A target locked
 * on a channel answers a further request only to the holder that asks under flag bit 1, and refuses
 * everyone else.
 *
 * <p>A release is only queued. The pre-pass, before the entities' pre-hooks, first drops every lock
 * whose target or holder is no longer found alive, unless the lock's flag bit 0 keeps it, then acts
 * on the queued releases: each takes the first lock on its target and channel, if its holder is the
 * one that released.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the request, the claim, the lock held by another, the queued"
            + " release, the pre-pass's drop and release queue and the post-pass's grant per"
            + " channel, the first of equals keeping it. Held by the reference battle"
            + " cg_giantbuffer_buffs_friends. The timeouts are never set in this build and are"
            + " carried as none.")
public final class TargetLocks {

  /** The two channels the manager is made with. */
  public static final int CHANNELS = 2;

  /** A lock that never times out. */
  private static final int NO_TIMEOUT = -1;

  /** The pre-pass step a running timeout loses. */
  private static final int STEP_MS = 50;

  /** One filed request, until the next post-pass. */
  private record Request(int owner, int target, int priority, int flags) {}

  /** One held lock. */
  private static final class Lock {
    private final int target;
    private final int owner;
    private final int channel;
    private final int flags;
    private int timeout;

    private Lock(int target, int owner, int channel, int flags, int timeout) {
      this.target = target;
      this.owner = owner;
      this.channel = channel;
      this.flags = flags;
      this.timeout = timeout;
    }
  }

  /** One queued release. */
  private record Release(int target, int owner, int channel) {}

  private final List<List<Request>> requests = new ArrayList<>();
  private final List<Lock> held = new ArrayList<>();
  private final List<Release> releases = new ArrayList<>();

  public TargetLocks() {
    for (int channel = 0; channel < CHANNELS; channel++) {
      requests.add(new ArrayList<>());
    }
  }

  /**
   * Asks for a target. With a lock held on the target and channel, answers whether the asker holds
   * it and asks under flag bit 1, filing nothing; with none, files the request and answers true.
   *
   * @param owner the id of the object asking
   * @param target the id of the target
   * @param channel the channel
   * @param priority the request's priority; the highest wins at the post-pass
   * @param flags the request's flags, kept on the lock it becomes
   */
  public boolean request(int owner, int target, int channel, int priority, int flags) {
    for (Lock lock : held) {
      if (lock.target == target && lock.channel == channel) {
        return (flags & 2) != 0 && lock.owner == owner;
      }
    }
    requests.get(channel).add(new Request(owner, target, priority, flags));
    return true;
  }

  /** Whether the object holds a lock on the target and channel. */
  public boolean claim(int owner, int target, int channel) {
    for (Lock lock : held) {
      if (lock.target == target && lock.owner == owner && lock.channel == channel) {
        return true;
      }
    }
    return false;
  }

  /** Whether another object holds a lock on the target and channel. */
  public boolean heldByOther(int owner, int target, int channel) {
    for (Lock lock : held) {
      if (lock.target == target && lock.owner != owner && lock.channel == channel) {
        return true;
      }
    }
    return false;
  }

  /** Queues a release, which the next pre-pass acts on. */
  public void release(int owner, int target, int channel) {
    releases.add(new Release(target, owner, channel));
  }

  /**
   * The holder pre-pass's step: the timeouts, the drop of every lock whose target or holder is no
   * longer found alive, then the release queue.
   *
   * @param alive whether the object with an id is still in the battle's live list and alive
   */
  public void prePass(IntPredicate alive) {
    for (Lock lock : List.copyOf(held)) {
      if (lock.timeout < 0) {
        continue;
      }
      int before = lock.timeout;
      lock.timeout = before - STEP_MS;
      if (before <= STEP_MS) {
        release(lock.owner, lock.target, lock.channel);
      }
    }
    for (Iterator<Lock> it = held.iterator(); it.hasNext(); ) {
      Lock lock = it.next();
      if ((lock.flags & 1) == 0 && !(alive.test(lock.target) && alive.test(lock.owner))) {
        it.remove();
      }
    }
    if (!held.isEmpty()) {
      for (Release release : releases) {
        for (int i = 0; i < held.size(); i++) {
          Lock lock = held.get(i);
          if (lock.target == release.target() && lock.channel == release.channel()) {
            if (lock.owner == release.owner()) {
              held.remove(i);
            }
            break;
          }
        }
      }
    }
    releases.clear();
  }

  /**
   * The holder post-pass's step, after the phase-3 pending pass: each channel in turn grants, per
   * target in ascending id, the request with the highest priority, and then every request is
   * forgotten.
   */
  public void postPass() {
    for (int channel = 0; channel < CHANNELS; channel++) {
      Map<Integer, Request> best = new TreeMap<>();
      for (Request request : requests.get(channel)) {
        Request current = best.get(request.target());
        // Strictly greater replaces, from -1, so the first of equals stays.
        if ((current == null ? -1 : current.priority()) >= request.priority()) {
          continue;
        }
        best.put(request.target(), request);
      }
      for (Request request : best.values()) {
        held.add(new Lock(request.target(), request.owner(), channel, request.flags(), NO_TIMEOUT));
      }
    }
    for (List<Request> channel : requests) {
      channel.clear();
    }
  }
}
