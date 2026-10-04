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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Musketeer's hero form played from the hero slot and its ability used: the ability places a
 * dummy building five half tiles ahead of the hero, and the dummy, a building, places the turret on
 * its own point. The building placement passes the object that runs the row by, so the dummy does
 * not block the point it stands on; any other building there still does. The turret's start, a
 * knockback projectile spawned from its cause, is refused.
 */
class BattleMusketeerHeroTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String HERO = "MusketeerHero";

  private static final String DUMMY = "Musketeer_hero_DummyBuilding";

  private static final String TURRET = "MusketeerTurret";

  /** The Musketeer first, in the hero slot, and seven other cards. */
  private static final List<String> DECK =
      List.of(
          "Musketeer", "Archer", "Knight", "Giant", "Minions", "Valkyrie", "Fireball", "Arrows");

  private static final List<String> KNIGHTS = Collections.nCopies(8, "Knight");

  @Test
  @DisplayName(
      "the ability places the dummy building 2500 ahead of the hero; the turret's placement there"
          + " passes the dummy by and keeps its point, while the dummy blocks a building another"
          + " object places on it")
  void theDummyDoesNotBlockItsOwnTurret() {
    Standard1v1Battle battle = abilityUsed();
    CharacterEntity hero = named(battle, HERO).get(0);
    int limit = battle.getBattle().getTick() + 60;
    while (named(battle, DUMMY).isEmpty()) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      step(battle);
    }
    CharacterEntity dummy = named(battle, DUMMY).get(0);
    assertThat(dummy.getTargetView().building()).isTrue();
    // The spawn row's RelativeY of 5 half tiles moves the bottom side's point up the arena.
    assertThat(dummy.getView().getX()).isEqualTo(hero.getView().getX());
    assertThat(dummy.getView().getY()).isEqualTo(hero.getView().getY() + 2500);

    UnitData turret = GameData.records().unit(TURRET);
    int x = dummy.getView().getX();
    int y = dummy.getView().getY();
    // The dummy's own row: the turret's 600 circle overlaps the dummy's centre, which is passed by.
    assertThat(battle.getWorld().buildingPlacement(dummy, turret).place(x, y))
        .containsExactly(x, y);
    // The hero's row on the same point: the dummy is a building of the live list and blocks it.
    assertThatThrownBy(() -> battle.getWorld().buildingPlacement(hero, turret).place(x, y))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining(TURRET + " is placed as a building at (" + x + ", " + y + ")");
  }

  @Test
  @DisplayName(
      "the dummy building's start places the turret; the turret's start, a knockback projectile"
          + " spawned from its cause, is refused")
  void theTurretsKnockbackIsRefused() {
    Standard1v1Battle battle = abilityUsed();
    assertThatThrownBy(
            () -> {
              for (int i = 0; i < 60; i++) {
                step(battle);
              }
            })
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("MusketeerTurret_SpawnKnockBack spawns a projectile from its cause");
  }

  /**
   * A battle with the hero Musketeer played at (3500, 14000), its ability used as soon as side 0
   * holds the ability's cost once the hero has deployed.
   */
  private static Standard1v1Battle abilityUsed() {
    Standard1v1Battle battle = null;
    LadderMatch match = null;
    for (int word = 0; match == null || !inHand(match, "Musketeer"); word++) {
      battle = new Standard1v1Battle(GameData.tables());
      match = battle.startLadderMatch(DECK, KNIGHTS, word, 0, heroFirst(), new int[8]);
    }
    int cost = GameData.records().matchCard("Musketeer").cost();
    while (match.side(0).wholeElixir() < cost) {
      step(battle);
    }
    battle.play(
        battle.getBattle().getTick(), GameData.card("Musketeer"), LEVEL, 0, 3500, 14000, "m");
    int limit = battle.getBattle().getTick() + 200;
    while (named(battle, HERO).isEmpty()) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      step(battle);
    }
    // The hero deploys for a second; the ability costs 3.
    for (int i = 0; i < 40 || match.side(0).wholeElixir() < 3; i++) {
      step(battle);
    }
    CharacterEntity hero = named(battle, HERO).get(0);
    battle.useAbility(battle.getBattle().getTick(), 0, hero.name(), "a");
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

  private static void step(Standard1v1Battle battle) {
    battle.getBattle().step();
  }
}
