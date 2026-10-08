package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The evolved Royal Hog flies until it falls: its first hit, or the step its hit points fall to 99%
 * of its maximum, runs its fall group, gated on a tag the fall's own run carries so it runs once. A
 * fall its hit runs starts in the step of the hit and descends over 500 ms, ten steps; the step
 * after them the hog lands, takes its grounded row and makes its landing area, which hits the
 * enemies around it a step later, and its route is emptied 100 ms after the landing. It is then
 * held on the ground layer for the rest of the battle.
 */
class BattleRoyalHogEvoTest {

  /** The card's first level. */
  private static final int LEVEL = 1;

  /** Side 1's right princess tower, at (14500, 25500). */
  private static final String TOWER = "PrincessTower_1_2";

  /** The grounded row the hog takes as it lands. */
  private static final String GROUNDED = "RoyalHog_EV1_Grounded";

  /**
   * What one run saw: the hog's hits and the area hits on side 1's tower, the landing areas made
   * with the step each was made, the hog, the step it took its grounded row and whether it was an
   * air unit after each step.
   */
  private record Fall(
      List<int[]> hits,
      List<int[]> areaHits,
      List<Integer> landings,
      CharacterEntity hog,
      int groundedAt,
      List<Boolean> air) {}

  /**
   * Runs one evolved Royal Hog of side 0 toward side 1's right princess tower, the towers at the
   * level given, holding fire or not.
   */
  private static Fall fall(int towerLevel, boolean towersAttack, int ticks) {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), towerLevel, towersAttack);
    List<int[]> hits = new ArrayList<>();
    List<int[]> areaHits = new ArrayList<>();
    List<Integer> landings = new ArrayList<>();
    CharacterEntity hog = match.deploy(0, GameData.unit("RoyalHog_EV1"), LEVEL, 0, 14500, 21000);
    int[] grounded = {-1};
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void damageDealt(
                  int tick, WorldEntity target, int damage, DamageResult result) {
                if (target.name().equals(TOWER)) {
                  hits.add(new int[] {tick, damage});
                }
              }

              @Override
              public void areaEffectSpawned(
                  int tick,
                  SpawnHost owner,
                  String action,
                  int phase,
                  SpawnHost source,
                  AreaEffectEntity areaEffect) {
                if (action.equals("RoyalHog_EV1_Spawn_Landing_Damage_Area") && owner == hog) {
                  landings.add(tick);
                  // The swap runs just before, in the same step.
                  if (hog.getData().name().equals(GROUNDED) && grounded[0] < 0) {
                    grounded[0] = tick;
                  }
                }
              }

              @Override
              public void areaEffectHit(
                  int tick,
                  AreaEffectEntity areaEffect,
                  WorldEntity victim,
                  int damage,
                  DamageResult result) {
                if (victim.name().equals(TOWER)) {
                  areaHits.add(new int[] {tick, damage});
                }
              }
            });
    List<Boolean> air = new ArrayList<>();
    for (int tick = 0; tick < ticks; tick++) {
      match.getBattle().step();
      air.add(hog.getView().isAir());
    }
    return new Fall(hits, areaHits, landings, hog, grounded[0], air);
  }

  @Test
  @DisplayName(
      "the first hit drops the hog: it lands 11 steps later as its grounded row, its landing area"
          + " hits the tower once, and it stays on the ground layer")
  void theFirstHitDropsTheHog() {
    Fall fall = fall(15, false, 400);
    List<int[]> hits = fall.hits();
    assertThat(hits).hasSizeGreaterThanOrEqualTo(4);
    int firstHit = hits.get(0)[0];
    assertThat(hits.get(0)[1]).as("the hog's hit at the first level").isEqualTo(29);
    assertThat(fall.landings()).as("one landing area, one fall").hasSize(1);
    int landing = fall.landings().get(0);
    assertThat(landing).as("the step it lands").isEqualTo(firstHit + 11);
    // The landing area is in the filter form: its hit on the tower is a typed hit, a step later.
    assertThat(fall.areaHits()).isEmpty();
    assertThat(hits.get(1))
        .as("the landing area's damage at the first level, a step later")
        .containsExactly(landing + 1, 17);
    // The fall runs once: every later hit is the hog's own, one every 1.2 s.
    List<int[]> own = new ArrayList<>(hits);
    own.remove(1);
    for (int i = 1; i < own.size(); i++) {
      assertThat(own.get(i)[0] - own.get(i - 1)[0]).as("hit %d", i + 1).isEqualTo(24);
    }
    assertThat(fall.hog().getData().name()).isEqualTo(GROUNDED);
    assertThat(fall.air().get(fall.air().size() - 1)).as("held on the ground layer").isFalse();
  }

  @Test
  @DisplayName(
      "a tower shot that takes the hog to 99% of its hit points drops it before it attacks")
  void aShotUnderTheThresholdDropsTheHog() {
    Fall fall = fall(15, true, 400);
    assertThat(fall.landings()).as("one landing area, one fall").hasSize(1);
    assertThat(fall.groundedAt()).as("the step it lands").isPositive();
    assertThat(fall.hits()).isNotEmpty();
    assertThat(fall.hits().get(0)[0])
        .as("the hog lands before its first hit")
        .isGreaterThan(fall.groundedAt());
  }
}
