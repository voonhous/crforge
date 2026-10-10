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
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.math.FixedMath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Spell-like projectiles - those whose deflection behaviour uses the spells' tower share, such as
 * the Fireball and the evolved Cannon's bombs - turned around by an enemy Monk's Deflect.
 *
 * <p>One with no target is sent at a point: the position of the enemy crown tower nearest the
 * Deflect, measured from the Deflect, with no target. One with a target, as a bomb dropped onto its
 * own area effect, is sent back at its root, that area effect, so it lands where it fell. Once
 * deflected, one whose owner, a character, stands within DOUBLEDEFLECT_SPELL_MIN_DISTANCE of the
 * next enemy Deflect it meets passes it untouched; farther, that Deflect turns it around again.
 */
class SpellDeflectTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** Long enough for everything to deploy, the Monks to cast, and a projectile to land. */
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

  /** The Fireball card's projectile. */
  private static final String FIREBALL = text(row("spells_other", "Fireball"), "Projectile");

  /** One deflection a world observer saw, and the projectile's state right after it. */
  private record Deflection(
      int tick,
      ProjectileEntity projectile,
      WorldEntity parent,
      WorldEntity source,
      int side,
      int aimX,
      int aimY,
      WorldEntity target,
      WorldEntity root,
      boolean areaTargeted) {}

  /** A battle that records every deflection. */
  private static final class Scene {

    final GameTables tables = GameData.tables();
    final BattleRecords records = new BattleRecords(tables);
    final Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    final List<Deflection> deflections = new ArrayList<>();
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
                          projectile.getTarget(),
                          projectile.getRoot(),
                          projectile.getAreaTarget() != null));
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

    List<WorldEntity> crownTowers() {
      List<WorldEntity> out = new ArrayList<>();
      for (BattleEntity entity : match.getWorld().getHolder().entities()) {
        if (entity instanceof WorldEntity tower
            && (tower.getData().king() || tower.getData().summonerTower())) {
          out.add(tower);
        }
      }
      return out;
    }
  }

  @Test
  @DisplayName(
      "a Fireball cast onto the Monk deals the Monk its damage and is sent, with no target, at the"
          + " enemy crown tower nearest the Deflect, where it lands")
  void fireballIsSentAtTheCrownTowerNearestTheDeflect() {
    Scene scene = new Scene();
    CharacterEntity monk = scene.monkWithDeflect(0, 3500, 10000);
    AreaEffectEntity deflect = scene.deflectOf(monk);
    WorldEntity tower = scene.nearestCrownTower(1, deflect.getX(), deflect.getY());
    assertThat(tower.getData().king()).as("a princess tower is the nearest here").isFalse();
    List<Integer> towersBefore = new ArrayList<>();
    for (WorldEntity t : scene.crownTowers()) {
      towersBefore.add(t.getHitPoints().getHitPoints());
    }
    int monkBefore = monk.getHitPoints().getHitPoints();
    scene.match.play(
        scene.tick() + 1, scene.records.card("Fireball"), LEVEL, 1, 3500, 10000, "fireball");
    while (scene.deflections.isEmpty()) {
      scene.step();
    }
    Deflection d = scene.deflections.get(0);
    assertThat(d.projectile().getData().name()).isEqualTo(FIREBALL);
    assertThat(d.parent()).isSameAs(monk);
    assertThat(d.source()).as("sent at a point, not back at its source").isNull();
    assertThat(d.side()).isEqualTo(monk.side());
    assertThat(d.aimX()).isEqualTo(tower.getView().getX());
    assertThat(d.aimY()).isEqualTo(tower.getView().getY());
    assertThat(d.target()).isNull();
    assertThat(d.root()).isSameAs(monk);
    ProjectileEntity fireball = d.projectile();
    while (!fireball.isReleased()) {
      scene.step();
    }
    scene.step();
    // The Monk took the Fireball's damage as it turned it around, and the Fireball landed on the
    // tower it was sent at, none of the others.
    assertThat(monk.getHitPoints().getHitPoints()).isLessThan(monkBefore);
    List<WorldEntity> towers = scene.crownTowers();
    for (int i = 0; i < towers.size(); i++) {
      if (towers.get(i) == tower) {
        assertThat(tower.getHitPoints().getHitPoints()).isLessThan(towersBefore.get(i));
      } else {
        assertThat(towers.get(i).getHitPoints().getHitPoints())
            .as(towers.get(i).name())
            .isEqualTo(towersBefore.get(i));
      }
    }
    assertThat(scene.deflections).hasSize(1);
  }

  @Test
  @DisplayName(
      "an evolved Cannon's bomb falling within an enemy Deflect is sent back at its own area"
          + " effect, still its root and target, so it lands where it fell on the next step")
  void barrageBombIsSentBackAtItsAreaEffect() {
    Scene scene = new Scene();
    int cannonX = 8500;
    int cannonY = 10500;
    // The farthest bomb ahead of a bottom-side Cannon, half tiles both.
    List<Integer> across = Shipped.numbers("Cannon_EV1_barrage", "BombAbsoluteHorizontalOffsets");
    List<Integer> ahead = Shipped.numbers("Cannon_EV1_barrage", "BombVerticalOffsets");
    int far = 0;
    for (int i = 1; i < ahead.size(); i++) {
      if (ahead.get(i) > ahead.get(far)) {
        far = i;
      }
    }
    int bombX = across.get(far) * 500;
    int bombY = cannonY + ahead.get(far) * 500;
    CharacterEntity monk = scene.monkWithDeflect(1, bombX, bombY + 1000);
    scene.match.deploy(
        scene.tick() + 1, scene.records.unit("Cannon_EV1"), LEVEL, 0, cannonX, cannonY, "C");
    while (scene.deflections.isEmpty()) {
      scene.step();
    }
    Deflection d = scene.deflections.get(0);
    ProjectileEntity bomb = d.projectile();
    assertThat(bomb.getStartX()).isEqualTo(bombX);
    assertThat(bomb.getStartY()).isEqualTo(bombY);
    assertThat(d.parent()).isSameAs(monk);
    assertThat(d.side()).isEqualTo(monk.side());
    // Not sent at a crown tower: back at the area effect it was dropped onto, where it fell.
    assertThat(d.aimX()).isEqualTo(bombX);
    assertThat(d.aimY()).isEqualTo(bombY);
    assertThat(d.areaTargeted()).isTrue();
    assertThat(d.root()).isSameAs(monk);
    assertThat(bomb.isReleased()).isFalse();
    scene.step();
    assertThat(bomb.isReleased()).as("it lands on the next step").isTrue();
    assertThat(bomb.getX()).isEqualTo(bombX);
    assertThat(bomb.getY()).isEqualTo(bombY);
  }

  /**
   * A bottom Monk standing by the enemy's left princess tower casts its Deflect; a top Monk of the
   * enemy, at the given point near that tower, casts its own; then the enemy casts a Fireball onto
   * the bottom Monk, which sends it at that tower, where the top Deflect reaches its arrival.
   */
  private static Scene twoMonks(int topX, int topY) {
    Scene scene = new Scene();
    scene.monkWithDeflect(0, BOTTOM_X, BOTTOM_Y);
    scene.monkWithDeflect(1, topX, topY);
    scene.match.play(
        scene.tick() + 1, scene.records.card("Fireball"), LEVEL, 1, BOTTOM_X, BOTTOM_Y, "fireball");
    while (scene.deflections.isEmpty()) {
      scene.step();
    }
    return scene;
  }

  /** Where the bottom Monk of the two-Monk scenes stands: by the enemy's left princess tower. */
  private static final int BOTTOM_X = 3500;

  private static final int BOTTOM_Y = 23300;

  @Test
  @DisplayName(
      "a deflected Fireball passes an enemy Deflect untouched while the Monk that turned it stands"
          + " within DOUBLEDEFLECT_SPELL_MIN_DISTANCE of it")
  void deflectedFireballPassesADeflectNearItsOwner() {
    int least =
        new BattleRecords(GameData.tables()).globalNumber("DOUBLEDEFLECT_SPELL_MIN_DISTANCE");
    // Between the bottom Monk and the tower, within the distance of the bottom Monk.
    Scene scene = twoMonks(BOTTOM_X, BOTTOM_Y + least - 500);
    ProjectileEntity fireball = scene.deflections.get(0).projectile();
    WorldEntity tower = scene.nearestCrownTower(1, BOTTOM_X, BOTTOM_Y);
    assertThat(scene.deflections.get(0).aimX()).isEqualTo(tower.getView().getX());
    assertThat(scene.deflections.get(0).aimY()).isEqualTo(tower.getView().getY());
    while (!fireball.isReleased()) {
      scene.step();
    }
    assertThat(scene.deflections).as("the top Monk does not turn it").hasSize(1);
    assertThat(fireball.getDeflections()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "a deflected Fireball meeting an enemy Deflect farther than DOUBLEDEFLECT_SPELL_MIN_DISTANCE"
          + " from the Monk that turned it is turned again, at that side's nearest crown tower")
  void deflectedFireballIsTurnedAgainFarFromItsOwner() {
    int least =
        new BattleRecords(GameData.tables()).globalNumber("DOUBLEDEFLECT_SPELL_MIN_DISTANCE");
    // Beside the tower, farther than the distance from the bottom Monk, within reach of the tower.
    Scene scene = twoMonks(BOTTOM_X + least + 200, BOTTOM_Y + least);
    ProjectileEntity fireball = scene.deflections.get(0).projectile();
    while (scene.deflections.size() < 2 && !fireball.isReleased()) {
      scene.step();
    }
    assertThat(scene.deflections).as("the top Monk turns it again").hasSize(2);
    Deflection second = scene.deflections.get(1);
    assertThat(second.projectile()).isSameAs(fireball);
    assertThat(second.source()).isNull();
    AreaEffectEntity deflect = scene.deflectOf((CharacterEntity) second.parent());
    WorldEntity tower = scene.nearestCrownTower(0, deflect.getX(), deflect.getY());
    assertThat(second.aimX()).isEqualTo(tower.getView().getX());
    assertThat(second.aimY()).isEqualTo(tower.getView().getY());
    assertThat(second.side()).isEqualTo(1);
  }
}
