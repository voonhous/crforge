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
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Whether an attacker keeps a target that dashes. A unit whose row runs an attack sequence - here
 * an Inferno Tower, whose numbered variable damage columns make one - neither keeps nor takes a
 * target that is untouchable, the immunity left after a dash counted: the validator asks it of such
 * an owner only. An ordinary attacker keeps the dashing target.
 *
 * <p>The scene: the top side's Inferno Tower stands in the left lane, and the bottom side's Bandit,
 * which dashes at a target between its dash's least and greatest range and is immune to damage
 * while it dashes, walks up the lane at it. The tower takes the Bandit as it comes into range, and
 * the Bandit then dashes at the tower. The scene writes every column its outcome is read from: both
 * rows' ranges, radii, pace and dash, and hit points no hit of the scene takes. The control is the
 * same scene with the tower's first variable damage time written 0, which leaves its row with no
 * attack sequence: an ordinary attacker.
 */
class BattleDashTargetHoldTest {

  /** The level both units are placed at: the first, whose stats are the rows' own. */
  private static final int LEVEL = 1;

  /** Long enough for the Bandit to walk into the tower's range, dash, land and attack. */
  private static final int TICKS = 400;

  /** The configured tables with the scene's columns written, the tower's first window given. */
  private static GameTables written(Path folder, int variableDamageTime) throws IOException {
    Files.createDirectories(folder);
    GameData.altered(
        folder,
        "characters",
        rows ->
            GameData.columns(rows, "Assassin")
                .put("Hitpoints", 1_000_000)
                .put("Damage", 76)
                .put("HitSpeed", 1000)
                .put("LoadTime", 600)
                .put("Speed", 90)
                .put("Mass", 3)
                .put("CollisionRadius", 600)
                .put("Range", 750)
                .put("SightRange", 6000)
                .put("DeployTime", 1000)
                .put("DashDamage", 152)
                .put("DashMinRange", 3500)
                .put("DashMaxRange", 6000)
                .put("DashCooldown", 800)
                .put("DashImmuneToDamageTime", 150)
                .put("JumpSpeed", 500));
    GameData.alterLoaded(
        folder,
        "buildings",
        rows -> {
          GameData.writeTowerColumns(rows);
          GameData.columns(rows, "InfernoTower")
              .put("Hitpoints", 1_000_000)
              .put("Damage", 20)
              .put("VariableDamage2", 75)
              .put("VariableDamage3", 400)
              .put("VariableDamageTime1", variableDamageTime)
              .put("VariableDamageTime2", 2000)
              .put("HitSpeed", 400)
              .put("LoadTime", 1200)
              .put("CollisionRadius", 1000)
              .put("Range", 6000)
              .put("SightRange", 6000)
              .put("DeployTime", 1000);
        });
    GameData.alterLoaded(folder, "projectiles", GameData::writeTowerShots);
    GameData.alterLoaded(folder, "spawn_groups", GameData::placeTowers);
    return GameTables.load(folder);
  }

  /**
   * What the scene saw of the tower's reference: before the dash, on the dash's ticks after the one
   * it starts on, and after it.
   */
  private record Seen(
      boolean dashed, boolean heldBeforeDash, boolean heldWhileDashing, boolean takenAfter) {}

  /** Plays the scene, the towers passive, and tells what the tower held around the dash. */
  private static Seen play(GameTables tables) {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    CharacterEntity tower =
        match.deploy(
            0, match.getWorld().getRecords().unit("InfernoTower"), LEVEL, 1, 3500, 18000, "Tower");
    CharacterEntity bandit =
        match.deploy(
            0, match.getWorld().getRecords().unit("Assassin"), LEVEL, 0, 3500, 9000, "Bandit");
    boolean dashed = false;
    boolean heldBefore = false;
    boolean heldWhile = false;
    boolean takenAfter = false;
    for (int step = 0; step < TICKS; step++) {
      match.getBattle().step();
      boolean held = tower.getTargeting().getReference() == bandit.getTargetView();
      if (bandit.getView().getState() == GridEntityState.DASHING) {
        // The tower visits before the Bandit each tick, so on the tick the dash starts it has
        // already kept its target; the dash's later ticks tell.
        heldWhile |= dashed && held;
        dashed = true;
      } else if (!dashed) {
        heldBefore |= held;
      } else {
        takenAfter |= held;
      }
    }
    return new Seen(dashed, heldBefore, heldWhile, takenAfter);
  }

  @Test
  @DisplayName(
      "an attacker with an attack sequence lets a dashing target go and takes it again after its"
          + " immunity; an ordinary one keeps it through the dash")
  void anAttackSequenceOwnerLetsTheDasherGo(@TempDir Path folder) throws IOException {
    Seen scene = play(written(folder.resolve("scene"), 2000));
    assertThat(scene.dashed()).as("the Bandit dashed").isTrue();
    assertThat(scene.heldBeforeDash()).as("taken before the dash").isTrue();
    assertThat(scene.heldWhileDashing()).as("let go while the Bandit dashes").isFalse();
    assertThat(scene.takenAfter()).as("taken again after the dash").isTrue();

    // The control's tower, with no attack sequence, keeps the Bandit through its dash.
    Seen control = play(written(folder.resolve("control"), 0));
    assertThat(control.dashed()).isTrue();
    assertThat(control.heldBeforeDash()).isTrue();
    assertThat(control.heldWhileDashing()).as("kept while the Bandit dashes").isTrue();
  }
}
