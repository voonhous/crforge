package org.crforge.core.battle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.crforge.core.battle.BattleEntity.KIND_CHARACTER;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pins what one battle step does, and in which order, around the entity tick. */
class BattleTest {

  private final List<String> log = new ArrayList<>();

  /** An entity that records the tick its post-hook was handed through the holder. */
  private final class Witness extends BattleEntity {
    Witness() {
      super(KIND_CHARACTER);
    }

    @Override
    protected void postHook() {
      log.add("entity tick");
    }

    @Override
    public boolean isRemovable() {
      return false;
    }
  }

  private BattleCommand command(int tick, String name) {
    return new BattleCommand() {
      @Override
      public int tick() {
        return tick;
      }

      @Override
      public void execute(Battle battle) {
        log.add(name + " at tick " + battle.getTick());
      }
    };
  }

  private Battle battleWithWitness(BattleMode mode) {
    EntityHolder holder = new EntityHolder(HolderPasses.NONE);
    holder.add(new Witness());
    return new Battle(holder, mode);
  }

  @Test
  @DisplayName("a step is 50 ms and one tick, with no float anywhere")
  void stepAdvancesClockAndTick() {
    Battle battle = battleWithWitness(BattleMode.ENDLESS);

    for (int i = 0; i < 20; i++) {
      battle.step();
    }

    assertThat(battle.getTick()).isEqualTo(20);
    assertThat(battle.getClockMs()).isEqualTo(1000);
  }

  @Test
  @DisplayName("a due command runs at the head of its step, before the entity tick")
  void commandsRunBeforeTheEntityTick() {
    Battle battle = battleWithWitness(BattleMode.ENDLESS);
    battle.queue(command(0, "deploy"));

    battle.step();

    assertThat(log).containsExactly("deploy at tick 0", "entity tick");
  }

  @Test
  @DisplayName("a command due on the next tick waits for the next step: one pass per step")
  void aCommandRunsInTheStepOfItsTick() {
    Battle battle = battleWithWitness(BattleMode.ENDLESS);
    battle.queue(command(1, "later"));

    battle.step();
    assertThat(log).containsExactly("entity tick");

    log.clear();
    battle.step();
    assertThat(log).containsExactly("later at tick 1", "entity tick");
  }

  @Test
  @DisplayName("commands run in the order they were queued, and a command may queue another")
  void commandOrder() {
    Battle battle = battleWithWitness(BattleMode.ENDLESS);
    battle.queue(
        new BattleCommand() {
          @Override
          public int tick() {
            return 0;
          }

          @Override
          public void execute(Battle target) {
            log.add("first");
            target.queue(command(0, "queued by first"));
          }
        });
    battle.queue(command(0, "second"));
    battle.queue(command(5, "later"));

    battle.step();

    assertThat(log)
        .containsExactly("first", "second at tick 0", "queued by first at tick 0", "entity tick");
  }

  @Test
  @DisplayName("what a command creates takes part in the entity tick of its own step")
  void entityCreatedByCommandTakesPartInItsStep() {
    EntityHolder holder = new EntityHolder(HolderPasses.NONE);
    Battle battle = new Battle(holder, BattleMode.ENDLESS);
    battle.queue(
        new BattleCommand() {
          @Override
          public int tick() {
            return 0;
          }

          @Override
          public void execute(Battle target) {
            target.getHolder().add(new Witness());
          }
        });

    battle.step();
    assertThat(log).containsExactly("entity tick");
  }

  @Test
  @DisplayName("a finished match freezes the clock, the tick counter and the entities")
  void finishedMatchSkipsTheStep() {
    boolean[] over = {false};
    BattleMode mode =
        new BattleMode() {
          @Override
          public boolean isOver() {
            return over[0];
          }

          @Override
          public boolean update(Battle battle) {
            return true;
          }
        };
    Battle battle = battleWithWitness(mode);
    battle.step();
    over[0] = true;
    log.clear();

    battle.step();

    assertThat(battle.getTick()).isEqualTo(1);
    assertThat(battle.getClockMs()).isEqualTo(50);
    assertThat(log).isEmpty();
  }

  @Test
  @DisplayName("a step whose mode update declines the entity tick still advances the counter")
  void declinedEntityTickStillCounts() {
    BattleMode mode =
        new BattleMode() {
          @Override
          public boolean isOver() {
            return false;
          }

          @Override
          public boolean update(Battle battle) {
            return false;
          }
        };
    Battle battle = battleWithWitness(mode);

    battle.step();

    assertThat(log).isEmpty();
    assertThat(battle.getTick()).isEqualTo(1);
    // The holder was still cleaned up, so the pending entity was admitted.
    assertThat(battle.getHolder().entities()).hasSize(1);
  }

  @Test
  @DisplayName("a command cannot be stamped with a tick that is already over")
  void staleCommandIsRejected() {
    Battle battle = battleWithWitness(BattleMode.ENDLESS);
    battle.step();
    battle.step();

    assertThatThrownBy(() -> battle.queue(command(1, "stale")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
