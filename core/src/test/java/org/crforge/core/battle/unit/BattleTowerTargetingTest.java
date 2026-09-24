package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
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
        BattleTowerRunTest.WIZARD_REFERENCE
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
    battle.step();

    Map<String, String> held = new HashMap<>();
    int lockTick = -1;
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
        }
      }
      TowerEntity princess = BattleMusketeerRunTest.towerNamed(battle, PRINCESS_TOWER);
      if (lockTick < 0 && princess.getView().getState() == GridEntityState.ATTACKING) {
        lockTick = tick;
      }
    }

    assertThat(lockTick).as("the princess tower's lock").isEqualTo(eventTick(reference, "lock"));
    assertThat(battle.getHolder().entities())
        .as("the unit was removed along the way")
        .doesNotContain(unit);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        BattleTowerRunTest.KNIGHT_REFERENCE,
        BattleTowerRunTest.MUSKETEER_REFERENCE,
        BattleTowerRunTest.WIZARD_REFERENCE
      })
  void theRemovalOfItsTargetLeavesThePrincessTowerWithTheTargetLostCountdownStarted(
      String resource) {
    JsonNode reference = BattleMusketeerRunTest.load(resource);
    int removalTick = eventTick(reference, "removed");

    Standard1v1Battle match = new Standard1v1Battle(reference.get("tower_level").asInt());
    Battle battle = match.getBattle();
    BattleTowerRunTest.deploy(match, reference);
    battle.step();
    for (int tick = 0; tick <= removalTick; tick++) {
      battle.step();
    }

    TowerEntity princess = BattleMusketeerRunTest.towerNamed(battle, PRINCESS_TOWER);
    assertThat(referenceName(princess)).isNull();
    assertThat(princess.getView().getState()).isEqualTo(GridEntityState.ATTACKING);
    for (JsonNode event : reference.get("tower_events")) {
      if (event.get("tick").asInt() == removalTick && event.has("target_lost_timer")) {
        assertThat(princess.getTargeting().getTargetLostTimerMs())
            .isEqualTo(event.get("target_lost_timer").asInt());
      }
    }
    assertThat(princess.getTargeting().getTargetLostTimerMs()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "a king tower is visited once, takes its default reference, and is then kept out of the"
          + " fight")
  void aKingTowerIsVisitedOnceAndThenSwitchedOff() {
    Standard1v1Battle match = new Standard1v1Battle();
    Battle battle = match.getBattle();

    battle.step();
    TowerEntity bottomKing = BattleMusketeerRunTest.towerNamed(battle, "KingTower_0_0");
    TowerEntity topKing = BattleMusketeerRunTest.towerNamed(battle, "KingTower_1_0");
    TowerEntity princess = BattleMusketeerRunTest.towerNamed(battle, PRINCESS_TOWER);
    assertThat(referenceName(bottomKing)).isEqualTo("KingTower_1_0");
    assertThat(referenceName(topKing)).isEqualTo("KingTower_0_0");
    assertThat(bottomKing.isInactive()).isTrue();
    assertThat(bottomKing.isActive(TowerEntity.TARGETING_SLOT)).isFalse();
    assertThat(princess.isInactive()).isFalse();
    assertThat(princess.isActive(TowerEntity.TARGETING_SLOT)).isTrue();

    // Switched off, the king's attack timer no longer advances: its first visit is its last.
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

  /** The tick of the reference's first tower event of a kind. */
  private static int eventTick(JsonNode reference, String kind) {
    for (JsonNode event : reference.get("tower_events")) {
      if (event.get("event").asText().equals(kind)) {
        return event.get("tick").asInt();
      }
    }
    throw new IllegalStateException("no " + kind + " event in the reference");
  }

  private static String referenceName(TowerEntity tower) {
    TargetView reference = tower.getTargeting().getReference();
    return reference == null ? null : reference.name();
  }
}
