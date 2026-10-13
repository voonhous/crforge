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
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Fisherman's hook reaching the Monk's Deflect on its way to the Monk. The deflection does not
 * send it back: the hook is not turned at all, its flight simply ends where it is - for the
 * Fisherman's side, still aimed at the Monk - and it leaves at the next cleanup without hooking
 * anything, the Fisherman pulled to nothing and the Monk not pulled.
 */
class FishermanDeflectTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** Long enough for every step of the scene. */
  private static final int TICKS = 400;

  private static final int MONK_SIDE = 0;

  private static final int FISHERMAN_SIDE = 1;

  /** Where the Monk stands, in its own half, out of every crown tower's reach. */
  private static final int MONK_X = 9000;

  private static final int MONK_Y = 12000;

  private static final GameRow FISHERMAN = unitRow("Fisherman");

  /**
   * How far along the length the Fisherman stands from the Monk: inside the ring his special loads
   * in, 1500 past its near bound, counted from the Monk's radius.
   */
  private static final int GAP =
      number(unitRow("Monk"), "CollisionRadius") + number(FISHERMAN, "SpecialMinRange") + 1500;

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
  @DisplayName("the Fisherman's hook deflected by the Monk ends where it is, hooking nothing")
  void hookAtTheDeflectEnds() {
    GameTables tables = GameData.tables();
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, true);
    BattleWorld world = match.getWorld();
    List<Deflection> deflections = new ArrayList<>();
    List<ProjectileEntity> hooks = new ArrayList<>();
    List<String> dragStates = new ArrayList<>();
    world.addObserver(
        new WorldObserver() {
          @Override
          public void projectileLaunched(int tick, ProjectileEntity projectile) {
            if (projectile.getData().dragBackSpeed() >= 1) {
              hooks.add(projectile);
            }
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

          @Override
          public void dragStateSet(
              int tick, ProjectileEntity projectile, WorldEntity unit, int oldState, int now) {
            dragStates.add(tick + " " + unit.name() + " " + now);
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

    // A Fisherman of the other side, held still within his special's ring, throws his hook at the
    // Monk.
    int now = match.getBattle().getTick() + 1;
    CharacterEntity fisherman =
        match.deploy(
            now, records.unit("Fisherman"), LEVEL, FISHERMAN_SIDE, MONK_X, MONK_Y + GAP, "fisher");
    match.getBattle().step();
    fisherman.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    while (hooks.isEmpty()) {
      match.getBattle().step();
      assertThat(++steps).as("the Fisherman throws his hook").isLessThan(TICKS);
    }
    ProjectileEntity hook = hooks.get(0);
    assertThat(hook.getTarget()).as("the hook is thrown at the Monk").isSameAs(monk);
    assertThat(deflectAlive(world)).as("the Deflect still stands").isTrue();

    // The hook reaches the Deflect: not turned, its flight ended where it is.
    while (deflections.isEmpty()) {
      match.getBattle().step();
      assertThat(++steps).as("the hook reaches the Deflect").isLessThan(TICKS);
    }
    Deflection deflection = deflections.get(0);
    assertThat(deflection.projectile()).isSameAs(hook);
    assertThat(deflection.source()).as("sent at nothing").isNull();
    assertThat(hook.isReleased()).as("its flight ended").isTrue();
    assertThat(hook.side()).as("still the Fisherman's").isEqualTo(FISHERMAN_SIDE);
    assertThat(hook.getTarget()).as("still aimed at the Monk").isSameAs(monk);
    assertThat(hook.getOwner()).as("still the Fisherman's hook").isSameAs(fisherman);
    assertThat(hook.getDeflections()).as("not counted as turned").isZero();
    assertThat(hook.isHooked()).as("hooking nothing").isFalse();

    // It leaves at the next cleanup; nobody is pulled or dragged.
    match.getBattle().step();
    assertThat(world.getHolder().entities()).as("the hook has left").doesNotContain(hook);
    for (int i = 0; i < 20; i++) {
      match.getBattle().step();
    }
    assertThat(dragStates).as("no hook's state set on either").isEmpty();
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
