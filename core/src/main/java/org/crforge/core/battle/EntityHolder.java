package org.crforge.core.battle;

import static org.crforge.core.util.ValidationUtils.checkState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * Owns the battle's entities and runs the entity tick: every hook, component pass and action pass
 * of every entity, in one fixed order.
 *
 * <p>The order is the point of this class. One tick is:
 *
 * <ol>
 *   <li>cleanup: drop removable entities, telling every remaining entity and the holder's passes of
 *       each removal, then admit the entities added since the last cleanup;
 *   <li>take a snapshot of the live list, which every entity loop below runs over;
 *   <li>the holder pre-pass;
 *   <li>every entity's pre-hook;
 *   <li>every entity's pending actions of phase 1;
 *   <li>one whole-list pass per component slot, lowest slot first: the component's refresh, then
 *       its visit when the component is switched on;
 *   <li>every entity's running actions;
 *   <li>every entity's pending actions of phase 2;
 *   <li>every entity's post-hook;
 *   <li>the holder's after-post-hooks step, then every entity's pending actions of phase 3;
 *   <li>the holder post-pass;
 *   <li>cleanup again;
 *   <li>the end-of-tick action countdown, over the live list rather than the snapshot.
 * </ol>
 *
 * <p>Three consequences that the rest of the simulation leans on. Entities are visited in ascending
 * id, which is creation order within a kind, with every kind's band of ids ahead of the next: a
 * projectile created in the middle of a battle is still visited before every character. An entity
 * added during a tick is not visited until the next one, because it is admitted by a cleanup and
 * the snapshot is already taken. An entity that becomes removable during a tick is still visited
 * for the rest of that tick, each of its components as long as that component is switched on, and
 * is gone before the next snapshot; a character's death switches its movement component off.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "The order of hooks, component passes and action passes within a tick is settled, and so"
            + " are the snapshot, the ids as the kind's band plus a per-kind counter taken when the"
            + " entity is handed over, the live list sorted by id, and that every remaining entity"
            + " is told of a removal inside the cleanup that removes it, the entities handed over"
            + " that tick before the live list, so a reference to a dead entity is dropped before"
            + " the next visit; and that an entity killed during a tick is visited by the rest of"
            + " it, less the components its death switches off. Not settled: whether the removed entity is"
            + " told of its own removal, and whether anything reorders the live list between"
            + " ticks.")
public class EntityHolder {

  private final HolderPasses passes;

  /** The registered entities in ascending id. */
  private final List<BattleEntity> live = new ArrayList<>();

  /** Entities handed over since the last cleanup, in the order they arrived. */
  private final List<BattleEntity> pendingAdditions = new ArrayList<>();

  /** How many entities of each kind have been handed over so far, indexed by kind. */
  private final Map<Integer, Integer> handedOverByKind = new HashMap<>();

  /** True while a tick is running, so a re-entrant tick fails loudly instead of corrupting it. */
  private boolean ticking;

  public EntityHolder(HolderPasses passes) {
    this.passes = passes;
  }

  /**
   * Hands an entity to the holder. It is given its id at once - its kind's band plus how many of
   * its kind came before it - and waits until the next cleanup admits it to the live list; an
   * entity added during a tick therefore has its id in that tick but takes no part in it.
   */
  public void add(BattleEntity entity) {
    checkState(
        entity.getId() == BattleEntity.UNASSIGNED_ID,
        () -> "entity " + entity.getId() + " is already registered");
    int kind = entity.getKind();
    int counter = handedOverByKind.getOrDefault(kind, 0);
    handedOverByKind.put(kind, counter + 1);
    entity.assignId(kind * BattleEntity.IDS_PER_KIND + counter % BattleEntity.IDS_PER_KIND);
    pendingAdditions.add(entity);
  }

  /** The entities handed over since the last cleanup, in the order they arrived. */
  public List<BattleEntity> queued() {
    return Collections.unmodifiableList(pendingAdditions);
  }

  /** The registered entities in ascending id. Entities still waiting for a cleanup are absent. */
  public List<BattleEntity> entities() {
    return Collections.unmodifiableList(live);
  }

  /**
   * Drops every removable entity from both lists, telling every entity still listed and the passes
   * of each removal in turn, then folds the pending additions into the live list, which stays
   * sorted by id: an entity of a lower kind lands ahead of every entity of a higher one however
   * late it arrived. The admitted entities are told of their registration in ascending id.
   */
  public void cleanup() {
    List<BattleEntity> removed = new ArrayList<>();
    drainRemovable(live, removed);
    drainRemovable(pendingAdditions, removed);
    for (BattleEntity gone : removed) {
      // The entities handed over this tick hear of it first, then the live list, so a projectile
      // launched on the tick its target dies loses the target in the same cleanup.
      for (BattleEntity entity : pendingAdditions) {
        entity.entityRemoved(gone);
      }
      for (BattleEntity entity : live) {
        entity.entityRemoved(gone);
      }
      passes.entityRemoved(gone);
    }
    if (pendingAdditions.isEmpty()) {
      return;
    }
    List<BattleEntity> admitted = new ArrayList<>(pendingAdditions);
    pendingAdditions.clear();
    live.addAll(admitted);
    live.sort(Comparator.comparingInt(BattleEntity::getId));
    admitted.sort(Comparator.comparingInt(BattleEntity::getId));
    for (BattleEntity entity : admitted) {
      entity.onRegistered();
    }
  }

  /** Moves every removable entity of a list, in list order, to the end of the removed list. */
  private static void drainRemovable(List<BattleEntity> list, List<BattleEntity> removed) {
    for (Iterator<BattleEntity> it = list.iterator(); it.hasNext(); ) {
      BattleEntity entity = it.next();
      if (entity.isRemovable()) {
        it.remove();
        removed.add(entity);
      }
    }
  }

  /**
   * Runs one entity tick.
   *
   * @param tick the battle tick this entity tick belongs to
   */
  public void tick(int tick) {
    checkState(!ticking, "the entity tick is not re-entrant");
    ticking = true;
    try {
      cleanup();
      List<BattleEntity> snapshot = new ArrayList<>(live);
      passes.prePass(tick, snapshot);
      for (BattleEntity entity : snapshot) {
        entity.preHook();
      }
      for (BattleEntity entity : snapshot) {
        entity.actions().pendingPass(EntityActions.PHASE_POST_TICK_INIT);
      }
      for (int slot = 0; slot < BattleEntity.COMPONENT_SLOTS; slot++) {
        for (BattleEntity entity : snapshot) {
          BattleComponent component = entity.component(slot);
          if (component == null) {
            continue;
          }
          component.refresh();
          if (entity.isActive(slot)) {
            component.visit();
          }
        }
      }
      for (BattleEntity entity : snapshot) {
        entity.actions().runPass(tick);
      }
      for (BattleEntity entity : snapshot) {
        entity.actions().pendingPass(EntityActions.PHASE_POST_COMPONENT_TICK);
      }
      for (BattleEntity entity : snapshot) {
        entity.postHook();
      }
      passes.afterPostHooks();
      for (BattleEntity entity : snapshot) {
        entity.actions().pendingPass(EntityActions.PHASE_POST_GAME_OBJECT_TICK);
      }
      passes.postPass(tick);
      cleanup();
      for (BattleEntity entity : live) {
        entity.actions().endOfTick();
      }
    } finally {
      ticking = false;
    }
  }
}
