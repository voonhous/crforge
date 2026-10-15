/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A rider's shot turned by the Monk's Deflect: a shot whose root owner rides a carrier, as the
 * Spear Goblins on a Goblin Giant or the Ram Rider on her Ram, is sent back at the carrier, not at
 * the rider: the carrier is its new target, and the damage it registers falls on the carrier.
 */
class RiderShotDeflectTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The Monk's side; the riders are side 1's. */
  private static final int MONK_SIDE = 0;

  /** Where the Monk stands still, in the left lane on its side of the river. */
  private static final int MONK_X = 3500;

  private static final int MONK_Y = 12500;

  /** One deflection as the observers are told of it. */
  private record Deflection(ProjectileEntity projectile, WorldEntity sentAt) {}

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"GoblinGiant", "RamRider"})
  @DisplayName("a rider's shot turned by the Deflect is sent back at the carrier, not the rider")
  void ridersShotIsSentBackAtTheCarrier(String card) {
    GameTables tables = GameData.tables();
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
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
                  WorldEntity sentAt) {
                deflections.add(new Deflection(projectile, sentAt));
              }
            });
    CharacterEntity monk =
        match.deploy(0, records.unit("Monk"), LEVEL, MONK_SIDE, MONK_X, MONK_Y, "monk");
    // The carrier walks down the left lane at the Monk, its rider shooting at it.
    match.play(0, GameData.card(card), LEVEL, 1, MONK_X, MONK_Y + 8000, "rider");

    // The loop guard is a battle's whole length, which the walk and the first shots never near.
    int guard = Shipped.battleTicks();
    boolean requested = false;
    Deflection turned = null;
    while (turned == null) {
      match.getBattle().step();
      assertThat(match.getBattle().getTick()).as("a rider's shot is deflected").isLessThan(guard);
      if (monk.getView().getState() != GridEntityState.DEPLOYING
          && monk.getView().getState() != GridEntityState.WAITING_TO_DEPLOY) {
        monk.setActive(CharacterEntity.MOVEMENT_SLOT, false);
      }
      if (!requested
          && launched.stream()
              .anyMatch(
                  shot ->
                      shot.getRoot() instanceof CharacterEntity rider
                          && rider.getParent() != null)) {
        // A rider shoots: the Monk casts its Deflect.
        monk.requestAbility();
        requested = true;
      }
      for (Deflection deflection : deflections) {
        if (deflection.projectile().getDeflections() == 1) {
          turned = deflection;
          break;
        }
      }
    }

    CharacterEntity carrier =
        launched.stream()
            .map(ProjectileEntity::getRoot)
            .filter(root -> root instanceof CharacterEntity rider && rider.getParent() != null)
            .map(root -> ((CharacterEntity) root).getParent())
            .findFirst()
            .orElseThrow();
    assertThat(turned.sentAt()).as("sent back at the carrier").isSameAs(carrier);
    assertThat(turned.projectile().getTarget()).isSameAs(carrier);
    assertThat(turned.projectile().side()).isEqualTo(MONK_SIDE);
  }
}
