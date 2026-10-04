package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Barbarian Barrel's hero form played with no ability use: a spell card in the hero slot, cast
 * as its hero row. Its thrown and rolling projectiles hold the champion slot's button state ready
 * while they fly, and the roll's end leaves the hero barbarian, which the slot follows by the play
 * and whose starting actions run.
 */
class BattleBarbLogHeroTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String BARBARIAN = "BarbLogBarbarianHero";

  /** The Barbarian Barrel first, in the hero slot, and seven other cards. */
  private static final List<String> BARREL_DECK =
      List.of("BarbLog", "Archer", "Knight", "Giant", "Minions", "Musketeer", "Fireball", "Arrows");

  private static final List<String> KNIGHTS = Collections.nCopies(8, "Knight");

  @Test
  @DisplayName(
      "a hero slot's Barbarian Barrel makes the first champion slot follow the barbarian the hero"
          + " form links, with the card's deck index")
  void theSlotFollowsTheLinkedBarbarian() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    battle.startLadderMatch(BARREL_DECK, KNIGHTS, 0, 0, heroFirst(), new int[8]);
    ChampionController slot = battle.getWorld().kingTower(0).championSlot(1);
    assertThat(slot.getChampion().name()).isEqualTo(BARBARIAN);
    assertThat(slot.getDeckIndex()).isZero();
  }

  @Test
  @DisplayName(
      "the play casts the hero row's projectile, whose flight and roll hold the slot's button"
          + " state ready; the roll leaves the hero barbarian, carrying the play, which the slot"
          + " follows and whose starting actions are running")
  void theRollLeavesTheFollowedBarbarian() {
    Standard1v1Battle battle = null;
    LadderMatch match = null;
    for (int word = 0; match == null || !inHand(match, "BarbLog"); word++) {
      battle = new Standard1v1Battle(GameData.tables());
      match = battle.startLadderMatch(BARREL_DECK, KNIGHTS, word, 0, heroFirst(), new int[8]);
    }
    ChampionController slot = battle.getWorld().kingTower(0).championSlot(1);
    int cost = GameData.records().matchCard("BarbLog").cost();
    while (match.side(0).wholeElixir() < cost) {
      step(battle);
    }
    int deployIndex = match.side(0).getDeployCounter();
    battle.play(battle.getBattle().getTick(), GameData.card("BarbLog"), LEVEL, 0, 3500, 20500, "b");
    step(battle);
    List<ProjectileEntity> thrown = projectiles(battle, "BarbLogHeroProjectile");
    assertThat(thrown).hasSize(1);
    assertThat(thrown.get(0).getDeployIndex()).isEqualTo(deployIndex);

    // While a hero projectile flies or rolls, its starting action writes the ready state.
    int limit = battle.getBattle().getTick() + 200;
    boolean rolled = false;
    while (named(battle, BARBARIAN).isEmpty()) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      step(battle);
      List<ProjectileEntity> rolling = projectiles(battle, "BarbLogHeroProjectileRolling");
      if (!rolling.isEmpty()) {
        rolled = true;
        assertThat(rolling.get(0).getDeployIndex()).isEqualTo(deployIndex);
        assertThat(slot.getState()).isEqualTo(ChampionController.READY);
      }
    }
    assertThat(rolled).isTrue();
    CharacterEntity barbarian = named(battle, BARBARIAN).get(0);
    assertThat(barbarian.getDeployIndex()).isEqualTo(deployIndex);
    step(battle);
    step(battle);
    assertThat(slot.champions()).containsExactly(barbarian);
    assertThat(barbarian.actionHolder().running())
        .extracting(run -> run.getAction().name())
        .contains(
            "BarbLog_hero_Listen_To_New_Deploy", "BarbLog_hero_interval_check_ability_played");
  }

  @Test
  @DisplayName(
      "a hero slot on a building card, or on a spell card with no hero form, is refused, as no"
          + " reference holds it")
  void theHeroSlotsNoReferenceHoldsAreRefused() {
    List<String> zaps =
        List.of("Zap", "Archer", "Knight", "Giant", "Minions", "Musketeer", "Fireball", "Arrows");
    assertThatThrownBy(
            () ->
                new Standard1v1Battle(GameData.tables())
                    .startLadderMatch(zaps, KNIGHTS, 0, 0, heroFirst(), new int[8]))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("Zap in a hero slot, a spell card with no hero form");
    List<String> cannons =
        List.of(
            "Cannon", "Archer", "Knight", "Giant", "Minions", "Musketeer", "Fireball", "Arrows");
    assertThatThrownBy(
            () ->
                new Standard1v1Battle(GameData.tables())
                    .startLadderMatch(cannons, KNIGHTS, 0, 0, heroFirst(), new int[8]))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("Cannon in a hero slot, which is neither a troop card nor a spell");
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

  /** The projectiles of a row the holder lists, in its order. */
  private static List<ProjectileEntity> projectiles(Standard1v1Battle battle, String row) {
    return battle.getWorld().getHolder().entities().stream()
        .filter(ProjectileEntity.class::isInstance)
        .map(ProjectileEntity.class::cast)
        .filter(projectile -> projectile.getData().name().equals(row))
        .toList();
  }

  private static void step(Standard1v1Battle battle) {
    battle.getBattle().step();
  }
}
