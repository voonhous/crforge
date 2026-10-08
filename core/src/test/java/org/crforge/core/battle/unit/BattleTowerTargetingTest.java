package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.BattleTowers;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.target.TargetView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds the towers' own targeting: the king towers, which are visited on their first two ticks and
 * then kept out of the fight until a princess tower falls and the activation has run, a tower's
 * elapsed time, and a passive tower, which never selects.
 *
 * <p>A tower with nothing in range holds the opposing side's tower its default selection gives, out
 * of range. Battle tick {@code n} is the {@code n}-th step: a placement runs at the head of its
 * step.
 */
class BattleTowerTargetingTest {

  /** The left princess tower of the top side. */
  private static final String PRINCESS_TOWER = "PrincessTower_1_1";

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
    TowerEntity bottomKing = BattleTowers.towerNamed(battle, "KingTower_0_0");
    TowerEntity topKing = BattleTowers.towerNamed(battle, "KingTower_1_0");
    TowerEntity princess = BattleTowers.towerNamed(battle, PRINCESS_TOWER);
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
  void aDestroyedPrincessTowerWakesTheKingAfterTheActivation(@TempDir Path folder)
      throws IOException {
    // The activating run's ticks, a part tick counted whole.
    int run = (Shipped.number(ACTIVATING, "ActionDuration") + 49) / 50;

    Standard1v1Battle match = new Standard1v1Battle(kingScene(folder), 1);
    Battle battle = match.getBattle();
    match.deploy(0, match.getWorld().getRecords().unit("Knight"), 1, 0, 3500, 10000);
    TowerEntity king = BattleTowers.towerNamed(battle, "KingTower_1_0");
    // The tick each step of the top king's activation is first told on.
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
      String where = "tick " + tick;
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

  /**
   * The configured tables with the king scene's columns written: the towers at the first level and
   * a Knight that walks up the left lane, outlasts the princess tower's arrows and brings it down,
   * standing within the king's range when it wakes.
   */
  private static GameTables kingScene(Path folder) throws IOException {
    GameData.altered(
        folder,
        "characters",
        rows ->
            GameData.columns(rows, "Knight")
                .put("Hitpoints", 3000)
                .put("Damage", 200)
                .put("HitSpeed", 1200)
                .put("LoadTime", 700)
                .put("Speed", 60)
                .put("Mass", 6)
                .put("CollisionRadius", 500)
                .put("Range", 1200)
                .put("SightRange", 5500)
                .put("DeployTime", 1000));
    GameData.writeTowers(folder);
    return GameTables.load(folder);
  }

  @Test
  @DisplayName(
      "a tower's elapsed time steps by 50 on each of its state visits, from its first tick")
  void aTowersElapsedTimeStepsFromItsFirstTick() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    Battle battle = match.getBattle();

    for (int step = 1; step <= 10; step++) {
      battle.step();
      TowerEntity princess = BattleTowers.towerNamed(battle, PRINCESS_TOWER);
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
