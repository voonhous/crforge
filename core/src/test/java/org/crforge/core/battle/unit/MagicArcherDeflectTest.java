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
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.math.FixedMath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Magic Archer's arrow reaching the Monk's Deflect. The arrow flies to a point with a body,
 * does not stop at collisions and has no deflection behaviour of its own: the pass over its body's
 * cells turns it around, sent back at the Magic Archer for the Monk's side, and its relaunch
 * stretches its aim to its range from where it stands toward the Magic Archer, forgetting the
 * target, as its first launch stretches it from its launcher. It then flies back through the Magic
 * Archer and hits it on its way.
 */
class MagicArcherDeflectTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** Long enough for every step of the scene. */
  private static final int TICKS = 400;

  private static final int MONK_SIDE = 0;

  private static final int SHOOTER_SIDE = 1;

  /** Where the Monk stands, in its own half, out of every crown tower's reach. */
  private static final int MONK_X = 9000;

  private static final int MONK_Y = 12000;

  /** How far along the length the Magic Archer stands from the Monk, within its range. */
  private static final int GAP = 6000;

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

  /**
   * One deflection as the observers are told of it, with what the turned arrow holds right then.
   */
  private record Deflection(
      ProjectileEntity projectile,
      WorldEntity source,
      int deflections,
      int x,
      int y,
      int aimX,
      int aimY,
      WorldEntity target) {}

  @Test
  @DisplayName("the Magic Archer's arrow is turned back at it, aimed to its range from where it is")
  void arrowTurnedToItsRange() {
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
            deflections.add(
                new Deflection(
                    projectile,
                    source,
                    projectile.getDeflections(),
                    projectile.getX(),
                    projectile.getY(),
                    projectile.getAimX(),
                    projectile.getAimY(),
                    projectile.getTarget()));
          }
        });

    // The Monk deploys, held still.
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

    // A Magic Archer of the other side, held still in range, shoots at the Monk.
    int now = match.getBattle().getTick() + 1;
    CharacterEntity archer =
        match.deploy(
            now, records.unit("EliteArcher"), LEVEL, SHOOTER_SIDE, MONK_X, MONK_Y + GAP, "archer");
    match.getBattle().step();
    archer.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    // The Monk casts its Deflect before the first arrow.
    monk.requestAbility();
    while (!deflectAlive(world)) {
      match.getBattle().step();
      assertThat(++steps).as("the Monk casts its Deflect").isLessThan(TICKS);
    }
    int archerHp = archer.getHitPoints().getHitPoints();
    while (deflections.isEmpty()) {
      match.getBattle().step();
      assertThat(++steps).as("an arrow reaches the Deflect").isLessThan(TICKS);
    }

    Deflection deflection = deflections.get(0);
    ProjectileEntity arrow = deflection.projectile();
    ProjectileData data = arrow.getData();
    assertThat(launched).as("an arrow of the Magic Archer").contains(arrow);
    assertThat(deflection.deflections()).as("turned once").isEqualTo(1);
    assertThat(deflection.source()).as("sent back at the Magic Archer").isSameAs(archer);
    assertThat(arrow.side()).as("for the Monk's side").isEqualTo(MONK_SIDE);
    assertThat(deflection.target()).as("the stretch forgets the target").isNull();
    // Its range from where it was turned, toward the Magic Archer.
    int[] vec = {
      archer.getView().getX() - deflection.x(), archer.getView().getY() - deflection.y()
    };
    FixedMath.normalize(vec, data.projectileRange());
    assertThat(deflection.aimX())
        .as("aimed to its range from where it stands")
        .isEqualTo(deflection.x() + vec[0]);
    assertThat(deflection.aimY()).isEqualTo(deflection.y() + vec[1]);

    // Turned, it flies back through the Magic Archer and hits it on its way.
    while (!arrow.isReleased() && archer.getHitPoints().getHitPoints() == archerHp) {
      match.getBattle().step();
      assertThat(++steps).as("the arrow flies back").isLessThan(TICKS);
    }
    assertThat(archer.getHitPoints().getHitPoints())
        .as("the turned arrow hits the Magic Archer")
        .isLessThan(archerHp);
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
