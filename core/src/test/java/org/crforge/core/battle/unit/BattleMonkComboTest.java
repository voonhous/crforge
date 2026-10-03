package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.grid.PathfindingGlobals;
import org.crforge.core.pathfinding.move.MovementState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Monk's three-hit combo. Its row's AttackSequence is the order 0, 1, 2, loaded in the static
 * loop: every hit step moves the index on by one, the third entry hitting with VariableDamage3 and
 * MeleePushback3. A unit whose mode is not 0 walks with its range 500 short, so the Monk stops
 * closer than its Range says. The third hit's pushback leaves a tower where it stands and pushes a
 * unit away from the Monk, before its damage, with every gate lifted by IsMeleePushbackAll3: even a
 * Giant, whose row ignores pushback.
 */
class BattleMonkComboTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  @Test
  @DisplayName("the Monk loads its order 0, 1, 2 in the static loop, the third entry with a push")
  void theComboIsLoaded() {
    AttackSequence sequence = GameData.unit("Monk").attackSequence();

    assertThat(sequence.mode()).isEqualTo(AttackSequence.MODE_STATIC_LOOP);
    assertThat(sequence.order()).containsExactly(0, 1, 2);
    assertThat(sequence.entries())
        .extracting(AttackSequence.Entry::damage)
        .containsExactly(55, 55, 165);
    assertThat(sequence.entries().get(2).meleePushback()).isEqualTo(1800);
    assertThat(sequence.entries())
        .extracting(AttackSequence.Entry::meleePushbackAll)
        .containsExactly(false, false, true);
  }

  @Test
  @DisplayName(
      "against a princess tower the Monk stops 500 inside its reach and hits two, two, three times"
          + " as hard, around and around")
  void theComboCyclesOnATower() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    Battle battle = match.getBattle();
    CharacterEntity monk = match.deploy(0, GameData.unit("Monk"), LEVEL, 0, 3500, 20000, "Monk");
    GridEntity view = monk.getView();
    WorldEntity tower = princessTower(match, 1, 3500);
    int before = tower.getHitPoints().getHitPoints();
    List<Integer> hits = new ArrayList<>();
    int stopDistance = -1;
    for (int tick = 0; tick < 600 && hits.size() < 6; tick++) {
      battle.step();
      if (stopDistance < 0 && view.getState() == GridEntityState.ATTACKING) {
        long dx = view.getX() - tower.getView().getX();
        long dy = view.getY() - tower.getView().getY();
        stopDistance = (int) Math.sqrt((double) (dx * dx + dy * dy));
      }
      int now = tower.getHitPoints().getHitPoints();
      if (now < before) {
        hits.add(before - now);
        before = now;
      }
    }

    // Range 1200, the Monk's radius 500 and the tower's 1000, less the 500 a walking unit with a
    // mode loses; a step of the walk is 60.
    int reach =
        1200 + 500 + 1000 - PathfindingGlobals.LOGIC_CHARACTER_CONTINUOUS_DAMAGE_ATTACK_CLOSER;
    assertThat(stopDistance).isLessThanOrEqualTo(reach).isGreaterThan(reach - 60);
    assertThat(hits).hasSize(6);
    assertThat(hits.get(0)).isEqualTo(hits.get(1));
    assertThat(hits.get(2)).isGreaterThan(hits.get(0));
    assertThat(hits.subList(3, 6)).isEqualTo(hits.subList(0, 3));
  }

  @Test
  @DisplayName(
      "the third hit pushes a Giant, whose row ignores pushback, 1800 away from where the Monk"
          + " stands")
  void theThirdHitPushesAGiant() {
    assertThat(GameData.unit("Giant").ignorePushback()).isTrue();
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    CharacterEntity monk = match.deploy(0, GameData.unit("Monk"), LEVEL, 0, 3500, 12000, "Monk");
    CharacterEntity giant = match.deploy(0, GameData.unit("Giant"), LEVEL, 1, 3500, 15000, "Giant");
    List<int[]> pushes = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void pushbackRequested(
                  int tick,
                  WorldEntity unit,
                  boolean started,
                  int fromX,
                  int fromY,
                  MovementState pushback) {
                if (unit == giant) {
                  pushes.add(
                      new int[] {
                        started ? 1 : 0,
                        fromX,
                        fromY,
                        giant.getView().getX(),
                        giant.getView().getY(),
                        pushback.getTargetX(),
                        pushback.getTargetY(),
                        monk.getView().getX(),
                        monk.getView().getY()
                      });
                }
              }
            });
    int before = giant.getHitPoints().getHitPoints();
    int hits = 0;
    for (int tick = 0; tick < 600 && hits < 3; tick++) {
      match.getBattle().step();
      int now = giant.getHitPoints().getHitPoints();
      if (now < before) {
        hits++;
        assertThat(pushes).as("hit %d", hits).hasSize(hits == 3 ? 1 : 0);
        before = now;
      }
    }
    assertThat(hits).isEqualTo(3);
    int[] push = pushes.get(0);
    assertThat(push[0]).as("started").isEqualTo(1);
    assertThat(new int[] {push[1], push[2]})
        .as("pushed from where the Monk stands")
        .containsExactly(push[7], push[8]);
    // The setter aims 1800 along the line from the Monk through the Giant, C division.
    int dx = push[3] - push[1];
    int dy = push[4] - push[2];
    int length = (int) Math.sqrt((double) dx * dx + (double) dy * dy);
    assertThat(new int[] {push[5], push[6]})
        .containsExactly(push[3] + 1800 * dx / length, push[4] + 1800 * dy / length);
    assertThat(giant.getUnit().movement().getPushbackInFlight()).isEqualTo(1);
  }

  private static WorldEntity princessTower(Standard1v1Battle match, int side, int x) {
    for (BattleEntity entity : match.getBattle().getHolder().entities()) {
      if (entity instanceof TowerEntity t
          && t.side() == side
          && !t.getData().king()
          && t.getView().getX() == x) {
        return t;
      }
    }
    throw new IllegalStateException("no princess tower at " + x);
  }
}
