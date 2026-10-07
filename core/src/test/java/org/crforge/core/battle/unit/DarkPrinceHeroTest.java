package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.crforge.core.battle.Version16Tables;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Dark Prince's hero form (data version 16.402.18): the ability's tap dismounts the hero. Its
 * activation group swaps the hero onto its walking row, which neither charges nor jumps the river,
 * spawns the mount where the hero stands, and runs the warp-back loop: a group gated on WARP_TIME
 * below 500 whose parts warp the hero 200 back and, 50 ms later, add 50 to WARP_TIME and schedule
 * the group again. So the hero is warped back one step a tick, ten times, 2000 in all.
 *
 * <p>The scene is the recorded hero Dark Prince battle's: side 0's hero Dark Prince placed at
 * (3500, 14000) on 220, an enemy Musketeer at (3500, 21500) on 270, the ability tapped on 311.
 */
class DarkPrinceHeroTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The Dark Prince first, in the hero slot, and seven other cards. */
  private static final List<String> HERO_DECK =
      List.of(
          "DarkPrince", "Archer", "Goblins", "Giant", "Minions", "Musketeer", "Fireball", "Arrows");

  /** The other side's cards, the Musketeer among them. */
  private static final List<String> OTHER_DECK =
      List.of("Knight", "Archer", "Goblins", "Giant", "Minions", "Musketeer", "Fireball", "Arrows");

  /** The tick the ability command runs on. */
  private static final int TAP = 311;

  @Test
  @DisplayName(
      "the tap swaps the hero onto its walking row and spawns its mount; the warp-back loop then"
          + " warps it 200 back a tick, ten times, as its count reaches 500")
  void theTapDismountsAndWarpsBack() {
    GameTables tables = Version16Tables.load();
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle battle = battle(tables);
    battle.play(220, records.card("DarkPrince"), LEVEL, 0, 3500, 14000, "h");
    battle.play(270, records.card("Musketeer"), LEVEL, 1, 3500, 21500, "m");
    stepTo(battle, TAP - 20);
    CharacterEntity hero = named(battle, "DarkPrinceHero").get(0);
    battle.useAbility(TAP, 0, hero.name(), "a");
    stepTo(battle, TAP);
    int before = hero.getView().getY();
    List<Integer> steps = new ArrayList<>();
    int last = before;
    for (int k = 1; k <= 14; k++) {
      stepTo(battle, TAP + k);
      if (k == 1) {
        assertThat(hero.getData().name()).isEqualTo("DarkPrinceHero_Walking");
        assertThat(named(battle, "DarkPrinceHero_Mount")).hasSize(1);
      }
      int y = hero.getView().getY();
      steps.add(y - last);
      last = y;
    }
    // Ten warps of 200 back toward its own side, one a tick from the tap's own step, then none.
    assertThat(steps)
        .containsExactly(-200, -200, -200, -200, -200, -200, -200, -200, -200, -200, 0, 0, 0, 0);
    assertThat(before - last).isEqualTo(2000);
  }

  /** A battle with the Dark Prince hero form in side 0's hand. */
  private static Standard1v1Battle battle(GameTables tables) {
    Standard1v1Battle battle = null;
    LadderMatch match = null;
    for (int word = 0; match == null || !inHand(match, "DarkPrince"); word++) {
      battle = new Standard1v1Battle(tables);
      match = battle.startLadderMatch(HERO_DECK, OTHER_DECK, word, 0, heroFirst(), new int[8]);
    }
    return battle;
  }

  private static int[] heroFirst() {
    int[] slots = new int[8];
    slots[0] = MatchSide.HERO_SLOT;
    return slots;
  }

  private static boolean inHand(LadderMatch match, String card) {
    List<MatchCard> deck = match.side(0).deck();
    return Arrays.stream(match.side(0).getHand().slots())
        .anyMatch(index -> index >= 0 && deck.get(index).name().equals(card));
  }

  private static List<CharacterEntity> named(Standard1v1Battle battle, String row) {
    return battle.getWorld().getHolder().entities().stream()
        .filter(CharacterEntity.class::isInstance)
        .map(CharacterEntity.class::cast)
        .filter(unit -> unit.getData().name().equals(row))
        .toList();
  }

  private static void stepTo(Standard1v1Battle battle, int tick) {
    while (battle.getBattle().getTick() < tick) {
      battle.getBattle().step();
    }
  }
}
