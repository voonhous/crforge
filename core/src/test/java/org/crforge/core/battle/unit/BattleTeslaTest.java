package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A Tesla's hiding where the reference runs leave it: hidden is not untouchable, what an area
 * effect that reaches hidden units gets through, and the building a character building's targeting
 * is.
 */
class BattleTeslaTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** A Tesla placed on the bottom side's half, stepped until it is hidden, the towers passive. */
  private static CharacterEntity hiddenTesla(Standard1v1Battle match) {
    CharacterEntity tesla = match.deploy(0, GameData.unit("Tesla"), LEVEL, 0, 10000, 12000);
    // Its deploy ends on the twentieth step; sixteen visits of 50 take its counter to 800.
    for (int step = 0; step < 40 && !tesla.hidden(); step++) {
      match.getBattle().step();
    }
    return tesla;
  }

  @Test
  @DisplayName("a hidden Tesla is hidden but not untouchable")
  void hiddenIsNotUntouchable() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    CharacterEntity tesla = hiddenTesla(match);

    assertThat(tesla.hidden()).isTrue();
    assertThat(tesla.untouchable()).isFalse();
    assertThat(tesla.untouchable(false)).isFalse();
  }

  @Test
  @DisplayName(
      "an area passes a hidden Tesla by unless it reaches hidden units, whose damage the entry"
          + " lets through")
  void anAreaReachingHiddenUnitsGetsThrough() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    CharacterEntity tesla = hiddenTesla(match);
    int before = tesla.getHitPoints().getHitPoints();

    assertThat(tesla.passedBy(false)).isTrue();
    assertThat(tesla.passedBy(true)).isFalse();
    assertThat(tesla.takeDamage(100, 0, 0, 1)).isEqualTo(DamageResult.NOTHING);
    assertThat(tesla.getHitPoints().getHitPoints()).isEqualTo(before);
    assertThat(tesla.takeDamage(100, 0, 0, 1, true).landed()).isTrue();
    assertThat(tesla.getHitPoints().getHitPoints()).isEqualTo(before - 100);
  }

  @Test
  @DisplayName("a character building's targeting is a building's, a troop's is not")
  void aCharacterBuildingTargetsAsABuilding() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    CharacterEntity tesla = match.deploy(0, GameData.unit("Tesla"), LEVEL, 0, 10000, 12000);
    CharacterEntity knight = match.deploy(0, GameData.unit("Knight"), LEVEL, 0, 3500, 10000);

    assertThat(tesla.getTargeting().getConfig().isBuilding()).isTrue();
    assertThat(knight.getTargeting().getConfig().isBuilding()).isFalse();
  }

  @Test
  @DisplayName(
      "a stun over its deploy end switches its targeting off in the gate, before the targeting"
          + " visit could take a target")
  void aStunOverItsDeployEndSkipsTheVisit() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    CharacterEntity tesla = match.deploy(0, GameData.unit("Tesla"), LEVEL, 0, 10000, 12000);
    // A Freeze of the top side on the deploying Tesla lasts well past its deploy end.
    match.placeAreaEffect(5, "Freeze", LEVEL, 1, 10000, 12000, "Freeze");
    List<Boolean> referenced = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void deployEndVisited(int tick, CharacterEntity unit) {
                referenced.add(unit.getTargeting().getReference() != null);
              }
            });
    for (int step = 0; step < 25; step++) {
      match.getBattle().step();
    }

    assertThat(tesla.getBuffs().carries("Freeze")).isTrue();
    assertThat(referenced).containsExactly(false);
    assertThat(tesla.isActive(CharacterEntity.TARGETING_SLOT)).isFalse();
  }

  @Test
  @DisplayName("without a stun, its deploy end takes its default target at once")
  void itsDeployEndTakesItsDefaultTarget() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    match.deploy(0, GameData.unit("Tesla"), LEVEL, 0, 10000, 12000);
    List<Boolean> referenced = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void deployEndVisited(int tick, CharacterEntity unit) {
                referenced.add(unit.getTargeting().getReference() != null);
              }
            });
    for (int step = 0; step < 25; step++) {
      match.getBattle().step();
    }

    assertThat(referenced).containsExactly(true);
  }
}
