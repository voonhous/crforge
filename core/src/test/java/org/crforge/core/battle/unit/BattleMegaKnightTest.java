package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.core.pathfinding.combat.PackedLevel;
import org.crforge.core.pathfinding.combat.RarityTable;
import org.crforge.core.pathfinding.move.MovementState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Mega Knight's card play where the reference runs do not take it: its appearance cast from
 * either side, its push on deploy finding nobody in the command pass, and the push of one that
 * waits its turn and so enters the deploying state inside the tick, with the index filled.
 */
class BattleMegaKnightTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** A tick after every unit placed on the first one has deployed. */
  private static final int PLAY_TICK = 25;

  /** The Mega Knight's card row. */
  private static final GameRow CARD = Shipped.row("spells_characters", "MegaKnight");

  /** The Mega Knight's unit row. */
  private static final GameRow UNIT = Shipped.unitRow(Shipped.text(CARD, "SummonCharacter"));

  /** The appearance the card casts. */
  private static final GameRow APPEAR =
      Shipped.row("projectiles", Shipped.text(CARD, "Projectile"));

  /** The king tower's collision radius, which the cast of a troop card's projectile scales. */
  private static final int KING_RADIUS =
      Shipped.number(Shipped.unitRow("KingTower"), "CollisionRadius");

  /**
   * How far before the point, along the length, the appearance starts: five times the king's
   * collision radius.
   */
  private static final int APPEAR_BEHIND = 5 * KING_RADIUS;

  /** The push on deploy as the deploy push line writes it: its radius, then its distance. */
  private static final String DEPLOY_PUSH =
      Shipped.number(UNIT, "SpawnPushbackRadius") + " " + Shipped.number(UNIT, "SpawnPushback");

  /** A battle with the towers passive, its deploy pushes, pushbacks and damage logged. */
  private static final class Scene {
    final Standard1v1Battle match;
    final List<String> deployPushes = new ArrayList<>();
    final List<String> hiddenFound = new ArrayList<>();
    final List<String> pushbacks = new ArrayList<>();
    final List<String> damage = new ArrayList<>();
    private int units;

    Scene() {
      this(GameData.tables());
    }

    Scene(GameTables tables) {
      match = new Standard1v1Battle(tables, LEVEL, false);
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void deployPushed(
                    int tick,
                    CharacterEntity unit,
                    int radius,
                    int distance,
                    List<WorldEntity> found,
                    List<WorldEntity> pushed) {
                  found.stream()
                      .filter(WorldEntity::hidden)
                      .forEach(e -> hiddenFound.add(e.name()));
                  deployPushes.add(
                      "%d %s %d %d %s %s"
                          .formatted(
                              tick,
                              unit.name(),
                              radius,
                              distance,
                              found.stream().map(WorldEntity::name).toList(),
                              pushed.stream().map(WorldEntity::name).toList()));
                }

                @Override
                public void projectileImpacted(
                    int tick,
                    ProjectileEntity projectile,
                    WorldEntity target,
                    int amount,
                    DamageResult result) {
                  damage.add(tick + " " + target.name() + " " + amount);
                }

                @Override
                public void pushbackRequested(
                    int tick,
                    WorldEntity unit,
                    boolean started,
                    int fromX,
                    int fromY,
                    MovementState pushback) {
                  pushbacks.add(
                      "%d %s %s %d %d %d"
                          .formatted(
                              tick,
                              unit.name(),
                              started,
                              fromX,
                              fromY,
                              pushback.getPushbackBudget()));
                }
              });
    }

    /**
     * A unit placed under a name of its own, five ticks before the play: still deploying as the
     * play's units deploy, so it stands where it was placed.
     */
    CharacterEntity unit(int side, String row, int x, int y) {
      return match.deploy(
          PLAY_TICK - 5, GameData.unit(row), LEVEL, side, x, y, row + "_" + units++);
    }

    /** Steps the battle through the given tick. */
    void stepThrough(int tick) {
      while (match.getBattle().getTick() <= tick) {
        match.getBattle().step();
      }
    }

    /** The projectiles in the battle. */
    List<ProjectileEntity> projectiles() {
      List<ProjectileEntity> out = new ArrayList<>();
      for (BattleEntity entity : match.getBattle().getHolder().entities()) {
        if (entity instanceof ProjectileEntity projectile) {
          out.add(projectile);
        }
      }
      return out;
    }
  }

  /** The Mega Knight's card with two units, the second waiting its turn by the given stagger. */
  private static DeployCard twoWaiting(int staggerMs) {
    return twoWaiting(GameData.unit("MegaKnight"), staggerMs);
  }

  /** The Mega Knight's card with two of the given unit, the second waiting by the stagger. */
  private static DeployCard twoWaiting(UnitData unit, int staggerMs) {
    DeployCard c = GameData.card("MegaKnight");
    return new DeployCard(
        c.name(),
        unit,
        2,
        c.secondary(),
        c.secondaryCount(),
        c.summonRadius(),
        c.summonWidth(),
        staggerMs,
        c.summonDeployDelaySecondMs(),
        c.canDeployOnEnemySide(),
        c.canPlaceOnBuildings(),
        c.canPlaceOnWater(),
        c.fullLaneDeploy(),
        c.touchdownLimitedDeploy(),
        c.deployWTileMargin(),
        c.deployStartY(),
        c.deployEndY(),
        c.projectile(),
        c.areaEffect(),
        c.searchUnit(),
        c.spellAsDeploy(),
        c.radius(),
        c.multipleProjectiles(),
        c.projectileWaves(),
        c.projectileWaveIntervalMs(),
        c.projectileIntervalMs(),
        c.listed(),
        c.listOffsetsXMirrored(),
        c.group());
  }

  @Test
  @DisplayName(
      "the card casts its appearance before it makes its unit: from the point less five king"
          + " radii along the length, at three, whichever side plays; with a king radius of 1400"
          + " and a speed of 1000, from 7000 short at 4200, its first visit 1000 along and 600"
          + " down")
  void theAppearanceStartsShortOfThePointOnEitherSide(@TempDir Path folder) throws IOException {
    // The king's radius and the appearance's speed written into a copy of their rows, so the
    // start and the first visit are the numbers below.
    GameData.altered(
        folder,
        "buildings",
        rows -> GameData.columns(rows, "KingTower").put("CollisionRadius", 1400));
    GameData.alterLoaded(
        folder,
        "projectiles",
        rows -> GameData.columns(rows, Shipped.text(CARD, "Projectile")).put("Speed", 1000));
    GameTables tables = GameTables.load(folder);
    for (int side = 0; side < 2; side++) {
      Scene scene = new Scene(tables);
      int y = side == 0 ? 11000 : 21000;
      scene.match.play(PLAY_TICK, GameData.card("MegaKnight"), LEVEL, side, 14500, y, "MK");
      scene.stepThrough(PLAY_TICK);
      Standard1v1Battle.Play play = scene.match.getPlays().get(0);
      assertThat(play.units()).hasSize(Shipped.number(CARD, "SummonNumber"));
      List<ProjectileEntity> shots = scene.projectiles();
      assertThat(shots).hasSize(1);
      ProjectileEntity shot = shots.get(0);
      assertThat(shot.getData().name()).isEqualTo(Shipped.text(CARD, "Projectile"));
      assertThat(shot.getOwner().name()).isEqualTo("KingTower_" + side + "_0");
      // One visit of its flight on the play tick: 1000 along its way, 600 down.
      assertThat(new int[] {shot.getX(), shot.getY(), shot.getZ()})
          .as("side %d", side)
          .containsExactly(play.result().x(), play.result().y() - 6000, 3600);
    }
  }

  @Test
  @DisplayName(
      "a card play's push finds nobody, the index being empty in the command pass; its appearance"
          + " then hits and pushes the enemy beside it, but not a Giant, which ignores pushback")
  void aCardPlaysPushFindsNobody() {
    Scene scene = new Scene();
    CharacterEntity knight = scene.unit(1, "Knight", 3500, 12300);
    CharacterEntity giant = scene.unit(1, "Giant", 3500, 10600);
    scene.match.play(PLAY_TICK, GameData.card("MegaKnight"), LEVEL, 0, 3500, 11000, "MK");
    // The appearance's first visit is on the play tick; it lands on the visit that covers the rest
    // of its way.
    int speed = Shipped.number(APPEAR, "Speed");
    int lands = PLAY_TICK + (APPEAR_BEHIND + speed - 1) / speed - 1;
    scene.stepThrough(lands);

    // In the command pass the world's tick is still the last one run: each unit of the play
    // pushes there and finds nobody.
    Standard1v1Battle.Play play = scene.match.getPlays().get(0);
    List<String> commandPass = new ArrayList<>();
    for (int i = 0; i < play.units().size(); i++) {
      commandPass.add((PLAY_TICK - 1) + " MK_" + i + " " + DEPLOY_PUSH + " [] []");
    }
    assertThat(scene.deployPushes).containsExactlyElementsOf(commandPass);
    // The appearance lands on the placed point and pushes the Knight its pushback away.
    assertThat(scene.pushbacks)
        .containsExactly(
            "%d %s true %d %d %d"
                .formatted(
                    lands,
                    knight.name(),
                    play.result().x(),
                    play.result().y(),
                    budget(Shipped.number(APPEAR, "Pushback"))));
    int damage = scaled(Shipped.number(APPEAR, "Damage"), play.units().get(0));
    assertThat(scene.damage)
        .containsExactlyInAnyOrder(
            lands + " " + knight.name() + " " + damage, lands + " " + giant.name() + " " + damage);
  }

  @Test
  @DisplayName(
      "one that waits its turn enters the deploying state inside the tick, the index filled: it"
          + " pushes the ground enemies around it, a Giant too, but not a friend, a flying enemy, a"
          + " building or one out of its reach")
  void aWaitingMegaKnightPushesInsideTheTick() {
    Scene scene = new Scene();
    // The second Mega Knight of the play waits 50 ms at (2749, 11500); the first stands at (4250,
    // 11500), with an enemy beside it that its push in the command pass does not find.
    CharacterEntity beside = scene.unit(1, "Knight", 4250, 12300);
    CharacterEntity knight = scene.unit(1, "Knight", 2749, 12300);
    CharacterEntity giant = scene.unit(1, "Giant", 2749, 10700);
    CharacterEntity friend = scene.unit(0, "Knight", 1950, 11500);
    CharacterEntity minion = scene.unit(1, "Minion", 2000, 12000);
    CharacterEntity cannon = scene.unit(1, "Cannon", 2000, 10800);
    CharacterEntity far = scene.unit(1, "Knight", 2749, 14500);
    scene.match.play(PLAY_TICK, twoWaiting(50), LEVEL, 0, 3500, 11000, "MK");
    scene.stepThrough(PLAY_TICK);

    assertThat(scene.deployPushes).hasSize(2);
    assertThat(scene.deployPushes.get(0))
        .isEqualTo((PLAY_TICK - 1) + " MK_0 " + DEPLOY_PUSH + " [] []");
    String waiting = scene.deployPushes.get(1);
    assertThat(waiting).startsWith(PLAY_TICK + " MK_1 " + DEPLOY_PUSH + " [");
    assertThat(waiting)
        .contains(knight.name(), giant.name(), friend.name(), minion.name(), cannon.name())
        .doesNotContain(beside.name(), far.name());
    assertThat(waiting.substring(waiting.lastIndexOf('[')))
        .isIn(
            "[%s, %s]".formatted(knight.name(), giant.name()),
            "[%s, %s]".formatted(giant.name(), knight.name()));
    // Each asked away from the waiting Mega Knight by the whole distance.
    CharacterEntity second = scene.match.getPlays().get(0).units().get(1);
    String from =
        " true %d %d %d"
            .formatted(
                second.getView().getX(),
                second.getView().getY(),
                budget(Shipped.number(UNIT, "SpawnPushback")));
    assertThat(scene.pushbacks)
        .containsExactlyInAnyOrder(
            PLAY_TICK + " " + knight.name() + from, PLAY_TICK + " " + giant.name() + from);
  }

  @Test
  @DisplayName(
      "a flying enemy is pushed only by a unit that attacks both air and ground; a ground enemy by"
          + " either")
  void aFlyingEnemyNeedsAirAndGround() {
    UnitData row = GameData.unit("MegaKnight");
    for (boolean ground : new boolean[] {true, false}) {
      Scene scene = new Scene();
      CharacterEntity knight = scene.unit(1, "Knight", 2749, 12300);
      CharacterEntity minion = scene.unit(1, "Minion", 2000, 12000);
      UnitData unit = row.toBuilder().attacksAir(true).attacksGround(ground).build();
      scene.match.play(PLAY_TICK, twoWaiting(unit, 50), LEVEL, 0, 3500, 11000, "MK");
      scene.stepThrough(PLAY_TICK);

      String waiting = scene.deployPushes.get(1);
      assertThat(waiting).contains(minion.name());
      String pushed = waiting.substring(waiting.lastIndexOf('['));
      if (ground) {
        assertThat(pushed).contains(knight.name(), minion.name());
      } else {
        assertThat(pushed).isEqualTo("[" + knight.name() + "]");
      }
    }
  }

  @Test
  @DisplayName("a hidden enemy is passed by: a Miner tunnelling past is found and not pushed")
  void aHiddenEnemyIsPassedBy() {
    // A Miner played on 0 still tunnels past the waiting Mega Knight on the tick before it
    // surfaces beside it, where it would be pushed: that tick is found by a battle of the Miner
    // alone.
    Scene alone = new Scene();
    alone.match.play(0, GameData.card("Miner"), LEVEL, 1, 2749, 11300, "Miner");
    alone.stepThrough(0);
    CharacterEntity tunnelling = alone.match.getPlays().get(0).units().get(0);
    while (tunnelling.hidden()) {
      assertThat(alone.match.getBattle().getTick()).isLessThan(200);
      alone.match.getBattle().step();
    }
    int played = alone.match.getBattle().getTick() - 2;
    Scene scene = new Scene();
    scene.match.play(0, GameData.card("Miner"), LEVEL, 1, 2749, 11300, "Miner");
    scene.match.play(played, twoWaiting(50), LEVEL, 0, 3500, 11000, "MK");
    scene.stepThrough(played);

    CharacterEntity miner = scene.match.getPlays().get(0).units().get(0);
    assertThat(scene.hiddenFound).as("tunnelling as it is found").containsExactly(miner.name());
    assertThat(scene.deployPushes).hasSize(2);
    String waiting = scene.deployPushes.get(1);
    assertThat(waiting).startsWith(played + " MK_1 " + DEPLOY_PUSH + " [").contains(miner.name());
    assertThat(waiting).endsWith(" []");
  }

  /**
   * The budget of a pushback asked over a distance: the first step of a run of steps growing by 25
   * whose sum covers the distance.
   */
  private static int budget(int distance) {
    int step = 0;
    int total = 0;
    do {
      step += 25;
      total += step;
    } while (total < distance);
    return step;
  }

  /** A damage of the Mega Knight's card at the level of the unit its play made. */
  private static int scaled(int damage, CharacterEntity megaKnight) {
    int steps = PackedLevel.steps(megaKnight.getPackedLevel());
    return steps == 0 ? damage : damage * RarityTable.LEGENDARY.multiplier(steps - 1) / 100;
  }

  @Test
  @DisplayName(
      "a Mega Knight placed directly starts deploying without the setter's entry: it pushes"
          + " nobody, and casts nothing, a play's cast being the card's")
  void aDirectPlacementPushesNobodyAndCastsNothing() {
    Scene scene = new Scene();
    scene.match.deploy(0, GameData.unit("Knight"), LEVEL, 1, 3500, 10500, "k");
    scene.match.deploy(5, GameData.unit("MegaKnight_EV1"), LEVEL, 0, 3500, 10000, "mk");
    scene.stepThrough(8);

    assertThat(scene.deployPushes).isEmpty();
    assertThat(scene.pushbacks).isEmpty();
    assertThat(scene.projectiles()).isEmpty();
  }
}
