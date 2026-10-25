/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.math.FixedMath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Goblin Barrel turned around by an enemy Monk's Deflect. The barrel is cast by its king tower,
 * its root, and has no target nor a deflection behaviour: a deflection sends it, as it does a
 * spell-like projectile without a target, at the enemy crown tower nearest the Deflect, with no
 * target, for the Monk's side. Its impact there makes its Goblins for the Monk's side.
 */
class GoblinBarrelDeflectTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** Long enough for everything to deploy, the Monks to cast, and a barrel to land. */
  private static final int TICKS = 500;

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

  /** The Goblin Barrel card's projectile. */
  private static final String BARREL = text(row("spells_other", "GoblinBarrel"), "Projectile");

  /** One deflection a world observer saw, and the projectile's state right after it. */
  private record Deflection(
      ProjectileEntity projectile,
      WorldEntity parent,
      WorldEntity source,
      int side,
      int aimX,
      int aimY,
      WorldEntity target) {}

  /** A child a projectile's impact made. */
  private record Spawned(ProjectileEntity source, CharacterEntity child) {}

  /** A battle that records every deflection and every child a projectile's impact makes. */
  private static final class Scene {

    final GameTables tables = GameData.tables();
    final BattleRecords records = new BattleRecords(tables);
    final Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    final List<Deflection> deflections = new ArrayList<>();
    final List<Spawned> spawned = new ArrayList<>();
    int ticks;

    Scene() {
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
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
                          parent,
                          source,
                          projectile.side(),
                          projectile.getAimX(),
                          projectile.getAimY(),
                          projectile.getTarget()));
                }

                @Override
                public void characterSpawned(
                    int tick, SpawnHost source, CharacterEntity child, int x, int y) {
                  if (source instanceof ProjectileEntity projectile) {
                    spawned.add(new Spawned(projectile, child));
                  }
                }
              });
    }

    void step() {
      match.getBattle().step();
      assertThat(++ticks).as("the scene plays out in time").isLessThan(TICKS);
    }

    int tick() {
      return match.getBattle().getTick();
    }

    /** A Monk standing still at a point, its Deflect cast and alive. */
    CharacterEntity monkWithDeflect(int side, int x, int y) {
      CharacterEntity monk =
          match.deploy(tick() + 1, records.unit("Monk"), LEVEL, side, x, y, "monk" + side);
      while (monk.getId() == 0
          || monk.getView().getState() == GridEntityState.DEPLOYING
          || monk.getView().getState() == GridEntityState.WAITING_TO_DEPLOY) {
        step();
      }
      monk.setActive(CharacterEntity.MOVEMENT_SLOT, false);
      int before = deflectors().size();
      monk.requestAbility();
      while (deflectors().size() == before) {
        step();
      }
      return monk;
    }

    List<AreaEffectEntity> deflectors() {
      List<AreaEffectEntity> out = new ArrayList<>();
      for (BattleEntity entity : match.getWorld().getHolder().entities()) {
        if (entity instanceof AreaEffectEntity area && area.getData().name().equals(DEFLECT)) {
          out.add(area);
        }
      }
      return out;
    }

    /** The Deflect a Monk casts. */
    AreaEffectEntity deflectOf(CharacterEntity monk) {
      return deflectors().stream()
          .filter(area -> area.getFollow() == monk)
          .findFirst()
          .orElseThrow();
    }

    /** The crown tower of a side nearest a point, by squared distance, the first on a tie. */
    WorldEntity nearestCrownTower(int side, int x, int y) {
      WorldEntity best = null;
      int bestSquared = Integer.MAX_VALUE;
      for (BattleEntity entity : match.getWorld().getHolder().entities()) {
        if (entity instanceof WorldEntity tower
            && (tower.getData().king() || tower.getData().summonerTower())
            && (tower.side() & 1) == side) {
          int squared =
              FixedMath.guardedSumOfSquares(tower.getView().getX() - x, tower.getView().getY() - y);
          if (squared < bestSquared) {
            best = tower;
            bestSquared = squared;
          }
        }
      }
      return best;
    }

    /** The enemy casts a Goblin Barrel onto a point. */
    void castBarrel(int side, int x, int y) {
      match.play(tick() + 1, records.card("GoblinBarrel"), LEVEL, side, x, y, "barrel");
    }
  }

  @Test
  @DisplayName(
      "a Goblin Barrel cast onto the Monk is sent, with no target, at the enemy crown tower nearest"
          + " the Deflect, where its Goblins land for the Monk's side")
  void barrelIsSentAtTheCrownTowerNearestTheDeflect() {
    Scene scene = new Scene();
    CharacterEntity monk = scene.monkWithDeflect(0, 3500, 10000);
    AreaEffectEntity deflect = scene.deflectOf(monk);
    WorldEntity tower = scene.nearestCrownTower(1, deflect.getX(), deflect.getY());
    assertThat(tower.getData().king()).as("a princess tower is the nearest here").isFalse();
    scene.castBarrel(1, 3500, 10000);
    while (scene.deflections.isEmpty()) {
      scene.step();
    }
    Deflection d = scene.deflections.get(0);
    ProjectileEntity barrel = d.projectile();
    assertThat(barrel.getData().name()).isEqualTo(BARREL);
    assertThat(d.parent()).isSameAs(monk);
    assertThat(d.source()).as("sent at a point, not back at the king tower").isNull();
    assertThat(d.side()).isEqualTo(monk.side());
    assertThat(d.aimX()).isEqualTo(tower.getView().getX());
    assertThat(d.aimY()).isEqualTo(tower.getView().getY());
    assertThat(d.target()).isNull();
    assertThat(scene.spawned).as("nothing landed where the barrel was cast").isEmpty();
    while (!barrel.isReleased()) {
      scene.step();
    }
    scene.step();
    // Its Goblins, as many as the row makes, land by that tower for the Monk's side.
    assertThat(scene.spawned)
        .hasSize(number(row("projectiles", BARREL), "SpawnCharacterCount"))
        .allSatisfy(
            s -> {
              assertThat(s.source()).isSameAs(barrel);
              assertThat(s.child().getData().name())
                  .isEqualTo(text(row("projectiles", BARREL), "SpawnCharacter"));
              assertThat(s.child().side()).isEqualTo(monk.side());
            });
    assertThat(scene.deflections).hasSize(1);
  }

  @Test
  @DisplayName(
      "a Goblin Barrel already deflected that meets an enemy Deflect again is refused: its second"
          + " turn-around, back at the Monk that turned it, is held by no recording")
  void barrelDeflectedTwiceIsRefused() {
    Scene scene = new Scene();
    // The bottom Monk stands by the enemy's left princess tower; the enemy's Monk stands between it
    // and that tower, where the turned barrel passes low on its way to the tower.
    CharacterEntity bottom = scene.monkWithDeflect(0, 3500, 23300);
    WorldEntity tower = scene.nearestCrownTower(1, 3500, 23300);
    int topY = (23300 + tower.getView().getY()) / 2;
    scene.monkWithDeflect(1, tower.getView().getX(), topY);
    scene.castBarrel(1, 3500, 23300);
    assertThatThrownBy(
            () -> {
              while (scene.deflections.size() < 2) {
                scene.step();
              }
            })
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("(" + BARREL + ") flies within")
        .hasMessageContaining("whose deflection of it is not modelled");
    assertThat(scene.deflections).hasSize(1);
    assertThat(scene.deflections.get(0).parent()).isSameAs(bottom);
  }

  @Test
  @DisplayName(
      "a Goblin Barrel turned by a Deflect nearer the enemy king tower than either enemy princess"
          + " tower is refused: its turn at the king tower is held by no recording")
  void barrelSentAtTheKingTowerIsRefused() {
    Scene scene = new Scene();
    // In front of the enemy's king tower, nearer it than either princess tower.
    CharacterEntity monk = scene.monkWithDeflect(0, 9000, 25000);
    AreaEffectEntity deflect = scene.deflectOf(monk);
    WorldEntity king = scene.nearestCrownTower(1, deflect.getX(), deflect.getY());
    assertThat(king.getData().king()).as("the enemy king tower is the nearest here").isTrue();
    scene.castBarrel(1, 9000, 25000);
    assertThatThrownBy(
            () -> {
              while (scene.deflections.isEmpty()) {
                scene.step();
              }
            })
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("(" + BARREL + "), cast from the king tower")
        .hasMessageContaining("at the king tower " + king.name() + ", which is not modelled");
    assertThat(scene.deflections).isEmpty();
  }
}
