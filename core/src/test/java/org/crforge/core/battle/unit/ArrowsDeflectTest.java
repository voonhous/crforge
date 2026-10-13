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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileChain;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.index.ShapeTests;
import org.crforge.core.pathfinding.math.FixedMath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Arrows spell's volley met by an enemy Monk's Deflect. Arrows' projectiles are measured only
 * at their target's point: no deflection pass runs on their way, and at their arrival the pass is
 * asked at the arrow's ring point - the unjittered point of the spell's ring its cast gave it -
 * rather than its aim, and only a Deflect whose circle overlaps the wave's circle (the placed point
 * and the spell's radius) may turn it. A turned arrow is sent as a spell is, with no target, at the
 * caster's crown tower nearest the Deflect; the Monk takes the arrows' damage once a wave, the
 * wave's shared list of what it has hit holding it after the first turn.
 */
class ArrowsDeflectTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** Long enough for everything to deploy, the Monk to cast, and the volley to land. */
  private static final int TICKS = 400;

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

  /** The Arrows card's projectile. */
  private static final String ARROWS = text(row("spells_other", "Arrows"), "Projectile");

  /** One deflection a world observer saw, and the projectile's state right after it. */
  private record Deflection(
      int tick,
      ProjectileEntity projectile,
      WorldEntity parent,
      WorldEntity source,
      int side,
      int aimX,
      int aimY,
      WorldEntity target) {}

  /** A battle that records every deflection. */
  private static final class Scene {

    final GameTables tables = GameData.tables();
    final BattleRecords records = new BattleRecords(tables);
    final Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    final List<Deflection> deflections = new ArrayList<>();
    final List<ProjectileEntity> arrows = new ArrayList<>();
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
                          tick,
                          projectile,
                          parent,
                          source,
                          projectile.side(),
                          projectile.getAimX(),
                          projectile.getAimY(),
                          projectile.getTarget()));
                }
              });
    }

    void step() {
      match.getBattle().step();
      assertThat(++ticks).as("the scene plays out in time").isLessThan(TICKS);
      for (BattleEntity entity : match.getWorld().getHolder().entities()) {
        if (entity instanceof ProjectileEntity p
            && p.getData().name().equals(ARROWS)
            && !arrows.contains(p)) {
          arrows.add(p);
        }
      }
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
      monk.requestAbility();
      while (deflectOf(monk) == null) {
        step();
      }
      return monk;
    }

    /** The Deflect a Monk casts, or null before it is cast. */
    AreaEffectEntity deflectOf(CharacterEntity monk) {
      for (BattleEntity entity : match.getWorld().getHolder().entities()) {
        if (entity instanceof AreaEffectEntity area
            && area.getData().name().equals(DEFLECT)
            && area.getFollow() == monk) {
          return area;
        }
      }
      return null;
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

    /** Steps until every arrow of the volley has landed or left. */
    void playOutVolley() {
      int waves = number(row("spells_other", "Arrows"), "ProjectileWaves");
      int count = number(row("spells_other", "Arrows"), "MultipleProjectiles");
      while (arrows.size() < waves * count || arrows.stream().anyMatch(p -> !p.isReleased())) {
        step();
      }
      step();
    }
  }

  @Test
  @DisplayName(
      "Arrows cast onto the Monk are turned at their ring points and sent, with no target, at the"
          + " caster's crown tower nearest the Deflect; the Monk is listed by each wave it turned")
  void arrowsAreSentAtTheCastersCrownTowerNearestTheDeflect() {
    Scene scene = new Scene();
    CharacterEntity monk = scene.monkWithDeflect(0, 3500, 10000);
    AreaEffectEntity deflect = scene.deflectOf(monk);
    WorldEntity tower = scene.nearestCrownTower(1, deflect.getX(), deflect.getY());
    assertThat(tower.getData().king()).as("a princess tower is the nearest here").isFalse();
    scene.match.play(
        scene.tick() + 1, scene.records.card("Arrows"), LEVEL, 1, 3500, 10000, "arrows");
    scene.playOutVolley();
    assertThat(scene.deflections).as("arrows of the volley are turned").isNotEmpty();
    Set<ProjectileChain> waves = new HashSet<>();
    for (Deflection d : scene.deflections) {
      assertThat(d.projectile().getData().name()).isEqualTo(ARROWS);
      assertThat(d.parent()).isSameAs(monk);
      assertThat(d.source()).as("sent at a point, not back at its source").isNull();
      assertThat(d.side()).isEqualTo(monk.side());
      assertThat(d.aimX()).isEqualTo(tower.getView().getX());
      assertThat(d.aimY()).isEqualTo(tower.getView().getY());
      assertThat(d.target()).isNull();
      assertThat(d.projectile().getDeflections()).isEqualTo(1);
      // Turned at its arrival, as measured at its ring point: within the Deflect's reach of it.
      ProjectileEntity arrow = d.projectile();
      int reach = deflect.deflectRadius() + arrow.getData().deflectRadius();
      assertThat(
              FixedMath.guardedSumOfSquares(
                  arrow.getRingX() - deflect.getX(), arrow.getRingY() - deflect.getY()))
          .isLessThan(reach * reach);
      waves.add(arrow.getChain());
    }
    for (ProjectileChain wave : waves) {
      assertThat(wave.getHitIds()).as("the wave lists the Monk").contains(monk.getId());
    }
    // Arrows that were not turned land on their ring points as before.
    assertThat(scene.arrows.stream().filter(p -> p.getDeflections() == 0)).isNotEmpty();
  }

  @Test
  @DisplayName(
      "an arrow whose ring point the Deflect reaches is not turned when the Deflect does not overlap"
          + " its wave's circle")
  void arrowsOutsideTheWaveCirclePassTheDeflect() {
    Scene scene = new Scene();
    int spellRadius = number(row("spells_other", "Arrows"), "Radius");
    int deflectRadius = number(row("area_effect_objects", DEFLECT), "Radius");
    int arrowReach = number(row("projectiles", ARROWS), "DeflectRadius");
    // The spell's point, on the placement grid, and the Monk beside it, the Deflect's circle just
    // missing the wave's circle.
    int centreX = 3500;
    int centreY = 10500;
    CharacterEntity monk =
        scene.monkWithDeflect(0, centreX + deflectRadius + spellRadius + 300, centreY);
    AreaEffectEntity deflect = scene.deflectOf(monk);
    assertThat(deflect.deflectRadius()).isEqualTo(deflectRadius);
    scene.match.play(
        scene.tick() + 1, scene.records.card("Arrows"), LEVEL, 1, centreX, centreY, "arrows");
    scene.playOutVolley();
    assertThat(scene.arrows.get(0).getChain().getX()).isEqualTo(centreX);
    assertThat(scene.arrows.get(0).getChain().getY()).isEqualTo(centreY);
    assertThat(
            ShapeTests.withinCircle(
                deflect.getX(),
                deflect.getY(),
                deflect.deflectRadius(),
                centreX,
                centreY,
                spellRadius))
        .isFalse();
    assertThat(scene.deflections).isEmpty();
    // An arrow of every wave arrived with its ring point well within the Deflect's reach.
    int reach = deflectRadius + arrowReach - 200;
    assertThat(
            scene.arrows.stream()
                .filter(
                    p ->
                        FixedMath.guardedSumOfSquares(
                                p.getRingX() - deflect.getX(), p.getRingY() - deflect.getY())
                            < reach * reach))
        .hasSizeGreaterThanOrEqualTo(number(row("spells_other", "Arrows"), "ProjectileWaves"));
  }
}
