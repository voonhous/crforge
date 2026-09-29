package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A continuous-damage attacker where the Inferno runs do not reach it: the windows its timer walks,
 * the ramp a broken shield resets, the beam a target beyond the plain range stops, and what is
 * refused of it.
 */
class BattleContinuousDamageTest {

  private static Standard1v1Battle passiveTowers() {
    return new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
  }

  @Test
  @DisplayName(
      "the Inferno Tower's numbered columns make a Hittime sequence of three, whose windows its"
          + " timer walks")
  void theWindowsFollowTheTimer() {
    AttackSequence sequence = GameData.unit("InfernoTower").attackSequence();
    assertThat(sequence.mode()).isEqualTo(AttackSequence.MODE_HITTIME);
    assertThat(sequence.order()).containsExactly(0, 1, 2);

    assertThat(sequence.windowAt(0)).isZero();
    assertThat(sequence.windowAt(1999)).isZero();
    assertThat(sequence.windowAt(2000)).isEqualTo(1);
    assertThat(sequence.windowAt(3999)).isEqualTo(1);
    assertThat(sequence.windowAt(4000)).as("the last window has no end").isEqualTo(2);
    assertThat(sequence.windowAt(60000)).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "the hit that breaks its target's shield resets the ramp in the hit's own pass, keeping the"
          + " reference and the index")
  void aBrokenShieldResetsTheRamp() {
    Standard1v1Battle match = passiveTowers();
    CharacterEntity inferno =
        match.deploy(
            0, GameData.unit("InfernoTower"), Standard1v1Battle.DEFAULT_LEVEL, 0, 3500, 10000);
    CharacterEntity guard =
        match.deploy(
            40, GameData.unit("SkeletonWarrior"), Standard1v1Battle.DEFAULT_LEVEL, 1, 3500, 14000);
    List<String> broken = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void shieldHit(
                  int tick, WorldEntity target, int damage, int shieldBefore, int shieldAfter) {
                if (target == guard && shieldBefore > 0 && shieldAfter == 0) {
                  broken.add(
                      "timer "
                          + inferno.getTargeting().getAttackTimerMs()
                          + " index "
                          + inferno.getTargeting().getAttackSequenceIndex());
                }
              }
            });
    for (int step = 0; step < 200 && broken.isEmpty(); step++) {
      match.getBattle().step();
    }

    // Told before the break's walk ran: the ramp had reached its second window.
    assertThat(broken).hasSize(1);
    assertThat(broken.get(0)).startsWith("timer ").endsWith(" index 1");
    assertThat(inferno.getTargeting().getAttackTimerMs()).as("reset").isZero();
    assertThat(inferno.getTargeting().getAttackSequenceIndex()).as("kept").isEqualTo(1);
    assertThat(inferno.getTargeting().getReference()).isSameAs(guard.getTargetView());
  }

  @Test
  @DisplayName(
      "a reference beyond the plain range while the beam runs is dropped at once, and the"
          + " target-lost timer starts")
  void aTargetBeyondTheRangeIsDropped() {
    Standard1v1Battle match = passiveTowers();
    CharacterEntity inferno =
        match.deploy(
            0, GameData.unit("InfernoTower"), Standard1v1Battle.DEFAULT_LEVEL, 0, 3500, 10000);
    CharacterEntity knight =
        match.deploy(40, GameData.unit("Knight"), Standard1v1Battle.DEFAULT_LEVEL, 1, 3500, 15000);
    for (int step = 0; step < 200 && inferno.getTargeting().getAttackTimerMs() < 1000; step++) {
      match.getBattle().step();
    }
    assertThat(inferno.getTargeting().getReference()).isSameAs(knight.getTargetView());
    assertThat(inferno.getView().getState()).isEqualTo(GridEntityState.ATTACKING);

    // Carried out of the tower's 6000 plus both radii; well inside its sight.
    knight.getView().setY(17500);
    match.getBattle().step();

    assertThat(inferno.getTargeting().getReference()).isNull();
    assertThat(inferno.getTargeting().getAttackTimerMs()).isZero();
    assertThat(inferno.getTargeting().getTargetLostTimerMs())
        .as("started at 1, then counted on in the same visit")
        .isEqualTo(51);
  }

  @Test
  @DisplayName("a morph of a unit a continuous-damage attacker references is refused")
  void aMorphUnderTheBeamIsRefused() {
    Standard1v1Battle match = passiveTowers();
    CharacterEntity inferno =
        match.deploy(
            0, GameData.unit("InfernoTower"), Standard1v1Battle.DEFAULT_LEVEL, 1, 3500, 22000);
    match.play(
        0, GameData.card("GoblinDrill"), Standard1v1Battle.DEFAULT_LEVEL, 0, 3500, 25500, "Drill");
    match.getBattle().step();
    CharacterEntity dig = match.getPlays().get(0).units().get(0);
    inferno.getTargeting().setReference(dig.getTargetView());

    assertThatThrownBy(() -> match.getWorld().morph(dig))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("continuous-damage attacker");
  }

  @Test
  @DisplayName("the Mighty Miner's lane switch is refused when its ability is requested")
  void theLaneSwitchIsRefused() {
    Standard1v1Battle match = passiveTowers();
    CharacterEntity miner =
        new CharacterEntity(
            match.getWorld(), GameData.unit("MightyMiner"), "Miner", 0, 3500, 10000, 11);

    assertThatThrownBy(miner::requestAbility)
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("SwitchLanes");
  }
}
