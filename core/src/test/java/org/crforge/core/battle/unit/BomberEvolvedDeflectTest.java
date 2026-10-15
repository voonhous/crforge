/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.battle.Shipped.actionNames;
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
import org.crforge.core.pathfinding.math.FixedMath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The evolved Bomber's bouncing bomb reaching the Monk's Deflect. The bomb's impact spawns the next
 * bounce, which spawns the one after it: a chain whose links count down from the row's spawn chain.
 * A bomb turned by the Deflect is sent back at the Bomber for the Monk's side as any shot is, and,
 * landing there, bounces on for the Monk's side, the Monk its root, along the line it was turned
 * on. A bounce turned by the Deflect, which has a range and no body, is aimed its range from where
 * it stands toward the Bomber, forgetting any target, and its relaunch counts its chain from the
 * row's spawn chain again, as every launch does.
 */
class BomberEvolvedDeflectTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** Long enough for every step of the scene. */
  private static final int TICKS = 400;

  private static final int MONK_SIDE = 0;

  private static final int SHOOTER_SIDE = 1;

  /** Where the Monk stands, in its own half, out of every crown tower's reach. */
  private static final int MONK_X = 9000;

  private static final int MONK_Y = 12000;

  /** How far along the length the Bomber stands from what it throws at, within its range. */
  private static final int GAP = 4000;

  /**
   * How far along the length a Knight of the Monk's side stands from the Monk, toward the Bomber.
   */
  private static final int KNIGHT_GAP = 2500;

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

  /** One deflection as the observers are told of it, with what the turned projectile then holds. */
  private record Deflection(
      ProjectileEntity projectile,
      WorldEntity source,
      int x,
      int y,
      int aimX,
      int aimY,
      WorldEntity target,
      int spawnChain) {}

  /** A projectile as it stands when launched or first seen. */
  private record Launch(
      ProjectileEntity projectile,
      int side,
      WorldEntity root,
      int startX,
      int startY,
      int aimX,
      int aimY,
      int spawnChain) {}

  @Test
  @DisplayName("a turned evolved Bomber's bomb bounces on from the Bomber for the Monk's side")
  void turnedBombBouncesOn() {
    Scene scene = new Scene();
    CharacterEntity monk = scene.monk();
    CharacterEntity bomber = scene.shooter(MONK_Y + GAP);
    scene.castDeflect(monk);
    ProjectileEntity bomb = scene.firstShotOf(bomber);
    String bounceRow = bomb.getData().spawnProjectile();
    assertThat(bounceRow).as("the bomb spawns its next bounce").isNotNull();
    assertThat(bomb.getSpawnChain()).as("a chain of more than one link").isGreaterThan(1);

    Deflection deflection = scene.deflectionOf(bomb);
    assertThat(deflection.source()).as("sent back at the Bomber").isSameAs(bomber);
    assertThat(bomb.side()).as("turned for the Monk's side").isEqualTo(MONK_SIDE);
    assertThat(deflection.spawnChain())
        .as("the relaunch counts its chain from the row's again")
        .isEqualTo(bomb.getData().spawnChain());

    // Landing at the Bomber, it spawns the next bounce there, for the Monk's side and with the Monk
    // as its root, flying on along the line from where it was turned to the Bomber, one link
    // shorter.
    Launch bounce = scene.firstOfRow(bounceRow);
    assertThat(bounce.side()).as("the bounce is the Monk's side's").isEqualTo(MONK_SIDE);
    assertThat(bounce.root()).as("its root is the Monk").isSameAs(monk);
    assertThat(bounce.startX()).as("it starts where the bomb landed").isEqualTo(bomb.getX());
    assertThat(bounce.startY()).isEqualTo(bomb.getY());
    assertThat(bounce.aimY())
        .as("it flies on away from the Monk, past the Bomber")
        .isGreaterThan(bounce.startY());
    assertThat(bounce.spawnChain()).as("one link shorter").isEqualTo(deflection.spawnChain() - 1);
  }

  @Test
  @DisplayName("a turned bounce is aimed its range from where it stands toward the Bomber")
  void turnedBounceAimedToItsRange() {
    Scene scene = new Scene();
    CharacterEntity monk = scene.monk();
    // A Knight of the Monk's side, held just out of the Deflect's reach toward the Bomber, takes
    // the bomb; its bounce flies on beyond it into the Deflect.
    CharacterEntity knight =
        scene.match.deploy(
            scene.match.getBattle().getTick() + 1,
            scene.records.unit("Knight"),
            LEVEL,
            MONK_SIDE,
            MONK_X,
            MONK_Y + KNIGHT_GAP,
            "knight");
    scene.step();
    knight.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    // The Monk asks for its Deflect as the Bomber takes the field, so it stands from before the
    // bomb lands until after its bounce has flown into it.
    monk.requestAbility();
    CharacterEntity bomber = scene.shooter(MONK_Y + KNIGHT_GAP + GAP);
    ProjectileEntity bomb = scene.firstShotOf(bomber);
    assertThat(bomb.getTarget()).as("the bomb is thrown at the Knight").isSameAs(knight);
    String bounceRow = bomb.getData().spawnProjectile();

    Deflection deflection = scene.deflectionOfRow(bounceRow);
    ProjectileEntity bounce = deflection.projectile();
    assertThat(bounce.getData().projectileRange()).as("a bounce has a range").isPositive();
    assertThat(bounce.getData().projectileRadius()).as("and no body").isZero();
    assertThat(deflection.source()).as("turned back at the Bomber").isSameAs(bomber);
    assertThat(bounce.side()).as("for the Monk's side").isEqualTo(MONK_SIDE);
    assertThat(deflection.target()).as("with no target").isNull();
    // Its range from where it was turned, toward the Bomber.
    int[] vec = {
      bomber.getView().getX() - deflection.x(), bomber.getView().getY() - deflection.y()
    };
    FixedMath.normalize(vec, bounce.getData().projectileRange());
    assertThat(deflection.aimX())
        .as("aimed to its range from where it stands")
        .isEqualTo(deflection.x() + vec[0]);
    assertThat(deflection.aimY()).isEqualTo(deflection.y() + vec[1]);
    assertThat(deflection.spawnChain())
        .as("the relaunch counts its chain from the row's again")
        .isEqualTo(bounce.getData().spawnChain());
  }

  /** A Ladder battle with an observer of the launches and deflections. */
  private static final class Scene {

    final GameTables tables = GameData.tables();

    final BattleRecords records = new BattleRecords(tables);

    final Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, true);

    final BattleWorld world = match.getWorld();

    final List<Deflection> deflections = new ArrayList<>();

    final List<Launch> launched = new ArrayList<>();

    int steps;

    Scene() {
      world.addObserver(
          new WorldObserver() {
            @Override
            public void projectileLaunched(int tick, ProjectileEntity projectile) {
              launched.add(
                  new Launch(
                      projectile,
                      projectile.side(),
                      projectile.getRoot(),
                      projectile.getStartX(),
                      projectile.getStartY(),
                      projectile.getAimX(),
                      projectile.getAimY(),
                      projectile.getSpawnChain()));
            }

            @Override
            public void projectileDeflected(
                int tick,
                AreaEffectEntity deflector,
                ProjectileEntity projectile,
                WorldEntity parent,
                WorldEntity source) {
              deflections.add(
                  new Deflection(
                      projectile,
                      source,
                      projectile.getX(),
                      projectile.getY(),
                      projectile.getAimX(),
                      projectile.getAimY(),
                      projectile.getTarget(),
                      projectile.getSpawnChain()));
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

    /** An evolved Bomber of the other side, held still at a point along the Monk's line. */
    CharacterEntity shooter(int y) {
      int now = match.getBattle().getTick() + 1;
      CharacterEntity shooter =
          match.deploy(now, records.unit("Bomber_EV1"), LEVEL, SHOOTER_SIDE, MONK_X, y, "bomber");
      step();
      shooter.setActive(CharacterEntity.MOVEMENT_SLOT, false);
      return shooter;
    }

    ProjectileEntity firstShotOf(WorldEntity shooter) {
      while (true) {
        for (Launch launch : launched) {
          if (launch.root() == shooter) {
            return launch.projectile();
          }
        }
        step();
      }
    }

    Deflection deflectionOf(ProjectileEntity projectile) {
      while (true) {
        for (Deflection deflection : deflections) {
          if (deflection.projectile() == projectile) {
            return deflection;
          }
        }
        step();
      }
    }

    Deflection deflectionOfRow(String row) {
      while (true) {
        for (Deflection deflection : deflections) {
          if (deflection.projectile().getData().name().equals(row)) {
            return deflection;
          }
        }
        step();
      }
    }

    /**
     * The first projectile of a row in the battle, as it stands when first seen: an impact's spawn
     * is handed to the holder without a launch the observers hear of.
     */
    Launch firstOfRow(String row) {
      while (true) {
        for (BattleEntity entity : world.getHolder().entities()) {
          if (entity instanceof ProjectileEntity projectile
              && projectile.getData().name().equals(row)) {
            return new Launch(
                projectile,
                projectile.side(),
                projectile.getRoot(),
                projectile.getStartX(),
                projectile.getStartY(),
                projectile.getAimX(),
                projectile.getAimY(),
                projectile.getSpawnChain());
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
