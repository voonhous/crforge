package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The evolved Inferno Dragon: its attack sequence has four entries and the Manual mode, so only its
 * actions move the index. Each hit counts one attack in a variable of the dragon, capped at 50; a
 * ticker started with the dragon sets the index from that count every 50 ms (entry 0 below 4
 * attacks, 1 below 9, 2 below 49, then 3), so a count reached by a hit is read from the hit after
 * the next tick's set.
 */
class BattleInfernoDragonEvoTest {

  /** The card's first level, as the evolved play in the reference case stands. */
  private static final int LEVEL = 1;

  /** Side 1's right princess tower, at (14500, 25500). */
  private static final String TOWER = "PrincessTower_1_2";

  /** The dragon's hits on side 1's buildings, and the dragon itself. */
  private record Beam(List<int[]> hits, CharacterEntity dragon, BattleWorld world) {}

  /**
   * Runs an evolved Inferno Dragon of side 0 in front of side 1's right princess tower, the towers
   * at the level given and passive, so only the dragon deals damage.
   */
  private static Beam beamAtTheTower(int towerLevel, int ticks) {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), towerLevel, false);
    List<int[]> hits = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void damageDealt(
                  int tick, WorldEntity target, int damage, DamageResult result) {
                if (target.side() == WorldEntity.SIDE_TOP) {
                  hits.add(new int[] {tick, damage, target.name().equals(TOWER) ? 1 : 0});
                }
              }
            });
    CharacterEntity dragon =
        match.deploy(0, GameData.unit("InfernoDragon_EV1"), LEVEL, 0, 14500, 20000);
    for (int tick = 0; tick < ticks; tick++) {
      match.getBattle().step();
    }
    return new Beam(hits, dragon, match.getWorld());
  }

  @Test
  @DisplayName(
      "the first four hits deal the first entry's damage, the next five the second's and the hits"
          + " after them the third's")
  void theDamageStepsWithTheAttackCount() {
    Beam beam = beamAtTheTower(15, 300);
    List<int[]> hits = beam.hits();
    assertThat(hits).hasSizeGreaterThanOrEqualTo(12);
    int first = hits.get(0)[1];
    int second = hits.get(4)[1];
    int third = hits.get(9)[1];
    assertThat(first).as("the first entry's damage at the first level").isEqualTo(14);
    assertThat(second).as("the second entry's").isEqualTo(47);
    assertThat(third).as("the third entry's").isEqualTo(165);
    for (int i = 0; i < 12; i++) {
      int expected = i < 4 ? first : i < 9 ? second : third;
      assertThat(hits.get(i)[1]).as("hit %d", i + 1).isEqualTo(expected);
      assertThat(hits.get(i)[2]).as("hit %d on the tower", i + 1).isEqualTo(1);
    }
    for (int i = 1; i < 12; i++) {
      assertThat(hits.get(i)[0] - hits.get(i - 1)[0]).as("one hit every 400 ms").isEqualTo(8);
    }
  }

  @Test
  @DisplayName("each hit counts one attack in the dragon's own variable, the count capped at 50")
  void theCountIsTheDragonsAndCapped() {
    Beam beam = beamAtTheTower(15, 300);
    int key = beam.world().variableKey("InfernoDragon_EV1_AttackCount");
    long towerHits = beam.hits().stream().filter(hit -> hit[2] == 1).count();
    assertThat(beam.dragon().variable(key)).isEqualTo((int) Math.min(50, towerHits));
  }

  @Test
  @DisplayName(
      "from the 50th hit on, the count kept across targets, the hits deal the fourth entry's"
          + " damage")
  void theFourthEntryFromTheFiftiethHit() {
    Beam beam = beamAtTheTower(1, 900);
    List<int[]> hits = beam.hits();
    assertThat(hits).hasSizeGreaterThanOrEqualTo(51);
    assertThat(hits.get(48)[1]).as("the 49th hit").isEqualTo(165);
    assertThat(hits.get(49)[1]).as("the 50th hit").isEqualTo(330);
    assertThat(hits.get(50)[1]).as("the 51st hit").isEqualTo(330);
  }
}
