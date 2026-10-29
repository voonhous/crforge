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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.BattleTowers;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Crown tower shots at a Monk with its Deflect up. A shot a crown tower fires at the Monk has the
 * tower as its root owner and the Monk as its target: the Deflect turns it around at the tower, a
 * king tower as a princess tower, for the Monk's side. A Deflect on its last step, its countdown at
 * 0, still deflects: the deflection pass passes over only an area effect the cleanup would remove,
 * one whose countdown is below 0.
 */
class TowerShotDeflectTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** Long enough for the Monk to deploy, wake the king, cast and see a shot deflected. */
  private static final int TICKS = 1200;

  /** The Monk's side; the towers that shoot at it are side 1's. */
  private static final int MONK_SIDE = 0;

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

  /** One deflection as the observers are told of it, with the deflector's countdown then. */
  private record Deflection(
      int tick, ProjectileEntity projectile, WorldEntity source, int countdown) {}

  /** A battle with side 0's Monk standing still at a point, and the deflections told. */
  private record Scene(
      Standard1v1Battle match,
      CharacterEntity monk,
      List<Deflection> deflections,
      List<ProjectileEntity> launched) {

    void step() {
      match.getBattle().step();
    }

    int tick() {
      return match.getBattle().getTick();
    }

    TowerEntity tower(String name) {
      return BattleTowers.towerNamed(match.getBattle(), name);
    }

    AreaEffectEntity deflect() {
      for (BattleEntity entity : match.getWorld().getHolder().entities()) {
        if (entity instanceof AreaEffectEntity area && area.getData().name().equals(DEFLECT)) {
          return area;
        }
      }
      return null;
    }
  }

  private static Scene scene(int x, int y, boolean holdStill) {
    GameTables tables = GameData.tables();
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, true);
    List<Deflection> deflections = new ArrayList<>();
    List<ProjectileEntity> launched = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
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
                deflections.add(new Deflection(tick, projectile, source, deflector.getCountdown()));
              }
            });
    CharacterEntity monk = match.deploy(0, records.unit("Monk"), LEVEL, MONK_SIDE, x, y, "monk");
    Scene scene = new Scene(match, monk, deflections, launched);
    int steps = 0;
    while (monk.getView().getState() == GridEntityState.DEPLOYING
        || monk.getView().getState() == GridEntityState.WAITING_TO_DEPLOY
        || monk.getId() == 0) {
      scene.step();
      assertThat(++steps).as("the Monk deploys").isLessThan(TICKS);
    }
    if (holdStill) {
      monk.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    }
    return scene;
  }

  /** The Monk in range of side 1's left princess tower, its Deflect requested as it deploys. */
  private static Scene princessScene() {
    Scene scene = scene(3500, 19000, true);
    scene.monk().requestAbility();
    return scene;
  }

  /** Steps until the first deflection; returns the battle's tick after the step that made it. */
  private static int firstDeflection(Scene scene) {
    while (scene.deflections().isEmpty()) {
      scene.step();
      assertThat(scene.tick()).as("a shot is deflected").isLessThan(TICKS);
    }
    return scene.tick();
  }

  @Test
  @DisplayName(
      "a princess tower's arrow at the Monk is turned around at the tower, for the Monk's side")
  void princessTowerArrowIsSentBackAtTheTower() {
    Scene scene = princessScene();
    firstDeflection(scene);
    Deflection deflection = scene.deflections().get(0);
    TowerEntity tower = scene.tower("PrincessTower_1_1");
    assertThat(deflection.source()).isSameAs(tower);
    assertThat(deflection.projectile().side()).isEqualTo(MONK_SIDE);
    assertThat(deflection.projectile().getTarget()).isSameAs(tower);
  }

  @Test
  @DisplayName(
      "a Deflect on its last step, its countdown at 0, still turns a princess tower's arrow around")
  void deflectOnItsLastStepStillDeflects() {
    // Where the arrow is first turned with the Deflect's whole life.
    int at = firstDeflection(princessScene());

    // The same battle, with the Deflect's life cut so that its countdown is 0 on that tick: its
    // update, which comes first in the tick, takes one step off.
    Scene scene = princessScene();
    while (scene.tick() < at - 1) {
      scene.step();
    }
    AreaEffectEntity deflect = scene.deflect();
    assertThat(deflect).as("the Deflect stands").isNotNull();
    deflect.cutLife(Battle.STEP_MS);
    scene.step();

    assertThat(scene.deflections()).as("the arrow is deflected on that tick").isNotEmpty();
    Deflection deflection = scene.deflections().get(0);
    assertThat(scene.tick()).isEqualTo(at);
    assertThat(deflection.countdown()).as("the Deflect's countdown then").isZero();
    assertThat(deflection.projectile().side()).isEqualTo(MONK_SIDE);
    assertThat(deflection.source()).isSameAs(scene.tower("PrincessTower_1_1"));
  }

  @Test
  @DisplayName(
      "a king tower's shot at the Monk, which has a target, is turned around at the king tower")
  void kingTowerShotIsSentBackAtTheKing() {
    // The Monk walks onto side 1's king tower and wakes it with its hits.
    Scene scene = scene(9000, 25000, false);
    TowerEntity king = scene.tower("KingTower_1_0");
    boolean requested = false;
    Deflection fromKing = null;
    while (fromKing == null) {
      scene.step();
      assertThat(scene.tick()).as("a king tower shot is deflected").isLessThan(TICKS);
      if (!requested) {
        for (ProjectileEntity shot : scene.launched()) {
          if (shot.getRoot() == king) {
            // The king shoots: the Monk casts its Deflect.
            scene.monk().requestAbility();
            requested = true;
            break;
          }
        }
      }
      for (Deflection deflection : scene.deflections()) {
        if (deflection.source() == king) {
          fromKing = deflection;
          break;
        }
      }
    }
    assertThat(fromKing.projectile().side()).isEqualTo(MONK_SIDE);
    assertThat(fromKing.projectile().getTarget()).isSameAs(king);
    assertThat(fromKing.projectile().getDeflections()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "a king tower's shot that has lost its target is sent at the king's crown tower nearest the"
          + " Deflect, here the king tower, as a point with no target")
  void kingTowerShotWithoutATargetIsSentAtTheNearestCrownTower() {
    // The Monk walks onto side 1's king tower and wakes it with its hits; standing there, the king
    // tower is the crown tower of side 1 nearest its Deflect.
    Scene scene = scene(9000, 25000, false);
    TowerEntity king = scene.tower("KingTower_1_0");
    boolean requested = false;
    Set<ProjectileEntity> kingShots = new HashSet<>();
    Deflection fromKing = null;
    while (fromKing == null) {
      scene.step();
      assertThat(scene.tick()).as("a king tower shot is deflected").isLessThan(TICKS);
      for (ProjectileEntity shot : scene.launched()) {
        if (shot.getRoot() == king && shot.getDeflections() == 0) {
          kingShots.add(shot);
          // The king shoots: the Monk casts its Deflect, and every shot of the king loses its
          // target in flight, as one whose target has left does.
          if (!requested) {
            scene.monk().requestAbility();
            requested = true;
          }
          shot.dropTarget();
        }
      }
      for (Deflection deflection : scene.deflections()) {
        if (kingShots.contains(deflection.projectile())) {
          fromKing = deflection;
          break;
        }
      }
    }
    ProjectileEntity shot = fromKing.projectile();
    assertThat(fromKing.source()).as("sent at a point, not back at its source").isNull();
    assertThat(shot.side()).isEqualTo(MONK_SIDE);
    assertThat(shot.getTarget()).isNull();
    assertThat(shot.getRoot()).isSameAs(scene.monk());
    assertThat(shot.getDeflections()).isEqualTo(1);
    // The nearest of side 1's crown towers to the Deflect: the king tower, whose position the shot
    // is aimed at.
    AreaEffectEntity deflect = scene.deflect();
    TowerEntity nearest = null;
    long best = Long.MAX_VALUE;
    for (BattleEntity entity : scene.match().getWorld().getHolder().entities()) {
      if (entity instanceof TowerEntity tower && tower.side() == 1) {
        long dx = deflect.getX() - tower.getView().getX();
        long dy = deflect.getY() - tower.getView().getY();
        if (dx * dx + dy * dy < best) {
          best = dx * dx + dy * dy;
          nearest = tower;
        }
      }
    }
    assertThat(nearest).isSameAs(king);
    assertThat(shot.getAimX()).isEqualTo(king.getView().getX());
    assertThat(shot.getAimY()).isEqualTo(king.getView().getY());
  }
}
