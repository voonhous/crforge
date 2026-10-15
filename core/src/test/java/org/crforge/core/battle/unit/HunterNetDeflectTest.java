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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The evolved Hunter's net reaching the Monk's Deflect. The evolved Hunter's row sets
 * GroupProjectiles, but only the volley of a hit links its projectiles into one group: the net its
 * starting action throws is in no group. So the Deflect turns it as any homing projectile: it is
 * sent back at the Hunter, for the Monk's side, with the Hunter as its target, and its hit there
 * runs its action on reaching its target on the Hunter, which snares it.
 */
class HunterNetDeflectTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** Long enough for every step of the scene. */
  private static final int TICKS = 400;

  private static final int MONK_SIDE = 0;

  private static final int HUNTER_SIDE = 1;

  /** Where the Monk stands, in its own half, out of every crown tower's reach. */
  private static final int MONK_X = 9000;

  private static final int MONK_Y = 12000;

  /** How far along the length the Hunter stands from the Monk, within its net's range. */
  private static final int GAP = 3500;

  /** The evolved Hunter's row. */
  private static final String HUNTER = "Hunter_EV1";

  /** The net's row: the projectile the evolved Hunter's starting action throws. */
  private static final String NET = text(text(unitRow(HUNTER), "OnStartingAction"), "Projectile");

  /** The snares the net's action on reaching its target may put on what it hits. */
  private static final List<String> SNARES = snares();

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

  /** One deflection as the observers are told of it, with what the net holds right then. */
  private record Deflection(
      ProjectileEntity projectile, WorldEntity source, int deflections, int side) {}

  @Test
  @DisplayName(
      "the evolved Hunter's net, in no group, is deflected back at the Hunter and snares it")
  void netDeflectedBackAtTheHunter() {
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
                new Deflection(projectile, source, projectile.getDeflections(), projectile.side()));
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

    // An evolved Hunter of the other side, held still in its net's range, throws its net at the
    // Monk once its first cooldown has run out; the Monk casts its Deflect before it.
    int now = match.getBattle().getTick() + 1;
    CharacterEntity hunter =
        match.deploy(now, records.unit(HUNTER), LEVEL, HUNTER_SIDE, MONK_X, MONK_Y + GAP, "hunter");
    match.getBattle().step();
    hunter.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    monk.requestAbility();
    while (!deflectAlive(world)) {
      match.getBattle().step();
      assertThat(++steps).as("the Monk casts its Deflect").isLessThan(TICKS);
    }
    ProjectileEntity net = null;
    while (net == null) {
      match.getBattle().step();
      assertThat(++steps).as("the Hunter throws its net").isLessThan(TICKS);
      net =
          launched.stream()
              .filter(p -> p.getRoot() == hunter && p.getData().name().equals(NET))
              .findFirst()
              .orElse(null);
    }
    assertThat(hunter.getData().groupProjectiles())
        .as("the evolved Hunter's row links its volleys into groups")
        .isTrue();
    assertThat(net.isGrouped()).as("the net is in no group").isFalse();
    assertThat(net.getGroup()).isNull();

    // The net reaches the Deflect and is turned back at the Hunter; on its hit there the Hunter
    // carries a snare.
    boolean snared = false;
    while (steps < TICKS && !snared && hunter.getHitPoints().getHitPoints() > 0) {
      match.getBattle().step();
      steps++;
      snared = SNARES.stream().anyMatch(hunter.getBuffs()::carries);
    }
    ProjectileEntity thrown = net;
    List<Deflection> turns = deflections.stream().filter(d -> d.projectile() == thrown).toList();
    assertThat(turns).as("the net deflected once").hasSize(1);
    Deflection turn = turns.get(0);
    assertThat(turn.deflections()).isEqualTo(1);
    assertThat(turn.source()).as("sent back at the Hunter").isSameAs(hunter);
    assertThat(turn.side()).as("for the Monk's side").isEqualTo(MONK_SIDE);
    assertThat(net.getTarget()).as("the Hunter its target").isSameAs(hunter);
    assertThat(snared).as("the net's hit snares the Hunter").isTrue();
    assertThat(net.isReleased()).as("the net has hit").isTrue();
  }

  /** The buffs the net's action on reaching its target spawns, read through its sub-actions. */
  private static List<String> snares() {
    List<String> out = new ArrayList<>();
    collect(text(row("projectiles", NET), "OnHitTargetAction"), out);
    assertThat(out).as("the net's action spawns snares").isNotEmpty();
    return out;
  }

  private static void collect(String action, List<String> out) {
    if (fields(action).has("SpawnData")) {
      out.add(text(action, "SpawnData"));
    }
    if (fields(action).has("SubActions")) {
      for (String sub : actionNames(action, "SubActions")) {
        collect(sub, out);
      }
    }
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
