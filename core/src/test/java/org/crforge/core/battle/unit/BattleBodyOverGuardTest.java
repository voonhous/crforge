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
 * A travelling body's hit on a character first asks the character whether it takes part in contact,
 * with the state clauses left out: one under the no-check-collisions tag, or attached to a parent,
 * takes no part, and the body spares it without listing it as hit. The Little Prince's guard
 * carries that tag from its run's start, through its deploy and its charge, to the run's finish, so
 * a body rolling over the guard while it deploys neither hits nor lists it.
 *
 * <p>The scene: the top side's Log is played on the point the bottom side's guard appears on, its
 * rolling body long and so slow that it all but stands there; then the guard's area effect,
 * DummySpawnLittlePrinceGuard, is placed for the bottom side, and its start makes the guard behind
 * it, under the body, where it deploys before it charges up the lane away from the body. The scene
 * writes the body's size, pace, reach, damage and push.
 */
class BattleBodyOverGuardTest {

  /** The first level, whose stats are the rows' own. */
  private static final int LEVEL = 1;

  /** The guard area effect's point: the guard appears behind it and charges ahead of it. */
  private static final int X = 3500;

  private static final int Y = 19500;

  /** How far behind the area effect's point the guard appears, written into the spawn row. */
  private static final int BEHIND = 2000;

  /** The tick the guard's area effect is placed on, once the Log's body rolls. */
  private static final int PLACED = 60;

  /** The tick the scene runs to, after the guard's run has finished. */
  private static final int END = 160;

  /** The rolling body's half length along the arena's length. */
  private static final int BODY_HALF_LENGTH = 2500;

  /** The rolling body's half width across the arena. */
  private static final int BODY_HALF_WIDTH = 1950;

  /** The guard spawn row the guard's area effect starts. */
  private static final String GUARD_SPAWN = "Spawn_ChampionGuardCharge";

  /** The rolling body the Log's thrown projectile spawns, its row taken over by the scene. */
  private static final String BODY = "LogProjectileRolling";

  /** The configured tables with the scene's columns written. */
  private static GameTables written(Path folder) throws IOException {
    Files.createDirectories(folder);
    GameData.altered(
        folder,
        "projectiles",
        rows ->
            GameData.columns(rows, BODY)
                .put("Speed", 1)
                .put("ProjectileRadius", BODY_HALF_WIDTH)
                .put("ProjectileRadiusY", BODY_HALF_LENGTH)
                .put("ProjectileRange", 100_000)
                .put("Damage", 100)
                .put("Pushback", 0)
                .put("PushbackAll", false));
    GameData.alterLoaded(
        folder,
        "actions",
        rows -> GameData.fields(rows, GUARD_SPAWN).put("AppearBehindAtDistance", BEHIND));
    return GameTables.load(folder);
  }

  @Test
  @DisplayName(
      "a rolling body over the Little Prince's guard while it deploys neither hits nor lists it")
  void theBodyPassesTheDeployingGuardBy(@TempDir Path folder) throws IOException {
    GameTables tables = written(folder);
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    match.play(0, records.card("Log"), LEVEL, 1, X, Y - BEHIND, "Log");
    match.placeAreaEffect(PLACED, "DummySpawnLittlePrinceGuard", LEVEL, 0, X, Y, "dummy");
    CharacterEntity[] made = new CharacterEntity[1];
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void guardRegistered(int tick, CharacterEntity guard) {
                made[0] = guard;
              }
            });
    int reachedWhileDeploying = 0;
    int listedWhileDeploying = 0;
    int hitWhileDeploying = 0;
    while (match.getBattle().getTick() < END) {
      match.getBattle().step();
      CharacterEntity guard = made[0];
      ProjectileEntity body = body(match);
      if (guard == null || body == null) {
        continue;
      }
      if (guard.getView().getState() != GridEntityState.DEPLOYING) {
        continue;
      }
      // Within the body's reach along the length and across: its half sizes.
      boolean reached =
          Math.abs(guard.getView().getY() - body.getY()) <= BODY_HALF_LENGTH
              && Math.abs(guard.getView().getX() - body.getX()) <= BODY_HALF_WIDTH;
      reachedWhileDeploying += reached ? 1 : 0;
      listedWhileDeploying += body.getHitIds().contains(guard.getId()) ? 1 : 0;
      int hitPoints = guard.getHitPoints().getHitPoints();
      hitWhileDeploying += hitPoints < guard.getHitPoints().getMaximum() ? 1 : 0;
    }

    assertThat(made[0]).as("the guard").isNotNull();
    assertThat(reachedWhileDeploying)
        .as("steps the body reached the guard while it deployed")
        .isPositive();
    assertThat(listedWhileDeploying)
        .as("steps the body listed the guard while it deployed")
        .isZero();
    assertThat(hitWhileDeploying).as("steps the guard was hit while it deployed").isZero();
  }

  /** The rolling body in the battle, or null before it rolls. */
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
