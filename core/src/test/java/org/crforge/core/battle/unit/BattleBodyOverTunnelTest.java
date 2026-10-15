/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A travelling body's hit on a character tunnelling to its placement: the tunnel is no exception to
 * the body's hit. The character is listed as hit and the hit is queued for it, which the damage
 * entry refuses at the drain, so it keeps its hit points; a body that stops at collisions stops at
 * it; and a push the body would give it is refused, a tunnelling character being hidden, even under
 * a row that pushes all.
 *
 * <p>The scene: the bottom side's Miner tunnels from its king tower up the arena to the top side's
 * left princess tower. The top side's Log is played across its way first, the rolling body taken
 * over by the scene: as wide as the arena, so slow that it all but stands, and long enough to cover
 * the Miner for some steps of its tunnel. The scene writes every column its outcome is read from:
 * the body's size, pace, reach, damage, push and whether it stops at collisions.
 */
class BattleBodyOverTunnelTest {

  /** The level every object is placed at: the first, whose stats are the rows' own. */
  private static final int LEVEL = 1;

  /** Long enough for the Miner to tunnel past the body and surface. */
  private static final int TICKS = 200;

  /** The step the Miner is played on, once the body rolls. */
  private static final int MINER_TICK = 40;

  /** The rolling body the Log's thrown projectile spawns, its row taken over by the scene. */
  private static final String BODY = "LogProjectileRolling";

  /** The configured tables with the body's columns written. */
  private static GameTables written(Path folder, boolean stops, int pushback) throws IOException {
    Files.createDirectories(folder);
    GameData.altered(
        folder,
        "projectiles",
        rows ->
            GameData.columns(rows, BODY)
                .put("Speed", 1)
                .put("ProjectileRadius", 9000)
                .put("ProjectileRadiusY", 2500)
                .put("ProjectileRange", 100_000)
                .put("Damage", 100)
                .put("CheckCollisions", stops)
                .put("Pushback", pushback)
                .put("PushbackAll", pushback >= 1));
    return GameTables.load(folder);
  }

  /** What the scene saw of the body and the Miner while the Miner tunnelled. */
  private record Seen(
      int tunnellingSteps,
      int listedWhileTunnelling,
      int bodyGoneWhileTunnelling,
      int pushedWhileTunnelling,
      int hitPointsLost,
      boolean surfaced) {}

  private static Seen run(GameTables tables) {
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    match.play(0, records.card("Log"), LEVEL, 1, 9000, 16000, "Log");
    match.play(MINER_TICK, records.card("Miner"), LEVEL, 0, 3500, 25500, "Miner");
    CharacterEntity miner = null;
    int hitPoints = 0;
    boolean bodyRolled = false;
    int tunnellingSteps = 0;
    int listed = 0;
    int gone = 0;
    int pushed = 0;
    boolean surfaced = false;
    // The Miner's hit points on its last tunnelling step: what it meets once surfaced is not the
    // scene's.
    int lastTunnelHitPoints = 0;
    for (int step = 0; step < TICKS; step++) {
      match.getBattle().step();
      ProjectileEntity body = body(match);
      bodyRolled |= body != null;
      if (miner == null && !match.getPlays().isEmpty()) {
        for (var play : match.getPlays()) {
          if (!play.units().isEmpty() && play.units().get(0).getData().name().equals("Miner")) {
            miner = play.units().get(0);
            hitPoints = miner.getHitPoints().getHitPoints();
          }
        }
      }
      if (miner == null) {
        continue;
      }
      if (miner.getView().getState() != GridEntityState.SPAWN_PATHFIND) {
        surfaced |= tunnellingSteps > 0;
        continue;
      }
      tunnellingSteps++;
      lastTunnelHitPoints = miner.getHitPoints().getHitPoints();
      if (body != null && body.getHitIds().contains(miner.getId())) {
        listed++;
      }
      if (bodyRolled && body == null) {
        gone++;
      }
      if (miner.getUnit().movement().getPushbackInFlight() != 0) {
        pushed++;
      }
    }
    int lost = tunnellingSteps == 0 ? 0 : hitPoints - lastTunnelHitPoints;
    return new Seen(tunnellingSteps, listed, gone, pushed, lost, surfaced);
  }

  @Test
  @DisplayName(
      "a body that stops at collisions stops at a tunnelling Miner, which keeps its hit points")
  void aBodyThatStopsAtCollisionsStopsAtTheTunnellingMiner(@TempDir Path folder)
      throws IOException {
    Seen seen = run(written(folder, true, 0));

    assertThat(seen.tunnellingSteps()).as("steps the Miner tunnelled").isPositive();
    assertThat(seen.surfaced()).as("the Miner surfaced in the scene").isTrue();
    assertThat(seen.bodyGoneWhileTunnelling())
        .as("steps the body was gone while the Miner still tunnelled")
        .isPositive();
    assertThat(seen.hitPointsLost()).as("hit points the Miner lost while it tunnelled").isZero();
  }

  @Test
  @DisplayName(
      "a body that pushes all lists a tunnelling Miner as hit and neither damages nor pushes it")
  void aPushingBodyListsTheTunnellingMinerAndDoesNotPushIt(@TempDir Path folder)
      throws IOException {
    Seen seen = run(written(folder, false, 1000));

    assertThat(seen.listedWhileTunnelling())
        .as("steps the body listed the Miner while it tunnelled")
        .isPositive();
    assertThat(seen.pushedWhileTunnelling())
        .as("steps the Miner was pushed while it tunnelled")
        .isZero();
    assertThat(seen.hitPointsLost()).as("hit points the Miner lost while it tunnelled").isZero();
  }

  /** The rolling body in the battle, or null before it rolls and once it is gone. */
  private static ProjectileEntity body(Standard1v1Battle match) {
    for (BattleEntity entity : match.getBattle().getHolder().entities()) {
      if (entity instanceof ProjectileEntity projectile
          && projectile.getData().name().equals(BODY)) {
        return projectile;
      }
    }
    return null;
  }
}
