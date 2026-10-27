/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.EntityFlags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The tags the evolved Mega Knight carries through its uppercut are its row's own: the run sets the
 * row's GameTagsToSet on the unit for as long as it is listed, and after the push it adds
 * LOCK_TARGET for one step only while the row's LockTargetAfterUppercut is on. No NO_MOVE or
 * NO_ATTACK of the run's own is added. So a row that sets NO_MOVE_ALLOW_ATTRACT in place of NO_MOVE
 * leaves the unit to a Tornado's pull while it stands in its uppercut: the movement visit stands a
 * unit under either tag, and only NO_MOVE also skips the pushes, the pull among them.
 *
 * <p>The scene: the bottom side's evolved Mega Knight stands in front of a Knight of the top side,
 * the towers passive, and its uppercut is started on the Knight; on the same tick a Tornado of the
 * top side, in the filter form, is placed beside the Mega Knight. The scene writes every column its
 * outcome is read from: the uppercut's tags and lock, the Tornado's circle, timings and buff time,
 * and its buff's pull.
 */
class BattleUppercutAttractTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The uppercut row the scenes rewrite. */
  private static final String UPPERCUT = "MegaKnight_EV1_uppercut";

  /** How long the Tornado's buff lasts after a hit, and how long the Tornado lives. */
  private static final int BUFF_TIME = 500;

  private static final int TORNADO_LIFE = 1050;

  /** How far beside the Mega Knight the Tornado stands, across the lane. */
  private static final int TORNADO_OFFSET = 2000;

  /**
   * The configured tables with the scene's columns written.
   *
   * @param folder the folder the tables are copied into
   * @param tags the uppercut's GameTagsToSet
   * @param lockTarget the uppercut's LockTargetAfterUppercut
   */
  private static GameTables written(Path folder, String tags, boolean lockTarget)
      throws IOException {
    Files.createDirectories(folder);
    BattleAreaEffectFilterFormTest.filterForm(
        folder,
        "Tornado",
        columns -> {
          columns.put("Radius", 5500);
          columns.put("HitSpeed", 50);
          columns.put("HitSpeedOffset", 50);
          columns.put("LifeDuration", TORNADO_LIFE);
          columns.put("BuffTime", BUFF_TIME);
        });
    GameData.alterLoaded(
        folder,
        "character_buffs",
        rows -> {
          ObjectNode buff = GameData.columns(rows, "Tornado");
          buff.put("PushSpeedFactor", 100);
          buff.put("AttractPercentage", 360);
          buff.put("DamagePerSecond", 0);
          buff.remove(
              List.of(
                  "PushMassFactor", "LateralPushPercentage", "AttractMaxAngle", "SpeedMultiplier"));
        });
    GameData.alterLoaded(
        folder,
        "actions",
        rows -> {
          ObjectNode uppercut = GameData.fields(rows, UPPERCUT);
          uppercut.put("GameTagsToSet", tags);
          // Left out, the column takes the loader's default: on.
          if (!lockTarget) {
            uppercut.put("LockTargetAfterUppercut", false);
          }
        });
    GameData.writeTowers(folder);
    return GameTables.load(folder);
  }

  /** The uppercut started on the Knight, and the Tornado placed beside the Mega Knight. */
  private static final class Scene {
    final Standard1v1Battle match;
    final EntityFlags bits;
    final CharacterEntity megaKnight;
    final CharacterEntity knight;

    Scene(GameTables tables) {
      match = new Standard1v1Battle(tables, LEVEL, false);
      bits = EntityFlags.of(tables);
      BattleWorld world = match.getWorld();
      megaKnight =
          match.deploy(0, world.getRecords().unit("MegaKnight_EV1"), LEVEL, 0, 3500, 14000, "mk");
      knight = match.deploy(0, world.getRecords().unit("Knight"), LEVEL, 1, 3500, 15600, "k");
      // Both deployed, before the Mega Knight's first hit.
      for (int i = 0; i < 22; i++) {
        match.getBattle().step();
      }
      megaKnight.getUnit().targeting().setReference(knight.getTargetView());
      megaKnight.setVariable(world.variableKey("MegaKnight_EV1_Do_Uppercut_Counter"), 0);
      megaKnight
          .actionHolder()
          .start(
              world.getActions().build(UPPERCUT, world.binding(megaKnight)), knight.actionHolder());
      match.placeAreaEffect(
          match.getBattle().getTick(),
          "Tornado",
          LEVEL,
          1,
          megaKnight.getView().getX() + TORNADO_OFFSET,
          megaKnight.getView().getY(),
          "Tornado");
    }

    /** Whether the uppercut's run is listed on the Mega Knight. */
    boolean uppercutListed() {
      return megaKnight.actionHolder().running().stream()
          .anyMatch(run -> run.getAction().name().equals(UPPERCUT));
    }

    /**
     * Steps until the uppercut is no longer listed, at most 40 steps: the steps whose tag word held
     * the given tag and the Mega Knight's pull under the Tornado's buff, and how far it moved over
     * those steps.
     */
    int movedWhileTagged(long tag) {
      int moved = 0;
      int taggedSteps = 0;
      for (int i = 0; i < 40 && uppercutListed(); i++) {
        int x = megaKnight.getView().getX();
        int y = megaKnight.getView().getY();
        match.getBattle().step();
        // The word the step's movement visit read, folded by its pre-hook.
        if ((megaKnight.getView().getFlags() & tag) != 0) {
          taggedSteps++;
          moved +=
              Math.abs(megaKnight.getView().getX() - x) + Math.abs(megaKnight.getView().getY() - y);
        }
      }
      assertThat(taggedSteps).as("steps under the uppercut's tag").isGreaterThan(5);
      return moved;
    }
  }

  @Test
  @DisplayName(
      "an uppercut whose row sets NO_MOVE_ALLOW_ATTRACT leaves the standing Mega Knight to a"
          + " Tornado's pull")
  void theRowsAttractTagLetsTheTornadoPull(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(written(folder, "NO_ATTACK,NO_MOVE_ALLOW_ATTRACT", true));

    assertThat(scene.movedWhileTagged(scene.bits.noMoveAllowAttract()))
        .as("pulled toward the Tornado while it stands in its uppercut")
        .isGreaterThan(0);
  }

  @Test
  @DisplayName(
      "the control: an uppercut whose row sets NO_MOVE keeps the Mega Knight where it stands under"
          + " the same pull")
  void theRowsNoMoveTagHoldsAgainstThePull(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(written(folder, "NO_ATTACK,NO_MOVE", true));

    assertThat(scene.movedWhileTagged(scene.bits.noMove())).as("not pulled").isZero();
  }

  @Test
  @DisplayName(
      "the run adds no NO_MOVE of its own: with the row's attract tag the Mega Knight's word never"
          + " holds NO_MOVE while the uppercut is listed")
  void theRunAddsNoNoMoveOfItsOwn(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(written(folder, "NO_ATTACK,NO_MOVE_ALLOW_ATTRACT", true));
    long hold = scene.bits.noAttack() | scene.bits.noMoveAllowAttract();

    for (int i = 0; i < 40 && scene.uppercutListed(); i++) {
      scene.match.getBattle().step();
      long word = scene.megaKnight.getView().getFlags();
      assertThat(word & scene.bits.noMove()).as("NO_MOVE on step %d", i).isZero();
      if (scene.uppercutListed()) {
        assertThat(word & hold).as("the row's tags on step %d", i).isEqualTo(hold);
      }
    }
  }

  @Test
  @DisplayName(
      "after the push the unit carries LOCK_TARGET for one step only while the row's"
          + " LockTargetAfterUppercut is on")
  void theLockAfterThePushFollowsTheRow(@TempDir Path folder) throws IOException {
    Scene locked = new Scene(written(folder.resolve("on"), "NO_ATTACK", true));
    Scene unlocked = new Scene(written(folder.resolve("off"), "NO_ATTACK", false));

    // The first update pushes the Knight and leaves the lock in the one-step word.
    locked.match.getBattle().step();
    unlocked.match.getBattle().step();

    long lock = locked.bits.lockTarget();
    assertThat(locked.knight.getUnit().movement().getAttackPushback()).isEqualTo(1);
    assertThat(unlocked.knight.getUnit().movement().getAttackPushback()).isEqualTo(1);
    assertThat(locked.megaKnight.getView().getPendingFlags() & lock).isEqualTo(lock);
    assertThat(unlocked.megaKnight.getView().getPendingFlags() & lock).isZero();
  }
}
