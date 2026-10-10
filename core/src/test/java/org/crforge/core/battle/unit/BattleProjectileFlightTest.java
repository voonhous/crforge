/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.BattleTowers;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds a shooter's projectiles to what its written rows give: every launch with its id, start and
 * aim, every position after every flight step, every impact with its damage and the tower's
 * remaining hit points, and the projectile's place in the holder - ahead of every character while
 * it flies, gone in the cleanup of the tick it arrives. The last test takes the shooter's target
 * away mid-flight and holds the shot to what the removal notice says: it flies on to where the
 * tower stood and lands on nothing.
 *
 * <p>The scene writes every column it reads: the Musketeer row, its shot's row and the towers', at
 * the first level, with the towers passive. The Musketeer stands within its range of the left
 * princess tower of the top side from its placement, so it never walks and every shot leaves from
 * the same start. The tick of the first launch is found by running the battle; every other tick,
 * place, height and amount is worked out here from the written columns with the integer arithmetic
 * of the flight: the start is the launch radius along the line to the tower, each step covers the
 * shot's speed along the line to its aim, the height follows the climb to the aim plus the gravity
 * parabola over the flight's time, and the step whose distance left is at most the speed arrives.
 */
class BattleProjectileFlightTest {

  /** The shooter's row, written by the scene. */
  private static final String SHOOTER = "Musketeer";

  /** The shooter's shot row, written by the scene. */
  private static final String SHOT = "MusketeerProjectile";

  /** The tower the shooter shoots down. */
  private static final String PRINCESS_TOWER = "PrincessTower_1_1";

  /** Where the scene places the left princess tower of the top side, as the tower writer does. */
  private static final int TOWER_X = 3500;

  private static final int TOWER_Y = 25500;

  /** Where the Musketeer is placed: within its range of the tower, so it never walks. */
  private static final int SHOOTER_X = 4300;

  private static final int SHOOTER_Y = 21000;

  /** The written columns. */
  private static final int HIT_SPEED = 1100;

  private static final int DEPLOY_TIME = 1000;

  private static final int START_RADIUS = 450;

  private static final int START_Z = 450;

  private static final int SHOT_SPEED = 700;

  private static final int GRAVITY = 40;

  private static final int DAMAGE = 230;

  private static final int TOWER_HIT_POINTS = 1400;

  /** The level of the towers and of the Musketeer: the first, where no column is scaled. */
  private static final int LEVEL = 1;

  /** The id of the run's first shot, the first of the projectile band. */
  private static final int FIRST_SHOT = 4000000;

  /** The steps the scene gives up after, should no shot leave or the tower stand. */
  private static final int LAST_TICK = 1000;

  /** The configured tables with the scene's columns written. */
  private static GameTables written(Path folder) throws IOException {
    GameData.altered(
        folder,
        "characters",
        rows ->
            GameData.columns(rows, SHOOTER)
                .put("Hitpoints", 600)
                .put("HitSpeed", HIT_SPEED)
                .put("LoadTime", 300)
                .put("Speed", 60)
                .put("Mass", 5)
                .put("CollisionRadius", 500)
                .put("Range", 6000)
                .put("SightRange", 6000)
                .put("DeployTime", DEPLOY_TIME)
                .put("ProjectileStartRadius", START_RADIUS)
                .put("ProjectileStartZ", START_Z)
                .put("Projectile", SHOT));
    GameData.alterLoaded(
        folder,
        "projectiles",
        rows -> {
          // No crown-tower share: the tower takes the whole damage.
          GameData.columns(rows, SHOT).remove("CrownTowerDamagePercent");
          GameData.columns(rows, SHOT)
              .put("Damage", DAMAGE)
              .put("Speed", SHOT_SPEED)
              .put("Gravity", GRAVITY)
              .put("Homing", true);
        });
    GameData.writeTowers(folder);
    GameData.alterLoaded(
        folder,
        "buildings",
        rows -> GameData.columns(rows, "PrincessTower").put("Hitpoints", TOWER_HIT_POINTS));
    return GameTables.load(folder);
  }

  /** The towers at the first level, passive, and the Musketeer placed on tick 0. */
  private static Scene scene(Path folder) throws IOException {
    Standard1v1Battle match = new Standard1v1Battle(written(folder), LEVEL, false);
    CharacterEntity musketeer =
        match.deploy(
            0, match.getWorld().getRecords().unit(SHOOTER), LEVEL, 0, SHOOTER_X, SHOOTER_Y);
    Recording recording = new Recording(musketeer);
    match.getWorld().addObserver(recording);
    return new Scene(match, match.getBattle(), musketeer, recording);
  }

  private record Scene(
      Standard1v1Battle match, Battle battle, CharacterEntity musketeer, Recording recording) {

    /** Steps the battle until the first shot has left, and returns the tick it left on. */
    int stepToFirstLaunch() {
      while (recording.launchTicks.isEmpty() && battle.getTick() < LAST_TICK) {
        battle.step();
      }
      assertThat(recording.launchTicks).as("a shot left").isNotEmpty();
      return recording.launchTicks.get(0);
    }

    /** Steps the battle until its tick is the given one. */
    void stepTo(int tick) {
      while (battle.getTick() < tick) {
        battle.step();
      }
    }

    /** Steps the battle until the tower has left the holder. */
    void stepUntilTheTowerLeaves(TowerEntity tower) {
      while (battle.getHolder().entities().contains(tower) && battle.getTick() < LAST_TICK) {
        battle.step();
      }
      assertThat(battle.getHolder().entities()).as("the tower fell").doesNotContain(tower);
    }
  }

  /** Every launch, impact, position and death the battle tells, flattened to comparable lines. */
  private static final class Recording implements WorldObserver {
    final List<String> launches = new ArrayList<>();
    final List<Integer> launchTicks = new ArrayList<>();
    final List<String> impacts = new ArrayList<>();
    final List<String> positions = new ArrayList<>();
    final List<String> deaths = new ArrayList<>();
    int firstTick = -1;
    final CharacterEntity unit;

    Recording(CharacterEntity unit) {
      this.unit = unit;
    }

    @Override
    public void afterPrePass(int tick, List<WorldEntity> present) {
      if (firstTick < 0 && present.contains(unit)) {
        firstTick = tick;
      }
    }

    @Override
    public void projectileLaunched(int tick, ProjectileEntity p) {
      launchTicks.add(tick - firstTick);
      launches.add(
          "%d %s %s %s %s %d %d %d aim %d %d %d"
              .formatted(
                  tick - firstTick,
                  p.name(),
                  p.getData().name(),
                  p.getOwner().name(),
                  p.getTarget() == null ? "null" : p.getTarget().name(),
                  p.getX(),
                  p.getY(),
                  p.getZ(),
                  p.getAimX(),
                  p.getAimY(),
                  p.getAimZ()));
    }

    @Override
    public void projectileImpacted(
        int tick, ProjectileEntity p, WorldEntity target, int damage, DamageResult result) {
      impacts.add(
          "%d %s %s %d %d %d %d %d"
              .formatted(
                  tick - firstTick,
                  p.name(),
                  target.name(),
                  damage,
                  target.getTargetView().getHitPoints(),
                  p.getX(),
                  p.getY(),
                  p.getZ()));
      if (result.died()) {
        deaths.add("%d %s".formatted(tick - firstTick, target.name()));
      }
    }

    @Override
    public void afterPostHooks(
        int tick, List<WorldEntity> present, List<ProjectileEntity> projectiles) {
      for (ProjectileEntity p : projectiles) {
        if (!p.isReleased()) {
          positions.add(
              "%d %d %d %d %d"
                  .formatted(tick - firstTick, p.getId(), p.getX(), p.getY(), p.getZ()));
        }
      }
    }
  }

  /**
   * The flight the written columns give a shot from the Musketeer to the tower: its start, the
   * position and height after each step that does not arrive, and the step that arrives.
   */
  private static final class Flight {
    final int startX;
    final int startY;
    final int startZ = START_Z;
    final List<int[]> steps = new ArrayList<>();

    Flight() {
      // The start is the launch radius along the line from the Musketeer to the tower's centre.
      int dx = TOWER_X - SHOOTER_X;
      int dy = TOWER_Y - SHOOTER_Y;
      int length = distance(dx, dy);
      startX = SHOOTER_X + dx * START_RADIUS / length;
      startY = SHOOTER_Y + dy * START_RADIUS / length;
      // Each step moves the speed along the line to the aim, unless what is left is no more than
      // the speed: then the shot arrives. The height is the start height plus the climb's share
      // of the time flown plus the parabola, time being the distance from the start over the
      // speed, whole steps only.
      int total = Math.max(1, distance(TOWER_X - startX, TOWER_Y - startY) / SHOT_SPEED);
      int gravityHalf = GRAVITY * -500 / 1000;
      int climb = -startZ;
      int x = startX;
      int y = startY;
      while (true) {
        int remaining = distance(x - TOWER_X, y - TOWER_Y);
        if (remaining <= SHOT_SPEED) {
          break;
        }
        x = (TOWER_X - x) * SHOT_SPEED / remaining + x;
        y = (TOWER_Y - y) * SHOT_SPEED / remaining + y;
        int time = distance(x - startX, y - startY) / SHOT_SPEED;
        int z = (time - total) * time * gravityHalf + climb * time / total + startZ;
        steps.add(new int[] {x, y, z});
      }
    }

    /** The tick a shot that left on the given tick arrives on: the step after its last one. */
    int arrival(int launchTick) {
      return launchTick + steps.size() + 1;
    }

    /** The position line of a shot after the given step of its flight, counted from 1. */
    String position(int launchTick, int id, int step) {
      int[] at = steps.get(step - 1);
      return "%d %d %d %d %d".formatted(launchTick + step, id, at[0], at[1], at[2]);
    }

    /** The floor of the length of a vector, in whole units. */
    static int distance(int dx, int dy) {
      long squares = (long) dx * dx + (long) dy * dy;
      long root = (long) Math.sqrt((double) squares);
      while (root * root > squares) {
        root--;
      }
      while ((root + 1) * (root + 1) <= squares) {
        root++;
      }
      return (int) root;
    }
  }

  /** The shots it takes the written damage to bring the tower down. */
  private static int shotsToKill() {
    return (TOWER_HIT_POINTS + DAMAGE - 1) / DAMAGE;
  }

  /** The tick of every launch: the first, found by running, and one every hit speed after it. */
  private static List<Integer> launchTicks(int first) {
    List<Integer> ticks = new ArrayList<>();
    for (int shot = 0; shot < shotsToKill(); shot++) {
      ticks.add(first + shot * HIT_SPEED / 50);
    }
    return ticks;
  }

  @Test
  @DisplayName(
      "the flight the written columns give: a start along the line, a step of the speed, an arc")
  void theWrittenColumnsGiveThisFlight() {
    // The scene's own arithmetic, pinned once so that it does not only agree with itself: the
    // start lies 450 along the line to the tower, the first step's time rounds down to zero, so
    // the shot holds its height on it, and the fifth step leaves 621 to go, which arrives.
    Flight flight = new Flight();
    assertThat(new int[] {flight.startX, flight.startY}).containsExactly(4222, 21443);
    assertThat(flight.steps)
        .containsExactly(
            new int[] {4100, 22132, 450},
            new int[] {3978, 22821, 440},
            new int[] {3856, 23510, 390},
            new int[] {3733, 24199, 300},
            new int[] {3610, 24888, 170});
    assertThat(shotsToKill()).isEqualTo(7);
  }

  @Test
  @DisplayName("every launch leaves from the start the rows give, with its id and its aim")
  void everyLaunchLeavesFromTheStartTheRowsGive(@TempDir Path folder) throws IOException {
    Scene scene = scene(folder);
    int first = scene.stepToFirstLaunch();
    TowerEntity tower = BattleTowers.towerNamed(scene.battle(), PRINCESS_TOWER);
    scene.stepUntilTheTowerLeaves(tower);

    Flight flight = new Flight();
    List<String> expected = new ArrayList<>();
    List<Integer> ticks = launchTicks(first);
    for (int shot = 0; shot < ticks.size(); shot++) {
      expected.add(
          "%d proj_%d %s %s %s %d %d %d aim %d %d 0"
              .formatted(
                  ticks.get(shot),
                  FIRST_SHOT + shot,
                  SHOT,
                  SHOOTER,
                  PRINCESS_TOWER,
                  flight.startX,
                  flight.startY,
                  flight.startZ,
                  TOWER_X,
                  TOWER_Y));
    }
    // Deployed on tick 0, the Musketeer cannot shoot before its deploy has run out.
    assertThat(first).as("the first launch").isGreaterThan(DEPLOY_TIME / 50);
    assertThat(scene.recording().launches).containsExactlyElementsOf(expected);
    assertThat(scene.musketeer().getView().getX())
        .as("the Musketeer never walked")
        .isEqualTo(SHOOTER_X);
    assertThat(scene.musketeer().getView().getY()).isEqualTo(SHOOTER_Y);
  }

  @Test
  @DisplayName("every projectile stands where the rows put it after every flight step")
  void everyProjectileStandsWhereTheRowsPutIt(@TempDir Path folder) throws IOException {
    Scene scene = scene(folder);
    int first = scene.stepToFirstLaunch();
    TowerEntity tower = BattleTowers.towerNamed(scene.battle(), PRINCESS_TOWER);
    scene.stepUntilTheTowerLeaves(tower);

    Flight flight = new Flight();
    List<String> expected = new ArrayList<>();
    List<Integer> ticks = launchTicks(first);
    for (int shot = 0; shot < ticks.size(); shot++) {
      for (int step = 1; step <= flight.steps.size(); step++) {
        expected.add(flight.position(ticks.get(shot), FIRST_SHOT + shot, step));
      }
    }
    assertThat(expected).as("five positions for each of seven shots").hasSize(35);
    assertThat(scene.recording().positions).containsExactlyElementsOf(expected);
  }

  @Test
  @DisplayName(
      "every impact deals the written damage and leaves the tower at what the earlier ones left")
  void everyImpactDealsTheWrittenDamage(@TempDir Path folder) throws IOException {
    Scene scene = scene(folder);
    int first = scene.stepToFirstLaunch();
    TowerEntity tower = BattleTowers.towerNamed(scene.battle(), PRINCESS_TOWER);
    scene.stepUntilTheTowerLeaves(tower);

    Flight flight = new Flight();
    List<String> expected = new ArrayList<>();
    List<Integer> ticks = launchTicks(first);
    int standing = TOWER_HIT_POINTS;
    for (int shot = 0; shot < ticks.size(); shot++) {
      standing = Math.max(0, standing - DAMAGE);
      expected.add(
          "%d proj_%d %s %d %d %d %d 0"
              .formatted(
                  flight.arrival(ticks.get(shot)),
                  FIRST_SHOT + shot,
                  PRINCESS_TOWER,
                  DAMAGE,
                  standing,
                  TOWER_X,
                  TOWER_Y));
    }
    int last = flight.arrival(ticks.get(ticks.size() - 1));
    assertThat(scene.recording().impacts).containsExactlyElementsOf(expected);
    assertThat(scene.recording().deaths).containsExactly(last + " " + PRINCESS_TOWER);
    assertThat(scene.battle().getTick())
        .as("the tower left in the tick it died")
        .isEqualTo(last + 1);
  }

  @Test
  @DisplayName(
      "a shot in flight precedes every character in the holder and leaves in the tick it arrives")
  void aShotPrecedesEveryCharacterAndLeavesWhenItArrives(@TempDir Path folder) throws IOException {
    Scene scene = scene(folder);
    Battle battle = scene.battle();
    CharacterEntity musketeer = scene.musketeer();
    int launch = scene.stepToFirstLaunch();
    scene.stepTo(launch + 3);

    Flight flight = new Flight();
    List<BattleEntity> entities = battle.getHolder().entities();
    assertThat(entities).hasSize(8);
    assertThat(entities.get(0)).isInstanceOf(ProjectileEntity.class);
    ProjectileEntity shot = (ProjectileEntity) entities.get(0);
    assertThat(shot.getKind()).isEqualTo(BattleEntity.KIND_PROJECTILE);
    assertThat(shot.getId()).isEqualTo(FIRST_SHOT);
    assertThat(shot.name()).isEqualTo("proj_" + FIRST_SHOT);
    assertThat(entities.stream().map(BattleEntity::getId).toList())
        .as("the projectile band precedes the character band")
        .containsExactly(4000000, 5000000, 5000001, 5000002, 5000003, 5000004, 5000005, 5000006);
    assertThat(shot.getOwner()).isSameAs(musketeer);
    assertThat(shot.getRoot()).isSameAs(musketeer);
    assertThat(shot.getTarget()).isSameAs(BattleTowers.towerNamed(battle, PRINCESS_TOWER));
    assertThat(shot.getSide()).isEqualTo(musketeer.side());
    assertThat(shot.level()).isEqualTo(LEVEL);
    assertThat(shot.damage()).isEqualTo(DAMAGE);
    assertThat(shot.isReleased()).isFalse();
    assertThat(shot.isRemovable()).isFalse();
    assertThat(new int[] {shot.getX(), shot.getY(), shot.getZ()})
        .as("after its second step")
        .containsExactly(flight.steps.get(1));

    // The arrival tick: the impact lands in the post-hook pass and the closing cleanup drops the
    // released projectile, before the Musketeer's next shot has even left.
    scene.stepTo(flight.arrival(launch) + 1);
    assertThat(shot.isReleased()).isTrue();
    assertThat(shot.isRemovable()).isTrue();
    assertThat(shot.getX()).as("at its aim").isEqualTo(TOWER_X);
    assertThat(shot.getY()).isEqualTo(TOWER_Y);
    assertThat(shot.getZ()).isZero();
    assertThat(battle.getHolder().entities()).doesNotContain(shot);
    assertThat(battle.getHolder().entities())
        .noneMatch(entity -> entity instanceof ProjectileEntity);
  }

  @Test
  @DisplayName("a homing shot whose target left flies on to where it stood and lands on nothing")
  void aHomingShotWhoseTargetLeftLandsOnNothing(@TempDir Path folder) throws IOException {
    Scene scene = scene(folder);
    Battle battle = scene.battle();
    CharacterEntity musketeer = scene.musketeer();
    int launch = scene.stepToFirstLaunch();
    scene.stepTo(launch + 3);
    ProjectileEntity shot = (ProjectileEntity) battle.getHolder().entities().get(0);
    TowerEntity tower = BattleTowers.towerNamed(battle, PRINCESS_TOWER);
    assertThat(shot.getTarget()).isSameAs(tower);

    // The tower is destroyed from outside, between two steps; the next step's opening cleanup
    // removes it and tells the shot, which keeps its aim on the tower's last position.
    scene.match().getWorld().dealDamage(tower.getTargetView(), 100000, 0, 1);
    battle.step();
    assertThat(battle.getHolder().entities()).doesNotContain(tower);
    assertThat(shot.getTarget()).as("the target is forgotten").isNull();
    assertThat(shot.getOwner()).as("the owner stands").isSameAs(musketeer);
    assertThat(shot.getAimX()).isEqualTo(TOWER_X);
    assertThat(shot.getAimY()).isEqualTo(TOWER_Y);
    assertThat(shot.getAimZ()).isZero();
    assertThat(musketeer.getUnit().targeting().getReference()).isNull();
    assertThat(musketeer.getView().getState()).isEqualTo(GridEntityState.ATTACKING);

    // The shot flies the rest of the way as the rows give it, arrives and impacts on nothing: no
    // damage is dealt and it leaves the holder in that tick's cleanup.
    Flight flight = new Flight();
    scene.stepTo(flight.arrival(launch) + 1);
    assertThat(scene.recording().positions)
        .containsSubsequence(
            flight.position(launch, FIRST_SHOT, 3),
            flight.position(launch, FIRST_SHOT, 4),
            flight.position(launch, FIRST_SHOT, 5));
    assertThat(scene.recording().impacts).isEmpty();
    assertThat(shot.isReleased()).isTrue();
    assertThat(shot.getX()).isEqualTo(TOWER_X);
    assertThat(shot.getY()).isEqualTo(TOWER_Y);
    assertThat(battle.getHolder().entities()).doesNotContain(shot);
  }
}
