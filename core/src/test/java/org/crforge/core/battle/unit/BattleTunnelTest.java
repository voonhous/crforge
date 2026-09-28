package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What a unit that tunnels to its placement is while it tunnels, and what is refused of it. */
class BattleTunnelTest {

  private static Standard1v1Battle passiveTowers() {
    return new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
  }

  /** Plays a Miner for the bottom side onto the top side's half and runs its play's step. */
  private static CharacterEntity playMiner(Standard1v1Battle match) {
    match.play(0, GameData.card("Miner"), Standard1v1Battle.DEFAULT_LEVEL, 0, 3500, 25500, "Miner");
    match.getBattle().step();
    return match.getPlays().get(0).units().get(0);
  }

  @Test
  @DisplayName("a tunnelling unit is hidden from the hand-over on, before its first state visit")
  void itIsHiddenFromTheHandOver() {
    Standard1v1Battle match = passiveTowers();
    match.play(0, GameData.card("Miner"), Standard1v1Battle.DEFAULT_LEVEL, 0, 3500, 25500, "Miner");
    List<Boolean> accepts = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void afterPrePass(int tick, List<WorldEntity> present) {
                for (WorldEntity entity : present) {
                  if (entity.name().equals("Miner_0")) {
                    accepts.add(entity.getTargetView().acceptsAttacker(true));
                  }
                }
              }
            });
    match.getBattle().step();

    // The play's command pass ran before this pre-pass: the towers' visits that follow see it
    // hidden already.
    assertThat(accepts).containsExactly(false);
  }

  @Test
  @DisplayName("a tunnelling unit starts on its own king, hidden, and surfaces deploying")
  void itTunnelsHiddenFromItsKing() {
    Standard1v1Battle match = passiveTowers();
    CharacterEntity miner = playMiner(match);
    assertThat(miner.untouchable()).as("every hit and buff passes it by").isTrue();

    assertThat(miner.getView().getState()).isEqualTo(GridEntityState.SPAWN_PATHFIND);
    assertThat(miner.hidden()).isTrue();
    assertThat(miner.getTargetView().acceptsAttacker(true)).as("no attacker takes it").isFalse();
    // Its registration visit took the first step off the king at (9000, 3000).
    assertThat(miner.getView().getY()).isGreaterThan(3000);

    while (miner.getView().getState() == GridEntityState.SPAWN_PATHFIND) {
      match.getBattle().step();
    }
    assertThat(miner.getView().getState()).isEqualTo(GridEntityState.DEPLOYING);
    assertThat(miner.hidden()).isFalse();
    assertThat(miner.getTargetView().acceptsAttacker(true)).isTrue();
    assertThat(miner.getView().getX()).as("on the placed point").isEqualTo(3500);
  }

  @Test
  @DisplayName("the damage entry refuses a tunnelling unit")
  void theDamageEntryRefusesIt() {
    Standard1v1Battle match = passiveTowers();
    CharacterEntity miner = playMiner(match);
    int before = miner.getHitPoints().getHitPoints();

    assertThat(miner.takeDamage(100, 0, 0, 1)).isEqualTo(DamageResult.NOTHING);
    assertThat(miner.getHitPoints().getHitPoints()).isEqualTo(before);
  }

  @Test
  @DisplayName("an area effect that reaches hidden units reaching a tunnelling one is refused")
  void anAreaReachingHiddenUnitsIsRefused() {
    Standard1v1Battle match = passiveTowers();
    CharacterEntity miner = playMiner(match);
    // A Freeze of the top side cast where the Miner walks: it reaches hidden units.
    match.placeAreaEffect(
        1,
        "Freeze",
        Standard1v1Battle.DEFAULT_LEVEL,
        1,
        miner.getView().getX(),
        miner.getView().getY(),
        "Freeze");

    assertThatThrownBy(
            () -> {
              for (int step = 0; step < 5; step++) {
                match.getBattle().step();
              }
            })
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("reaches hidden units");
  }

  @Test
  @DisplayName(
      "a dig that surfaces morphs into its building, deploying, with the dig's share of hit points,"
          + " whose entry makes its area object at once")
  void theDigMorphsIntoItsBuilding() {
    Standard1v1Battle match = passiveTowers();
    List<String> made = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void morphed(int tick, CharacterEntity old, CharacterEntity building) {
                made.add(
                    old.name()
                        + " "
                        + building.name()
                        + " "
                        + building.getView().getState()
                        + " "
                        + building.getView().getDeployCountdown()
                        + " "
                        + building.getHitPoints().getHitPoints()
                        + " facing "
                        + building.getView().getDirX()
                        + " "
                        + building.getView().getDirY());
              }

              @Override
              public void areaEffectCreated(
                  int tick, AreaEffectEntity areaEffect, String how, String source) {
                made.add(how + " " + source);
              }
            });
    match.play(
        0, GameData.card("GoblinDrill"), Standard1v1Battle.DEFAULT_LEVEL, 0, 3500, 25500, "Drill");
    CharacterEntity dig = null;
    for (int step = 0; step < 100 && made.isEmpty(); step++) {
      match.getBattle().step();
      if (dig == null) {
        dig = match.getPlays().get(0).units().get(0);
      }
    }

    // The area object is made inside the entry, before the morph is told; the building's
    // registration visit took one LifeTime step off its 1313 before it was set deploying.
    assertThat(made)
        .containsExactly(
            "spawn_area_object Drill_0_GoblinDrill",
            // A building faces as the dig did as it surfaced: up its side's length.
            "Drill_0 Drill_0_GoblinDrill 4 1000 1307 facing 0 256");
    assertThat(dig.getView().getX()).as("on the searched corner").isEqualTo(1000);
    match.getBattle().step();
    assertThat(match.getBattle().getHolder().entities()).doesNotContain(dig);
  }
}
