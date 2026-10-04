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
 * The Elite Archer's hero form played from the hero slot: it fights with its arrow, and a use of
 * its ability runs its activation group, which leaves the decoy behind; the warp that carries the
 * hero back while a tower's arrow is on its way to it drops the hero as the arrow's target, and the
 * arrow lands on nothing. The ability shot's side shots, which come later, are refused.
 */
class BattleEliteArcherHeroTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String HERO = "EliteArcherHero";

  private static final String DECOY = "EliteArcherHero_Dummy";

  /** How far the ability's warp carries the hero back toward its own side. */
  private static final int WARP_LENGTH = 5000;

  /** Long enough for the hero to walk back and fire its ability shot. */
  private static final int SHOT_TICKS = 300;

  /** The six crown towers, which stamp the overlay on every build. */
  private static final int TOWER_FOOTPRINTS = 6;

  /** The Elite Archer first, in the hero slot, and seven other cards. */
  private static final List<String> DECK =
      List.of(
          "EliteArcher", "Archer", "Knight", "Giant", "Minions", "Musketeer", "Fireball", "Arrows");

  private static final List<String> KNIGHTS = Collections.nCopies(8, "Knight");

  @Test
  @DisplayName(
      "a hero slot's Elite Archer plays the hero form; a use of its ability leaves the decoy, which"
          + " stamps the routing overlay while it stands still, and the warp drops the tower's arrow"
          + " aimed at the hero, which lands on nothing")
  void theHeroAbilityLeavesTheDecoy() {
    Standard1v1Battle battle = null;
    LadderMatch match = null;
    for (int word = 0; match == null || !inHand(match, "EliteArcher"); word++) {
      battle = new Standard1v1Battle(GameData.tables());
      match = battle.startLadderMatch(DECK, KNIGHTS, word, 0, heroFirst(), new int[8]);
    }
    int cost = GameData.records().matchCard("EliteArcher").cost();
    while (match.side(0).wholeElixir() < cost) {
      step(battle);
    }
    battle.play(
        battle.getBattle().getTick(), GameData.card("EliteArcher"), LEVEL, 0, 3500, 14000, "e");
    int limit = battle.getBattle().getTick() + 200;
    while (named(battle, HERO).isEmpty()) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      step(battle);
    }
    CharacterEntity hero = named(battle, HERO).get(0);
    // The ability is used as a tower's arrow sets off toward the hero.
    while (aimedAt(battle, hero) == null) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      step(battle);
    }
    ProjectileEntity arrow = aimedAt(battle, hero);
    int hitPoints = hero.getHitPoints().getHitPoints();
    battle.useAbility(battle.getBattle().getTick(), 0, hero.name(), "a");
    while (named(battle, DECOY).isEmpty()) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      step(battle);
    }
    CharacterEntity decoy = named(battle, DECOY).get(0);
    assertThat(decoy.getView().isOccluder()).isTrue();
    assertThat(decoy.getView().isOccludes()).as("no building").isFalse();
    int warpedFrom = hero.getView().getY();
    while (hero.getView().getY() > warpedFrom - WARP_LENGTH / 2) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      step(battle);
      // The decoy stands still, so every build stamps it with the crown towers.
      assertThat(battle.getWorld().getGrid().getFootprints()).hasSize(TOWER_FOOTPRINTS + 1);
    }
    assertThat(arrow.getTarget()).as("the arrow the warp's reset drops").isNull();
    assertThat(hero.getView().getPendingDamageAmount()).isZero();
    while (battle.getWorld().getHolder().entities().contains(arrow)) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      step(battle);
    }
    assertThat(hero.getHitPoints().getHitPoints()).as("it landed on nothing").isEqualTo(hitPoints);
    Standard1v1Battle played = battle;
    assertThatThrownBy(
            () -> {
              for (int i = 0; i < SHOT_TICKS; i++) {
                step(played);
              }
            })
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining(
            "EliteArcherHero_Triple_Shot_Action is an ActionShootProjectilesInCharacterDirection");
  }

  /** The first projectile the holder lists on its way to a unit, or null. */
  private static ProjectileEntity aimedAt(Standard1v1Battle battle, CharacterEntity unit) {
    return battle.getWorld().getHolder().entities().stream()
        .filter(e -> e instanceof ProjectileEntity p && p.getTarget() == unit)
        .map(ProjectileEntity.class::cast)
        .findFirst()
        .orElse(null);
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

  private static void step(Standard1v1Battle battle) {
    battle.getBattle().step();
  }
}
