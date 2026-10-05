package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.EntityFlags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Elite Archer's hero form played from the hero slot: it fights with its arrow, and a use of
 * its ability runs its activation group, which leaves the decoy behind; the warp that carries the
 * hero back while a tower's arrow is on its way to it drops the hero as the arrow's target, and the
 * arrow lands on nothing. The ability shot the hero fires later starts two side shots beside it.
 */
class BattleEliteArcherHeroTest {

  /** Which bit of a tag word each flag is, as the configured tables number the game tags. */
  private static final EntityFlags BITS = EntityFlags.of(GameData.tables());

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String HERO = "EliteArcherHero";

  private static final String DECOY = "EliteArcherHero_Dummy";

  /** How far the ability's warp carries the hero back toward its own side. */
  private static final int WARP_LENGTH = 5000;

  /** How far the hero is pushed off the decoy that spawns on its point, on the next tick. */
  private static final int DECOY_PUSH = 150;

  /** The ability shot, the attack sequence's second entry. */
  private static final String MIDDLE = "EliteArcherHero_Ability_Power_Shot_Projectile_Middle";

  /** The row of the ability shot's two side shots. */
  private static final String SIDE = "EliteArcherHero_Ability_Triple_Shot_Projectile";

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
          + " aimed at the hero, which lands on nothing; the ability shot then starts its two side"
          + " shots")
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
    int heroX = hero.getView().getX();
    int heroY = hero.getView().getY();
    while (named(battle, DECOY).isEmpty()) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      heroX = hero.getView().getX();
      heroY = hero.getView().getY();
      step(battle);
    }
    CharacterEntity decoy = named(battle, DECOY).get(0);
    assertThat(decoy.getView().isOccluder()).isTrue();
    assertThat(decoy.getView().isOccludes()).as("no building").isFalse();
    // The holder's add folds the decoy's row tags into its tag word before its registration visit,
    // so the push pass of that visit leaves it alone: it stands on the hero's point.
    assertThat(decoy.getView().getFlags() & BITS.avoidanceAsObstacle()).isNotZero();
    assertThat(decoy.getView().getX()).isEqualTo(heroX);
    assertThat(decoy.getView().getY()).as("not pushed on its spawn tick").isEqualTo(heroY);
    step(battle);
    // On the next tick the decoy still stands, and the hero is pushed off it toward its own side.
    assertThat(decoy.getView().getY()).isEqualTo(heroY);
    assertThat(hero.getView().getX()).isEqualTo(heroX);
    assertThat(hero.getView().getY()).isEqualTo(heroY - DECOY_PUSH);
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
    // The ability shot, fired once the hero has walked back, starts its side shots as it sets
    // off: one tick after it, before its first step, two shots of the side row, each 750 to one
    // side of where it stands, across its line.
    limit = battle.getBattle().getTick() + SHOT_TICKS;
    while (shots(battle, MIDDLE).isEmpty()) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      step(battle);
    }
    ProjectileEntity middle = shots(battle, MIDDLE).get(0);
    assertThat(shots(battle, SIDE)).isEmpty();
    int startX = middle.getX();
    int startY = middle.getY();
    int[] line = {middle.getAimX() - startX, middle.getAimY() - startY};
    step(battle);
    List<ProjectileEntity> sides = shots(battle, SIDE);
    assertThat(sides).hasSize(2);
    ProjectileEntity first = sides.get(0);
    ProjectileEntity second = sides.get(1);
    assertThat(first.getId()).isLessThan(second.getId());
    assertThat(first.getX() + second.getX()).isEqualTo(2 * startX);
    assertThat(first.getY() + second.getY()).isEqualTo(2 * startY);
    int acrossX = first.getX() - startX;
    int acrossY = first.getY() - startY;
    // Across the line, the first to the right of it as it flies.
    assertThat(Math.abs(acrossX * line[0] + acrossY * line[1]))
        .isLessThan(Math.abs(line[0]) + Math.abs(line[1]));
    assertThat(acrossX * line[1] - acrossY * line[0]).isPositive();
    assertThat(Math.round(Math.hypot(acrossX, acrossY))).isBetween(749L, 751L);
    for (ProjectileEntity side : sides) {
      assertThat(side.side()).isEqualTo(middle.side());
      assertThat(side.level()).isEqualTo(middle.level());
      assertThat(side.getTarget()).isNull();
      assertThat(side.getRoot()).as("the ability shot's root").isSameAs(hero);
      assertThat(side.getZ()).as("its constant height").isEqualTo(2000);
    }
  }

  /** The projectiles of a row the holder lists, in its order. */
  private static List<ProjectileEntity> shots(Standard1v1Battle battle, String row) {
    return battle.getWorld().getHolder().entities().stream()
        .filter(e -> e instanceof ProjectileEntity p && p.getData().name().equals(row))
        .map(ProjectileEntity.class::cast)
        .toList();
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
