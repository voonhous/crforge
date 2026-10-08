package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Berserker hero's ability (data version 16.402.18): the tap lists BerserkerHero_buff on the
 * hero for 3500 ms and swaps it into its bear form. The buff's DamageMultiplier of 164 scales every
 * hit the hero deals to 164 percent of the plain hit, truncated, and its UNKILLABLE tag keeps the
 * hero's hit points at 1 at least for as long as it is listed: a hit that would kill it leaves it
 * at 1.
 *
 * <p>The scene is the recorded hero Berserker battle's commands, at the default level: side 0's
 * hero Berserker played on tick 220 at (3500, 14000), side 1's Musketeer on tick 270 at (3500,
 * 21500), the ability tapped on tick 305.
 */
class BerserkerHeroAbilityTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The Berserker first, in the hero slot, and seven other cards. */
  private static final List<String> HERO_DECK =
      List.of(
          "Berserker", "Archer", "Goblins", "Giant", "Minions", "Musketeer", "Fireball", "Arrows");

  /** The other side's cards, the Musketeer among them. */
  private static final List<String> OTHER_DECK =
      List.of("Knight", "Archer", "Goblins", "Giant", "Minions", "Musketeer", "Fireball", "Arrows");

  private static final int TAP = 305;

  /** A Ladder match of 16.402.18 whose side 0 holds the hero Berserker in its hand. */
  private static Standard1v1Battle battle(GameTables tables) {
    int[] heroFirst = new int[8];
    heroFirst[0] = MatchSide.HERO_SLOT;
    // Each side's word shuffles only its own deck: find side 0's, then side 1's.
    int word0 = 0;
    while (!inHand(start(tables, word0, 0, heroFirst), 0, "Berserker")) {
      word0++;
    }
    int word1 = 0;
    while (!inHand(start(tables, word0, word1, heroFirst), 1, "Musketeer")) {
      word1++;
    }
    Standard1v1Battle battle = new Standard1v1Battle(tables);
    battle.startLadderMatch(HERO_DECK, OTHER_DECK, word0, word1, heroFirst, new int[8]);
    return battle;
  }

  /** The match a battle starts with the two words given. */
  private static LadderMatch start(GameTables tables, int word0, int word1, int[] heroFirst) {
    return new Standard1v1Battle(tables)
        .startLadderMatch(HERO_DECK, OTHER_DECK, word0, word1, heroFirst, new int[8]);
  }

  @Test
  @DisplayName(
      "under the buff each of the hero's hits takes 164 percent of the plain hit off the Musketeer,"
          + " truncated, and a hit that would kill the hero leaves it at 1 hit point while the buff"
          + " is listed")
  void theBuffScalesTheHitsAndKeepsTheHeroAlive() {
    GameTables tables = GameData.tables();
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle battle = battle(tables);
    battle.play(220, records.card("Berserker"), LEVEL, 0, 3500, 14000, "b");
    battle.play(270, records.card("Musketeer"), LEVEL, 1, 3500, 21500, "m");
    stepTo(battle, 272);
    CharacterEntity hero = named(battle, "BerserkerHero").get(0);
    CharacterEntity musketeer = named(battle, "Musketeer").get(0);
    battle.useAbility(TAP, 0, hero.name(), "BerserkerHeroAbility");
    List<Integer> musketeerLosses = new ArrayList<>();
    List<Integer> heroHitPoints = new ArrayList<>();
    int musketeerBefore = musketeer.getHitPoints().getHitPoints();
    int heroBefore = hero.getHitPoints().getHitPoints();
    while (battle.getBattle().getTick() < 360) {
      battle.getBattle().step();
      int musketeerNow = musketeer.getHitPoints().getHitPoints();
      // The killing hit takes only what is left; the hits before it show the amount.
      if (musketeerNow != musketeerBefore && musketeerNow > 0) {
        musketeerLosses.add(musketeerBefore - musketeerNow);
        musketeerBefore = musketeerNow;
      }
      int heroNow = hero.getHitPoints().getHitPoints();
      if (heroNow != heroBefore) {
        heroHitPoints.add(heroNow);
        heroBefore = heroNow;
      }
    }
    assertThat(battle.getAbilityUses()).hasSize(1);
    // One plain hit before the tap, then the buffed ones until the Musketeer dies.
    int plain = musketeerLosses.get(0);
    assertThat(musketeerLosses.subList(1, musketeerLosses.size()))
        .isNotEmpty()
        .containsOnly(plain * 164 / 100);
    // The Musketeer's and the towers' shots bring the hero down to 1, and no further.
    assertThat(heroHitPoints).contains(1);
    assertThat(heroHitPoints).allMatch(hitPoints -> hitPoints >= 1);
    assertThat(hero.getHitPoints().getHitPoints()).isEqualTo(1);
  }

  private static boolean inHand(LadderMatch match, int side, String card) {
    List<MatchCard> deck = match.side(side).deck();
    return Arrays.stream(match.side(side).getHand().slots())
        .anyMatch(index -> index >= 0 && deck.get(index).name().equals(card));
  }

  /** The characters of a row the holder lists, in its order. */
  private static List<CharacterEntity> named(Standard1v1Battle battle, String row) {
    return battle.getWorld().getHolder().entities().stream()
        .filter(CharacterEntity.class::isInstance)
        .map(CharacterEntity.class::cast)
        .filter(unit -> unit.getData().name().equals(row))
        .toList();
  }

  /** Steps the battle until its tick is the one given. */
  private static void stepTo(Standard1v1Battle battle, int tick) {
    while (battle.getBattle().getTick() < tick) {
      battle.getBattle().step();
    }
  }
}
