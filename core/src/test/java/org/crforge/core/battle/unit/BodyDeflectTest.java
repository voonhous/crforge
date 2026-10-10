package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.battle.Shipped.actionNames;
import static org.crforge.core.battle.Shipped.row;
import static org.crforge.core.battle.Shipped.text;
import static org.crforge.core.battle.Shipped.unitRow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A body projectile that flies to a point and inverts its direction when deflected - the Bowler's
 * ball - rolling into an enemy Monk's Deflect: the Monk takes its damage, and the ball rolls back
 * the way it came, from where it was turned, by the length of its flight from its start to its aim,
 * for the Monk's side and with no target, hitting what it passes on its way back.
 */
class BodyDeflectTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** Long enough for everything to deploy, the Monk to cast and the ball to roll back. */
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

  /** The Bowler's ball. */
  private static final String BALL = text(unitRow("Bowler"), "Projectile");

  /** A projectile's start and aim as they stood at the end of a tick. */
  private record Flight(int startX, int startY, int aimX, int aimY) {}

  /** One deflection a world observer saw, and the projectile's state right after it. */
  private record Deflection(
      int tick,
      ProjectileEntity projectile,
      WorldEntity parent,
      WorldEntity source,
      int x,
      int y,
      int side,
      int aimX,
      int aimY,
      WorldEntity target,
      Flight before) {}

  /** A battle that records every deflection and every ball's flight at the end of each tick. */
  private static final class Scene {

    final GameTables tables = GameData.tables();
    final BattleRecords records = new BattleRecords(tables);
    final Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    final List<Deflection> deflections = new ArrayList<>();
    final List<ProjectileEntity> balls = new ArrayList<>();
    final Map<ProjectileEntity, Flight> flights = new HashMap<>();
    int ticks;

    Scene() {
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void projectileLaunched(int tick, ProjectileEntity projectile) {
                  if (projectile.getData().name().equals(BALL)) {
                    balls.add(projectile);
                  }
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
                          parent,
                          source,
                          projectile.getX(),
                          projectile.getY(),
                          projectile.side(),
                          projectile.getAimX(),
                          projectile.getAimY(),
                          projectile.getTarget(),
                          flights.get(projectile)));
                }
              });
    }

    void step() {
      match.getBattle().step();
      for (ProjectileEntity ball : balls) {
        flights.put(
            ball, new Flight(ball.getStartX(), ball.getStartY(), ball.getAimX(), ball.getAimY()));
      }
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
      int before = deflectors();
      monk.requestAbility();
      while (deflectors() == before) {
        step();
      }
      return monk;
    }

    int deflectors() {
      int count = 0;
      for (BattleEntity entity : match.getWorld().getHolder().entities()) {
        if (entity instanceof AreaEffectEntity area && area.getData().name().equals(DEFLECT)) {
          count++;
        }
      }
      return count;
    }
  }

  @Test
  @DisplayName(
      "a Bowler's ball rolling into an enemy Deflect deals the Monk its damage and rolls back the"
          + " way it came, for the Monk's side with no target, and hits the Bowler on its way")
  void ballRollsBackTheWayItCame() {
    assertThat(
            new BattleRecords(GameData.tables()).projectile(BALL).deflectBehaviour()
                & ProjectileData.INVERT_DIRECTION)
        .as("the ball inverts its direction when deflected")
        .isNotZero();
    Scene scene = new Scene();
    // Out of every crown tower's reach, the Bowler within its range of the Monk.
    CharacterEntity monk = scene.monkWithDeflect(0, 3500, 14000);
    int monkBefore = monk.getHitPoints().getHitPoints();
    CharacterEntity bowler =
        scene.match.deploy(
            scene.tick() + 1, scene.records.unit("Bowler"), LEVEL, 1, 3500, 17500, "bowler");
    scene.step();
    bowler.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    int bowlerBefore = bowler.getHitPoints().getHitPoints();
    while (scene.deflections.isEmpty()) {
      scene.step();
    }
    Deflection d = scene.deflections.get(0);
    assertThat(d.projectile().getData().name()).isEqualTo(BALL);
    assertThat(d.parent()).isSameAs(monk);
    assertThat(d.source()).as("sent on to a point, not back at its source").isNull();
    assertThat(d.side()).isEqualTo(monk.side());
    assertThat(d.target()).isNull();
    // Its new aim: from where it was turned, back by its flight from its start to its aim.
    Flight before = d.before();
    assertThat(d.aimX()).isEqualTo(d.x() + before.startX() - before.aimX());
    assertThat(d.aimY()).isEqualTo(d.y() + before.startY() - before.aimY());
    assertThat(d.projectile().getStartX()).isEqualTo(d.x());
    assertThat(d.projectile().getStartY()).isEqualTo(d.y());
    ProjectileEntity ball = d.projectile();
    scene.step();
    assertThat(monk.getHitPoints().getHitPoints())
        .as("the Monk takes the ball's damage as it turns it")
        .isLessThan(monkBefore);
    while (bowler.getHitPoints().getHitPoints() == bowlerBefore && !ball.isReleased()) {
      scene.step();
    }
    assertThat(bowlerBefore - bowler.getHitPoints().getHitPoints())
        .as("the ball rolling back hits the Bowler that threw it")
        .isEqualTo(ball.damage());
    assertThat(scene.deflections).hasSize(1);
  }

  @Test
  @DisplayName(
      "a Bowler's ball turned by an enemy Deflect and rolling back through its own princess tower"
          + " hits the tower for its whole crown-tower damage, without the deflected share")
  void ballRolledBackHitsItsTowerWithoutTheDeflectedShare() {
    BattleRecords records = new BattleRecords(GameData.tables());
    assertThat(records.globalNumber("DEFLECTED_PRJ_TOWERS_DMG_MUL"))
        .as("a deflected projectile's impact takes a share of its crown-tower damage")
        .isLessThan(100);
    Scene scene = new Scene();
    // A Monk before the enemy's left princess tower; a Bowler right behind that tower, in line.
    CharacterEntity monk = scene.monkWithDeflect(0, 3500, 23300);
    monk.setActive(CharacterEntity.TARGETING_SLOT, false);
    WorldEntity tower = null;
    for (BattleEntity entity : scene.match.getWorld().getHolder().entities()) {
      if (entity instanceof WorldEntity candidate
          && candidate.getData().summonerTower()
          && candidate.side() == 1
          && candidate.getView().getX() < 9000) {
        tower = candidate;
      }
    }
    assertThat(tower).isNotNull();
    int behind =
        tower.getView().getY()
            + tower.getTargetView().radius()
            + records.unit("Bowler").collisionRadius();
    CharacterEntity bowler =
        scene.match.deploy(
            scene.tick() + 1,
            records.unit("Bowler"),
            LEVEL,
            1,
            tower.getView().getX(),
            behind + 100,
            "bowler");
    scene.step();
    bowler.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    while (scene.deflections.isEmpty()) {
      scene.step();
    }
    ProjectileEntity ball = scene.deflections.get(0).projectile();
    int towerBefore = tower.getHitPoints().getHitPoints();
    while (!ball.getHitIds().contains(tower.getId())) {
      assertThat(ball.isReleased()).as("the ball reaches the tower").isFalse();
      towerBefore = tower.getHitPoints().getHitPoints();
      scene.step();
    }
    // The hit is queued in the pass and dealt by the drain in the same step.
    assertThat(towerBefore - tower.getHitPoints().getHitPoints())
        .isEqualTo(ball.undeflectedTowerDamage())
        .isNotEqualTo(ball.towerDamage());
  }
}
