/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.GiantBufferBuff;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An enchanting buff, the Giant Buffer's, landed by a projectile whose launcher has left the battle
 * before it lands.
 *
 * <p>The scene: the bottom side's Archer and the top side's Knight walk at each other, the towers
 * passive. The Archer's arrow, given a hand-written on-hit action, starts an enchanting buff on
 * what it hits, its giver two links back from its cause (the arrow, then the Archer), lasting a
 * finish time once its giver has left. In one run the Archer is killed as its first arrow flies; in
 * the other it lives until the Knight reaches it.
 */
class BattleEnchantGiverLeftTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The hand-written enchanting buff the arrow's hit starts. */
  private static final String ENCHANT = "TestEnchant";

  /** How long the run lasts once its giver has left, in ms. */
  private static final int FINISH_MS = 500;

  /** Long enough for the two to meet, the arrow to land and the run's finish time to pass. */
  private static final int TICKS = 400;

  /** What one run of the scene saw. */
  private record Scene(
      int launched, int archerLeft, int arrowLeft, int started, List<Integer> listed) {}

  @Test
  @DisplayName(
      "an enchanting buff whose cause's launcher has left takes the cause itself as its giver: it"
          + " runs, and ends its finish time after the cause leaves the battle")
  void aRunWhoseGiversMakerLeftEndsAfterItsCause(@TempDir Path folder) throws IOException {
    Scene scene = play(tables(folder), true);

    assertThat(scene.archerLeft())
        .as("the Archer left before its arrow")
        .isPositive()
        .isLessThan(scene.arrowLeft());
    assertThat(scene.started()).as("the arrow's hit started the run").isPositive();
    // The giver is the arrow: the run counts its finish time down from the step the arrow is no
    // longer listed and ends on the step the count reaches 0.
    assertThat(scene.listed())
        .as("the steps the run is listed on")
        .isEqualTo(steps(scene.started(), scene.arrowLeft() + FINISH_MS / 50 - 1));
  }

  @Test
  @DisplayName(
      "an enchanting buff whose cause's launcher lives takes the launcher as its giver: it runs on"
          + " after the cause leaves, and ends its finish time after the launcher leaves")
  void aRunWhoseGiverLivesEndsAfterTheGiver(@TempDir Path folder) throws IOException {
    Scene scene = play(tables(folder), false);

    assertThat(scene.started()).as("the arrow's hit started the run").isPositive();
    // The Knight kills the Archer later, well after the arrow has gone.
    assertThat(scene.archerLeft())
        .as("the Archer leaves after the run would have ended with its arrow as the giver")
        .isGreaterThan(scene.arrowLeft() + FINISH_MS / 50);
    assertThat(scene.listed())
        .as("the steps the run is listed on")
        .isEqualTo(steps(scene.started(), scene.archerLeft() + FINISH_MS / 50 - 1));
  }

  /** The steps from first to last, both included. */
  private static List<Integer> steps(int first, int last) {
    return IntStream.rangeClosed(first, last).boxed().toList();
  }

  /**
   * Plays the scene, killing the Archer as its first arrow flies or not, and returns the ticks the
   * arrow was launched on, the Archer and the arrow left on (0 for never), the run was first listed
   * on, and every tick the run was listed on.
   */
  private static Scene play(GameTables tables, boolean killArcher) {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    BattleWorld world = match.getWorld();
    CharacterEntity archer =
        match.deploy(0, world.getRecords().unit("Archer"), LEVEL, 0, 3500, 6000, "A");
    CharacterEntity knight =
        match.deploy(0, world.getRecords().unit("Knight"), LEVEL, 1, 3500, 16000, "K");
    List<ProjectileEntity> arrows = new ArrayList<>();
    world.addObserver(
        new WorldObserver() {
          @Override
          public void projectileLaunched(int tick, ProjectileEntity projectile) {
            if (projectile.getOwner() == archer && arrows.isEmpty()) {
              arrows.add(projectile);
            }
          }
        });
    int launched = 0;
    int archerLeft = 0;
    int arrowLeft = 0;
    int started = 0;
    List<Integer> listed = new ArrayList<>();
    for (int tick = 1; tick <= TICKS; tick++) {
      match.getBattle().step();
      if (launched == 0 && !arrows.isEmpty()) {
        launched = tick;
        if (killArcher) {
          world.getHits().kill(archer, null);
        }
      }
      if (archerLeft == 0 && launched > 0 && !knight.liveObject(archer.getId())) {
        archerLeft = tick;
      }
      if (arrowLeft == 0 && launched > 0 && !knight.liveObject(arrows.get(0).getId())) {
        arrowLeft = tick;
      }
      if (enchanted(knight)) {
        listed.add(tick);
        if (started == 0) {
          started = tick;
        }
      }
    }
    return new Scene(launched, archerLeft, arrowLeft, started, listed);
  }

  /** True while the Knight lists an unfinished run of the enchanting buff. */
  private static boolean enchanted(CharacterEntity knight) {
    for (ActionInstance run : knight.actionHolder().running()) {
      if (run instanceof GiantBufferBuff.Run && !run.isFinished()) {
        return true;
      }
    }
    return false;
  }

  /** The tables with the enchanting buff written and the Archer's arrow starting it on a hit. */
  private static GameTables tables(Path folder) throws IOException {
    String arrow = Shipped.text(Shipped.unitRow("Archer"), "Projectile");
    GameData.altered(
        folder,
        "projectiles",
        rows -> GameData.columns(rows, arrow).put("OnHitTargetAction", ENCHANT));
    GameData.alterLoaded(
        folder,
        "actions",
        rows -> {
          ObjectNode row = rows.putObject(ENCHANT);
          row.put("class", "LogicActionGiantBufferBuffData");
          row.put("ClassType", "ActionGiantBufferBuff");
          row.putObject("fields")
              .put("ClassType", "ActionGiantBufferBuff")
              .put("AttackAmount", 3)
              .put("AddedDamage", 50)
              .put("AddedCrownTowerDamage", 70)
              .put("FinishIfInstigatorDies", FINISH_MS)
              .put("InstigatorDepth", 2);
        });
    return GameTables.load(folder);
  }
}
