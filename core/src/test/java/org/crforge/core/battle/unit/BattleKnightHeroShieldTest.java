package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The hero Knight's shield: its start sets the shield to none, and its ability's shield action
 * fills it back to the shield's maximum (ShieldHitpoints at the level), not to the Knight's maximum
 * hit points.
 */
class BattleKnightHeroShieldTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String HERO = "KnightHero";

  /** The Knight first, in the hero slot, and seven other cards. */
  private static final List<String> DECK =
      List.of(
          "Knight", "Archer", "Musketeer", "Giant", "Minions", "Valkyrie", "Fireball", "Arrows");

  private static final List<String> KNIGHTS = Collections.nCopies(8, "Knight");

  @Test
  @DisplayName(
      "the hero Knight starts with no shield, and its ability gives it a shield of its shield"
          + " maximum, not of its maximum hit points")
  void theAbilityFillsTheShieldToItsMaximum() {
    Standard1v1Battle battle = heroDeployed();
    CharacterEntity hero = named(battle, HERO).get(0);
    HitPoints hp = hero.getHitPoints();
    // The start's ShieldPercent 0 row leaves the shield empty and its maximum as it is.
    assertThat(hp.getShield()).isZero();
    assertThat(hp.getShieldMaximum()).isPositive().isNotEqualTo(hp.getMaximum());
    int shieldMaximum = hp.getShieldMaximum();

    battle.useAbility(battle.getBattle().getTick(), 0, hero.name(), "a");
    int limit = battle.getBattle().getTick() + 60;
    while (hp.getShield() == 0) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      battle.getBattle().step();
    }
    // ShieldPercent 100 of the shield maximum.
    assertThat(hp.getShield()).isEqualTo(shieldMaximum);
    assertThat(hp.getShieldMaximum()).isEqualTo(shieldMaximum);
  }

  /**
   * A battle with the hero Knight played at (3500, 14000) and deployed, side 0 holding at least the
   * ability's cost.
   */
  private static Standard1v1Battle heroDeployed() {
    Standard1v1Battle battle = null;
    LadderMatch match = null;
    for (int word = 0; match == null || !inHand(match, "Knight"); word++) {
      battle = new Standard1v1Battle(GameData.tables());
      match = battle.startLadderMatch(DECK, KNIGHTS, word, 0, heroFirst(), new int[8]);
    }
    int cost = GameData.records().matchCard("Knight").cost();
    while (match.side(0).wholeElixir() < cost) {
      battle.getBattle().step();
    }
    battle.play(battle.getBattle().getTick(), GameData.card("Knight"), LEVEL, 0, 3500, 14000, "k");
    int limit = battle.getBattle().getTick() + 200;
    while (named(battle, HERO).isEmpty()) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      battle.getBattle().step();
    }
    // The hero deploys for a second; then wait for the ability's cost.
    for (int i = 0; i < 40 || match.side(0).wholeElixir() < 3; i++) {
      battle.getBattle().step();
    }
    return battle;
  }

  /** Slot flags with the first card in the hero slot. */
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

  /** The characters of a row the holder lists, in its order. */
  private static List<CharacterEntity> named(Standard1v1Battle battle, String row) {
    return battle.getWorld().getHolder().entities().stream()
        .filter(CharacterEntity.class::isInstance)
        .map(CharacterEntity.class::cast)
        .filter(unit -> unit.getData().name().equals(row))
        .toList();
  }
}
