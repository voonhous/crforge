package org.crforge.core.battle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
      };

  /** An entity whose every hook, component and action pass records itself. */
  private class RecordingEntity extends BattleEntity {

    private final String name;
    boolean removable;

    /** Runs inside the named hook, so a test can change the holder in the middle of a tick. */
    Runnable duringPostHook = () -> {};

    RecordingEntity(String name, int... componentSlots) {
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
    protected void onRegistered() {
      log.add(name + " registered as " + getId());
    }

    @Override
    protected void preHook() {
      log.add(name + " preHook");
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
            "a registered as 1",
            "b registered as 2",
            "prePass 7 [1, 2]",
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
    assertThat(late.getId()).isEqualTo(2);
    assertThat(log).contains("late endOfTick").doesNotContain("late preHook", "late postHook");

    first.duringPostHook = () -> {};
    log.clear();
    holder.tick(1);
    assertThat(log).contains("late preHook", "late postHook");
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
  @DisplayName("ids are given in admission order and never reused")
  void idsFollowAdmissionOrder() {
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

    assertThat(List.of(a.getId(), b.getId(), c.getId())).containsExactly(1, 2, 3);
    assertThat(holder.entities()).containsExactly(b, c);
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
