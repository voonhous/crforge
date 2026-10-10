/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.EntityFlags;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Barbarian Barrel's hero form: a spell card in the hero slot, cast as its hero row. Its thrown
 * and rolling projectiles hold the champion slot's button state ready while they fly, and the
 * roll's end leaves the hero barbarian, which the slot follows by the play and whose starting
 * actions run. The barbarian's ability rerolls it: it backs off, rolls forward in a barrel it
 * follows, and stands up again where the barrel ends.
 */
class BattleBarbLogHeroTest {

  /** Which bit of a tag word each flag is, as the configured tables number the game tags. */
  private static final EntityFlags BITS = EntityFlags.of(GameData.tables());

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String BARBARIAN = "BarbLogBarbarianHero";

  /** The Barbarian Barrel first, in the hero slot, and seven other cards. */
  private static final List<String> BARREL_DECK =
      List.of("BarbLog", "Archer", "Knight", "Giant", "Minions", "Musketeer", "Fireball", "Arrows");

  private static final List<String> KNIGHTS = Collections.nCopies(8, "Knight");

  /** The barbarian's ability row. */
  private static final GameRow ABILITY =
      Shipped.row("character_abilities", Shipped.text(Shipped.unitRow(BARBARIAN), "Ability"));

  /** The reroll, the action the ability's activation runs. */
  private static final String REROLL = Shipped.text(ABILITY, "OnActivationAction");

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
      "the ability's reroll backs the barbarian off toward its side by the offset's share of a"
          + " step while its spawn delay runs, then rolls it in its projectile from where it"
          + " stands, healed by the variable's percent of its missing hit points; it follows the"
          + " projectile a step behind, taking no damage and untargetable, and deploys for the"
          + " reroll's deploy duration where the projectile ends")
  void theRerollRollsTheBarbarianForward() {
    Standard1v1Battle battle = null;
    LadderMatch match = null;
    for (int word = 0; match == null || !inHand(match, "BarbLog"); word++) {
      battle = new Standard1v1Battle(GameData.tables());
      match = battle.startLadderMatch(BARREL_DECK, KNIGHTS, word, 0, heroFirst(), new int[8]);
    }
    int cost = GameData.records().matchCard("BarbLog").cost();
    while (match.side(0).wholeElixir() < cost) {
      step(battle);
    }
    battle.play(battle.getBattle().getTick(), GameData.card("BarbLog"), LEVEL, 0, 3500, 20500, "b");
    int limit = battle.getBattle().getTick() + 200;
    while (named(battle, BARBARIAN).isEmpty()) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      step(battle);
    }
    CharacterEntity barbarian = named(battle, BARBARIAN).get(0);
    int abilityCost = Shipped.number(ABILITY, "ManaCost");
    for (int i = 0; i < 40 || match.side(0).wholeElixir() < abilityCost; i++) {
      step(battle);
    }
    battle.useAbility(battle.getBattle().getTick(), 0, barbarian.getId(), "a");
    limit = battle.getBattle().getTick() + 30;
    while (barbarian.getView().getState() != GridEntityState.CASTING) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      step(battle);
    }
    // The activation starts the run on the cast's first step; its first update is on the next.
    // Each step takes 50 ms off the spawn delay; while some is left the barbarian backs off toward
    // its side (down for side 0) by the offset's share of a step.
    String projectile = Shipped.text(REROLL, "ReRollProjectile");
    int spawnDelay = Shipped.number(REROLL, "SpawnDelay");
    int share = Math.abs(Shipped.number(REROLL, "OffsetY")) * 50 / spawnDelay;
    int backSteps = (spawnDelay + 49) / 50 - 1;
    int x = barbarian.getView().getX();
    int y = barbarian.getView().getY();
    for (int i = 1; i <= backSteps; i++) {
      step(battle);
      assertThat(barbarian.getView().getY()).isEqualTo(y - share * i);
      assertThat(barbarian.getView().getX()).isEqualTo(x);
      assertThat(projectiles(battle, projectile)).isEmpty();
    }
    int missing = barbarian.getHitPoints().getMaximum() - barbarian.getHitPoints().getHitPoints();
    int before = barbarian.getHitPoints().getHitPoints();
    step(battle);
    // The step that finds the delay out launches the projectile where the barbarian stands, and
    // the start action heals it by the variable's percent of what it is missing.
    int percent =
        Shipped.number(Shipped.row("variables", "BarbLog_hero_heal_percent"), "DefaultValue");
    assertThat(barbarian.getHitPoints().getHitPoints()).isEqualTo(before + missing * percent / 100);
    step(battle);
    List<ProjectileEntity> rolling = projectiles(battle, projectile);
    assertThat(rolling).hasSize(1);
    ProjectileEntity barrel = rolling.get(0);
    assertThat(barrel.getOwner()).isSameAs(barbarian);
    assertThat(barbarian.getView().getY()).isEqualTo(y - share * backSteps);
    long rollingTags = BITS.noDamage() | BITS.untargetable() | BITS.noAttack();
    int steps = 0;
    int healed = barbarian.getHitPoints().getHitPoints();
    while (projectiles(battle, projectile).contains(barrel)) {
      assertThat(barbarian.getView().getFlags() & rollingTags).isEqualTo(rollingTags);
      assertThat(barbarian.getHitPoints().getHitPoints()).isEqualTo(healed);
      int px = barrel.getX();
      int py = barrel.getY();
      step(battle);
      assertThat(barbarian.getView().getX()).isEqualTo(px);
      assertThat(barbarian.getView().getY()).isEqualTo(py);
      assertThat(++steps).isLessThan(40);
    }
    assertThat(barbarian.getView().getState()).isEqualTo(GridEntityState.DEPLOYING);
    assertThat(barbarian.getView().getDeployCountdown())
        .isEqualTo(Shipped.number(REROLL, "DeployDuration"));
    step(battle);
    step(battle);
    assertThat(barbarian.actionHolder().running())
        .extracting(run -> run.getAction().name())
        .doesNotContain(REROLL);
  }

  @Test
  @DisplayName(
      "a hero slot on a spell or building card with no hero form is refused, as no reference"
          + " holds it")
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
        .hasMessageContaining("Cannon in a hero slot, a building card with no hero form");
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
