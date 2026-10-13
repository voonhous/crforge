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
 * The Princess's volley reaching the Monk's Deflect. Her row links the volley's arrows into one
 * group, and the last arrow's row leaves DeflectGroup empty, so a deflection turns the group around
 * as one: only the first arrow, the one that deals the damage, is deflected; its deflection sends
 * every arrow of the volley still flying back at the Princess for the Monk's side, the first one
 * turned twice - once with the group, once on its own.
 */
class PrincessDeflectTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** Long enough for every step of the scene. */
  private static final int TICKS = 300;

  private static final int MONK_SIDE = 0;

  private static final int SHOOTER_SIDE = 1;

  /** Where the Monk stands, in its own half, out of every crown tower's reach. */
  private static final int MONK_X = 9000;

  private static final int MONK_Y = 12000;

  /** How far along the length the Princess stands from the Monk, within her range. */
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

  /** One deflection as the observers are told of it. */
  private record Deflection(int tick, ProjectileEntity projectile, WorldEntity source) {}

  @Test
  @DisplayName("the Princess's volley is turned around as one when its first arrow is deflected")
  void volleyTurnsAsOne() {
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

    // A Princess of the other side, held still in range, fires her volley at the Monk.
    int now = match.getBattle().getTick() + 1;
    CharacterEntity princess =
        match.deploy(
            now, records.unit("Princess"), LEVEL, SHOOTER_SIDE, MONK_X, MONK_Y + GAP, "princess");
    match.getBattle().step();
    princess.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    List<ProjectileEntity> volley = new ArrayList<>();
    while (volley.isEmpty()) {
      match.getBattle().step();
      assertThat(++steps).as("the Princess fires").isLessThan(TICKS);
      launched.stream().filter(p -> p.getRoot() == princess).forEach(volley::add);
    }
    ProjectileEntity first = volley.get(0);
    assertThat(volley).as("a volley of several arrows").hasSizeGreaterThan(1);
    assertThat(first.getGroup()).as("linked into one group").isNotNull();
    assertThat(first.getGroup().deflectsAsOne()).as("turned around as one").isTrue();
    assertThat(volley).allSatisfy(p -> assertThat(p.getGroup()).isSameAs(first.getGroup()));

    // The first arrow reaches the Deflect: the Monk takes its damage, and every arrow still flying
    // is sent back at the Princess for the Monk's side.
    int monkBefore = monk.getHitPoints().getHitPoints() + monk.getHitPoints().getShield();
    while (deflections.isEmpty()) {
      match.getBattle().step();
      assertThat(++steps).as("the volley reaches the Deflect").isLessThan(TICKS);
    }
    assertThat(deflections).as("one deflection").hasSize(1);
    Deflection deflection = deflections.get(0);
    assertThat(deflection.projectile()).as("the first arrow is the one deflected").isSameAs(first);
    assertThat(deflection.source()).as("sent back at the Princess").isSameAs(princess);
    assertThat(first.getDeflections()).as("the first arrow turned twice").isEqualTo(2);
    for (ProjectileEntity arrow : volley) {
      if (arrow == first || arrow.isReleased()) {
        continue;
      }
      assertThat(arrow.side()).as("turned for the Monk's side").isEqualTo(MONK_SIDE);
      assertThat(arrow.getTarget()).as("sent at the Princess").isSameAs(princess);
      assertThat(arrow.getDeflections()).as("turned once").isEqualTo(1);
    }
    assertThat(first.side()).isEqualTo(MONK_SIDE);
    assertThat(first.getTarget()).isSameAs(princess);
    assertThat(monk.getHitPoints().getHitPoints() + monk.getHitPoints().getShield())
        .as("the Monk takes the first arrow's damage")
        .isLessThan(monkBefore);
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
