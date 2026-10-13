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
 * A travelling body's hit on a character in its dash under a row with a dash immunity: the body
 * spares it without listing it as hit, so the same body can still hit it once its dash and the
 * immunity left after it are over. A character protected only by the immunity left after its dash
 * is listed as hit and spared.
 *
 * <p>The scene: the top side's Knight stands up the left lane and the bottom side's Bandit walks up
 * the lane at it and dashes. The top side's Log is played ahead of the Knight, its rolling body
 * long and so slow that it all but stands: the Bandit's dash takes it into the body's reach, and it
 * lands, beside the Knight, still in it, with some of its immunity left. The scene writes every
 * column its outcome is read from: the Bandit's and the Knight's ranges, radii, pace and dash, and
 * the body's size, pace, reach, damage and push.
 */
class BattleBodyOverDasherTest {

  /** The level every object is placed at: the first, whose stats are the rows' own. */
  private static final int LEVEL = 1;

  /** Long enough for the Bandit to walk, dash, land and stand under the body past its immunity. */
  private static final int TICKS = 300;

  /** The Bandit's hit points, more than the scene's hits take. */
  private static final int BANDIT_HIT_POINTS = 1_000_000;

  /** The Bandit's body radius. */
  private static final int BANDIT_RADIUS = 600;

  /** The rolling body's half length along the arena's length. */
  private static final int BODY_HALF_LENGTH = 2500;

  /** The rolling body the Log's thrown projectile spawns, its row taken over by the scene. */
  private static final String BODY = "LogProjectileRolling";

  /** The configured tables with the scene's columns written. */
  private static GameTables written(Path folder) throws IOException {
    Files.createDirectories(folder);
    GameData.altered(
        folder,
        "characters",
        rows -> {
          GameData.columns(rows, "Assassin")
              .put("Hitpoints", BANDIT_HIT_POINTS)
              .put("Damage", 1)
              .put("HitSpeed", 1000)
              .put("LoadTime", 600)
              .put("Speed", 90)
              .put("Mass", 3)
              .put("CollisionRadius", BANDIT_RADIUS)
              .put("Range", 750)
              .put("SightRange", 9500)
              .put("DeployTime", 1000)
              .put("DashDamage", 1)
              .put("DashMinRange", 3500)
              .put("DashMaxRange", 9000)
              .put("DashCooldown", 800)
              .put("DashImmuneToDamageTime", 150)
              .put("JumpSpeed", 500);
          GameData.columns(rows, "Knight")
              .put("Hitpoints", 1_000_000)
              .put("Damage", 1)
              .put("HitSpeed", 1200)
              .put("LoadTime", 700)
              .put("Speed", 0)
              .put("CollisionRadius", 500)
              .put("Range", 1200)
              .put("SightRange", 1200)
              .put("DeployTime", 1000);
        });
    GameData.alterLoaded(
        folder,
        "projectiles",
        rows ->
            GameData.columns(rows, BODY)
                .put("Speed", 1)
                .put("ProjectileRadius", 1950)
                .put("ProjectileRadiusY", BODY_HALF_LENGTH)
                .put("ProjectileRange", 100_000)
                .put("Damage", 100)
                .put("Pushback", 0)
                .put("PushbackAll", false));
    return GameTables.load(folder);
  }

  @Test
  @DisplayName(
      "a rolling body that reaches a Bandit in its dash spares it without listing it as hit, and"
          + " lists it once only the immunity left after its dash protects it")
  void theBodySparesTheDashingBanditWithoutListingIt(@TempDir Path folder) throws IOException {
    GameTables tables = written(folder);
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    match.deploy(0, records.unit("Knight"), LEVEL, 1, 3500, 19500, "Knight");
    CharacterEntity bandit =
        match.deploy(0, records.unit("Assassin"), LEVEL, 0, 3500, 7000, "Bandit");
    match.play(0, records.card("Log"), LEVEL, 1, 3500, 16000, "Log");
    boolean reachedWhileWalking = false;
    int reachedWhileDashing = 0;
    int listedWhileDashing = 0;
    int listedAfterTheDash = 0;
    for (int step = 0; step < TICKS; step++) {
      match.getBattle().step();
      ProjectileEntity body = body(match);
      if (body == null) {
        continue;
      }
      // Within the body's reach along the length: its half length and the Bandit's radius.
      boolean reached =
          Math.abs(bandit.getView().getY() - body.getY()) <= BODY_HALF_LENGTH + BANDIT_RADIUS;
      boolean dashing = bandit.getView().getState() == GridEntityState.DASHING;
      boolean listed = body.getHitIds().contains(bandit.getId());
      if (dashing) {
        reachedWhileDashing += reached ? 1 : 0;
        listedWhileDashing += listed ? 1 : 0;
      } else if (reachedWhileDashing == 0) {
        reachedWhileWalking |= reached;
      } else {
        listedAfterTheDash += listed ? 1 : 0;
      }
    }

    assertThat(reachedWhileWalking).as("the body first reached the Bandit in its dash").isFalse();
    assertThat(reachedWhileDashing)
        .as("steps the body reached the Bandit in its dash")
        .isPositive();
    assertThat(listedWhileDashing).as("steps the body listed the Bandit in its dash").isZero();
    assertThat(listedAfterTheDash)
        .as("steps the body listed the Bandit after its dash, in its immunity")
        .isPositive();
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
