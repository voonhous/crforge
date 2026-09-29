package org.crforge.core.battle.projectile;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.unit.CharacterEntity;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.core.battle.unit.TowerEntity;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.core.pathfinding.GridEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A projectile's part of the damage on its way to its target where no reference run shows it: which
 * projectile registers it and how much, and the hand-backs other than the arrival's, which every
 * reference run's homing shot takes first.
 */
class ProjectilePendingDamageTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The princess tower's arrow, a homing projectile. */
  private static final String ARROW = "TowerPrincessProjectile";

  private static Standard1v1Battle passiveTowers() {
    return new Standard1v1Battle(GameData.tables(), LEVEL, false);
  }

  private static TowerEntity tower(Standard1v1Battle match, String name) {
    for (BattleEntity entity : match.getBattle().getHolder().entities()) {
      if (entity instanceof TowerEntity tower && tower.name().equals(name)) {
        return tower;
      }
    }
    throw new IllegalStateException("No tower named " + name);
  }

  /** A projectile of the row launched from the launcher at the target, not yet started. */
  private static ProjectileEntity launched(
      Standard1v1Battle match, ProjectileData data, WorldEntity launcher, WorldEntity target) {
    ProjectileEntity p = new ProjectileEntity(match.getWorld(), data, launcher.side());
    GridEntity from = launcher.getView();
    GridEntity to = target.getView();
    p.launch(launcher, target, from.getX(), from.getY(), 1000, to.getX(), to.getY());
    return p;
  }

  @Test
  @DisplayName("a release hands the registered damage back once, and so does a collision's finish")
  void aReleaseHandsTheDamageBackOnce() {
    Standard1v1Battle match = passiveTowers();
    CharacterEntity knight = match.deploy(0, GameData.unit("Knight"), LEVEL, 0, 3500, 20000);
    match.getBattle().step();
    TowerEntity tower = tower(match, "PrincessTower_1_1");
    ProjectileData arrow = GameData.records().projectile(ARROW);
    GridEntity view = knight.getView();

    ProjectileEntity released = launched(match, arrow, tower, knight);
    released.onRegistered();
    assertThat(view.getPendingDamageAmount()).isEqualTo(released.damage());
    released.release();
    assertThat(view.getPendingDamageAmount()).isZero();
    assertThat(released.isPendingRegistered()).isFalse();
    released.release();
    assertThat(view.getPendingDamageAmount()).as("nothing left to hand back").isZero();

    ProjectileEntity finished = launched(match, arrow, tower, knight);
    finished.onRegistered();
    assertThat(view.getPendingDamageAmount()).isEqualTo(finished.damage());
    finished.finishOnCollision();
    assertThat(view.getPendingDamageAmount()).isZero();
    assertThat(finished.isPendingRegistered()).isFalse();
  }

  @Test
  @DisplayName("a projectile that is not homing registers nothing on its target")
  void aProjectileThatIsNotHomingRegistersNothing() {
    Standard1v1Battle match = passiveTowers();
    CharacterEntity knight = match.deploy(0, GameData.unit("Knight"), LEVEL, 0, 3500, 20000);
    match.getBattle().step();
    TowerEntity tower = tower(match, "PrincessTower_1_1");
    ProjectileData straight =
        GameData.records().projectile(ARROW).toBuilder().homing(false).build();

    ProjectileEntity p = launched(match, straight, tower, knight);
    p.onRegistered();
    assertThat(p.getTarget()).as("it keeps its target").isSameAs(knight);
    assertThat(knight.getView().getPendingDamageAmount()).isZero();
    assertThat(p.isPendingRegistered()).isFalse();
  }

  @Test
  @DisplayName("a shot at a crown tower registers its crown-tower damage")
  void aShotAtACrownTowerRegistersItsCrownTowerDamage() {
    Standard1v1Battle match = passiveTowers();
    CharacterEntity knight = match.deploy(0, GameData.unit("Knight"), LEVEL, 0, 3500, 20000);
    match.getBattle().step();
    TowerEntity tower = tower(match, "PrincessTower_1_1");
    ProjectileData halved =
        GameData.records().projectile(ARROW).toBuilder().crownTowerDamagePercent(-50).build();

    ProjectileEntity p = launched(match, halved, knight, tower);
    p.onRegistered();
    assertThat(p.towerDamage()).isNotEqualTo(p.damage());
    assertThat(tower.getView().getPendingDamageAmount()).isEqualTo(p.towerDamage());
  }

  @Test
  @DisplayName(
      "a homing projectile's arrival hands its damage back without asking whether it registered")
  void theArrivalHandsBackWithoutAskingWhetherItRegistered() {
    Standard1v1Battle match = passiveTowers();
    CharacterEntity knight = match.deploy(0, GameData.unit("Knight"), LEVEL, 0, 3500, 20000);
    match.getBattle().step();
    TowerEntity tower = tower(match, "PrincessTower_1_1");
    ProjectileEntity p = launched(match, GameData.records().projectile(ARROW), tower, knight);
    knight.addPendingDamage(500, 300);

    for (int step = 0; step < 100 && !p.isReleased(); step++) {
      ProjectileFlight.fly(p, match.getWorld());
    }
    assertThat(p.isReleased()).isTrue();
    assertThat(knight.getView().getPendingDamageAmount()).isEqualTo(500 - p.damage());
  }
}
