package org.crforge.core.pathfinding.target;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.pathfinding.GridEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The tower a unit walks at when nothing nearer is worth attacking. */
class DefaultTargetSelectionTest {

  private static final int ARENA_HEIGHT_CELLS = 64;
  private static final int KNIGHT_ATTACK_RANGE = 1700;

  private TargetView kingTower;
  private TargetView leftPrincessTower;
  private TargetView rightPrincessTower;
  private List<TargetView> candidates;

  private static TargetView tower(String name, int id, int x, int y, int lane, boolean king) {
    GridEntity e = new GridEntity();
    e.setName(name);
    e.setId(id);
    e.setSide(1);
    e.setX(x);
    e.setY(y);
    e.setLane(lane);
    e.setCollisionRadius(king ? 1400 : 1000);
    e.setBuilding(true);
    e.setKing(king);
    e.setKingCandidate(1);
    e.setTargetable(1);
    return new TargetView(
        e,
        king
            ? TargetingConfig.tower("KingTower", 7000, 7000, 1400, 1000, 500, false)
            : TargetingConfig.tower("PrincessTower", 7500, 7500, 1000, 800, 0, true));
  }

  @BeforeEach
  void setUp() {
    // The opposing side's registered towers, in placement order, with the lanes the arena assigns
    // to their positions.
    kingTower = tower("KingTower_1_0", 4, 9000, 29000, 2, true);
    leftPrincessTower = tower("PrincessTower_1_1", 5, 3500, 25500, 1, false);
    rightPrincessTower = tower("PrincessTower_1_2", 6, 14500, 25500, 2, false);
    candidates = List.of(kingTower, leftPrincessTower, rightPrincessTower);
  }

  private TargetView select(int unitX, int unitY, int unitLane, List<TargetView> list) {
    return DefaultTargetSelection.selectDefaultTarget(
        unitX,
        unitY,
        unitLane,
        KNIGHT_ATTACK_RANGE,
        ARENA_HEIGHT_CELLS,
        kingTower,
        list,
        DefaultTargetSelection.Rules.standard(),
        DefaultSelectionQueries.standard1v1(),
        candidate -> true);
  }

  @Test
  @DisplayName("a unit on the left walks at the left princess tower")
  void leftDeploymentTakesTheLeftTower() {
    assertThat(select(3500, 10000, 1, candidates)).isSameAs(leftPrincessTower);
  }

  @Test
  @DisplayName("a unit on the right walks at the right princess tower")
  void rightDeploymentTakesTheRightTower() {
    assertThat(select(14500, 10000, 2, candidates)).isSameAs(rightPrincessTower);
  }

  @Test
  @DisplayName("a unit in the middle walks at the king tower, which is the closest in x")
  void centreDeploymentTakesTheKingTower() {
    assertThat(select(9000, 12000, 2, candidates)).isSameAs(kingTower);
  }

  @Test
  @DisplayName("the king has to be among the candidates for the middle to answer the king tower")
  void withoutTheKingTheCentreTakesAPrincessTower() {
    List<TargetView> withoutTheKing = List.of(leftPrincessTower, rightPrincessTower);

    assertThat(select(9000, 12000, 2, withoutTheKing)).isSameAs(leftPrincessTower);
  }

  @Test
  @DisplayName("a unit that has walked past the middle switches to the nearer princess tower")
  void pastTheMiddleTheRightTowerWins() {
    assertThat(select(12000, 20000, 2, candidates)).isSameAs(rightPrincessTower);
  }

  @Test
  @DisplayName("a refused candidate leaves the seed in place")
  void aRefusedCandidateKeepsTheSeed() {
    TargetView chosen =
        DefaultTargetSelection.selectDefaultTarget(
            3500,
            10000,
            1,
            KNIGHT_ATTACK_RANGE,
            ARENA_HEIGHT_CELLS,
            kingTower,
            candidates,
            DefaultTargetSelection.Rules.standard(),
            DefaultSelectionQueries.standard1v1(),
            candidate -> false);

    assertThat(chosen).isSameAs(kingTower);
  }

  @Test
  @DisplayName("a short-ranged unit is kept to the candidates of its own lane")
  void shortRangedUnitsStayInTheirLane() {
    TargetView chosen =
        DefaultTargetSelection.selectDefaultTarget(
            9000,
            12000,
            1,
            400,
            ARENA_HEIGHT_CELLS,
            kingTower,
            candidates,
            DefaultTargetSelection.Rules.standard(),
            DefaultSelectionQueries.standard1v1(),
            candidate -> true);

    assertThat(chosen).isSameAs(leftPrincessTower);
  }

  @Test
  @DisplayName(
      "a candidate the validator refuses still lowers the threshold for the ones behind it")
  void refusedCandidatesStillLowerTheThreshold() {
    DefaultTargetSelection.Ranking ranking =
        DefaultTargetSelection.rankCandidates(
            3500,
            10000,
            1,
            ARENA_HEIGHT_CELLS,
            candidates,
            kingTower,
            Integer.MAX_VALUE,
            false,
            false,
            candidate -> candidate != leftPrincessTower);

    // The left tower scores best and is refused; the right tower scores worse than the threshold it
    // left behind, so the seed survives.
    assertThat(ranking.selected()).isSameAs(kingTower);
    assertThat(ranking.threshold()).isEqualTo(15500 * 15500);
  }

  @Test
  @DisplayName("the approximate distance keeps the larger axis and adds 53/128 of the smaller")
  void approximateDistance() {
    assertThat(DefaultTargetSelection.approxDistance(3, 4)).isEqualTo(5);
    assertThat(DefaultTargetSelection.approxDistance(1000, 0)).isEqualTo(1000);
    assertThat(DefaultTargetSelection.approxDistance(600, 800)).isEqualTo(1048);
  }
}
