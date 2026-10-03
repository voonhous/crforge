package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.core.pathfinding.move.MovementState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Mega Knight's card play where the reference runs do not take it: its appearance cast from
 * either side, its push on deploy finding nobody in the command pass, and the push of one that
 * waits its turn and so enters the deploying state inside the tick, with the index filled.
 */
class BattleMegaKnightTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** A tick after every unit placed on the first one has deployed. */
  private static final int PLAY_TICK = 25;

  /** A battle with the towers passive, its deploy pushes, pushbacks and damage logged. */
  private static final class Scene {
    final Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    final List<String> deployPushes = new ArrayList<>();
    final List<String> hiddenFound = new ArrayList<>();
    final List<String> pushbacks = new ArrayList<>();
    final List<String> damage = new ArrayList<>();
    private int units;

    Scene() {
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
      "the card casts its appearance before it makes its unit: from the point less 7000 along the"
          + " length, at 4200, whichever side plays")
  void theAppearanceStartsShortOfThePointOnEitherSide() {
    for (int side = 0; side < 2; side++) {
      Scene scene = new Scene();
      int y = side == 0 ? 11000 : 21000;
      scene.match.play(PLAY_TICK, GameData.card("MegaKnight"), LEVEL, side, 14500, y, "MK");
      scene.stepThrough(PLAY_TICK);
      Standard1v1Battle.Play play = scene.match.getPlays().get(0);
      assertThat(play.units()).hasSize(1);
      List<ProjectileEntity> shots = scene.projectiles();
      assertThat(shots).hasSize(1);
      ProjectileEntity shot = shots.get(0);
      assertThat(shot.getData().name()).isEqualTo("MegaKnightAppear");
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
    scene.stepThrough(PLAY_TICK + 6);

    // In the command pass the world's tick is still the last one run.
    assertThat(scene.deployPushes).containsExactly((PLAY_TICK - 1) + " MK_0 1000 1000 [] []");
    // The appearance lands six ticks after the play, on the placed point.
    assertThat(scene.pushbacks)
        .containsExactly((PLAY_TICK + 6) + " " + knight.name() + " true 3499 11500 225");
    assertThat(scene.damage)
        .containsExactlyInAnyOrder(
            (PLAY_TICK + 6) + " " + knight.name() + " 430",
            (PLAY_TICK + 6) + " " + giant.name() + " 430");
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
    assertThat(scene.deployPushes.get(0)).isEqualTo((PLAY_TICK - 1) + " MK_0 1000 1000 [] []");
    String waiting = scene.deployPushes.get(1);
    assertThat(waiting).startsWith(PLAY_TICK + " MK_1 1000 1000 [");
    assertThat(waiting)
        .contains(knight.name(), giant.name(), friend.name(), minion.name(), cannon.name())
        .doesNotContain(beside.name(), far.name());
    assertThat(waiting.substring(waiting.lastIndexOf('[')))
        .isIn(
            "[%s, %s]".formatted(knight.name(), giant.name()),
            "[%s, %s]".formatted(giant.name(), knight.name()));
    // Each asked away from the Mega Knight by the whole distance: a budget of 225.
    assertThat(scene.pushbacks)
        .containsExactlyInAnyOrder(
            PLAY_TICK + " " + knight.name() + " true 2749 11500 225",
            PLAY_TICK + " " + giant.name() + " true 2749 11500 225");
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
    Scene scene = new Scene();
    // A Miner played on 0 still tunnels past the waiting Mega Knight on 27; it surfaces beside it
    // on 28, where it is pushed.
    int played = 27;
    scene.match.play(0, GameData.card("Miner"), LEVEL, 1, 2749, 11300, "Miner");
    scene.match.play(played, twoWaiting(50), LEVEL, 0, 3500, 11000, "MK");
    scene.stepThrough(played);

    CharacterEntity miner = scene.match.getPlays().get(0).units().get(0);
    assertThat(scene.hiddenFound).as("tunnelling as it is found").containsExactly(miner.name());
    assertThat(scene.deployPushes).hasSize(2);
    String waiting = scene.deployPushes.get(1);
    assertThat(waiting).startsWith(played + " MK_1 1000 1000 [").contains(miner.name());
    assertThat(waiting).endsWith(" []");
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
