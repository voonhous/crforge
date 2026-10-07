package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.Version16Tables;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Projectiles that fly while the Monk's Deflect lives, on data version 16.402.18, away from it and
 * at it.
 *
 * <p>A projectile with a body that flies to a point, such as the Magic Archer's arrow, runs a pass
 * over what its body covers after each step: the spatial index answers it, deflecting area effects
 * included, and only an enemy deflecting area effect the index lists would turn it around. Away
 * from the Deflect it hits as without one. A spell projectile such as the Fireball is measured
 * against the Deflect's radius widened by its own deflect radius; one that passes outside it lands
 * as without one.
 */
class DeflectPassTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** Long enough for everything to deploy, the Monk to cast, and a projectile to land. */
  private static final int TICKS = 300;

  /** The Monk's side; everything that shoots at its side is side 1. */
  private static final int MONK_SIDE = 0;

  private static final int ENEMY_SIDE = 1;

  /** A battle with side 0's Monk standing at (3500, 10000), its Deflect cast and alive. */
  private record Scene(
      Standard1v1Battle match,
      BattleRecords records,
      CharacterEntity monk,
      List<ProjectileEntity> launchedBesideDeflect) {

    void step() {
      match.getBattle().step();
    }

    int tick() {
      return match.getBattle().getTick();
    }
  }

  private static Scene scene() {
    GameTables tables = Version16Tables.load();
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    List<ProjectileEntity> launched = new ArrayList<>();
    CharacterEntity monk =
        match.deploy(0, records.unit("Monk"), LEVEL, MONK_SIDE, 3500, 10000, "monk");
    Scene scene = new Scene(match, records, monk, launched);
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void projectileLaunched(int tick, ProjectileEntity projectile) {
                if (deflectAlive(match)) {
                  launched.add(projectile);
                }
              }
            });
    int steps = 0;
    while (monk.getView().getState() == GridEntityState.DEPLOYING
        || monk.getView().getState() == GridEntityState.WAITING_TO_DEPLOY
        || monk.getId() == 0) {
      scene.step();
      assertThat(++steps).as("the Monk deploys").isLessThan(TICKS);
    }
    monk.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    monk.requestAbility();
    while (!deflectAlive(match)) {
      scene.step();
      assertThat(++steps).as("the Monk casts its Deflect").isLessThan(TICKS);
    }
    return scene;
  }

  private static boolean deflectAlive(Standard1v1Battle match) {
    for (BattleEntity entity : match.getWorld().getHolder().entities()) {
      if (entity instanceof AreaEffectEntity area && area.getData().name().equals("Deflect")) {
        return true;
      }
    }
    return false;
  }

  @Test
  @DisplayName("a Magic Archer's arrow flying far from the Deflect hits its target as without one")
  void arrowAwayFromTheDeflectHits() {
    Scene scene = scene();
    int now = scene.tick() + 1;
    CharacterEntity knight =
        scene
            .match()
            .deploy(now, scene.records().unit("Knight"), LEVEL, MONK_SIDE, 14500, 12000, "knight");
    CharacterEntity archer =
        scene
            .match()
            .deploy(
                now,
                scene.records().unit("EliteArcher"),
                LEVEL,
                ENEMY_SIDE,
                14500,
                17000,
                "archer");
    scene.step();
    knight.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    archer.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    int full = knight.getHitPoints().getHitPoints();
    int steps = 0;
    while (knight.getHitPoints().getHitPoints() == full) {
      scene.step();
      assertThat(++steps).as("the Knight is hit").isLessThan(TICKS);
    }
    assertThat(scene.launchedBesideDeflect())
        .as("the arrow that hit was launched while the Deflect lived")
        .anyMatch(p -> p.getData().name().equals("EliteArcherArrow"));
    assertThat(deflectAlive(scene.match())).as("the Deflect still lives").isTrue();
  }

  @Test
  @DisplayName("a Magic Archer's arrow at the Monk would be deflected in its pass, refused")
  void arrowAtTheDeflectIsRefused() {
    Scene scene = scene();
    int now = scene.tick() + 1;
    CharacterEntity archer =
        scene
            .match()
            .deploy(
                now, scene.records().unit("EliteArcher"), LEVEL, ENEMY_SIDE, 3500, 15000, "archer");
    scene.step();
    archer.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    assertThatThrownBy(
            () -> {
              for (int i = 0; i < TICKS; i++) {
                scene.step();
              }
            })
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("EliteArcherArrow")
        .hasMessageContaining("Deflect");
  }

  @Test
  @DisplayName("a Fireball landing far from the Deflect hits as without one")
  void fireballAwayFromTheDeflectLands() {
    Scene scene = scene();
    int now = scene.tick() + 1;
    CharacterEntity knight =
        scene
            .match()
            .deploy(now, scene.records().unit("Knight"), LEVEL, MONK_SIDE, 14500, 12000, "knight");
    scene.step();
    knight.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    int full = knight.getHitPoints().getHitPoints();
    scene
        .match()
        .play(
            scene.tick() + 1,
            scene.records().card("Fireball"),
            LEVEL,
            ENEMY_SIDE,
            14500,
            12000,
            "fireball");
    int steps = 0;
    while (knight.getHitPoints().getHitPoints() == full) {
      scene.step();
      assertThat(++steps).as("the Fireball lands").isLessThan(TICKS);
    }
    // Cast after the Deflect came, the Fireball flew and landed while it lived.
    assertThat(deflectAlive(scene.match())).as("the Deflect still lives").isTrue();
  }

  @Test
  @DisplayName("a Fireball cast onto the Monk would be deflected, refused")
  void fireballOntoTheDeflectIsRefused() {
    Scene scene = scene();
    scene
        .match()
        .play(
            scene.tick() + 1,
            scene.records().card("Fireball"),
            LEVEL,
            ENEMY_SIDE,
            3500,
            10000,
            "fireball");
    assertThatThrownBy(
            () -> {
              for (int i = 0; i < TICKS; i++) {
                scene.step();
              }
            })
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("FireballSpell")
        .hasMessageContaining("Deflect");
  }
}
