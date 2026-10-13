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
import org.crforge.core.battle.projectile.ProjectileGroup;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Hunter's volley reaching the Monk's Deflect. The Hunter's row links the volley's pellets into
 * one group, but the pellets' row sets DeflectGroup False, so the group is not turned around as
 * one: each pellet the Deflect reaches is deflected on its own and only it is turned. A pellet
 * flies to a point and stops at collisions: turned, it is aimed at the Hunter's position itself,
 * keeps the Hunter as its target and draws a fresh random delay. The group's list gives the Monk
 * the damage of the first pellet deflected only.
 */
class HunterDeflectTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** Long enough for every step of the scene. */
  private static final int TICKS = 400;

  private static final int MONK_SIDE = 0;

  private static final int SHOOTER_SIDE = 1;

  /** Where the Monk stands, in its own half, out of every crown tower's reach. */
  private static final int MONK_X = 9000;

  private static final int MONK_Y = 12000;

  /** How far along the length the Hunter stands from the Monk, within its range. */
  private static final int GAP = 3500;

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
   * One deflection as the observers are told of it, with what the turned pellet and the battle's
   * random source hold right then.
   */
  private record Deflection(
      int tick,
      ProjectileEntity projectile,
      WorldEntity source,
      int deflections,
      int aimX,
      int aimY,
      int delayMs,
      int randomState) {}

  @Test
  @DisplayName("the Hunter's pellets are deflected one by one, each sent back at the Hunter")
  void pelletsDeflectedOneByOne() {
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
                    tick,
                    projectile,
                    source,
                    projectile.getDeflections(),
                    projectile.getAimX(),
                    projectile.getAimY(),
                    projectile.getDelayMs(),
                    world.getRandom().getState()));
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

    // A Hunter of the other side, held still in range, fires its volley at the Monk.
    int now = match.getBattle().getTick() + 1;
    CharacterEntity hunter =
        match.deploy(
            now, records.unit("Hunter"), LEVEL, SHOOTER_SIDE, MONK_X, MONK_Y + GAP, "hunter");
    match.getBattle().step();
    hunter.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    // The Monk casts its Deflect just before the volley.
    monk.requestAbility();
    while (!deflectAlive(world)) {
      match.getBattle().step();
      assertThat(++steps).as("the Monk casts its Deflect").isLessThan(TICKS);
    }
    List<ProjectileEntity> volley = new ArrayList<>();
    while (volley.isEmpty()) {
      match.getBattle().step();
      assertThat(++steps).as("the Hunter fires").isLessThan(TICKS);
      launched.stream().filter(p -> p.getRoot() == hunter).forEach(volley::add);
    }
    ProjectileGroup group = volley.get(0).getGroup();
    assertThat(volley).as("a volley of several pellets").hasSizeGreaterThan(1);
    assertThat(group).as("linked into one group").isNotNull();
    assertThat(group.deflectsAsOne()).as("not turned around as one").isFalse();

    // The pellets reach the Deflect, each deflected on its own, until every pellet has left: the
    // Monk's hit points and shield on each step.
    List<Integer> drops = new ArrayList<>();
    int monkTotal = monk.getHitPoints().getHitPoints() + monk.getHitPoints().getShield();
    while (steps < TICKS && volley.stream().anyMatch(p -> !p.isReleased())) {
      match.getBattle().step();
      steps++;
      int total = monk.getHitPoints().getHitPoints() + monk.getHitPoints().getShield();
      if (total < monkTotal) {
        drops.add(match.getBattle().getTick());
      }
      monkTotal = total;
    }
    assertThat(deflections).as("several pellets deflected").hasSizeGreaterThan(1);
    assertThat(deflections.stream().map(Deflection::tick).distinct().count())
        .as("on several steps")
        .isGreaterThan(1);
    int range = volley.get(0).getData().randomDelayMs();
    for (Deflection deflection : deflections) {
      ProjectileEntity pellet = deflection.projectile();
      assertThat(volley).as("a pellet of the volley").contains(pellet);
      assertThat(deflection.deflections()).as("turned once, on its own").isEqualTo(1);
      assertThat(deflection.source()).as("sent back at the Hunter").isSameAs(hunter);
      assertThat(pellet.side()).as("for the Monk's side").isEqualTo(MONK_SIDE);
      assertThat(pellet.getTarget()).as("the Hunter its target").isSameAs(hunter);
      assertThat(deflection.aimX())
          .as("aimed at the Hunter itself, not stretched to the range")
          .isEqualTo(hunter.getView().getX());
      assertThat(deflection.aimY()).isEqualTo(hunter.getView().getY());
      // The turn's draw is the last one: the delay is what the random state it left gives.
      int magnitude =
          deflection.randomState() < 0 ? -deflection.randomState() : deflection.randomState();
      assertThat(deflection.delayMs())
          .as("a fresh random delay drawn by the turn")
          .isEqualTo(Integer.remainderUnsigned(magnitude, range));
    }
    long turned = volley.stream().filter(p -> p.getDeflections() >= 1).count();
    assertThat(turned).as("only the deflected pellets are turned").isEqualTo(deflections.size());

    // The Monk takes the damage of the first pellet deflected only, and the group lists it.
    assertThat(group.dooms(monk.getId())).as("the group lists the Monk").isTrue();
    assertThat(drops).as("the Monk is hit on one step only").hasSize(1);
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
