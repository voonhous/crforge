package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.crforge.core.battle.BattleTowers;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.target.TargetView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Golden Knight's ability (GoldenKnightChain): the ability row names both a dash range and an
 * activation action, and only the action dashes. The tap starts the activation group at once,
 * whatever lies within the dash range; its selector waits for the closest enemy ground character
 * within its shape while the charge buff speeds the knight up, then runs the dashing attack chain,
 * which dashes the knight at the closest such character. From that first landing the knight's own
 * chain goes on to the next characters within its secondary dash range, as its row's DashCount lets
 * it, and the run ends once the knight has stopped dashing.
 */
class GoldenKnightChargeTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String CHARGE_BUFF = "GoldenKnightCharge";

  /** The Golden Knight's row. */
  private static final GameRow KNIGHT = Shipped.unitRow("GoldenKnight");

  /** The Golden Knight's ability row. */
  private static final GameRow ABILITY =
      Shipped.row("character_abilities", Shipped.text(KNIGHT, "Ability"));

  /** The Golden Knight and seven Skeletons, which cost 1 elixir a play. */
  private static final List<String> KNIGHT_SKELETONS =
      List.of(
          "GoldenKnight",
          "Skeletons",
          "Skeletons",
          "Skeletons",
          "Skeletons",
          "Skeletons",
          "Skeletons",
          "Skeletons");

  private static final List<String> SKELETONS = Collections.nCopies(8, "Skeletons");

  @Test
  @DisplayName(
      "a tap with no enemy within the dash range does not dash: the knight walks faster under the"
          + " charge buff until an enemy comes within its selector's shape, then dashes at it")
  void aTapWithNoTargetWaitsForOne() {
    GameTables tables = GameData.tables();
    Standard1v1Battle battle = battle(tables);
    LadderMatch match = battle.getMatch();
    playWhenReady(battle, match, tables, 0, "GoldenKnight", 3500, 6000, "g");
    CharacterEntity knight = battle.getPlays().get(0).units().get(0);
    int tap = afterDeployWithElixir(battle, match);
    // Nothing of side 1 but its towers, all beyond the selector's shape around the knight.
    battle.useAbility(tap, 0, "g_0", "a");
    run(battle, tap + 2);
    assertThat(knight.getBuffs().carries(CHARGE_BUFF)).isTrue();
    assertThat(knight.getView().getState()).isNotEqualTo(GridEntityState.DASHING);

    // Skeletons of side 1 at the bridge's far end: the knight dashes once they are within reach.
    playWhenReady(battle, match, tables, 1, "Skeletons", 3500, 19500, "s");
    List<CharacterEntity> skeletons = battle.getPlays().get(1).units();
    int dashTick = -1;
    TargetView firstReference = null;
    for (int k = 0; k < 200 && dashTick < 0; k++) {
      battle.getBattle().step();
      if (knight.getView().getState() == GridEntityState.DASHING) {
        dashTick = battle.getBattle().getTick();
        firstReference = knight.getTargeting().getReference();
      }
    }
    assertThat(dashTick).as("the knight dashes").isPositive();
    assertThat(firstReference).isNotNull();
    assertThat(skeletons.stream().map(CharacterEntity::getId)).contains(firstReference.id());
  }

  @Test
  @DisplayName(
      "the charge dashes at the closest enemy and the knight's own chain goes on to the others;"
          + " once it stops dashing the charge buff is gone and it walks again")
  void theChargeChainsThroughTheKnightsOwnDashes() {
    GameTables tables = GameData.tables();
    Standard1v1Battle battle = battle(tables);
    LadderMatch match = battle.getMatch();
    playWhenReady(battle, match, tables, 0, "GoldenKnight", 3500, 14000, "g");
    CharacterEntity knight = battle.getPlays().get(0).units().get(0);
    playWhenReady(battle, match, tables, 1, "Skeletons", 3500, 18500, "s");
    List<CharacterEntity> skeletons = battle.getPlays().get(1).units();
    int tap = afterDeployWithElixir(battle, match);
    battle.useAbility(tap, 0, "g_0", "a");
    Set<Integer> references = new LinkedHashSet<>();
    List<Integer> states = new ArrayList<>();
    for (int k = 0; k < 80; k++) {
      battle.getBattle().step();
      states.add(knight.getView().getState());
      TargetView reference = knight.getTargeting().getReference();
      if (knight.getView().getState() == GridEntityState.DASHING && reference != null) {
        references.add(reference.id());
      }
    }
    // More than one skeleton dashed at in one charge: the knight's own chain went on.
    assertThat(references).hasSizeGreaterThan(1);
    assertThat(skeletons.stream().map(CharacterEntity::getId).toList()).containsAll(references);
    int lastDash = states.lastIndexOf(GridEntityState.DASHING);
    assertThat(lastDash).isLessThan(states.size() - 1);
    assertThat(knight.getView().getState()).isNotEqualTo(GridEntityState.DASHING);
    assertThat(knight.getBuffs().carries(CHARGE_BUFF)).isFalse();
  }

  @Test
  @DisplayName("a chain stops at its row's dash count with targets left in reach")
  void aChainStopsAtItsCount() {
    GameTables tables = GameData.tables();
    Standard1v1Battle battle = battle(tables);
    LadderMatch match = battle.getMatch();
    playWhenReady(battle, match, tables, 0, "GoldenKnight", 3500, 12000, "g");
    CharacterEntity knight = battle.getPlays().get(0).units().get(0);
    int dashes = Shipped.number(KNIGHT, "DashCount");
    // Four more Skeletons than the chain dashes, four abreast.
    for (int i = 0; i < dashes + 4; i++) {
      standing(battle, "Skeleton", 2900 + 400 * (i % 4), 15900 + 400 * (i / 4), "s" + i);
    }
    Chain chain = new Chain(battle);
    int tap = afterDeployWithElixir(battle, match);
    battle.useAbility(tap, 0, "g_0", "a");
    chain.untilItEnds(battle);

    assertThat(chain.started).hasSize(dashes).doesNotHaveDuplicates();
    // Each landing kills its Skeleton, the last too, so the chain ends with no reference.
    assertThat(chain.ends).containsExactly(dashes + " null");
    assertThat(knight.getView().getState()).isNotEqualTo(GridEntityState.DASHING);
  }

  @Test
  @DisplayName(
      "a chain whose next target is a crown tower stops there, with another target in reach")
  void aChainStopsAtACrownTower() {
    GameTables tables = GameData.tables();
    Standard1v1Battle battle = battle(tables);
    LadderMatch match = battle.getMatch();
    playWhenReady(battle, match, tables, 0, "GoldenKnight", 3500, 16000, "g");
    // The charge's selector takes enemy characters alone: its first dash is at this Skeleton in
    // front of the left princess tower, which is the nearest object to the landing. The knight
    // lands about its reach short of the Skeleton (its range and both collision radii), and the
    // Skeleton stands where such a landing is 500 inside the secondary range of the tower's
    // point. The second Skeleton is farther from that landing than the tower, and within the
    // secondary range of the tower's point, so only the stop at a crown tower ends the chain.
    int range = Shipped.number(KNIGHT, "DashSecondaryRange");
    int reach =
        Shipped.number(KNIGHT, "Range")
            + Shipped.number(KNIGHT, "CollisionRadius")
            + Shipped.number(Shipped.unitRow("Skeleton"), "CollisionRadius");
    TowerEntity tower = BattleTowers.towerNamed(battle.getBattle(), "PrincessTower_1_1");
    int towerX = tower.getView().getX();
    int towerY = tower.getView().getY();
    standing(battle, "Skeleton", towerX, towerY - range + reach + 500, "near");
    standing(battle, "Skeleton", towerX + range - 1000, towerY - 1500, "far");
    Chain chain = new Chain(battle);
    int tap = afterDeployWithElixir(battle, match);
    battle.useAbility(tap, 0, "g_0", "a");
    chain.untilItEnds(battle);

    assertThat(chain.started).containsExactly("near", "PrincessTower_1_1");
    assertThat(chain.ends).containsExactly("2 PrincessTower_1_1");
  }

  /** An enemy of side 1 placed directly, standing still. */
  private static void standing(Standard1v1Battle battle, String row, int x, int y, String name) {
    CharacterEntity enemy =
        battle.deploy(battle.getBattle().getTick(), GameData.unit(row), LEVEL, 1, x, y, name);
    enemy.setActive(CharacterEntity.MOVEMENT_SLOT, false);
  }

  /** The names of the chain's dash targets as each dash starts, and its end. */
  private static final class Chain {
    final List<String> started = new ArrayList<>();
    final List<String> ends = new ArrayList<>();

    Chain(Standard1v1Battle battle) {
      battle
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void chainDashStarted(
                    int tick,
                    CharacterEntity unit,
                    int fromX,
                    int fromY,
                    int aimX,
                    int aimY,
                    int radius) {
                  started.add(unit.getUnit().targeting().getReference().getEntity().getName());
                }

                @Override
                public void chainDashEnded(
                    int tick, CharacterEntity unit, int count, TargetView reference) {
                  ends.add(
                      count + " " + (reference == null ? null : reference.getEntity().getName()));
                }
              });
    }

    void untilItEnds(Standard1v1Battle battle) {
      for (int k = 0; k < 300 && ends.isEmpty(); k++) {
        battle.getBattle().step();
      }
      assertThat(ends).as("the chain's end").hasSize(1);
    }
  }

  /** A battle whose side 0 holds the Golden Knight in its opening hand. */
  private static Standard1v1Battle battle(GameTables tables) {
    Standard1v1Battle battle = null;
    LadderMatch match = null;
    for (int word = 0; match == null || !inHand(match, 0, "GoldenKnight"); word++) {
      battle = new Standard1v1Battle(tables, LEVEL, false);
      match = battle.startLadderMatch(KNIGHT_SKELETONS, SKELETONS, word, 0);
    }
    return battle;
  }

  private static boolean inHand(LadderMatch match, int side, String card) {
    List<MatchCard> deck = match.side(side).deck();
    return Arrays.stream(match.side(side).getHand().slots())
        .anyMatch(index -> index >= 0 && deck.get(index).name().equals(card));
  }

  /**
   * Steps until the side holds the card and the elixir for it, then plays it on the next tick and
   * runs that tick; the play must pass.
   */
  private static void playWhenReady(
      Standard1v1Battle battle,
      LadderMatch match,
      GameTables tables,
      int side,
      String card,
      int x,
      int y,
      String name) {
    BattleRecords records = battle.getWorld().getRecords();
    int cost = records.matchCard(card).cost();
    int limit = battle.getBattle().getTick() + 1000;
    while (!inHand(match, side, card) || match.side(side).wholeElixir() < cost) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      battle.getBattle().step();
    }
    int tick = battle.getBattle().getTick();
    battle.play(tick, records.card(card), LEVEL, side, x, y, name);
    run(battle, tick);
    Standard1v1Battle.Play play = battle.getPlays().get(battle.getPlays().size() - 1);
    assertThat(play.name()).isEqualTo(name);
    assertThat(play.matchCode()).as("%s passes the match's gates", name).isZero();
  }

  /**
   * Steps past the last play's deploy, the knight row's DeployTime and a tick, until side 0 holds
   * the elixir its ability row costs, and answers the next tick.
   */
  private static int afterDeployWithElixir(Standard1v1Battle battle, LadderMatch match) {
    int deployed = battle.getBattle().getTick() + Shipped.number(KNIGHT, "DeployTime") / 50 + 1;
    int cost = Shipped.number(ABILITY, "ManaCost");
    while (battle.getBattle().getTick() < deployed || match.side(0).wholeElixir() < cost) {
      battle.getBattle().step();
    }
    return battle.getBattle().getTick();
  }

  /** Steps the battle until it has run the given tick. */
  private static void run(Standard1v1Battle battle, int lastTick) {
    while (battle.getBattle().getTick() <= lastTick) {
      battle.getBattle().step();
    }
  }
}
