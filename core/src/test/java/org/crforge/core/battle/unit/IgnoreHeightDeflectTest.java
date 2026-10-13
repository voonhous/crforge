/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.battle.Shipped.actionNames;
import static org.crforge.core.battle.Shipped.number;
import static org.crforge.core.battle.Shipped.row;
import static org.crforge.core.battle.Shipped.text;
import static org.crforge.core.battle.Shipped.unitRow;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.BattleTowers;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Royal Giant's shot at a crown tower past the Monk's Deflect. Its row is measured on the
 * ground plane in the deflection pass: the shot flies down from its constant height, but the pass
 * leaves the height out, so the Deflect's radius reaches it as far as if it flew on the ground,
 * further than with its height. Turned, it is sent back at the Royal Giant, its root owner, for the
 * Monk's side, as any shot with a root. The evolved Royal Giant's shot has a body, whose radius
 * widens the reach; a body on a shot that does not fly to a point is read by nothing else in the
 * deflection.
 */
class IgnoreHeightDeflectTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** Long enough for the Royal Giant to deploy and fire twice. */
  private static final int TICKS = 200;

  /** The Monk's side, whose left princess tower the Royal Giant shoots at. */
  private static final int MONK_SIDE = 0;

  private static final int GIANT_SIDE = 1;

  private static final String TOWER = "PrincessTower_0_1";

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

  /** The Deflect's radius. */
  private static final int DEFLECT_RADIUS = number(row("area_effect_objects", DEFLECT), "Radius");

  /** One deflection as the observers are told of it. */
  private record Deflection(ProjectileEntity projectile, WorldEntity source) {}

  /** A battle with the Monk and a Royal Giant held still on one line, and what flew and turned. */
  private record Scene(
      Standard1v1Battle match,
      CharacterEntity giant,
      List<Deflection> deflections,
      List<ProjectileEntity> launched) {

    void step() {
      match.getBattle().step();
    }

    /** Steps until the giant has fired twice, or a shot of it is deflected. */
    void run() {
      for (int i = 0; i < TICKS && deflections.isEmpty() && shots() < 2; i++) {
        step();
      }
    }

    long shots() {
      return launched.stream().filter(p -> p.getRoot() == giant).count();
    }
  }

  /**
   * Side 0's Monk standing a lateral offset beside the line from its left princess tower out along
   * the length, its Deflect cast, then side 1's Royal Giant (or its evolved form) held still on
   * that line, in range of the tower, so its shots fly past the Monk.
   */
  private static Scene scene(String giantUnit, int lateral) {
    GameTables tables = GameData.tables();
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, true);
    TowerEntity tower = BattleTowers.towerNamed(match.getBattle(), TOWER);
    int tx = tower.getView().getX();
    int ty = tower.getView().getY();
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
                deflections.add(new Deflection(projectile, source));
              }
            });
    CharacterEntity monk =
        match.deploy(0, records.unit("Monk"), LEVEL, MONK_SIDE, tx + lateral, ty + 3000, "monk");
    int steps = 0;
    while (monk.getView().getState() == GridEntityState.DEPLOYING
        || monk.getView().getState() == GridEntityState.WAITING_TO_DEPLOY
        || monk.getId() == 0) {
      match.getBattle().step();
      assertThat(++steps).as("the Monk deploys").isLessThan(TICKS);
    }
    monk.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    monk.requestAbility();
    while (!deflectAlive(match)) {
      match.getBattle().step();
      assertThat(++steps).as("the Monk casts its Deflect").isLessThan(TICKS);
    }
    int now = match.getBattle().getTick() + 1;
    CharacterEntity giant =
        match.deploy(now, records.unit(giantUnit), LEVEL, GIANT_SIDE, tx, ty + 6500, "giant");
    match.getBattle().step();
    giant.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    return new Scene(match, giant, deflections, launched);
  }

  private static boolean deflectAlive(Standard1v1Battle match) {
    for (BattleEntity entity : match.getWorld().getHolder().entities()) {
      if (entity instanceof AreaEffectEntity area && area.getData().name().equals(DEFLECT)) {
        return true;
      }
    }
    return false;
  }

  /** The shot's row is one the deflection pass measures on the ground plane. */
  private static void assertIgnoresHeight(ProjectileEntity shot) {
    assertThat(shot.getData().deflectBehaviour() & ProjectileData.IGNORE_HEIGHT)
        .as("the shot is measured on the ground plane")
        .isNotZero();
  }

  @Test
  @DisplayName(
      "a Royal Giant's shot flying over the Monk's Deflect is turned back at the Royal Giant")
  void royalGiantShotOverTheDeflectIsSentBack() {
    Scene scene = scene("RoyalGiant", 0);
    scene.run();
    assertThat(scene.deflections()).as("a shot is deflected").isNotEmpty();
    Deflection deflection = scene.deflections().get(0);
    ProjectileEntity shot = deflection.projectile();
    assertThat(shot.getData().name()).isEqualTo(text(unitRow("RoyalGiant"), "Projectile"));
    assertIgnoresHeight(shot);
    assertThat(deflection.source()).isSameAs(scene.giant());
    assertThat(shot.side()).isEqualTo(MONK_SIDE);
    assertThat(shot.getTarget()).isSameAs(scene.giant());
  }

  @Test
  @DisplayName("an evolved Royal Giant's shot is reached by the Deflect across its body's radius")
  void evolvedShotIsReachedAcrossItsBody() {
    // Beside the line by more than the Deflect's radius, less than that plus the body's: only on
    // the ground plane does the Deflect reach the evolved shot there.
    int body =
        number(
            row("projectiles", text(unitRow("RoyalGiant_EV1"), "Projectile")), "ProjectileRadius");
    assertThat(body).as("the evolved shot has a body").isPositive();
    int lateral = DEFLECT_RADIUS + body / 2;

    Scene plain = scene("RoyalGiant", lateral);
    plain.run();
    assertThat(plain.deflections()).as("the plain shot, without a body, passes by").isEmpty();
    assertThat(plain.shots()).isEqualTo(2);

    Scene evolved = scene("RoyalGiant_EV1", lateral);
    evolved.run();
    assertThat(evolved.deflections()).as("the evolved shot is deflected").isNotEmpty();
    Deflection deflection = evolved.deflections().get(0);
    assertIgnoresHeight(deflection.projectile());
    assertThat(deflection.source()).isSameAs(evolved.giant());
    assertThat(deflection.projectile().side()).isEqualTo(MONK_SIDE);
  }
}
