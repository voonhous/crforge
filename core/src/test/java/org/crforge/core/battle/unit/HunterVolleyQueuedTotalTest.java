/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The queued total a Hunter's pellet asks about when its travelling hit is queued. The hit points'
 * own visit, in the third component pass, empties a character's queued total, so a hit queued for
 * it earlier in the tick - a direct hit of a targeting visit, an area's share - is not counted
 * against its hit points by a pellet flying in the post-hooks: only the hits queued since then are.
 * A volley whose pellets alone do not reach the target's hit points does not doom it, and each
 * pellet that reaches it that step stops at it, even though a hit queued before the hit points'
 * visit will kill it at the drain.
 */
class HunterVolleyQueuedTotalTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** Long enough for the volley to land. */
  private static final int TICKS = 400;

  private static final int TARGET_SIDE = 0;

  private static final int SHOOTER_SIDE = 1;

  /** Where the target stands, in its own half, out of every crown tower's reach. */
  private static final int TARGET_X = 9000;

  private static final int TARGET_Y = 12000;

  /** How far along the length the Hunter stands from the target, within its range. */
  private static final int GAP = 3500;

  /** What one step of a run of the scene saw. */
  private record Landing(int pellets, int hitPoints, boolean doomed) {}

  @Test
  @DisplayName("a hit queued before the hit points' visit does not doom the target for the volley")
  void hitQueuedBeforeTheHitPointsVisitIsNotCounted() {
    Map<Integer, Landing> plain = run(-1);
    // The step on which most pellets stop at the target, the first such.
    int busiest =
        plain.entrySet().stream()
            .max(
                Comparator.comparingInt((Map.Entry<Integer, Landing> e) -> e.getValue().pellets())
                    .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
            .orElseThrow()
            .getKey();
    Landing before = plain.get(busiest);
    assertThat(before.pellets())
        .as("several pellets reach the target on one step")
        .isGreaterThan(1);
    assertThat(before.doomed()).as("the pellets alone do not doom the target").isFalse();

    // The same scene, with a hit that kills the target queued at the head of that step, before
    // any component pass.
    Landing after = run(busiest).get(busiest);
    assertThat(after).as("the volley still lands on that step").isNotNull();
    assertThat(after.doomed())
        .as("the hit queued before the hit points' visit is not in the queued total")
        .isFalse();
    assertThat(after.pellets())
        .as("every pellet that reaches the target that step still stops at it")
        .isEqualTo(before.pellets());
    assertThat(after.hitPoints()).as("and the queued hit kills it at the drain").isZero();
  }

  /**
   * Plays the scene: a Giant held still, a Hunter of the other side in range firing at it, until
   * its first volley has left. On the step numbered {@code killStep}, when it is not negative, a
   * direct hit of the Giant's hit points and shield is queued for the Giant at the head of the
   * step, in the pre-pass.
   *
   * @return by step, on each step a pellet of the volley stopped at the Giant: how many did, the
   *     Giant's hit points after the step, and whether the volley's group dooms it
   */
  private static Map<Integer, Landing> run(int killStep) {
    GameTables tables = GameData.tables();
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, true);
    BattleWorld world = match.getWorld();
    List<ProjectileEntity> launched = new ArrayList<>();
    int[] step = {0};
    CharacterEntity[] giant = new CharacterEntity[1];
    CharacterEntity[] hunter = new CharacterEntity[1];
    world.addObserver(
        new WorldObserver() {
          @Override
          public void projectileLaunched(int tick, ProjectileEntity projectile) {
            launched.add(projectile);
          }

          @Override
          public void afterPrePass(int tick, List<WorldEntity> present) {
            if (step[0] == killStep) {
              int total =
                  giant[0].getHitPoints().getHitPoints() + giant[0].getHitPoints().getShield();
              world.dealDirectHit(hunter[0], giant[0].getTargetView(), total, 0, 0);
            }
          }
        });

    giant[0] =
        match.deploy(0, records.unit("Giant"), LEVEL, TARGET_SIDE, TARGET_X, TARGET_Y, "giant");
    while (giant[0].getView().getState() == GridEntityState.DEPLOYING
        || giant[0].getView().getState() == GridEntityState.WAITING_TO_DEPLOY
        || giant[0].getId() == 0) {
      advance(match, step);
    }
    giant[0].setActive(CharacterEntity.MOVEMENT_SLOT, false);
    giant[0].setActive(CharacterEntity.TARGETING_SLOT, false);

    int now = match.getBattle().getTick() + 1;
    hunter[0] =
        match.deploy(
            now, records.unit("Hunter"), LEVEL, SHOOTER_SIDE, TARGET_X, TARGET_Y + GAP, "hunter");
    advance(match, step);
    hunter[0].setActive(CharacterEntity.MOVEMENT_SLOT, false);

    int id = giant[0].getId();
    Map<Integer, Landing> landings = new TreeMap<>();
    Set<ProjectileEntity> stopped = new HashSet<>();
    while (step[0] < TICKS - 1) {
      int before = step[0];
      advance(match, step);
      List<ProjectileEntity> volley =
          launched.stream().filter(p -> p.getRoot() == hunter[0]).toList();
      int count = 0;
      for (ProjectileEntity pellet : volley) {
        if (pellet.isReleased() && pellet.getHitIds().contains(id) && stopped.add(pellet)) {
          count++;
        }
      }
      if (count > 0) {
        boolean doomed = volley.get(0).getGroup().dooms(id);
        landings.put(before, new Landing(count, giant[0].getHitPoints().getHitPoints(), doomed));
      }
      if (!volley.isEmpty() && volley.stream().allMatch(ProjectileEntity::isReleased)) {
        break;
      }
    }
    assertThat(landings).as("the volley reaches the Giant").isNotEmpty();
    return landings;
  }

  private static void advance(Standard1v1Battle match, int[] step) {
    match.getBattle().step();
    step[0]++;
    assertThat(step[0]).as("within the scene's steps").isLessThan(TICKS);
  }
}
