/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.battle.Shipped.actionNames;
import static org.crforge.core.battle.Shipped.fields;
import static org.crforge.core.battle.Shipped.row;
import static org.crforge.core.battle.Shipped.text;
import static org.crforge.core.battle.Shipped.unitRow;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.LevelScaling;
import org.crforge.core.pathfinding.combat.ScalingGlobals;
import org.crforge.core.pathfinding.combat.ScalingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Firecracker's rocket and its sparks reaching the Monk's Deflect. The rocket is sent back at
 * the Firecracker as any shot is, and its action on the deflector runs on the Monk, the Deflect's
 * parent, the rocket its cause: the damage it deals lands on the step after the turn, scaled by the
 * rocket's own level. A spark, which inverts its direction, rolls back the way it came at its
 * constant height.
 */
class FirecrackerDeflectTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** Long enough for every step of the scene. */
  private static final int TICKS = 400;

  private static final int MONK_SIDE = 0;

  private static final int SHOOTER_SIDE = 1;

  /** Where the Monk stands, in its own half, out of every crown tower's reach. */
  private static final int MONK_X = 9000;

  private static final int MONK_Y = 12000;

  /** How far along the length the Firecracker stands from the Monk, within its range. */
  private static final int GAP = 4000;

  /** The area effect the Monk's ability spawns as it activates: its Deflect. */
  private static final String DEFLECT =
      actionNames(
              text(
                  row("character_abilities", text(unitRow("Monk"), "Ability")),
                  "OnActivationAction"),
              "SubActions")
          .stream()
          .filter(action -> "AreaEffectType".equals(text(action, "SpawnType")))
          .map(action -> text(action, "SpawnData"))
          .findFirst()
          .orElseThrow();

  /** One deflection as the observers are told of it. */
  private record Deflection(int tick, ProjectileEntity projectile, WorldEntity source) {}

  @Test
  @DisplayName(
      "a deflected Firecracker rocket is sent back and its action on the deflector hurts the Monk")
  void rocketActionHurtsTheMonk() {
    Scene scene = new Scene();
    CharacterEntity monk = scene.monk();
    CharacterEntity firecracker = scene.shooter("Firecracker", MONK_Y + GAP);
    // The Deflect is cast as the Firecracker takes the field, so it stands when the rocket comes.
    scene.castDeflect(monk);
    ProjectileEntity rocket = scene.firstShotOf(firecracker);
    assertThat(rocket.getData().damage()).as("the rocket itself deals nothing").isZero();
    assertThat(rocket.getData().actionOnDeflector()).isNotNull();

    // The step of the turn: the rocket's own damage is nothing, and the action waits for the next
    // pending pass.
    int before = hitPoints(monk);
    while (scene.deflections.stream().noneMatch(d -> d.projectile() == rocket)) {
      before = hitPoints(monk);
      scene.step();
    }
    Deflection deflection =
        scene.deflections.stream().filter(d -> d.projectile() == rocket).findFirst().orElseThrow();
    assertThat(deflection.source()).as("sent back at the Firecracker").isSameAs(firecracker);
    assertThat(rocket.side()).as("turned for the Monk's side").isEqualTo(MONK_SIDE);
    assertThat(hitPoints(monk)).as("nothing lands on the step of the turn").isEqualTo(before);
    // The damage at the rocket's level, through the Monk's protection as it stands: once as the
    // drain works out the amount and once more as the hit lands, as for every damage-taking action.
    int scaled =
        LevelScaling.scale(
            ScalingGlobals.standard(),
            actionDamage(rocket.getData().actionOnDeflector()),
            rocket.packedLevel(),
            ScalingMode.CARD_DAMAGE,
            rocket.getData().rarity());
    int drained = Math.max(monk.getBuffs().damageReduction(scaled), 0);
    int expected = Math.max(monk.getBuffs().damageReduction(drained), 1);
    before = hitPoints(monk);
    scene.step();
    assertThat(expected).as("the action deals damage").isPositive();
    assertThat(before - hitPoints(monk))
        .as("the action's damage at the rocket's level, through the Monk's protection")
        .isEqualTo(expected);
  }

  @Test
  @DisplayName(
      "a rocket deflected after its Firecracker has left leaves before its action on the deflector"
          + " lands, and the action's damage lands unscaled on the Monk")
  void rocketActionFromARocketThatLeftLandsUnscaled() {
    Scene scene = new Scene();
    CharacterEntity monk = scene.monk();
    CharacterEntity firecracker = scene.shooter("Firecracker", MONK_Y + GAP);
    scene.castDeflect(monk);
    ProjectileEntity rocket = scene.firstShotOf(firecracker);
    // The Firecracker is killed as its rocket flies, so the rocket reaches the Deflect with no one
    // to be sent back at: its flight ends at the turn and it leaves at the next cleanup, before the
    // action it runs on the deflector is drained.
    scene.world.getHits().kill(firecracker, null);
    scene.step();
    assertThat(scene.world.getHolder().entities())
        .as("the Firecracker has left")
        .doesNotContain(firecracker);
    while (scene.deflections.stream().noneMatch(d -> d.projectile() == rocket)) {
      scene.step();
    }
    assertThat(
            scene.deflections.stream()
                .filter(d -> d.projectile() == rocket)
                .findFirst()
                .orElseThrow()
                .source())
        .as("sent back at no one")
        .isNull();
    int base = actionDamage(rocket.getData().actionOnDeflector());
    int scaled =
        LevelScaling.scale(
            ScalingGlobals.standard(),
            base,
            rocket.packedLevel(),
            ScalingMode.CARD_DAMAGE,
            rocket.getData().rarity());
    assertThat(scaled).as("the rocket's level would change the amount").isNotEqualTo(base);
    // With its source gone the drain has no level to scale by and no percentages: the row's
    // amount, through the Monk's protection as the drain works it out and once more as it lands.
    int drained = Math.max(monk.getBuffs().damageReduction(base), 0);
    int expected = Math.max(monk.getBuffs().damageReduction(drained), 1);
    int before = hitPoints(monk);
    scene.step();
    assertThat(scene.world.getHolder().entities()).as("the rocket has left").doesNotContain(rocket);
    assertThat(before - hitPoints(monk))
        .as("the action's damage, unscaled, through the Monk's protection")
        .isEqualTo(expected);
  }

  @Test
  @DisplayName("a Firecracker's spark that reaches the Deflect rolls back the way it came")
  void sparkRollsBack() {
    Scene scene = new Scene();
    CharacterEntity monk = scene.monk();
    // A Knight of the Monk's side, held just out of the Deflect's reach toward the Firecracker,
    // takes the rocket; its sparks fly on beyond it into the Deflect.
    CharacterEntity knight =
        scene.match.deploy(
            scene.match.getBattle().getTick() + 1,
            scene.records.unit("Knight"),
            LEVEL,
            MONK_SIDE,
            MONK_X,
            MONK_Y + 2500,
            "knight");
    scene.step();
    knight.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    // The Monk asks for its Deflect as the Firecracker takes the field, so it stands from before
    // the
    // rocket lands until after its sparks have flown into it.
    monk.requestAbility();
    CharacterEntity firecracker = scene.shooter("Firecracker", MONK_Y + 2500 + GAP);
    ProjectileEntity rocket = scene.firstShotOf(firecracker);
    assertThat(rocket.getTarget()).as("the rocket is aimed at the Knight").isSameAs(knight);
    assertThat(scene.deflectAlive()).as("the Deflect stands as the rocket leaves").isTrue();
    String sparkRow = rocket.getData().spawnProjectile();
    while (scene.deflections.stream()
        .noneMatch(d -> d.projectile().getData().name().equals(sparkRow))) {
      scene.step();
    }
    ProjectileEntity spark =
        scene.deflections.stream()
            .filter(d -> d.projectile().getData().name().equals(sparkRow))
            .findFirst()
            .orElseThrow()
            .projectile();
    assertThat(spark.getData().constantHeight())
        .as("a spark flies at a constant height")
        .isPositive();
    assertThat(spark.side()).as("turned for the Monk's side").isEqualTo(MONK_SIDE);
    assertThat(spark.getTarget()).as("with no target").isNull();
    assertThat(spark.getStartY()).as("it starts again where it was turned").isEqualTo(spark.getY());
    assertThat(spark.getAimY())
        .as("it rolls back toward the Firecracker, away from the Monk")
        .isGreaterThan(spark.getY());
    assertThat(spark.getZ())
        .as("at its constant height")
        .isEqualTo(spark.getData().constantHeight());
  }

  private static int hitPoints(WorldEntity entity) {
    return entity.getHitPoints().getHitPoints() + entity.getHitPoints().getShield();
  }

  /** The base damage of a damage-taking action row, read from the tables. */
  private static int actionDamage(String action) {
    return fields(action).path("Damage").path("BaseDamage").asInt();
  }

  /** A Ladder battle with an observer of the launches and deflections. */
  private static final class Scene {

    final GameTables tables = GameData.tables();

    final BattleRecords records = new BattleRecords(tables);

    final Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, true);

    final BattleWorld world = match.getWorld();

    final List<Deflection> deflections = new ArrayList<>();

    final List<ProjectileEntity> launched = new ArrayList<>();

    int steps;

    Scene() {
      world.addObserver(
          new WorldObserver() {
            @Override
            public void projectileLaunched(int tick, ProjectileEntity projectile) {
              launched.add(projectile);
            }

            @Override
            public void projectileDeflected(
                int tick,
                AreaEffectEntity deflector,
                ProjectileEntity projectile,
                WorldEntity parent,
                WorldEntity source) {
              deflections.add(new Deflection(tick, projectile, source));
            }
          });
    }

    void step() {
      match.getBattle().step();
      assertThat(++steps).as("the scene ends in time").isLessThan(TICKS);
    }

    /** The Monk, deployed and held still. */
    CharacterEntity monk() {
      CharacterEntity monk =
          match.deploy(0, records.unit("Monk"), LEVEL, MONK_SIDE, MONK_X, MONK_Y, "monk");
      while (monk.getView().getState() == GridEntityState.DEPLOYING
          || monk.getView().getState() == GridEntityState.WAITING_TO_DEPLOY
          || monk.getId() == 0) {
        step();
      }
      monk.setActive(CharacterEntity.MOVEMENT_SLOT, false);
      return monk;
    }

    /** A unit of the other side, held still at a point along the Monk's line. */
    CharacterEntity shooter(String row, int y) {
      int now = match.getBattle().getTick() + 1;
      CharacterEntity shooter =
          match.deploy(now, records.unit(row), LEVEL, SHOOTER_SIDE, MONK_X, y, "shooter");
      step();
      shooter.setActive(CharacterEntity.MOVEMENT_SLOT, false);
      return shooter;
    }

    ProjectileEntity firstShotOf(WorldEntity shooter) {
      while (true) {
        for (ProjectileEntity projectile : launched) {
          if (projectile.getRoot() == shooter) {
            return projectile;
          }
        }
        step();
      }
    }

    void castDeflect(CharacterEntity monk) {
      monk.requestAbility();
      while (!deflectAlive()) {
        step();
      }
    }

    boolean deflectAlive() {
      for (BattleEntity entity : world.getHolder().entities()) {
        if (entity instanceof AreaEffectEntity area && area.getData().name().equals(DEFLECT)) {
          return true;
        }
      }
      return false;
    }
  }
}
