/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * When the kill of a fallen king's circle lands within its tick. The game hands the circle's kill
 * to the damage entry, which queues it for the holder's damage drain after every post-hook, so a
 * tower that targets the killed unit still sees it alive in its targeting visit of that tick and
 * stays attacking until the next.
 *
 * <p>The scene: side 1's Knight walks down the left lane and fights side 0's left princess tower.
 * Side 1's king is then killed, which ends the match; from the next update its circle grows over
 * the arena and kills every entity of side 1 it reaches, the Knight among them.
 */
class BattleCircleKillDrainTest {

  /** The battle stream's seed of the scene. */
  private static final int SEED = 1131;

  /** The Knight's level. */
  private static final int KNIGHT_LEVEL = 11;

  /** How many ticks after the tower starts attacking the Knight side 1's king is killed. */
  private static final int KING_KILLED_AFTER = 10;

  /** The view state of an entity that attacks. */
  private static final int ATTACKING = 2;

  private static final List<String> DECK =
      List.of("Knight", "Archer", "Giant", "Minions", "Musketeer", "Fireball", "Arrows", "Zap");

  /** The towers at the first level, fighting, in a Ladder match; side 1's Knight played. */
  private static Standard1v1Battle scene(GameTables tables) {
    Standard1v1Battle match = new Standard1v1Battle(tables, 1, true);
    match.getWorld().seed(SEED);
    match.startLadderMatch(DECK, DECK, 0, 0);
    match.play(100, GameData.card("Knight"), KNIGHT_LEVEL, 1, 3500, 17500, "K");
    return match;
  }

  /** Side 0's left princess tower, the one in the Knight's lane. */
  private static TowerEntity leftTower(Standard1v1Battle match) {
    return match.getWorld().getHolder().entities().stream()
        .filter(TowerEntity.class::isInstance)
        .map(TowerEntity.class::cast)
        .filter(t -> t.side() == 0 && !t.getData().king() && t.x() < 9000)
        .findFirst()
        .orElseThrow();
  }

  /**
   * Plays the scene to the king's kill, then steps until the circle kills the Knight, and answers
   * the tower's state at the end of that tick and of the next.
   */
  private static int[] towerStateAtTheKill(Standard1v1Battle match) {
    TowerEntity tower = leftTower(match);
    while (tower.getView().getState() != ATTACKING) {
      match.getBattle().step();
    }
    for (int i = 0; i < KING_KILLED_AFTER; i++) {
      match.getBattle().step();
    }
    CharacterEntity knight = match.getPlays().get(0).units().get(0);
    assertThat(tower.getView().getState()).as("the tower fights the Knight").isEqualTo(ATTACKING);
    assertThat(tower.getTargeting().getReference().id()).isEqualTo(knight.getId());
    assertThat(HitPoints.alive(knight.getHitPoints())).isTrue();
    // A stand-in for the blow that brings the king down, between two steps.
    TowerEntity king = match.getWorld().kingTower(1);
    king.takeKill();
    king.die(null);
    for (int i = 0; i < 200 && HitPoints.alive(knight.getHitPoints()); i++) {
      match.getBattle().step();
      if (HitPoints.alive(knight.getHitPoints())) {
        assertThat(tower.getView().getState()).as("still fighting").isEqualTo(ATTACKING);
      }
    }
    assertThat(match.getMatch().isEnded()).as("the king's fall ended the match").isTrue();
    assertThat(HitPoints.alive(knight.getHitPoints())).as("the circle killed it").isFalse();
    assertThat(match.getWorld().getHolder().entities()).as("it has left").doesNotContain(knight);
    int atTheKill = tower.getView().getState();
    match.getBattle().step();
    return new int[] {atTheKill, tower.getView().getState()};
  }

  @Test
  @DisplayName(
      "the circle's kill lands at the damage drain, so the tower that"
          + " targets the Knight still attacks on the tick the circle kills it")
  void theTowerStillAttacksOnTheKillTick() {
    int[] states = towerStateAtTheKill(scene(GameData.tables()));
    assertThat(states[0]).as("on the kill's tick").isEqualTo(ATTACKING);
    assertThat(states[1]).as("on the next tick").isZero();
  }
}
