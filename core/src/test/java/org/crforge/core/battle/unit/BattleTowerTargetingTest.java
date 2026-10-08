package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
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

    Standard1v1Battle match =
        new Standard1v1Battle(GameData.tables(), reference.get("tower_level").asInt());
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
  void theRemovalOfTheUnitLeavesEveryTowerThatHeldItWithTheTargetLostCountdownTheReferenceGives(
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

    Standard1v1Battle match =
        new Standard1v1Battle(GameData.tables(), reference.get("tower_level").asInt());
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
        // A tower whose own arrow in flight was the kill held the unit through it, so the removal
        // skips the retarget load and starts no countdown; one that did not hold it that way, as
        // in valkyrie_two_victims, starts the countdown at 1.
        assertThat(tower.getTargeting().getTargetLostTimerMs())
            .as(tower.name())
            .isEqualTo(event.get("target_lost_timer").asInt());
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
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    Battle battle = match.getBattle();

    // The wait that keeps the king inactive starts in the first tick's first pending pass, after
    // that tick's tags were folded, so the gate still switches the component on at its end.
    battle.step();
    TowerEntity bottomKing = BattleMusketeerRunTest.towerNamed(battle, "KingTower_0_0");
    TowerEntity topKing = BattleMusketeerRunTest.towerNamed(battle, "KingTower_1_0");
    TowerEntity princess = BattleMusketeerRunTest.towerNamed(battle, PRINCESS_TOWER);
    // A king's default target is the other side's princess tower of its lane; the king it would
    // seed from is no candidate.
    assertThat(referenceName(bottomKing)).isEqualTo("PrincessTower_1_2");
    assertThat(referenceName(topKing)).isEqualTo("PrincessTower_0_2");
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
    assertThat(referenceName(bottomKing)).isEqualTo("PrincessTower_1_2");
  }

  /** The king's activating run, whose duration the king stays off for after the condition. */
  private static final String ACTIVATING = "WaitForKingTowerActivation_OnActivateAction";

  @Test
  @DisplayName(
      "a destroyed princess tower wakes its king, which stays off for the activation's duration and"
          + " is first visited four ticks after it from the tick that saw the condition")
  void aDestroyedPrincessTowerWakesTheKingAfterTheActivation() {
    JsonNode reference = BattleMusketeerRunTest.load(BattleTowerRunTest.LEVEL_ONE_REFERENCE);
    // The activating run's ticks, a part tick counted whole: 66 of its 3300 ms in the configured
    // tables.
    int run = (Shipped.number(ACTIVATING, "ActionDuration") + 49) / 50;

    Standard1v1Battle match =
        new Standard1v1Battle(GameData.tables(), reference.get("tower_level").asInt());
    Battle battle = match.getBattle();
    BattleTowerRunTest.deploy(match, reference);
    TowerEntity king = BattleMusketeerRunTest.towerNamed(battle, "KingTower_1_0");
    // The reference tick each step of the top king's activation is first told on.
    Map<ActivationEvent.Kind, Integer> steps = new EnumMap<>(ActivationEvent.Kind.class);
    int[] at = {0};
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void activation(int tick, TowerEntity tower, ActivationEvent event) {
                if (tower == king) {
                  steps.putIfAbsent(event.kind(), at[0]);
                }
              }
            });
    // What the king was after each tick, until four ticks past the activating run.
    List<Boolean> inactive = new ArrayList<>();
    List<Boolean> targeting = new ArrayList<>();
    List<String> references = new ArrayList<>();
    List<Integer> states = new ArrayList<>();
    for (int tick = 0; tick < LAST_TICK; tick++) {
      at[0] = tick;
      battle.step();
      inactive.add(king.isInactive());
      targeting.add(king.isActive(TowerEntity.TARGETING_SLOT));
      references.add(referenceName(king));
      states.add(king.getView().getState());
      Integer seen = steps.get(ActivationEvent.Kind.CONDITION);
      if (seen != null && tick == seen + run + 4) {
        break;
      }
    }
    assertThat(steps)
        .as("the knight takes a princess tower")
        .containsKey(ActivationEvent.Kind.CONDITION);
    int condition = steps.get(ActivationEvent.Kind.CONDITION);
    assertThat(steps.get(ActivationEvent.Kind.ACTIVATING_FINISHED)).isEqualTo(condition + run + 1);
    assertThat(steps.get(ActivationEvent.Kind.ACTIVATING_REMOVED)).isEqualTo(condition + run + 2);
    assertThat(inactive).hasSize(condition + run + 5);

    for (int tick = 0; tick < inactive.size(); tick++) {
      String where = "reference tick " + tick;
      if (tick == 0) {
        // The wait starts in the first tick's first pending pass, after that tick's fold.
        assertThat(inactive.get(tick)).as(where).isFalse();
        assertThat(targeting.get(tick)).as(where).isTrue();
      } else if (tick < condition + run + 3) {
        // Inactive until the condition, then activating; the finished run's tag still counts at
        // the fold before the run pass that removes it.
        assertThat(inactive.get(tick)).as(where).isTrue();
        assertThat(targeting.get(tick)).as(where).isFalse();
        assertThat(references.get(tick)).as(where).isEqualTo("PrincessTower_0_2");
      } else if (tick == condition + run + 3) {
        assertThat(inactive.get(tick)).as(where).isFalse();
        assertThat(targeting.get(tick)).as(where).isTrue();
        assertThat(references.get(tick)).as(where).isEqualTo("PrincessTower_0_2");
      } else {
        assertThat(references.get(tick))
            .as(where + ": the first visit locks on")
            .isEqualTo("Knight");
        assertThat(states.get(tick)).as(where).isEqualTo(GridEntityState.ATTACKING);
      }
    }
  }

  /** The tick the king scene gives up waiting for the condition. */
  private static final int LAST_TICK = 1200;

  @Test
  @DisplayName(
      "a tower's elapsed time steps by 50 on each of its state visits, from its first tick")
  void aTowersElapsedTimeStepsFromItsFirstTick() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
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
    Standard1v1Battle match =
        new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
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
