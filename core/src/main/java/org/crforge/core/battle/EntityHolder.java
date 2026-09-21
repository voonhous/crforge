package org.crforge.core.battle;

import static org.crforge.core.util.ValidationUtils.checkState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * Owns the battle's entities and runs the entity tick: every hook, component pass and action pass
 * of every entity, in one fixed order.
 *
 * <p>The order is the point of this class. One tick is:
 *
 * <ol>
 *   <li>cleanup: drop removable entities, then admit the entities added since the last cleanup;
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
 * id, which is creation order. An entity added during a tick is not visited until the next one,
 * because it is admitted by a cleanup and the snapshot is already taken. An entity that becomes
 * removable during a tick is still visited for the rest of that tick and is gone before the next
 * snapshot.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "The order of hooks, component passes and action passes within a tick is settled, and so"
            + " are the snapshot and the id ordering. Not settled: what removal does to an entity"
            + " that other entities still refer to, and whether anything reorders the live list"
            + " between ticks.")
public class EntityHolder {

  private final HolderPasses passes;

  /** The registered entities in ascending id. */
  private final List<BattleEntity> live = new ArrayList<>();

  /** Entities handed over since the last cleanup, in the order they arrived. */
  private final List<BattleEntity> pendingAdditions = new ArrayList<>();

  private int nextId = 1;

  /** True while a tick is running, so a re-entrant tick fails loudly instead of corrupting it. */
  private boolean ticking;

  public EntityHolder(HolderPasses passes) {
    this.passes = passes;
  }

  /**
   * Hands an entity to the holder. It waits, without an id, until the next cleanup admits it; an
   * entity added during a tick therefore takes no part in that tick.
   */
  public void add(BattleEntity entity) {
    checkState(
        entity.getId() == BattleEntity.UNASSIGNED_ID,
        () -> "entity " + entity.getId() + " is already registered");
    pendingAdditions.add(entity);
  }

  /** The registered entities in ascending id. Entities still waiting for a cleanup are absent. */
  public List<BattleEntity> entities() {
    return Collections.unmodifiableList(live);
  }

  /**
   * Drops every removable entity from both lists, then admits the pending additions in the order
   * they arrived, giving each its id as it is admitted. Ids only ever grow, so appending keeps the
   * live list sorted.
   */
  public void cleanup() {
    live.removeIf(BattleEntity::isRemovable);
    pendingAdditions.removeIf(BattleEntity::isRemovable);
    for (BattleEntity entity : pendingAdditions) {
      entity.assignId(nextId++);
      live.add(entity);
      entity.onRegistered();
    }
    pendingAdditions.clear();
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
