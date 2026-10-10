package org.crforge.core.battle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.crforge.core.battle.BattleEntity.KIND_CHARACTER;
import static org.crforge.core.battle.BattleEntity.KIND_PROJECTILE;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the order of one entity tick. Every hook of every stub writes one line into a shared log, so
 * each test reads as the exact sequence the holder is expected to produce.
 */
class EntityHolderTest {

  private final List<String> log = new ArrayList<>();

  private final HolderPasses recordingPasses =
      new HolderPasses() {
        @Override
        public void prePass(int tick, List<BattleEntity> snapshot) {
          log.add("prePass " + tick + " " + snapshot.stream().map(BattleEntity::getId).toList());
        }

        @Override
        public void afterPostHooks() {
          log.add("afterPostHooks");
        }

        @Override
        public void postPass(int tick) {
          log.add("postPass " + tick);
        }

        @Override
        public void entityRemoved(BattleEntity removed) {
          log.add("passes told " + removed.getId() + " left");
        }
      };

  /** An entity whose every hook, component and action pass records itself. */
  private class RecordingEntity extends BattleEntity {

    private final String name;
    boolean removable;

    /** Runs inside the named hook, so a test can change the holder in the middle of a tick. */
    Runnable duringPostHook = () -> {};

    /** True when the entity also records each recompute of its tag word by the holder's add. */
    boolean recordsTagFolds;

    RecordingEntity(String name, int... componentSlots) {
      this(KIND_CHARACTER, name, componentSlots);
    }

    RecordingEntity(int kind, String name, int... componentSlots) {
      super(kind);
      this.name = name;
      for (int slot : componentSlots) {
        attach(
            new BattleComponent() {
              @Override
              public int index() {
                return slot;
              }

              @Override
              public void refresh() {
                log.add(name + " refresh " + slot);
              }

              @Override
              public void visit() {
                log.add(name + " visit " + slot);
              }
            });
      }
    }

    @Override
    protected void entityRemoved(BattleEntity removed) {
      log.add(name + " told " + ((RecordingEntity) removed).name + " left");
    }

    @Override
    protected void onRegistered() {
      log.add(name + " registered as " + getId());
    }

    @Override
    protected void releasedAtFold() {
      log.add(name + " released");
    }

    @Override
    protected void preHook() {
      log.add(name + " preHook");
    }

    @Override
    protected void addTagFold() {
      if (recordsTagFolds) {
        log.add(name + " tag fold");
      }
    }

    @Override
    protected void postHook() {
      log.add(name + " postHook");
      duringPostHook.run();
    }

    @Override
    public EntityActions actions() {
      return new EntityActions() {
        @Override
        public void pendingPass(int phase) {
          log.add(name + " pending " + phase);
        }

        @Override
        public void runPass(int tick) {
          log.add(name + " run " + tick);
        }

        @Override
        public void endOfTick() {
          log.add(name + " endOfTick");
        }
      };
    }

    @Override
    public boolean isRemovable() {
      return removable;
    }
  }

  @Test
  @DisplayName("one tick runs every hook and pass of every entity in the fixed order")
  void tickOrder() {
    EntityHolder holder = new EntityHolder(recordingPasses);
    // a has targeting and movement; b has only a slot-1 component, so slot 0 skips it
    holder.add(new RecordingEntity("a", 0, 1));
    holder.add(new RecordingEntity("b", 1));

    holder.tick(7);

    assertThat(log)
        .containsExactly(
            "a registered as 5000000",
            "b registered as 5000001",
            "prePass 7 [5000000, 5000001]",
            "a preHook",
            "b preHook",
            "a pending 1",
            "b pending 1",
            // slot 0 for the whole list, then slot 1 for the whole list
            "a refresh 0",
            "a visit 0",
            "a refresh 1",
            "a visit 1",
            "b refresh 1",
            "b visit 1",
            "a run 7",
            "b run 7",
            "a pending 2",
            "b pending 2",
            "a postHook",
            "b postHook",
            "afterPostHooks",
            "a pending 3",
            "b pending 3",
            "postPass 7",
            "a endOfTick",
            "b endOfTick");
  }

  @Test
  @DisplayName(
      "the holder's add recomputes the tag word as it takes an entity: at hand-over, before a"
          + " registration visit, and again at admission, before the entity is registered")
  void theAddRecomputesTheTagWordAtHandOverAndAtAdmission() {
    EntityHolder holder = new EntityHolder(HolderPasses.NONE);
    RecordingEntity first = new RecordingEntity("a");
    RecordingEntity late = new RecordingEntity("late", 0);
    first.recordsTagFolds = true;
    late.recordsTagFolds = true;
    first.duringPostHook = () -> holder.addRegistered(late);
    holder.add(first);

    holder.tick(0);

    assertThat(log)
        .containsSubsequence(
            "a tag fold",
            "a tag fold",
            "a registered as 5000000",
            "a preHook",
            "a postHook",
            "late tag fold",
            "late visit 0",
            "late tag fold",
            "late registered as 5000001");
    assertThat(log.stream().filter(line -> line.endsWith("tag fold"))).hasSize(4);
  }

  @Test
  @DisplayName("a component that is switched off is still refreshed but not visited")
  void inactiveComponentIsRefreshedOnly() {
    EntityHolder holder = new EntityHolder(HolderPasses.NONE);
    RecordingEntity entity = new RecordingEntity("a", 0, 1);
    entity.setActive(1, false);
    holder.add(entity);

    holder.tick(0);

    assertThat(log).contains("a refresh 0", "a visit 0", "a refresh 1").doesNotContain("a visit 1");
  }

  @Test
  @DisplayName("an entity added during a tick is first visited on the next tick")
  void additionWaitsForTheNextTick() {
    EntityHolder holder = new EntityHolder(HolderPasses.NONE);
    RecordingEntity first = new RecordingEntity("a");
    RecordingEntity late = new RecordingEntity("late");
    first.duringPostHook = () -> holder.add(late);
    holder.add(first);

    holder.tick(0);

    // The closing cleanup admits it, so it is counted down at the end of the tick it arrived in,
    // but none of its hooks ran.
    assertThat(late.getId()).isEqualTo(5000001);
    assertThat(log).contains("late endOfTick").doesNotContain("late preHook", "late postHook");

    first.duringPostHook = () -> {};
    log.clear();
    holder.tick(1);
    assertThat(log).contains("late preHook", "late postHook");
  }

  @Test
  @DisplayName(
      "an entity handed over already removable is never admitted: the cleanup removes it from the"
          + " entities waiting before it folds them in")
  void aRemovableNewcomerIsNeverAdmitted() {
    EntityHolder holder = new EntityHolder(HolderPasses.NONE);
    RecordingEntity first = new RecordingEntity("a");
    RecordingEntity spent = new RecordingEntity("spent");
    spent.removable = true;
    first.duringPostHook = () -> holder.add(spent);
    holder.add(first);

    holder.tick(0);

    // The closing cleanup walks the entities waiting first, so it is gone before the fold.
    assertThat(holder.entities()).containsExactly(first);
    assertThat(log).doesNotContain("spent registered as 5000001");
    // Its notice reaches the entities waiting, itself among them, then the live list.
    assertThat(log).containsSubsequence("spent told spent left", "a told spent left");

    first.duringPostHook = () -> {};
    log.clear();
    holder.tick(1);

    assertThat(holder.entities()).containsExactly(first);
    assertThat(log).doesNotContain("spent preHook");
  }

  @Test
  @DisplayName(
      "an entity filed with the id of an object that is still listed and not removable is"
          + " admitted at the fold")
  void anEntityNeedingALiveSpawnerIsAdmitted() {
    EntityHolder holder = new EntityHolder(HolderPasses.NONE);
    RecordingEntity spawner = new RecordingEntity("spawner");
    RecordingEntity child = new RecordingEntity("child");
    spawner.duringPostHook = () -> holder.addRegistered(child, spawner.getId());
    holder.add(spawner);

    holder.tick(0);

    assertThat(holder.entities()).containsExactly(spawner, child);
  }

  @Test
  @DisplayName(
      "an entity filed with the id of an object that leaves in the same cleanup is released at the"
          + " fold: it is never admitted, nothing else hears of it, and the entities beside it are"
          + " admitted")
  void anEntityNeedingASpawnerThatLeftIsReleased() {
    EntityHolder holder = new EntityHolder(HolderPasses.NONE);
    RecordingEntity spawner = new RecordingEntity("spawner");
    RecordingEntity witness = new RecordingEntity("witness");
    RecordingEntity child = new RecordingEntity("child");
    RecordingEntity other = new RecordingEntity("other");
    holder.add(spawner);
    holder.add(witness);
    holder.tick(0);
    log.clear();
    spawner.duringPostHook =
        () -> {
          holder.addRegistered(child, spawner.getId());
          holder.add(other);
          spawner.removable = true;
        };

    holder.tick(1);

    // The spawner leaves in the cleanup's removals, which the waiting child hears of; the fold
    // then finds no spawner listed and releases the child instead of admitting it.
    assertThat(holder.entities()).containsExactly(witness, other);
    assertThat(log)
        .containsSubsequence(
            "child told spawner left", "witness told spawner left", "child released")
        .contains("other registered as 5000003")
        .doesNotContain("child registered as 5000002", "witness told child left")
        .doesNotContain("other told child left");

    log.clear();
    holder.tick(2);

    assertThat(holder.entities()).containsExactly(witness, other);
    assertThat(log).doesNotContain("child preHook");
  }

  @Test
  @DisplayName(
      "an entity that becomes removable is visited to the end of its tick and then dropped")
  void removalWaitsForTheCleanup() {
    EntityHolder holder = new EntityHolder(HolderPasses.NONE);
    RecordingEntity doomed = new RecordingEntity("doomed");
    RecordingEntity witness = new RecordingEntity("witness");
    doomed.duringPostHook = () -> doomed.removable = true;
    holder.add(doomed);
    holder.add(witness);

    holder.tick(0);

    // Still in the snapshot for phase 3, gone from the live list before the end-of-tick countdown.
    assertThat(log).contains("doomed pending 3").doesNotContain("doomed endOfTick");
    assertThat(holder.entities()).containsExactly(witness);
  }

  @Test
  @DisplayName(
      "each removal is told to the entities handed over this tick, then the live list, then the"
          + " passes, before any admission")
  void removalIsAnnouncedBeforeAdmission() {
    EntityHolder holder = new EntityHolder(recordingPasses);
    RecordingEntity doomed = new RecordingEntity("doomed");
    RecordingEntity witness = new RecordingEntity("witness");
    RecordingEntity newcomer = new RecordingEntity("newcomer");
    holder.add(doomed);
    holder.add(witness);
    holder.tick(0);
    log.clear();

    doomed.removable = true;
    holder.add(newcomer);
    holder.cleanup();

    // The newcomer, handed over but not yet admitted, hears of the removal before the live list.
    assertThat(log)
        .containsExactly(
            "newcomer told doomed left",
            "witness told doomed left",
            "passes told 5000000 left",
            "newcomer registered as 5000002");
    assertThat(holder.entities()).containsExactly(witness, newcomer);
  }

  @Test
  @DisplayName("ids are the kind's band plus the hand-over count of the kind, and never reused")
  void idsFollowHandOverOrderWithinTheKind() {
    EntityHolder holder = new EntityHolder(HolderPasses.NONE);
    RecordingEntity a = new RecordingEntity("a");
    RecordingEntity b = new RecordingEntity("b");
    holder.add(a);
    holder.add(b);
    holder.cleanup();
    a.removable = true;
    RecordingEntity c = new RecordingEntity("c");
    holder.add(c);
    holder.cleanup();

    assertThat(List.of(a.getId(), b.getId(), c.getId())).containsExactly(5000000, 5000001, 5000002);
    assertThat(holder.entities()).containsExactly(b, c);
  }

  @Test
  @DisplayName("an entity has its id from the moment it is handed over, before any cleanup")
  void theIdIsGivenAtHandOver() {
    EntityHolder holder = new EntityHolder(HolderPasses.NONE);
    RecordingEntity character = new RecordingEntity("c");
    RecordingEntity projectile = new RecordingEntity(KIND_PROJECTILE, "p");

    holder.add(character);
    holder.add(projectile);

    assertThat(character.getId()).isEqualTo(5000000);
    assertThat(projectile.getId()).isEqualTo(4000000);
    assertThat(holder.entities()).as("nothing is admitted before a cleanup").isEmpty();
    assertThat(log).doesNotContain("c registered as 5000000", "p registered as 4000000");
  }

  @Test
  @DisplayName("each kind counts its own ids, and a lower kind precedes a higher one in the list")
  void eachKindCountsItsOwnIdsAndSortsAheadOfHigherKinds() {
    EntityHolder holder = new EntityHolder(recordingPasses);
    RecordingEntity a = new RecordingEntity("a");
    RecordingEntity b = new RecordingEntity("b");
    holder.add(a);
    holder.add(b);
    holder.tick(0);
    log.clear();

    // A projectile handed over on tick 1 still lands ahead of both characters.
    RecordingEntity p = new RecordingEntity(KIND_PROJECTILE, "p");
    RecordingEntity q = new RecordingEntity(KIND_PROJECTILE, "q");
    holder.add(p);
    holder.add(q);
    holder.tick(1);

    assertThat(List.of(p.getId(), q.getId(), a.getId(), b.getId()))
        .containsExactly(4000000, 4000001, 5000000, 5000001);
    assertThat(holder.entities()).containsExactly(p, q, a, b);
    assertThat(log)
        .containsSubsequence(
            "p registered as 4000000",
            "q registered as 4000001",
            "prePass 1 [4000000, 4000001, 5000000, 5000001]",
            "p preHook",
            "q preHook",
            "a preHook",
            "b preHook",
            "p postHook",
            "q postHook",
            "a postHook",
            "b postHook");
  }

  @Test
  @DisplayName("an entity cannot be handed to the holder twice")
  void doubleRegistrationFails() {
    EntityHolder holder = new EntityHolder(HolderPasses.NONE);
    RecordingEntity entity = new RecordingEntity("a");
    holder.add(entity);
    holder.cleanup();

    assertThatThrownBy(() -> holder.add(entity)).isInstanceOf(IllegalStateException.class);
  }
}
