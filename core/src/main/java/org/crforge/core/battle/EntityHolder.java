package org.crforge.core.battle;

import static org.crforge.core.util.ValidationUtils.checkState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
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
 *   <li>cleanup: drop the removable entities of the live list, telling every remaining entity and
 *       the holder's passes of each removal, round after round until a round drops nothing, then
 *       admit the entities added since the last cleanup, whatever they have come to;
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
            + " are the snapshot, the ids as the kind's band plus a per-kind counter taken when"
            + " the entity is handed over, the live list sorted by id, and that every remaining"
            + " entity is told of a removal inside the cleanup that removes it, the entities"
            + " handed over that tick before the live list, so a reference to a dead entity is"
            + " dropped before the next visit; that the cleanup's removals repeat until a round"
            + " removes nothing, so a rider let go by its parent leaves in the same cleanup, and"
            + " walk the live list alone, so an entity handed over spent is admitted and leaves"
            + " at the next, held by the reference battle card_GoblinDrill; that the leaving"
            + " entity's own running actions hear of its leaving before every notice, held by"
            + " ability_goblinstein, and are stopped after every notice, held by"
            + " card_GoblinMachine; and that an entity killed during a tick is visited by the"
            + " rest of it, less the components its death switches off. Not settled: whether the"
            + " removed entity is told of its own removal, and whether anything reorders the live"
            + " list between ticks.")
public class EntityHolder {

  private final HolderPasses passes;

  /** The registered entities in ascending id. */
  private final List<BattleEntity> live = new ArrayList<>();

  /** Entities handed over since the last cleanup, in the order they arrived. */
  private final List<BattleEntity> pendingAdditions = new ArrayList<>();

  /**
   * The id filed beside an entity handed over, for those handed over with one: the id of the object
   * that must still be listed, and not removable, when the fold admits it (the int vector +0x48
   * beside the queue).
   */
  private final Map<BattleEntity, Integer> requiredIds = new IdentityHashMap<>();

  /** How many entities of each kind have been handed over so far, indexed by kind. */
  private final Map<Integer, Integer> handedOverByKind = new HashMap<>();

  /** True while a tick is running, so a re-entrant tick fails loudly instead of corrupting it. */
  private boolean ticking;

  /**
   * True while one of the three pending passes runs over the snapshot. An action with no delay that
   * anything schedules meanwhile, on any entity, starts at once instead of waiting for the next
   * pending pass.
   */
  private boolean inPendingPass;

  /** True from the end of the tick's last pending pass, that of phase 3, to the end of the tick. */
  private boolean pendingPassesDone;

  public EntityHolder(HolderPasses passes) {
    this.passes = passes;
  }

  /**
   * Hands an entity to the holder. It is given its id at once - its kind's band plus how many of
   * its kind came before it - and waits until the next cleanup admits it to the live list; an
   * entity added during a tick therefore has its id in that tick but takes no part in it. The
   * battle holder's add recomputes the entity's tag word first ({@link BattleEntity#addTagFold()}),
   * as it does again at the admission.
   */
  public void add(BattleEntity entity) {
    checkState(
        entity.getId() == BattleEntity.UNASSIGNED_ID,
        () -> "entity " + entity.getId() + " is already registered");
    entity.addTagFold();
    int kind = entity.getKind();
    int counter = handedOverByKind.getOrDefault(kind, 0);
    handedOverByKind.put(kind, counter + 1);
    entity.assignId(kind * BattleEntity.IDS_PER_KIND + counter % BattleEntity.IDS_PER_KIND);
    pendingAdditions.add(entity);
  }

  /**
   * Hands an entity to the holder and registers it on the spot, as a spawn inside a pending pass
   * does: it is given its id, waits for the next cleanup like any other, and is given its
   * registration visit at once - the visit of each of its active components - over the tick's index
   * as the pre-pass built it. It is not visited again in this tick.
   */
  public void addRegistered(BattleEntity entity) {
    add(entity);
    entity.registrationVisit();
  }

  /**
   * Hands an entity to the holder and registers it on the spot, with the id of another object filed
   * beside it, as a buff's spawner hands over a child that needs its spawner alive: the fold admits
   * it only while an object with that id is listed and not removable. Releasing it otherwise is not
   * modelled: that fold is refused.
   *
   * @param entity the entity
   * @param requiredId the id of the object it needs, or 0 for none
   */
  public void addRegistered(BattleEntity entity, int requiredId) {
    add(entity);
    if (requiredId != 0) {
      requiredIds.put(entity, requiredId);
    }
    entity.registrationVisit();
  }

  /** The entities handed over since the last cleanup, in the order they arrived. */
  public List<BattleEntity> queued() {
    return Collections.unmodifiableList(pendingAdditions);
  }

  /** True while a pending pass of the tick is running, over any entity. */
  public boolean isInPendingPass() {
    return inPendingPass;
  }

  /**
   * True while a tick is running and its last pending pass, that of phase 3, has not finished: an
   * action queued now with no delay on an entity of the snapshot still starts in this tick.
   */
  public boolean hasPendingPassAhead() {
    return ticking && !pendingPassesDone;
  }

  /** The registered entities in ascending id. Entities still waiting for a cleanup are absent. */
  public List<BattleEntity> entities() {
    return Collections.unmodifiableList(live);
  }

  /**
   * Drops every removable entity, first of those waiting to be admitted, then of the live list,
   * telling every entity still listed and the passes of each removal in turn, then folds the
   * pending additions into the live list, which stays sorted by id: an entity of a lower kind lands
   * ahead of every entity of a higher one however late it arrived. The admitted entities are told
   * of their registration in ascending id. One handed over already removable, such as an area
   * object spent in the update that made it, is never admitted (the cleanup walks its queue before
   * its live list, and folds only after both).
   */
  public void cleanup() {
    // A removal can make another entity removable - a rider let go by its parent - so the rounds
    // repeat until one removes nothing.
    while (removalRound()) {
      // Each round has told every entity of what it removed.
    }
    if (pendingAdditions.isEmpty()) {
      return;
    }
    // An entity filed with another object's id is admitted only while that object is listed and
    // not removable; the release of one that fails the test is refused.
    for (BattleEntity entity : pendingAdditions) {
      Integer required = requiredIds.get(entity);
      if (required != null && !listedAndNotRemovable(required)) {
        throw new UnsupportedOperationException(
            "the object "
                + entity.getId()
                + " needs the object "
                + required
                + " listed as it is admitted, which has left or is leaving; its release is not"
                + " modelled");
      }
    }
    requiredIds.clear();
    // The fold hands each waiting entity to the battle holder's add again, in the order they
    // arrived, which recomputes its tag word before it joins the live list and is started.
    for (BattleEntity entity : pendingAdditions) {
      entity.addTagFold();
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

  /** Whether an object with an id is in the live list and its removal test answers false. */
  private boolean listedAndNotRemovable(int id) {
    for (BattleEntity entity : live) {
      if (entity.getId() == id) {
        return !entity.isRemovable();
      }
    }
    return false;
  }

  /**
   * One round of the cleanup's removals: every removable entity leaves the live list, and every
   * entity still listed hears of each in turn. The entities handed over this tick hear of it first,
   * then the live list, so a projectile launched on the tick its target dies loses the target in
   * the same cleanup. Each entity's components hear first, then its action holder's running
   * actions, then the holder drops what the leaving entity caused and still waits, then an entity
   * attached to it is let go; the leaving entity's own running actions are stopped after all of
   * them, then the side lists and the level re-read. Each notice goes to the entities listed as it
   * starts, so a child a notice makes does not hear of it. Before all of them the leaving entity's
   * own running actions hear that their owner leaves.
   *
   * @return true when the round removed anything
   */
  private boolean removalRound() {
    // The entities waiting to be admitted are walked first (the cleanup's first loop): one handed
    // over already removable, such as an area object spent in the update that made it, is never
    // admitted. Each hears of its own leaving while it is still waiting, as the game's notice
    // walks the queue before the queue lets it go; it gets no owner-leaving call, which the game
    // makes only for a live object (its slot +0x30).
    boolean any = false;
    for (int i = 0; i < pendingAdditions.size(); i++) {
      BattleEntity gone = pendingAdditions.get(i);
      if (!gone.isRemovable()) {
        continue;
      }
      any = true;
      notifyRemoval(gone);
      // The game's queue removal takes the last entry into the gap and looks at it next.
      int last = pendingAdditions.size() - 1;
      pendingAdditions.set(i, pendingAdditions.get(last));
      pendingAdditions.remove(last);
      requiredIds.remove(gone);
      i--;
      gone.actions().released();
    }
    List<BattleEntity> removed = new ArrayList<>();
    drainRemovable(live, removed);
    for (BattleEntity gone : removed) {
      // The leaving entity's own running actions hear of it first, before any notice.
      gone.actions().leaving();
      List<BattleEntity> listed = new ArrayList<>(pendingAdditions);
      listed.addAll(live);
      for (BattleEntity entity : listed) {
        entity.entityRemoved(gone);
        entity.actions().objectLeft(gone.getId());
        entity.actions().instigatorLeft(gone.actions());
        entity.parentRemoved(gone);
      }
      // The leaving entity's own running actions are stopped after every notice.
      gone.actions().released();
      passes.entityRemoved(gone);
    }
    return any || !removed.isEmpty();
  }

  /**
   * The notice of a waiting entity that leaves before it was admitted: every waiting entity, the
   * leaving one among them, then the live list, hears of it, then the side lists.
   */
  private void notifyRemoval(BattleEntity gone) {
    List<BattleEntity> listed = new ArrayList<>(pendingAdditions);
    listed.addAll(live);
    for (BattleEntity entity : listed) {
      entity.entityRemoved(gone);
      entity.actions().objectLeft(gone.getId());
      entity.actions().instigatorLeft(gone.actions());
      entity.parentRemoved(gone);
    }
    passes.entityRemoved(gone);
  }

  /** One pending pass over the snapshot, with the battle's in-pass flag set around it. */
  private void pendingPass(List<BattleEntity> snapshot, int phase) {
    inPendingPass = true;
    try {
      for (BattleEntity entity : snapshot) {
        entity.actions().pendingPass(phase);
      }
    } finally {
      inPendingPass = false;
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
    pendingPassesDone = false;
    try {
      cleanup();
      List<BattleEntity> snapshot = new ArrayList<>(live);
      passes.prePass(tick, snapshot);
      for (BattleEntity entity : snapshot) {
        entity.preHook();
      }
      pendingPass(snapshot, EntityActions.PHASE_POST_TICK_INIT);
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
      pendingPass(snapshot, EntityActions.PHASE_POST_COMPONENT_TICK);
      for (BattleEntity entity : snapshot) {
        entity.postHook();
      }
      passes.afterPostHooks();
      pendingPass(snapshot, EntityActions.PHASE_POST_GAME_OBJECT_TICK);
      pendingPassesDone = true;
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
