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
 * target, for the Monk's side. Its impact there makes its Goblins for the Monk's side. Turned, its
 * root is that Monk: an enemy Monk's Deflect that meets it again sends it back at the first Monk,
 * or, once the first Monk has left, turns it where the first Monk turned it and ends its flight.
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
    final Standard1v1Battle match;
    final List<Deflection> deflections = new ArrayList<>();
    final List<Spawned> spawned = new ArrayList<>();
    int ticks;

    Scene() {
      this(Standard1v1Battle.PRINCESS_TOWERS);
    }

    /**
     * A battle whose top side's towers are the given spawn group's, the bottom's the princess's.
     */
    Scene(String topTowers) {
      match =
          new Standard1v1Battle(
              tables,
              List.of(
                  new Standard1v1Battle.Towers(Standard1v1Battle.PRINCESS_TOWERS, LEVEL, LEVEL),
                  new Standard1v1Battle.Towers(topTowers, LEVEL, LEVEL)),
              false);
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
      "a Goblin Barrel already deflected that meets an enemy Deflect again is sent back at the Monk"
          + " that turned it, its target, for the second Monk's side; a third turn ends its flight"
          + " and it lands nowhere")
  void barrelDeflectedAgainIsSentBackAtTheMonkThatTurnedIt() {
    Scene scene = new Scene();
    // The bottom Monk stands by the enemy's left princess tower; the enemy's Monk stands between it
    // and that tower, where the turned barrel passes low on its way to the tower.
    CharacterEntity bottom = scene.monkWithDeflect(0, 3500, 23300);
    WorldEntity tower = scene.nearestCrownTower(1, 3500, 23300);
    int topY = (23300 + tower.getView().getY()) / 2;
    CharacterEntity top = scene.monkWithDeflect(1, tower.getView().getX(), topY);
    scene.castBarrel(1, 3500, 23300);
    while (scene.deflections.size() < 2) {
      scene.step();
    }
    assertThat(scene.deflections.get(0).parent()).isSameAs(bottom);
    Deflection again = scene.deflections.get(1);
    ProjectileEntity barrel = again.projectile();
    assertThat(barrel.getData().name()).isEqualTo(BARREL);
    assertThat(again.parent()).isSameAs(top);
    assertThat(again.source()).as("sent back at the Monk that turned it").isSameAs(bottom);
    assertThat(again.side()).isEqualTo(top.side());
    assertThat(again.target()).isSameAs(bottom);
    assertThat(again.aimX()).isEqualTo(bottom.getView().getX());
    assertThat(again.aimY()).isEqualTo(bottom.getView().getY());
    assertThat(barrel.isReleased()).isFalse();
    // The bottom Monk's Deflect is still up: it turns the barrel a third time, past the most a
    // projectile takes, which ends its flight; it lands nowhere and makes no Goblins.
    while (!barrel.isReleased()) {
      scene.step();
    }
    assertThat(scene.deflections).hasSize(3);
    assertThat(scene.deflections.get(2).parent()).isSameAs(bottom);
    assertThat(barrel.getDeflections())
        .isEqualTo(scene.match.getWorld().globalNumber("MAX_DEFLECTION_TIMES") + 1);
    for (int i = 0; i < 40; i++) {
      scene.step();
    }
    assertThat(scene.spawned).isEmpty();
  }

  @Test
  @DisplayName(
      "a Goblin Barrel turned by a Monk that has left the battle, met by an enemy Deflect, is"
          + " turned at the point the first Monk turned it, for the second Monk's side, and its"
          + " flight ends: it lands nowhere and makes no Goblins")
  void barrelDeflectedAgainWithoutItsRootIsTurnedAtItsStartAndFinished() {
    Scene scene = new Scene();
    // The bottom Monk stands farther from the enemy's left princess tower, the enemy's Monk just
    // in front of the tower: the turned barrel flies a while before it reaches the second Deflect.
    CharacterEntity bottom = scene.monkWithDeflect(0, 3500, 19000);
    WorldEntity tower = scene.nearestCrownTower(1, 3500, 19000);
    CharacterEntity top =
        scene.monkWithDeflect(1, tower.getView().getX(), tower.getView().getY() - 2000);
    scene.castBarrel(1, 3500, 19000);
    while (scene.deflections.isEmpty()) {
      scene.step();
    }
    ProjectileEntity barrel = scene.deflections.get(0).projectile();
    assertThat(barrel.getRoot()).isSameAs(bottom);
    // Where the first turn launched it from: its start.
    int startX = barrel.getStartX();
    int startY = barrel.getStartY();
    // The Monk that turned it leaves at once: the barrel flies on without a root owner. Its Deflect
    // cuts the damage it takes, a kill's too, so kills of its whole hit points are queued until
    // their sum gets through.
    for (int i = 0; i < 20; i++) {
      scene.match.getWorld().getHits().kill(bottom, null);
    }
    while (barrel.getRoot() != null) {
      scene.step();
    }
    assertThat(scene.deflections).hasSize(1);
    while (scene.deflections.size() < 2) {
      scene.step();
    }
    Deflection again = scene.deflections.get(1);
    assertThat(again.projectile()).isSameAs(barrel);
    assertThat(again.parent()).isSameAs(top);
    assertThat(again.source()).as("sent at nothing: it has no root to go back to").isNull();
    assertThat(again.side()).isEqualTo(top.side());
    assertThat(again.target()).isNull();
    assertThat(again.aimX()).isEqualTo(startX);
    assertThat(again.aimY()).isEqualTo(startY);
    assertThat(barrel.isReleased()).as("its flight is ended").isTrue();
    for (int i = 0; i < 40; i++) {
      scene.step();
    }
    assertThat(scene.match.getWorld().getHolder().entities()).doesNotContain(barrel);
    assertThat(scene.spawned).isEmpty();
    assertThat(scene.deflections).hasSize(2);
  }

  /**
   * A bottom Monk in front of the enemy's king tower, nearer it than either of the enemy's other
   * crown towers, casts its Deflect, and the enemy casts a Goblin Barrel onto it: the barrel is
   * sent, with no target, at the king tower's position, as at a princess tower, and its Goblins
   * land there for the Monk's side.
   */
  private static void barrelIsSentAtTheKingTower(String topTowers) {
    Scene scene = new Scene(topTowers);
    // In front of the enemy's king tower, nearer it than either of its other crown towers.
    CharacterEntity monk = scene.monkWithDeflect(0, 9000, 25000);
    AreaEffectEntity deflect = scene.deflectOf(monk);
    WorldEntity king = scene.nearestCrownTower(1, deflect.getX(), deflect.getY());
    assertThat(king.getData().king()).as("the enemy king tower is the nearest here").isTrue();
    scene.castBarrel(1, 9000, 25000);
    while (scene.deflections.isEmpty()) {
      scene.step();
    }
    Deflection d = scene.deflections.get(0);
    ProjectileEntity barrel = d.projectile();
    assertThat(barrel.getData().name()).isEqualTo(BARREL);
    assertThat(barrel.getRoot()).as("once turned, the Monk is its root").isSameAs(monk);
    assertThat(d.parent()).isSameAs(monk);
    assertThat(d.source()).as("sent at a point, not back at the king tower").isNull();
    assertThat(d.side()).isEqualTo(monk.side());
    assertThat(d.aimX()).isEqualTo(king.getView().getX());
    assertThat(d.aimY()).isEqualTo(king.getView().getY());
    assertThat(d.target()).isNull();
    while (!barrel.isReleased()) {
      scene.step();
    }
    scene.step();
    // Its Goblins, as many as the row makes, land by the king tower for the Monk's side.
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
      "a Goblin Barrel turned by a Deflect nearer the enemy king tower than either enemy princess"
          + " tower is sent, with no target, at the king tower, where its Goblins land for the"
          + " Monk's side")
  void barrelIsSentAtTheNearestKingTower() {
    barrelIsSentAtTheKingTower(Standard1v1Battle.PRINCESS_TOWERS);
  }

  @Test
  @DisplayName(
      "a Goblin Barrel turned by a Deflect nearer the enemy Royal Chef's king tower than either of"
          + " his towers is sent, with no target, at that king tower")
  void barrelIsSentAtTheNearestChefKingTower() {
    barrelIsSentAtTheKingTower("King_ChefTowers");
  }
}
