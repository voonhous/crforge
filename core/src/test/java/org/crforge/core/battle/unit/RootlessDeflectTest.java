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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A shot reaching the Monk's Deflect after its shooter has left, so it has no root owner to be sent
 * back at. The deflection does not send it back: the Monk, the Deflect's parent, takes the shot's
 * damage, the shot is turned at its start with no target for the Monk's side, and its flight ends
 * there - it leaves at the next cleanup without landing on anything.
 */
class RootlessDeflectTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** Long enough for every step of the scene. */
  private static final int TICKS = 200;

  private static final int MONK_SIDE = 0;

  private static final int SHOOTER_SIDE = 1;

  /** Where the Monk stands, in its own half, out of every crown tower's reach. */
  private static final int MONK_X = 9000;

  private static final int MONK_Y = 12000;

  /** How far along the length the shooter stands from the Monk, within its range. */
  private static final int GAP = 3000;

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
      "a Musketeer's shot deflected after the Musketeer has left is finished, the Monk hit by it")
  void shotWithoutItsShooterIsFinished() {
    GameTables tables = GameData.tables();
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, true);
    BattleWorld world = match.getWorld();
    List<Deflection> deflections = new ArrayList<>();
    List<ProjectileEntity> launched = new ArrayList<>();
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

    // The Monk deploys, held still, and casts its Deflect.
    CharacterEntity monk =
        match.deploy(0, records.unit("Monk"), LEVEL, MONK_SIDE, MONK_X, MONK_Y, "monk");
    int steps = 0;
    while (monk.getView().getState() == GridEntityState.DEPLOYING
        || monk.getView().getState() == GridEntityState.WAITING_TO_DEPLOY
        || monk.getId() == 0) {
      match.getBattle().step();
      assertThat(++steps).as("the Monk deploys").isLessThan(TICKS);
    }
    monk.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    monk.requestAbility();
    while (!deflectAlive(world)) {
      match.getBattle().step();
      assertThat(++steps).as("the Monk casts its Deflect").isLessThan(TICKS);
    }

    // A Musketeer of the other side, held still in range, fires at the Monk and is killed as its
    // first shot leaves, so the shot flies on without its shooter.
    int now = match.getBattle().getTick() + 1;
    CharacterEntity musketeer =
        match.deploy(
            now, records.unit("Musketeer"), LEVEL, SHOOTER_SIDE, MONK_X, MONK_Y + GAP, "musketeer");
    match.getBattle().step();
    musketeer.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    ProjectileEntity shot = null;
    while (shot == null) {
      match.getBattle().step();
      assertThat(++steps).as("the Musketeer fires").isLessThan(TICKS);
      shot = launched.stream().filter(p -> p.getRoot() == musketeer).findFirst().orElse(null);
    }
    assertThat(shot.getTarget()).as("the shot is aimed at the Monk").isSameAs(monk);
    world.getHits().kill(musketeer, null);
    match.getBattle().step();
    assertThat(world.getHolder().entities()).as("the Musketeer has left").doesNotContain(musketeer);
    assertThat(shot.getRoot()).as("the shot has no root owner").isNull();

    // The shot reaches the Deflect: turned for the Monk's side with nothing to be sent at.
    int monkBefore = monk.getHitPoints().getHitPoints() + monk.getHitPoints().getShield();
    while (deflections.isEmpty()) {
      match.getBattle().step();
      assertThat(++steps).as("the shot reaches the Deflect").isLessThan(TICKS);
    }
    Deflection deflection = deflections.get(0);
    assertThat(deflection.projectile()).isSameAs(shot);
    assertThat(deflection.source()).as("sent at no source").isNull();
    assertThat(shot.side()).as("turned for the Monk's side").isEqualTo(MONK_SIDE);
    assertThat(shot.getTarget()).as("with no target").isNull();
    assertThat(shot.isReleased()).as("its flight ended").isTrue();
    assertThat(monk.getHitPoints().getHitPoints() + monk.getHitPoints().getShield())
        .as("the Monk takes the shot's damage")
        .isLessThan(monkBefore);

    // It leaves at the next cleanup without landing anywhere.
    match.getBattle().step();
    assertThat(world.getHolder().entities()).as("the shot has left").doesNotContain(shot);
    assertThat(deflections).as("deflected once").hasSize(1);
  }

  private static boolean deflectAlive(BattleWorld world) {
    for (BattleEntity entity : world.getHolder().entities()) {
      if (entity instanceof AreaEffectEntity area && area.getData().name().equals(DEFLECT)) {
        return true;
      }
    }
    return false;
  }
}
