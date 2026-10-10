/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Berserker hero's ability: the tap lists BerserkerHero_buff on the hero for a while and swaps
 * it into its bear form. The buff's DamageMultiplier scales every hit the hero deals to that
 * percent of the plain hit, truncated, and its UNKILLABLE tag keeps the hero's hit points at 1 at
 * least for as long as it is listed: a hit that would kill it leaves it at 1.
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

  /** The buff the ability lists on the hero. */
  private static final String BUFF = "BerserkerHero_buff";

  /** A Ladder match whose side 0 holds the hero Berserker in its hand. */
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
      "under the buff each of the hero's hits takes the buff's damage multiplier's percent of the"
          + " plain hit off the Musketeer, truncated, and a hit that would kill the hero leaves it at"
          + " 1 hit point while the buff is listed")
  void theBuffScalesTheHitsAndKeepsTheHeroAlive() {
    GameTables tables = GameData.tables();
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle battle = battle(tables);
    battle.play(220, records.card("Berserker"), LEVEL, 0, 3500, 14000, "b");
    battle.play(270, records.card("Musketeer"), LEVEL, 1, 3500, 21500, "m");
    stepTo(battle, 272);
    CharacterEntity hero = named(battle, "BerserkerHero").get(0);
    CharacterEntity musketeer = named(battle, "Musketeer").get(0);
    // The tap on the recorded tick, or later once the hero has hit the Musketeer once and side 0
    // holds the ability row's cost.
    int cost =
        Shipped.number(
            Shipped.row(
                "character_abilities",
                Shipped.text(Shipped.unitRow(hero.getData().name()), "Ability")),
            "ManaCost");
    boolean tapped = false;
    int tapTick = 0;
    List<Integer> plainLosses = new ArrayList<>();
    List<Integer> buffedLosses = new ArrayList<>();
    List<Integer> heroHitPoints = new ArrayList<>();
    int musketeerBefore = musketeer.getHitPoints().getHitPoints();
    int heroBefore = hero.getHitPoints().getHitPoints();
    // The recorded battle runs on 55 ticks past its tap.
    while (!tapped || battle.getBattle().getTick() < tapTick + 55) {
      if (!tapped) {
        assertThat(battle.getBattle().getTick()).as("the tap").isLessThan(TAP + 60);
      }
      int tick = battle.getBattle().getTick();
      if (!tapped
          && tick >= TAP
          && !plainLosses.isEmpty()
          && battle.getMatch().side(0).wholeElixir() >= cost) {
        battle.useAbility(tick, 0, hero.name(), "BerserkerHeroAbility");
        tapped = true;
        tapTick = tick;
      }
      battle.getBattle().step();
      int musketeerNow = musketeer.getHitPoints().getHitPoints();
      // The killing hit takes only what is left; the hits before it show the amount.
      if (musketeerNow != musketeerBefore && musketeerNow > 0) {
        (hero.getBuffs().carries(BUFF) ? buffedLosses : plainLosses)
            .add(musketeerBefore - musketeerNow);
        musketeerBefore = musketeerNow;
      }
      int heroNow = hero.getHitPoints().getHitPoints();
      if (heroNow != heroBefore) {
        heroHitPoints.add(heroNow);
        heroBefore = heroNow;
      }
    }
    assertThat(battle.getAbilityUses()).hasSize(1);
    // Plain hits before the tap, then the buffed ones until the Musketeer dies.
    assertThat(plainLosses).isNotEmpty();
    int plain = plainLosses.get(0);
    assertThat(plainLosses).containsOnly(plain);
    assertThat(buffedLosses)
        .isNotEmpty()
        .containsOnly(
            plain * Shipped.number(Shipped.row("character_buffs", BUFF), "DamageMultiplier") / 100);
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
