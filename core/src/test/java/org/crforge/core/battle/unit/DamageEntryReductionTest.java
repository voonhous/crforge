package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.battle.Shipped.number;
import static org.crforge.core.battle.Shipped.row;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A unit's damage reduction against the hits that lose it twice (data version 16.402.18): the
 * damage entry, which every queued hit passes, lowers an amount by the target's reduction and
 * floors it at 1, after whatever the hit's own stage took off. A damage type's stage - an area
 * effect's damage - lowers the amount by the reduction and floors it at 0 first; a buff's damage
 * over time is lowered by it as the buff hits. So a Zap and a Poison on the Valkyrie hero while its
 * whirlwind's ValkyrieHero_Damage_Reduction_Buff is listed take the buff's DamageReduction off
 * twice.
 */
class DamageEntryReductionTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String REDUCTION_BUFF = "ValkyrieHero_Damage_Reduction_Buff";

  /** What the reduction leaves of an amount, in percent: 100 less the buff's DamageReduction. */
  private static final int LEFT =
      100 - number(row("character_buffs", REDUCTION_BUFF), "DamageReduction");

  /** The Valkyrie first, in the hero slot, and seven other cards. */
  private static final List<String> HERO_DECK =
      List.of(
          "Valkyrie", "Archer", "Goblins", "Knight", "Minions", "Musketeer", "Fireball", "Arrows");

  /** The other side's cards, the Zap and the Poison among them. */
  private static final List<String> OTHER_DECK =
      List.of("Knight", "Giant", "Archer", "Goblins", "Minions", "Musketeer", "Zap", "Poison");

  /** The tick the ability command runs on. */
  private static final int CAST = 300;

  /** How many words of each side the search for a battle tries. */
  private static final int WORDS = 64;

  /** One hit on the hero: whether the reduction buff was listed and what the hit points lost. */
  private record Hit(boolean underBuff, int lost) {}

  @Test
  @DisplayName(
      "a Zap on the Valkyrie hero under its whirlwind's damage reduction takes the reduction off"
          + " in the damage type's stage and again at the damage entry")
  void zapLosesTheReductionTwice() {
    GameTables tables = GameData.tables();
    BattleRecords records = new BattleRecords(tables);

    // The Zap on the hero without the buff: what a Zap deals at this level.
    int plain = firstHitOnHero(tables, records, "Zap", false);
    // The same Zap while the buff is listed.
    int reduced = firstHitOnHero(tables, records, "Zap", true);

    assertThat(reduced).isEqualTo(plain * LEFT / 100 * LEFT / 100);
  }

  @Test
  @DisplayName(
      "a Poison's damage over time on the Valkyrie hero under its whirlwind's damage reduction"
          + " takes the reduction off as the buff hits and again at the damage entry")
  void poisonLosesTheReductionTwice() {
    GameTables tables = GameData.tables();
    BattleRecords records = new BattleRecords(tables);

    int plain = firstHitOnHero(tables, records, "Poison", false);
    int reduced = firstHitOnHero(tables, records, "Poison", true);

    assertThat(reduced).isEqualTo(plain * LEFT / 100 * LEFT / 100);
  }

  /**
   * What the first hit of a side 1 spell cast on the Valkyrie hero's point takes off the hero:
   * either while its whirlwind's damage reduction is listed, or with no ability used.
   */
  private static int firstHitOnHero(
      GameTables tables, BattleRecords records, String spell, boolean underBuff) {
    Standard1v1Battle battle = battle(tables, spell);
    battle.play(200, records.card("Valkyrie"), LEVEL, 0, 3500, 15500, "v");
    // A Musketeer that stands its ground, within the hero's circle but out of its reach.
    battle.play(205, records.card("Musketeer"), LEVEL, 1, 3500, 20500, "m");
    stepTo(battle, CAST - 20);
    CharacterEntity hero = named(battle, "ValkyrieHero").get(0);
    List<Hit> hits = spellHits(battle, hero);
    if (underBuff) {
      battle.useAbility(CAST, 0, hero.name(), "a");
      while (!hero.getBuffs().carries(REDUCTION_BUFF)) {
        battle.getBattle().step();
      }
    } else {
      stepTo(battle, CAST + 10);
    }
    int cast = battle.getBattle().getTick() + 1;
    battle.play(
        cast, records.card(spell), LEVEL, 1, hero.getView().getX(), hero.getView().getY(), "s");
    stepTo(battle, cast + 40);
    assertThat(hits).isNotEmpty();
    Hit first = hits.get(0);
    assertThat(first.underBuff()).isEqualTo(underBuff);
    return first.lost();
  }

  /** Collects every typed hit and every hit of a buff's damage over time on the hero. */
  private static List<Hit> spellHits(Standard1v1Battle battle, CharacterEntity hero) {
    List<Hit> hits = new ArrayList<>();
    battle
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void typedHitDealt(
                  int tick,
                  WorldEntity source,
                  WorldEntity target,
                  int amount,
                  int damageId,
                  DamageResult result) {
                if (target == hero) {
                  hits.add(new Hit(hero.getBuffs().carries(REDUCTION_BUFF), result.applied()));
                }
              }

              @Override
              public void buffDamaged(
                  int tick,
                  WorldEntity target,
                  BuffInstance buff,
                  int amount,
                  int hitPointsBefore,
                  DamageResult result) {
                if (target == hero) {
                  hits.add(new Hit(hero.getBuffs().carries(REDUCTION_BUFF), result.applied()));
                }
              }
            });
    return hits;
  }

  /**
   * A battle with the Valkyrie hero form in side 0's opening hand and the Musketeer and a spell in
   * side 1's: the first pair of the two sides' words that deals them.
   */
  private static Standard1v1Battle battle(GameTables tables, String spell) {
    for (int word = 0; word < WORDS * WORDS; word++) {
      Standard1v1Battle battle = new Standard1v1Battle(tables);
      LadderMatch match =
          battle.startLadderMatch(
              HERO_DECK, OTHER_DECK, word % WORDS, word / WORDS, heroFirst(), new int[8]);
      if (inHand(match, 0, "Valkyrie")
          && inHand(match, 1, "Musketeer")
          && inHand(match, 1, spell)) {
        return battle;
      }
    }
    throw new AssertionError("no pair of words deals the three cards");
  }

  /** Slot flags with the first card in the hero slot. */
  private static int[] heroFirst() {
    int[] slots = new int[8];
    slots[0] = MatchSide.HERO_SLOT;
    return slots;
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

  private static void stepTo(Standard1v1Battle battle, int tick) {
    while (battle.getBattle().getTick() < tick) {
      battle.getBattle().step();
    }
  }
}
