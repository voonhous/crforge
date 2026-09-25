package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.target.TargetView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Holds the towers' own targeting to the reference runs in which they fight: the reference each
 * tower holds on every tick, the tick the princess tower locks on, what the removal of its target
 * leaves it holding, and the king towers, which are visited once and then kept out of the fight.
 *
 * <p>The reference lists a tower's reference whenever it changes, from the tick the towers are
 * first visited. A tower with nothing in range holds the opposing side's tower its default
 * selection gives, out of range. See {@link BattleTowerRunTest} for the tick numbering.
 */
class BattleTowerTargetingTest {

  /** The princess tower that fights in both references. */
  private static final String PRINCESS_TOWER = "PrincessTower_1_1";

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        BattleTowerRunTest.KNIGHT_REFERENCE,
        BattleTowerRunTest.MUSKETEER_REFERENCE,
        BattleTowerRunTest.WIZARD_REFERENCE,
        BattleTowerRunTest.LEVEL_ONE_REFERENCE,
        BattleTowerRunTest.VALKYRIE_REFERENCE,
        BattleTowerRunTest.VALKYRIE_TWO_VICTIMS_REFERENCE,
        BattleTowerRunTest.VALKYRIE_OWN_TOWER_REFERENCE
      })
  void everyTowerHoldsTheReferenceTheReferenceGivesOnEveryTick(String resource) {
    JsonNode reference = BattleMusketeerRunTest.load(resource);
    int lastTick = 0;
    for (JsonNode event : reference.get("tower_events")) {
      lastTick = Math.max(lastTick, event.get("tick").asInt());
    }

    Standard1v1Battle match = new Standard1v1Battle(reference.get("tower_level").asInt());
    Battle battle = match.getBattle();
    CharacterEntity unit = BattleTowerRunTest.deploy(match, reference);

    Map<String, String> held = new HashMap<>();
    Map<String, Integer> states = new HashMap<>();
    List<String> locks = new ArrayList<>();
    for (int tick = 0; tick <= lastTick; tick++) {
      battle.step();
      for (JsonNode event : reference.get("tower_events")) {
        if (event.get("tick").asInt() == tick && event.get("event").asText().equals("reference")) {
          JsonNode target = event.get("target");
          held.put(event.get("tower").asText(), target.isNull() ? null : target.asText());
        }
      }
      for (BattleEntity entity : battle.getHolder().entities()) {
        if (entity instanceof TowerEntity tower) {
          assertThat(referenceName(tower))
              .as("reference tick %d: %s's reference", tick, tower.name())
              .isEqualTo(held.get(tower.name()));
          // A lock is the tower entering the attacking state.
          int state = tower.getView().getState();
          Integer before = states.put(tower.name(), state);
          if (state == GridEntityState.ATTACKING
              && (before == null || before != GridEntityState.ATTACKING)) {
            locks.add(tick + " " + tower.name() + " " + referenceName(tower));
          }
        }
      }
    }

    List<String> expectedLocks = new ArrayList<>();
    for (JsonNode event : reference.get("tower_events")) {
      if (event.get("event").asText().equals("lock")) {
        expectedLocks.add(
            event.get("tick").asInt()
                + " "
                + event.get("tower").asText()
                + " "
                + event.get("target").asText());
      }
    }
    assertThat(locks).as("every tower's lock").containsExactlyElementsOf(expectedLocks);
    assertThat(battle.getHolder().entities())
        .as("the unit was removed along the way")
        .doesNotContain(unit);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        BattleTowerRunTest.KNIGHT_REFERENCE,
        BattleTowerRunTest.MUSKETEER_REFERENCE,
        BattleTowerRunTest.WIZARD_REFERENCE,
        BattleTowerRunTest.LEVEL_ONE_REFERENCE,
        BattleTowerRunTest.VALKYRIE_REFERENCE,
        BattleTowerRunTest.VALKYRIE_TWO_VICTIMS_REFERENCE,
        BattleTowerRunTest.VALKYRIE_OWN_TOWER_REFERENCE
      })
  void theRemovalOfTheUnitLeavesEveryTowerThatHeldItWithTheTargetLostCountdownStarted(
      String resource) {
    JsonNode reference = BattleMusketeerRunTest.load(resource);
    String unitName = reference.get("card").asText();
    int removalTick = -1;
    for (JsonNode event : reference.get("tower_events")) {
      if (event.get("event").asText().equals("removed")
          && event.get("entity").asText().equals(unitName)) {
        removalTick = event.get("tick").asInt();
      }
    }
    assertThat(removalTick).as("the reference removes the unit").isNotNegative();

    Standard1v1Battle match = new Standard1v1Battle(reference.get("tower_level").asInt());
    Battle battle = match.getBattle();
    BattleTowerRunTest.deploy(match, reference);
    for (int tick = 0; tick <= removalTick; tick++) {
      battle.step();
    }

    int towersThatHeldIt = 0;
    for (JsonNode event : reference.get("tower_events")) {
      if (event.get("tick").asInt() == removalTick
          && event.get("event").asText().equals("reference")
          && event.path("removed").asText().equals(unitName)) {
        TowerEntity tower = BattleMusketeerRunTest.towerNamed(battle, event.get("tower").asText());
        assertThat(referenceName(tower)).as(tower.name()).isNull();
        assertThat(tower.getView().getState())
            .as(tower.name())
            .isEqualTo(GridEntityState.ATTACKING);
        assertThat(tower.getTargeting().getTargetLostTimerMs())
            .as(tower.name())
            .isEqualTo(event.get("target_lost_timer").asInt())
            .isEqualTo(1);
        towersThatHeldIt++;
      }
    }
    assertThat(towersThatHeldIt).as("at least one tower was shooting the unit").isPositive();
  }

  @Test
  @DisplayName(
      "a king tower is visited on its first two ticks, takes its default reference, and is then"
          + " kept out of the fight")
  void aKingTowerIsVisitedOnItsFirstTwoTicksAndThenSwitchedOff() {
    Standard1v1Battle match = new Standard1v1Battle();
    Battle battle = match.getBattle();

    // The wait that keeps the king inactive starts in the first tick's first pending pass, after
    // that tick's tags were folded, so the gate still switches the component on at its end.
    battle.step();
    TowerEntity bottomKing = BattleMusketeerRunTest.towerNamed(battle, "KingTower_0_0");
    TowerEntity topKing = BattleMusketeerRunTest.towerNamed(battle, "KingTower_1_0");
    TowerEntity princess = BattleMusketeerRunTest.towerNamed(battle, PRINCESS_TOWER);
    assertThat(referenceName(bottomKing)).isEqualTo("KingTower_1_0");
    assertThat(referenceName(topKing)).isEqualTo("KingTower_0_0");
    assertThat(bottomKing.isInactive()).isFalse();
    assertThat(bottomKing.isActive(TowerEntity.TARGETING_SLOT)).isTrue();

    // The second tick folds the wait's tag in: the king is visited once more, then switched off.
    battle.step();
    assertThat(bottomKing.isInactive()).isTrue();
    assertThat(bottomKing.isActive(TowerEntity.TARGETING_SLOT)).isFalse();
    assertThat(princess.isInactive()).isFalse();
    assertThat(princess.isActive(TowerEntity.TARGETING_SLOT)).isTrue();

    // Switched off, the king's attack timer no longer advances.
    int kingTimer = bottomKing.getTargeting().getAttackTimerMs();
    for (int tick = 0; tick < 40; tick++) {
      battle.step();
    }
    assertThat(bottomKing.getTargeting().getAttackTimerMs()).isEqualTo(kingTimer);
    assertThat(bottomKing.getView().getState()).isEqualTo(GridEntityState.STANDING);
    assertThat(referenceName(bottomKing)).isEqualTo("KingTower_1_0");
  }

  @Test
  @DisplayName(
      "a destroyed princess tower wakes its king, which stays off for the activation's 3300 ms and"
          + " is first visited seventy ticks after the tick that saw the condition")
  void aDestroyedPrincessTowerWakesTheKingSeventyTicksLater() {
    JsonNode reference = BattleMusketeerRunTest.load(BattleTowerRunTest.LEVEL_ONE_REFERENCE);
    Map<String, Integer> timeline = new HashMap<>();
    for (JsonNode event : reference.get("tower_events")) {
      timeline.putIfAbsent(event.get("event").asText(), event.get("tick").asInt());
    }
    int condition = timeline.get("activation_condition");
    assertThat(condition).isEqualTo(389);
    assertThat(timeline.get("activating_finished")).isEqualTo(condition + 67);
    assertThat(timeline.get("activating_removed")).isEqualTo(condition + 68);

    Standard1v1Battle match = new Standard1v1Battle(reference.get("tower_level").asInt());
    Battle battle = match.getBattle();
    BattleTowerRunTest.deploy(match, reference);
    TowerEntity king = BattleMusketeerRunTest.towerNamed(battle, "KingTower_1_0");
    for (int tick = 0; tick <= condition + 70; tick++) {
      battle.step();
      String where = "reference tick " + tick;
      if (tick == 0) {
        // The wait starts in the first tick's first pending pass, after that tick's fold.
        assertThat(king.isInactive()).as(where).isFalse();
        assertThat(king.isActive(TowerEntity.TARGETING_SLOT)).as(where).isTrue();
      } else if (tick < condition + 69) {
        // Inactive until the condition, then activating; the finished run's tag still counts at
        // the fold before the run pass that removes it.
        assertThat(king.isInactive()).as(where).isTrue();
        assertThat(king.isActive(TowerEntity.TARGETING_SLOT)).as(where).isFalse();
        assertThat(referenceName(king)).as(where).isEqualTo("KingTower_0_0");
      } else if (tick == condition + 69) {
        assertThat(king.isInactive()).as(where).isFalse();
        assertThat(king.isActive(TowerEntity.TARGETING_SLOT)).as(where).isTrue();
        assertThat(referenceName(king)).as(where).isEqualTo("KingTower_0_0");
      } else {
        assertThat(referenceName(king))
            .as(where + ": the first visit locks on")
            .isEqualTo("Knight");
        assertThat(king.getView().getState()).as(where).isEqualTo(GridEntityState.ATTACKING);
      }
    }
  }

  @Test
  @DisplayName(
      "a tower's elapsed time steps by 50 on each of its state visits, from its first tick")
  void aTowersElapsedTimeStepsFromItsFirstTick() {
    Standard1v1Battle match = new Standard1v1Battle();
    Battle battle = match.getBattle();

    for (int step = 1; step <= 10; step++) {
      battle.step();
      TowerEntity princess = BattleMusketeerRunTest.towerNamed(battle, PRINCESS_TOWER);
      assertThat(princess.getView().getDelay()).as("after step %d", step).isEqualTo(50 * step);
    }
  }

  @Test
  @DisplayName("a passive tower never selects a target")
  void aPassiveTowerNeverSelects() {
    Standard1v1Battle match = new Standard1v1Battle(Standard1v1Battle.DEFAULT_LEVEL, false);
    Battle battle = match.getBattle();

    for (int tick = 0; tick < 5; tick++) {
      battle.step();
    }
    for (BattleEntity entity : battle.getHolder().entities()) {
      TowerEntity tower = (TowerEntity) entity;
      assertThat(referenceName(tower)).as(tower.name()).isNull();
      assertThat(tower.isActive(TowerEntity.TARGETING_SLOT)).as(tower.name()).isFalse();
    }
  }

  private static String referenceName(TowerEntity tower) {
    TargetView reference = tower.getTargeting().getReference();
    return reference == null ? null : reference.name();
  }
}
