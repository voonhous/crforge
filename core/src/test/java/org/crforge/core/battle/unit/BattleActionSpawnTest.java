package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.ActionRow;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.RowAction;
import org.crforge.core.battle.spawn.SpawnCharacters;
import org.crforge.core.battle.spawn.SpawnRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rules of a character spawn in the battle that the three spawn runs do not reach: an action
 * with no delay scheduled onto another entity from inside a pending pass starts at once, as the
 * battle's own flag says, so a child's action to run on spawned runs inside the pass that spawned
 * it, before the child is live; and the parts of a spawn that are not established are refused.
 */
class BattleActionSpawnTest {

  private final List<String> log = new ArrayList<>();

  private static UnitData knight() {
    return GameData.unit("Knight");
  }

  @Test
  @DisplayName(
      "a zero-delay action scheduled onto another entity inside a pending pass starts at once")
  void aScheduleOntoAnotherEntityInsideAPassStartsAtOnce() {
    Standard1v1Battle match =
        new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
    Battle battle = match.getBattle();
    ActionOwnerEntity first = match.addActionOwner("First", 0, 3500, 21500, 10);
    ActionOwnerEntity second = match.addActionOwner("Second", 0, 14500, 21500, 10);
    BattleAction onSecond =
        new RowAction(ActionRow.builder().name("on_second").phase(1).build()) {
          @Override
          public ActionInstance start(ActionHolder holder) {
            log.add("on_second " + battle.getTick());
            return null;
          }
        };
    // Runs in the pending pass of phase 2 and schedules a phase 1 action onto the other entity.
    BattleAction onFirst =
        new RowAction(ActionRow.builder().name("on_first").phase(2).build()) {
          @Override
          public ActionInstance start(ActionHolder holder) {
            log.add("on_first " + battle.getTick());
            second.actionHolder().schedule(onSecond, 0, false, holder);
            return null;
          }
        };
    match.scheduleAction(3, first, onFirst);
    for (int tick = 0; tick < 6; tick++) {
      battle.step();
    }
    assertThat(log)
        .as("started in the same pass, not queued for the next phase 1")
        .containsExactly("on_first 3", "on_second 3");
    assertThat(second.actionHolder().queued()).isEmpty();
  }

  @Test
  @DisplayName("a child's action to run on spawned runs in the spawning pass, before it is live")
  void theActionToRunOnSpawnedRunsAtOnce() {
    Standard1v1Battle match =
        new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
    Battle battle = match.getBattle();
    ActionOwnerEntity owner = match.addActionOwner("Gift", 0, 14500, 12000, 10);
    BattleAction onSpawned =
        new BattleAction() {
          @Override
          public String name() {
            return "on_spawned";
          }

          @Override
          public ActionInstance start(ActionHolder holder) {
            return start(holder, null);
          }

          @Override
          public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
            CharacterEntity child = (CharacterEntity) holder.getOwner();
            log.add(
                "on_spawned %d on %s live %s cause %s"
                    .formatted(
                        battle.getTick(),
                        child.name(),
                        battle.getHolder().entities().contains(child),
                        instigator == owner.actionHolder() ? "the source" : instigator));
            return null;
          }
        };
    match.scheduleAction(
        5,
        owner,
        new SpawnCharacters(
            ActionRow.named("Gift_Delivery_Knight"),
            SpawnRow.builder()
                .spawnData(knight())
                .parentGoAsSource(true)
                .actionToRunOnSpawned(onSpawned)
                .build()));
    for (int tick = 0; tick < 7; tick++) {
      battle.step();
    }
    assertThat(log).containsExactly("on_spawned 5 on Gift_0 live false cause the source");
  }

  @Test
  @DisplayName("the parts of a spawn that are not established are refused, not guessed")
  void unestablishedPartsAreRefused() {
    SpawnRow[] refused = {
      SpawnRow.builder().spawnData(knight()).isEnemy(true).build(),
      SpawnRow.builder().spawnData(knight()).useMorph(true).build(),
      SpawnRow.builder().spawnData(knight()).validatePlacementAsBuilding(true).build(),
      SpawnRow.builder().spawnData(knight()).count(2).build(),
      SpawnRow.builder().spawnData(knight()).spawnAsClone(true).build(),
      SpawnRow.builder().spawnData(GameData.unit("Witch_crazy_1")).build(),
      SpawnRow.builder().spawnData(knight()).deployTimeMs(0).spawnLevelIndex(3).build()
    };
    for (int i = 0; i < refused.length; i++) {
      Standard1v1Battle match =
          new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
      ActionOwnerEntity owner = match.addActionOwner("Owner", 0, 14500, 12000, 10);
      match.scheduleAction(0, owner, new SpawnCharacters(ActionRow.named("spawn"), refused[i]));
      if (i < refused.length - 1) {
        assertThatThrownBy(match.getBattle()::step)
            .as("row %d", i)
            .isInstanceOf(UnsupportedOperationException.class);
      } else {
        match.getBattle().step();
        CharacterEntity child = (CharacterEntity) match.getBattle().getHolder().entities().get(7);
        assertThat(child.level()).as("a level index of its own, re-based on the row").isEqualTo(4);
      }
    }
  }
}
