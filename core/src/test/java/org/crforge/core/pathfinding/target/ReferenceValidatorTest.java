package org.crforge.core.pathfinding.target;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.pathfinding.GridEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The rules that decide whether a unit may keep or take a target. */
class ReferenceValidatorTest {

  private TargetingState knight;
  private TargetView enemyTower;
  private TargetView friendlyTower;
  private ValidatorQueries queries;

  private static GridEntity entity(String name, int id, int side, int x, int y) {
    GridEntity e = new GridEntity();
    e.setName(name);
    e.setId(id);
    e.setSide(side);
    e.setX(x);
    e.setY(y);
    e.setCollisionRadius(1000);
    return e;
  }

  private static TargetView tower(String name, int id, int side, int x, int y) {
    GridEntity e = entity(name, id, side, x, y);
    e.setBuilding(true);
    e.setSlot170(1);
    e.setSlot190(1);
    return new TargetView(
        e, TargetingConfig.tower("PrincessTower", 7500, 7500, 1000, 800, 0, true));
  }

  @BeforeEach
  void setUp() {
    GridEntity unit = entity("owner", 7, 0, 3500, 10000);
    unit.setCollisionRadius(500);
    unit.setSlot190(1);
    unit.setState(1);

    knight = new TargetingState();
    knight.setOwner(unit);
    knight.setConfig(TargetingConfig.forUnit(1200, 5500, 500, 1200, 700, true, false));
    knight.setMovementComponentActive(true);
    queries = ValidatorQueries.standard1v1();

    enemyTower = tower("PrincessTower_1_1", 5, 1, 3500, 25500);
    friendlyTower = tower("PrincessTower_0_1", 2, 0, 3500, 6500);
  }

  @Test
  @DisplayName("an enemy tower is a valid target and a friendly one is not")
  void teamDecidesFirst() {
    assertThat(
            ReferenceValidator.validate(knight, enemyTower, ReferenceValidator.MODE_TAKE, queries))
        .isTrue();
    assertThat(
            ReferenceValidator.validate(
                knight, friendlyTower, ReferenceValidator.MODE_TAKE, queries))
        .isFalse();
  }

  @Test
  @DisplayName("a null target and the unit itself are refused")
  void nullAndSelfAreRefused() {
    TargetView self = new TargetView(knight.getOwner(), knight.getConfig());
    self.setHitPointsPresent(true);

    assertThat(ReferenceValidator.validate(knight, null, ReferenceValidator.MODE_TAKE, queries))
        .isFalse();
    assertThat(ReferenceValidator.validate(knight, self, ReferenceValidator.MODE_TAKE, queries))
        .isFalse();
  }

  @Test
  @DisplayName("a target with no hit points left is refused unless the alive check is bypassed")
  void aliveCheckAndItsBypass() {
    enemyTower.getEntity().setAlive(false);

    assertThat(
            ReferenceValidator.validate(knight, enemyTower, ReferenceValidator.MODE_TAKE, queries))
        .isFalse();

    knight.setAliveCheckBypass(true);
    assertThat(
            ReferenceValidator.validate(knight, enemyTower, ReferenceValidator.MODE_TAKE, queries))
        .isTrue();
  }

  @Test
  @DisplayName("the untargetable flag hides an entity from everything")
  void untargetableIsRefused() {
    enemyTower.getEntity().setFlags(TargetingFlags.UNTARGETABLE);

    assertThat(
            ReferenceValidator.validate(knight, enemyTower, ReferenceValidator.MODE_TAKE, queries))
        .isFalse();
  }

  @Test
  @DisplayName("a target already on the hit list is refused to a dashing or special attacker only")
  void hitListRefusesDashAndSpecialAttackers() {
    knight.getHitTargetIds().add(enemyTower.id());

    assertThat(
            ReferenceValidator.validate(knight, enemyTower, ReferenceValidator.MODE_TAKE, queries))
        .isTrue();

    knight.setConfig(knight.getConfig().toBuilder().dashCount(1).build());
    assertThat(
            ReferenceValidator.validate(knight, enemyTower, ReferenceValidator.MODE_TAKE, queries))
        .isFalse();
  }

  @Test
  @DisplayName(
      "a ground attacker refuses an air target and an air-only attacker refuses the ground")
  void airAndGroundPairing() {
    GridEntity balloon = entity("Balloon", 9, 1, 3500, 12000);
    balloon.setAir(true);
    balloon.setSlot190(1);
    TargetView airTarget =
        new TargetView(balloon, TargetingConfig.forUnit(500, 5000, 500, 1000, 500, true, false));

    assertThat(
            ReferenceValidator.validate(knight, airTarget, ReferenceValidator.MODE_TAKE, queries))
        .isFalse();

    knight.setConfig(knight.getConfig().toBuilder().attacksAir(true).attacksGround(false).build());
    assertThat(
            ReferenceValidator.validate(knight, airTarget, ReferenceValidator.MODE_TAKE, queries))
        .isTrue();
    assertThat(
            ReferenceValidator.validate(knight, enemyTower, ReferenceValidator.MODE_TAKE, queries))
        .isFalse();
  }

  @Test
  @DisplayName("the air-only gate opens the first building check but not the second")
  void airOnlyGateOpensOnlyTheFirstBuildingCheck() {
    GridEntity balloon = entity("Balloon", 9, 1, 3500, 12000);
    balloon.setAir(true);
    balloon.setSlot190(1);
    TargetingConfig plain = TargetingConfig.forUnit(500, 5000, 500, 1000, 500, true, false);
    TargetView airTarget = new TargetView(balloon, plain);

    knight.setConfig(
        knight.getConfig().toBuilder()
            .attacksAir(true)
            .attacksGround(false)
            .targetOnlyBuildings(true)
            .build());

    // The gate carries the target past the building filter, but a non-building that is not a tower
    // still has to advertise itself as a building target.
    assertThat(
            ReferenceValidator.validate(knight, airTarget, ReferenceValidator.MODE_TAKE, queries))
        .isFalse();

    TargetView advertised = new TargetView(balloon, plain.toBuilder().buildingTarget(true).build());
    assertThat(
            ReferenceValidator.validate(knight, advertised, ReferenceValidator.MODE_TAKE, queries))
        .isTrue();
  }

  @Test
  @DisplayName("a building-only attacker refuses an ordinary troop")
  void buildingFilterRefusesTroops() {
    GridEntity troop = entity("Archer", 9, 1, 3500, 12000);
    troop.setSlot190(1);
    TargetView troopTarget =
        new TargetView(troop, TargetingConfig.forUnit(5000, 5500, 500, 1000, 500, true, true));

    knight.setConfig(knight.getConfig().toBuilder().targetOnlyBuildings(true).build());

    assertThat(
            ReferenceValidator.validate(knight, troopTarget, ReferenceValidator.MODE_TAKE, queries))
        .isFalse();
    assertThat(
            ReferenceValidator.validate(knight, enemyTower, ReferenceValidator.MODE_TAKE, queries))
        .isTrue();
  }

  @Test
  @DisplayName("a troop-only attacker refuses a building and a tower-avoider refuses a tower")
  void troopAndTowerFilters() {
    knight.setConfig(knight.getConfig().toBuilder().targetOnlyTroops(true).build());
    assertThat(
            ReferenceValidator.validate(knight, enemyTower, ReferenceValidator.MODE_TAKE, queries))
        .isFalse();

    knight.setConfig(
        TargetingConfig.forUnit(1200, 5500, 500, 1200, 700, true, false).toBuilder()
            .doNotTargetTowers(true)
            .build());
    assertThat(
            ReferenceValidator.validate(knight, enemyTower, ReferenceValidator.MODE_TAKE, queries))
        .isFalse();
  }

  @Test
  @DisplayName("a target that answers no hit-point object is refused by the final step")
  void finalStepNeedsAHitPointObject() {
    enemyTower.setHitPointsPresent(false);

    assertThat(
            ReferenceValidator.validate(knight, enemyTower, ReferenceValidator.MODE_TAKE, queries))
        .isFalse();

    enemyTower.setHitPointsPresent(true);
    enemyTower.setAcceptsAttacker(false);
    assertThat(
            ReferenceValidator.validate(knight, enemyTower, ReferenceValidator.MODE_TAKE, queries))
        .isFalse();
  }
}
