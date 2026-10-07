package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.crforge.core.battle.Version16Tables;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.target.TargetView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Golden Knight's ability in data version 16.402.18 (GoldenKnightChain): the ability row names
 * both a dash range and an activation action, and only the action dashes. The tap starts the
 * activation group at once, whatever lies within the dash range; its selector waits for the closest
 * enemy ground character within 5500 while the charge buff speeds the knight up, then runs the
 * dashing attack chain, which dashes the knight at the closest such character. From that first
 * landing the knight's own chain goes on to the next characters within its secondary dash range, as
 * its row's DashCount lets it, and the run ends once the knight has stopped dashing.
 */
class GoldenKnightChargeTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String CHARGE_BUFF = "GoldenKnightCharge";

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
          + " charge buff until an enemy comes within 5500, then dashes at it")
  void aTapWithNoTargetWaitsForOne() {
    GameTables tables = Version16Tables.load();
    Standard1v1Battle battle = battle(tables);
    LadderMatch match = battle.getMatch();
    playWhenReady(battle, match, tables, 0, "GoldenKnight", 3500, 6000, "g");
    CharacterEntity knight = battle.getPlays().get(0).units().get(0);
    int tap = afterDeployWithElixir(battle, match);
    // Nothing of side 1 but its towers, all beyond 5500 of the knight.
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
    GameTables tables = Version16Tables.load();
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
   * Steps past the last play's deploy until side 0 holds an elixir for the ability, and answers the
   * next tick.
   */
  private static int afterDeployWithElixir(Standard1v1Battle battle, LadderMatch match) {
    int deployed = battle.getBattle().getTick() + 21;
    while (battle.getBattle().getTick() < deployed || match.side(0).wholeElixir() < 1) {
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
