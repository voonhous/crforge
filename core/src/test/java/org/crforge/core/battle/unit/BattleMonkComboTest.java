package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.GameRow;
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

  private static final GameRow MONK = Shipped.unitRow("Monk");

  /** How far the third hit pushes. */
  private static final int THIRD_PUSH = Shipped.number(MONK, "MeleePushback3");

  @Test
  @DisplayName(
      "the Monk loads its row's order in the static loop, the entries hitting with Damage and the"
          + " variable damages, the third with a push")
  void theComboIsLoaded() {
    AttackSequence sequence = GameData.unit("Monk").attackSequence();

    assertThat(sequence.mode()).isEqualTo(AttackSequence.MODE_STATIC_LOOP);
    assertThat(sequence.order()).containsExactlyElementsOf(Shipped.numbers(MONK, "AttackSequence"));
    assertThat(sequence.entries())
        .extracting(AttackSequence.Entry::damage)
        .containsExactly(
            Shipped.number(MONK, "Damage"),
            Shipped.number(MONK, "VariableDamage2"),
            Shipped.number(MONK, "VariableDamage3"));
    assertThat(sequence.entries().get(2).meleePushback()).isEqualTo(THIRD_PUSH);
    assertThat(sequence.entries())
        .extracting(AttackSequence.Entry::meleePushbackAll)
        .containsExactly(
            Shipped.flag(MONK, "IsMeleePushbackAll1"),
            Shipped.flag(MONK, "IsMeleePushbackAll2"),
            Shipped.flag(MONK, "IsMeleePushbackAll3"));
  }

  @Test
  @DisplayName(
      "against a princess tower the Monk stops 500 inside its reach and hits with its three"
          + " entries' damages, around and around")
  void theComboCyclesOnATower() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    Battle battle = match.getBattle();
    CharacterEntity monk = match.deploy(0, GameData.unit("Monk"), LEVEL, 0, 3500, 20000, "Monk");
    GridEntity view = monk.getView();
    WorldEntity tower = leftPrincessTower(match, 1);
    int before = tower.getHitPoints().getHitPoints();
    // Its range, the Monk's radius and the tower's, less the 500 a walking unit with a mode
    // loses.
    long reach =
        Shipped.number(MONK, "Range")
            + Shipped.number(MONK, "CollisionRadius")
            + Shipped.number(Shipped.unitRow("PrincessTower"), "CollisionRadius")
            - PathfindingGlobals.LOGIC_CHARACTER_CONTINUOUS_DAMAGE_ATTACK_CLOSER;
    List<Integer> hits = new ArrayList<>();
    // The first step the Monk ends within that reach, where it stood, and the step it attacks.
    int within = -1;
    int[] withinAt = null;
    int attacks = -1;
    int[] attacksAt = null;
    for (int tick = 0; tick < 600 && hits.size() < 6; tick++) {
      battle.step();
      long dx = view.getX() - tower.getView().getX();
      long dy = view.getY() - tower.getView().getY();
      if (within < 0 && dx * dx + dy * dy <= reach * reach) {
        within = tick;
        withinAt = new int[] {view.getX(), view.getY()};
      }
      if (attacks < 0 && view.getState() == GridEntityState.ATTACKING) {
        attacks = tick;
        attacksAt = new int[] {view.getX(), view.getY()};
      }
      int now = tower.getHitPoints().getHitPoints();
      if (now < before) {
        hits.add(before - now);
        before = now;
      }
    }

    // It walks until it ends a step within the shortened reach, and attacks from there on the next
    // step.
    assertThat(within).isNotNegative();
    assertThat(attacks).isEqualTo(within + 1);
    assertThat(attacksAt).containsExactly(withinAt);
    // Its three entries' damages at its level, around and around.
    int first = Shipped.scaled(Shipped.number(MONK, "Damage"), monk);
    int second = Shipped.scaled(Shipped.number(MONK, "VariableDamage2"), monk);
    int third = Shipped.scaled(Shipped.number(MONK, "VariableDamage3"), monk);
    assertThat(hits).containsExactly(first, second, third, first, second, third);
  }

  @Test
  @DisplayName(
      "the third hit pushes a Giant, whose row ignores pushback, its push away from where the Monk"
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
    // The setter aims the push along the line from the Monk through the Giant, C division.
    int dx = push[3] - push[1];
    int dy = push[4] - push[2];
    int length = (int) Math.sqrt((double) dx * dx + (double) dy * dy);
    assertThat(new int[] {push[5], push[6]})
        .containsExactly(push[3] + THIRD_PUSH * dx / length, push[4] + THIRD_PUSH * dy / length);
    assertThat(giant.getUnit().movement().getPushbackInFlight()).isEqualTo(1);
  }

  /** The side's princess tower on the left lane, the one the Monk placed there walks to. */
  private static WorldEntity leftPrincessTower(Standard1v1Battle match, int side) {
    WorldEntity left = null;
    for (BattleEntity entity : match.getBattle().getHolder().entities()) {
      if (entity instanceof TowerEntity t
          && t.side() == side
          && !t.getData().king()
          && (left == null || t.getView().getX() < left.getView().getX())) {
        left = t;
      }
    }
    if (left == null) {
      throw new IllegalStateException("no princess tower of side " + side);
    }
    return left;
  }
}
