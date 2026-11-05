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

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
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
import org.junit.jupiter.api.io.TempDir;

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

  /** How many steps the Monk waits without its Deflect, about as long as the cast takes. */
  private static final int DEFLECT_WAIT = 40;

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

  /** The action the test's shot runs on reaching its target, written for the test. */
  private static final String REACHED_ACTION = "Test_Rootless_Reached_Spawn";

  /** The area effect that action spawns, written for the test. */
  private static final String REACHED_AREA = "Test_Rootless_Reached_Area";

  /** One deflection as the observers are told of it. */
  private record Deflection(int tick, ProjectileEntity projectile, WorldEntity source) {}

  /** The scene as it stands once the shot has been deflected, and what was seen on the way. */
  private record Scene(
      Standard1v1Battle match,
      CharacterEntity monk,
      ProjectileEntity shot,
      int monkBefore,
      List<Deflection> deflections,
      List<AreaEffectEntity> spawned) {}

  @Test
  @DisplayName(
      "a Musketeer's shot deflected after the Musketeer has left is finished, the Monk hit by it")
  void shotWithoutItsShooterIsFinished() {
    Scene scene = play(GameData.tables(), true);
    ProjectileEntity shot = scene.shot();
    Deflection deflection = scene.deflections().get(0);
    assertThat(deflection.projectile()).isSameAs(shot);
    assertThat(deflection.source()).as("sent at no source").isNull();
    assertThat(shot.side()).as("turned for the Monk's side").isEqualTo(MONK_SIDE);
    assertThat(shot.getTarget()).as("with no target").isNull();
    assertThat(shot.isReleased()).as("its flight ended").isTrue();
    assertThat(health(scene.monk()))
        .as("the Monk takes the shot's damage")
        .isLessThan(scene.monkBefore());

    // It leaves at the next cleanup without landing anywhere.
    scene.match().getBattle().step();
    assertThat(scene.match().getWorld().getHolder().entities())
        .as("the shot has left")
        .doesNotContain(shot);
    assertThat(scene.deflections()).as("deflected once").hasSize(1);
  }

  /**
   * The same scene with a shot whose row names an action on reaching its target, as the evolved
   * Elite Barbarians' thrown spear does: the deflection ends its flight all the same, and the
   * action is never run - the shot reaches nothing.
   */
  @Test
  @DisplayName(
      "a shot with an action on reaching its target, deflected after its shooter has left, is"
          + " finished without running the action")
  void shotWithReachedActionIsFinishedWithoutIt(@TempDir Path folder) throws IOException {
    Scene scene = play(withReachedAction(folder), true);
    ProjectileEntity shot = scene.shot();
    assertThat(shot.getData().onTargetReachedAction())
        .as("the shot's row names an action on reaching its target")
        .isNotNull();
    Deflection deflection = scene.deflections().get(0);
    assertThat(deflection.projectile()).isSameAs(shot);
    assertThat(deflection.source()).as("sent at no source").isNull();
    assertThat(shot.side()).as("turned for the Monk's side").isEqualTo(MONK_SIDE);
    assertThat(shot.getTarget()).as("with no target").isNull();
    assertThat(shot.isReleased()).as("its flight ended").isTrue();
    assertThat(health(scene.monk()))
        .as("the Monk takes the shot's damage")
        .isLessThan(scene.monkBefore());

    // It leaves at the next cleanup, and in the steps after nothing spawns its area effect.
    for (int step = 0; step < 20; step++) {
      scene.match().getBattle().step();
    }
    assertThat(scene.match().getWorld().getHolder().entities())
        .as("the shot has left")
        .doesNotContain(shot);
    assertThat(scene.spawned()).as("its action on reaching its target never ran").isEmpty();
    assertThat(scene.deflections()).as("deflected once").hasSize(1);
  }

  /**
   * The configured tables copied into a folder, the Musketeer's shot given an action on reaching
   * its target, {@link #REACHED_ACTION}, that spawns {@link #REACHED_AREA}, both written for the
   * test.
   */
  private static GameTables withReachedAction(Path folder) throws IOException {
    String projectile = text(unitRow("Musketeer"), "Projectile");
    GameData.altered(
        folder,
        "projectiles",
        rows -> GameData.columns(rows, projectile).put("OnTargetReachedAction", REACHED_ACTION));
    GameData.alterLoaded(
        folder,
        "actions",
        rows -> {
          ObjectNode action = rows.putObject(REACHED_ACTION);
          action.put("class", "LogicActionSpawnData");
          action.put("ClassType", "ActionSpawn");
          ObjectNode fields = action.putObject("fields");
          fields.put("ClassType", "ActionSpawn");
          fields.put("SpawnData", REACHED_AREA);
          fields.put("SpawnType", "AreaEffectType");
        });
    GameData.alterLoaded(
        folder,
        "area_effect_objects",
        rows -> {
          ObjectNode row = rows.putObject(REACHED_AREA);
          row.put("index", rows.size() - 1);
          row.put("class", "LogicAreaEffectObjectData");
          ObjectNode columns = row.putObject("columns");
          columns.put("LifeDuration", 1000);
          columns.put("Radius", 1000);
          columns.put("Rarity", "Common");
        });
    return GameTables.load(folder);
  }

  /**
   * The control of the test above: the same shot, its shooter gone, landing on a Monk that casts no
   * Deflect runs its action on reaching its target, so the area effect the test looks for is one
   * the shot's row makes.
   */
  @Test
  @DisplayName("the same shot landing on a Monk without its Deflect runs its action on reaching it")
  void shotWithReachedActionLandingRunsIt(@TempDir Path folder) throws IOException {
    Scene scene = play(withReachedAction(folder), false);
    assertThat(scene.deflections()).as("nothing deflects it").isEmpty();
    assertThat(scene.spawned()).as("its action on reaching its target ran").hasSize(1);
  }

  /**
   * Plays the scene up to the step the Musketeer's shot, its shooter gone, is deflected by the
   * Monk's Deflect; without the Deflect, until the shot has landed on the Monk and left.
   */
  private static Scene play(GameTables tables, boolean deflect) {
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, true);
    BattleWorld world = match.getWorld();
    List<Deflection> deflections = new ArrayList<>();
    List<ProjectileEntity> launched = new ArrayList<>();
    List<AreaEffectEntity> spawned = new ArrayList<>();
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

          @Override
          public void areaEffectAdmitted(int tick, AreaEffectEntity areaEffect) {
            // Every area effect of the row the shot's action on reaching its target would spawn.
            if (areaEffect.getData().name().equals(REACHED_AREA)) {
              spawned.add(areaEffect);
            }
          }
        });

    // The Monk deploys, held still, and casts its Deflect (or, without it, waits as long).
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
    if (deflect) {
      monk.requestAbility();
    }
    while (deflect ? !deflectAlive(world) : steps < DEFLECT_WAIT) {
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

    // The shot reaches the Deflect: turned for the Monk's side with nothing to be sent at. Without
    // the Deflect, it lands on the Monk and leaves.
    int monkBefore = health(monk);
    while (deflect ? deflections.isEmpty() : world.getHolder().entities().contains(shot)) {
      match.getBattle().step();
      assertThat(++steps).as("the shot reaches the Deflect or the Monk").isLessThan(TICKS);
    }
    if (!deflect) {
      // The action on reaching the target runs in the same step's last pending pass.
      match.getBattle().step();
    }
    return new Scene(match, monk, shot, monkBefore, deflections, spawned);
  }

  /** A unit's hit points and shield together. */
  private static int health(CharacterEntity unit) {
    return unit.getHitPoints().getHitPoints() + unit.getHitPoints().getShield();
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
