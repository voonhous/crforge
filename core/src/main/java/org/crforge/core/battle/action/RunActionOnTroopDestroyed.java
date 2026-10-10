/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import org.crforge.core.battle.filter.FilterSubject;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action whose run listens for destroyed objects, as the evolved Witch's run listens for the
 * deaths of the skeletons she summons. The run is told of every object its battle destroys, as the
 * object's death slot starts, and of every character its own object's spawner makes.
 *
 * <p>With {@code matchOnlyOwnSpawnedTroops} the run keeps the id of each character its object's
 * spawner made since the run started, and a destroyed object counts only if its id is kept; a
 * counted id is taken off the list, the later ones moving up. With {@code
 * matchOnlyFromSameOwnerIndex} the destroyed object must be of its object's side. With a filter the
 * destroyed object must pass it, asked for its object's team and row. Then the row's action is
 * scheduled on its object with its own delay, the destroyed object as the cause.
 *
 * <p>The run never finishes by itself; it is let go with its object, and from then on it listens
 * for nothing.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the spawn notice keeping each child's id, the death notice at the"
            + " start of the dying object's death slot, the id taken off the list, the side and"
            + " filter tests and the action on the object, the destroyed object its cause; the"
            + " registration lasting from the run's start to its release; held by"
            + " evo_witch_vs_musketeer. Refused as the row is built: a trigger limit, a range and"
            + " names to match, which no shipped row sets. Supplied: several listeners hear a death"
            + " in the order their runs started.")
public final class RunActionOnTroopDestroyed extends RowAction {

  /** What an object a run is on gives the run: the battle's death notices. */
  public interface Host {

    /**
     * Starts telling the listener of every destroyed object, from now until it is removed.
     *
     * @param listener the listener
     */
    void listen(Listener listener);

    /**
     * Stops telling the listener of destroyed objects.
     *
     * @param listener the listener
     */
    void unlisten(Listener listener);

    /** The object's side. */
    int side();

    /** The team a filter is asked for: the object's side's low bit. */
    int team();

    /** The object's row name, which a filter's same-objects exclusion compares with. */
    String rowName();
  }

  /** What a death notice tells a listener. */
  public interface Listener {

    /**
     * An object is destroyed: its death slot is starting.
     *
     * @param destroyed the object as a filter asks about it
     * @param id the object's id
     * @param side the object's side
     * @param holder the object's holder, the cause of what the listener schedules
     */
    void destroyed(FilterSubject destroyed, int id, int side, ActionHolder holder);
  }

  /** The action scheduled for each destroyed object that counts. */
  @Getter private final BattleAction actionToRun;

  private final GameObjectFilter filter;
  private final boolean matchOnlyOwnSpawnedTroops;
  private final boolean matchOnlyFromSameOwnerIndex;

  /**
   * @param row the row's shared columns
   * @param actionToRun the action scheduled for each destroyed object that counts, or null
   * @param filter the filter a destroyed object must pass, or null for none
   * @param matchOnlyOwnSpawnedTroops whether only characters its object's spawner made count
   * @param matchOnlyFromSameOwnerIndex whether only objects of its object's side count
   */
  public RunActionOnTroopDestroyed(
      ActionRow row,
      BattleAction actionToRun,
      GameObjectFilter filter,
      boolean matchOnlyOwnSpawnedTroops,
      boolean matchOnlyFromSameOwnerIndex) {
    super(row);
    this.actionToRun = actionToRun;
    this.filter = filter;
    this.matchOnlyOwnSpawnedTroops = matchOnlyOwnSpawnedTroops;
    this.matchOnlyFromSameOwnerIndex = matchOnlyFromSameOwnerIndex;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    Host host = holder.getOwner().troopDestroyedHost(this);
    Run run = new Run(holder, host);
    host.listen(run);
    return run;
  }

  /** One run: the ids of the characters its object's spawner made, and its registration. */
  private final class Run extends ActionInstance implements Listener {

    private final ActionHolder holder;
    private final Host host;
    private final List<Integer> spawned = new ArrayList<>();
    private boolean listening = true;

    private Run(ActionHolder holder, Host host) {
      super(RunActionOnTroopDestroyed.this);
      this.holder = holder;
      this.host = host;
    }

    @Override
    protected void update(ActionHolder holder) {
      // The run does nothing a step: all its work is in the notices.
    }

    @Override
    protected void childSpawned(int childId) {
      if (matchOnlyOwnSpawnedTroops) {
        spawned.add(childId);
      }
    }

    @Override
    public void destroyed(FilterSubject destroyed, int id, int side, ActionHolder cause) {
      if (matchOnlyOwnSpawnedTroops && !spawned.remove(Integer.valueOf(id))) {
        return;
      }
      if (matchOnlyFromSameOwnerIndex && host.side() != side) {
        return;
      }
      if (filter != null && !filter.matches(destroyed, host.team(), host.rowName())) {
        return;
      }
      if (actionToRun != null) {
        holder.schedule(actionToRun, ActionHolder.OWN_DELAY, false, cause);
      }
    }

    @Override
    protected void stop(ActionHolder holder) {
      if (listening) {
        host.unlisten(this);
        listening = false;
      }
      spawned.clear();
    }
  }
}
