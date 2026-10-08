package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Musketeer's hero form played from the hero slot and its ability used: the ability places a
 * dummy building five half tiles ahead of the hero, and the dummy, a building, places the turret on
 * its own point. The building placement passes the object that runs the row by, so the dummy does
 * not block the point it stands on; any other building there still does. The turret's start
 * launches its knockback projectile from its cause, the turret itself, on its own point.
 */
class BattleMusketeerHeroTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String HERO = "MusketeerHero";

  private static final String DUMMY = "Musketeer_hero_DummyBuilding";

  private static final String TURRET = "MusketeerTurret";

  /** The hero's ability row. */
  private static final GameRow ABILITY =
      Shipped.row("character_abilities", Shipped.text(Shipped.unitRow(HERO), "Ability"));

  /** The spawn of the dummy building, the ability's activation group's one sub-action. */
  private static final String SPAWN_DUMMY =
      Shipped.actionNames(Shipped.text(ABILITY, "OnActivationAction"), "SubActions").get(0);

  /** The Musketeer first, in the hero slot, and seven other cards. */
  private static final List<String> DECK =
      List.of(
          "Musketeer", "Archer", "Knight", "Giant", "Minions", "Valkyrie", "Fireball", "Arrows");

  private static final List<String> KNIGHTS = Collections.nCopies(8, "Knight");

  @Test
  @DisplayName(
      "the ability places the dummy building its spawn's half tiles ahead of the hero; the"
          + " turret's placement there"
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
    // The spawn row's RelativeY in half tiles moves the bottom side's point up the arena.
    assertThat(dummy.getView().getX())
        .isEqualTo(hero.getView().getX() + 500 * Shipped.number(SPAWN_DUMMY, "RelativeX"));
    assertThat(dummy.getView().getY())
        .isEqualTo(hero.getView().getY() + 500 * Shipped.number(SPAWN_DUMMY, "RelativeY"));

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
      "the turret's start launches its knockback from the turret, its own cause, on its own point"
          + " and at its own point, at no height and at no target, in the turret's first pending"
          + " pass")
  void theTurretsStartLaunchesItsKnockback() {
    Standard1v1Battle battle = abilityUsed();
    List<String> launches = new ArrayList<>();
    battle
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void actionProjectileLaunched(
                  int tick,
                  WorldEntity owner,
                  String action,
                  int phase,
                  ProjectileEntity projectile) {
                launches.add(
                    "%d %s %s %d %s %d %d %d %d %d %s %s %d"
                        .formatted(
                            tick,
                            owner.getData().name(),
                            action,
                            phase,
                            projectile.getData().name(),
                            projectile.getStartX(),
                            projectile.getStartY(),
                            projectile.getStartZ(),
                            projectile.getAimX(),
                            projectile.getAimY(),
                            projectile.launcherName(),
                            projectile.targetName(),
                            projectile.getSide()));
              }
            });
    int limit = battle.getBattle().getTick() + 60;
    while (named(battle, TURRET).isEmpty()) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      step(battle);
    }
    CharacterEntity turret = named(battle, TURRET).get(0);
    int listed = battle.getBattle().getTick();
    for (int i = 0; i < 40; i++) {
      step(battle);
    }
    int x = turret.getView().getX();
    int y = turret.getView().getY();
    // Listed at the cleanup of the tick the dummy placed it, the turret runs its start in its
    // phase-1 pending pass of the next tick.
    assertThat(launches)
        .containsExactly(
            "%d %s MusketeerTurret_SpawnKnockBack 1 MusketeerTurret_KnockBack %d %d 0 %d %d %s null 0"
                .formatted(listed, TURRET, x, y, x, y, turret.name()));
  }

  @Test
  @DisplayName(
      "the knockback spawn row's source is its cause: one other than the object that runs it is"
          + " refused")
  void aKnockbackFromAnotherCauseIsRefused() {
    Standard1v1Battle battle = abilityUsed();
    int limit = battle.getBattle().getTick() + 60;
    while (named(battle, DUMMY).isEmpty()) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      step(battle);
    }
    CharacterEntity hero = named(battle, HERO).get(0);
    CharacterEntity dummy = named(battle, DUMMY).get(0);
    BattleWorld world = battle.getWorld();
    BattleAction knockback =
        world.getActions().build("MusketeerTurret_SpawnKnockBack", world.binding(hero));
    // Run on the hero with the dummy as its cause.
    assertThatThrownBy(() -> hero.actionHolder().start(knockback, dummy.actionHolder()))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining(
            "MusketeerTurret_SpawnKnockBack spawns a projectile from a cause other than its"
                + " owner");
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
    // The hero deploys for a second; then side 0 waits for the ability's cost.
    int abilityCost = Shipped.number(ABILITY, "ManaCost");
    for (int i = 0; i < 40 || match.side(0).wholeElixir() < abilityCost; i++) {
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
