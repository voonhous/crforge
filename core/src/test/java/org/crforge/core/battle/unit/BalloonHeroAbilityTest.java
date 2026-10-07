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
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.math.FixedMath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Balloon hero's ability (BalloonHero_Ability of data version 16.402.18): the tap starts a
 * shape selector that, a step later, picks the enemy closest to the hero in its circle and runs the
 * activation group on the hero itself, the hero its own cause. The group makes a context, writes
 * the enemy its resolver finds into it, and 50 ms later launches the skeleton trooper's projectile
 * at that enemy, its start moved 250 toward it. The projectile speeds up every 150 ms by a speed
 * override its variable feeds, and where it lands a Skeleton Trooper is spawned, whose landing area
 * effect hurts what stands there. With nobody in the circle the selector finishes without a pick,
 * and its finishing action's failsafe launches the projectile with no context, at no target.
 */
class BalloonHeroAbilityTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String PROJECTILE = "BalloonHero_Skeletrooper_Projectile";

  /** The Balloon first, in the hero slot, and seven other cards. */
  private static final List<String> HERO_DECK =
      List.of(
          "Balloon", "Archer", "Goblins", "Knight", "Minions", "Musketeer", "Fireball", "Arrows");

  /** The other side's cards, the Knight and the Giant among them. */
  private static final List<String> OTHER_DECK =
      List.of("Knight", "Giant", "Archer", "Goblins", "Minions", "Musketeer", "Fireball", "Arrows");

  /** The tick the ability command runs on. */
  private static final int CAST = 320;

  /** An earlier tick, for the trooper's long flight: the hero has only just deployed. */
  private static final int EARLY_CAST = 262;

  /** What a launch of the trooper's projectile was: its tick, start, target and owner's point. */
  private record Launch(int tick, int x, int y, WorldEntity target, int ownerX, int ownerY) {}

  /** A battle with the Balloon hero form in side 0's hand, recording every trooper launch. */
  private static Standard1v1Battle battle(GameTables tables, List<Launch> launches) {
    Standard1v1Battle battle = null;
    LadderMatch match = null;
    for (int word = 0; match == null || !inHand(match, "Balloon"); word++) {
      battle = new Standard1v1Battle(tables);
      match = battle.startLadderMatch(HERO_DECK, OTHER_DECK, word, 0, heroFirst(), new int[8]);
    }
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
                if (projectile.getData().name().equals(PROJECTILE)) {
                  launches.add(
                      new Launch(
                          tick,
                          projectile.getX(),
                          projectile.getY(),
                          projectile.getTarget(),
                          owner.getView().getX(),
                          owner.getView().getY()));
                }
              }
            });
    return battle;
  }

  @Test
  @DisplayName(
      "the trooper falls on the enemy closest to the hero, not the one of the most hit points: its"
          + " projectile starts 250 toward it, speeds up every 150 ms and spawns the trooper where"
          + " it lands, whose landing hurts the enemy")
  void theTrooperFallsOnTheClosestEnemy() {
    GameTables tables = GameData.tables();
    BattleRecords records = new BattleRecords(tables);
    List<Launch> launches = new ArrayList<>();
    Standard1v1Battle battle = battle(tables, launches);
    battle.play(220, records.card("Balloon"), LEVEL, 0, 3500, 14000, "g");
    // The Knight close by, and an enemy Giant of far more hit points a little farther.
    battle.play(225, records.card("Knight"), LEVEL, 1, 3500, 21000, "k");
    battle.play(226, records.card("Giant"), LEVEL, 1, 5000, 21200, "eg");
    stepTo(battle, EARLY_CAST - 20);
    CharacterEntity hero = named(battle, "BalloonHero").get(0);
    CharacterEntity knight = named(battle, "Knight").get(0);
    LadderMatch match = battle.getMatch();
    battle.useAbility(EARLY_CAST, 0, hero.name(), "a");
    stepTo(battle, EARLY_CAST);
    int elixir = match.side(0).getElixir();
    List<Integer> speeds = new ArrayList<>();
    List<Integer> knightHp = new ArrayList<>();
    ProjectileEntity flying = null;
    for (int k = 1; k <= 60; k++) {
      stepTo(battle, EARLY_CAST + k);
      knightHp.add(knight.getHitPoints().getHitPoints());
      for (ProjectileEntity projectile : projectiles(battle)) {
        flying = projectile;
        speeds.add(projectile.getSpeedOverride());
      }
    }
    assertThat(battle.getAbilityUses()).hasSize(1);
    assertThat(match.side(0).getElixir()).isLessThan(elixir);
    assertThat(flying).isNotNull();
    // One launch, at the Knight, its start 250 from the hero's point toward the Knight.
    assertThat(launches).hasSize(1);
    Launch launch = launches.get(0);
    assertThat(launch.target()).isSameAs(knight);
    int[] toward = {
      knight.getView().getX() - launch.ownerX(), knight.getView().getY() - launch.ownerY()
    };
    // The Knight walks on after the launch; its point at the launch is not kept, so the start's
    // direction is checked by its length and side.
    int length =
        FixedMath.guardedDistance(launch.x() - launch.ownerX(), launch.y() - launch.ownerY());
    assertThat(length).isBetween(249, 250);
    assertThat(Integer.signum(launch.y() - launch.ownerY())).isEqualTo(Integer.signum(toward[1]));
    // The override starts at log(5) * 10000 / 80 and grows to log(7) * 10000 / 80 as the variable
    // passes 7; the flight lands before the variable passes 9.
    List<Integer> distinct = new ArrayList<>();
    for (int speed : speeds) {
      if (distinct.isEmpty() || distinct.get(distinct.size() - 1) != speed) {
        distinct.add(speed);
      }
    }
    assertThat(distinct).containsSubsequence(201, 243);
    // Where it lands the trooper is spawned and its landing area effect hurts the Knight.
    assertThat(named(battle, "SkeletonTrooper")).hasSize(1);
    assertThat(named(battle, "SkeletonTrooper").get(0).side()).isZero();
    assertThat(knightHp.get(knightHp.size() - 1)).isLessThan(knightHp.get(0));
  }

  @Test
  @DisplayName(
      "with nobody in the circle the selector finishes without a pick, and its finishing action's"
          + " failsafe launches the projectile from the hero's own point at no target, once")
  void theFailsafeLaunchesAtNoTarget() {
    GameTables tables = GameData.tables();
    BattleRecords records = new BattleRecords(tables);
    List<Launch> launches = new ArrayList<>();
    Standard1v1Battle battle = battle(tables, launches);
    battle.play(220, records.card("Balloon"), LEVEL, 0, 3500, 14000, "g");
    stepTo(battle, CAST - 20);
    CharacterEntity hero = named(battle, "BalloonHero").get(0);
    battle.useAbility(CAST, 0, hero.name(), "a");
    stepTo(battle, CAST + 80);
    assertThat(battle.getAbilityUses()).hasSize(1);
    assertThat(launches).hasSize(1);
    Launch launch = launches.get(0);
    assertThat(launch.target()).isNull();
    assertThat(launch.x()).isEqualTo(launch.ownerX());
    assertThat(launch.y()).isEqualTo(launch.ownerY());
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

  /** The trooper's projectiles the holder lists. */
  private static List<ProjectileEntity> projectiles(Standard1v1Battle battle) {
    return battle.getWorld().getHolder().entities().stream()
        .filter(ProjectileEntity.class::isInstance)
        .map(ProjectileEntity.class::cast)
        .filter(projectile -> projectile.getData().name().equals(PROJECTILE))
        .toList();
  }

  /** Steps the battle until its tick is the one given. */
  private static void stepTo(Standard1v1Battle battle, int tick) {
    while (battle.getBattle().getTick() < tick) {
      battle.getBattle().step();
    }
  }
}
