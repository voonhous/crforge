/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.AreaDamage;
import org.crforge.core.pathfinding.target.TargetView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The area a dash landing deals collects its victims as a projectile's area does: it passes by a
 * unit the untouchable test refuses, the immunity left after a dash counted, so such a unit is
 * neither damaged nor pushed by it. A unit dashing under a row with a dash immunity is one: a Mega
 * Knight landing beside the Little Prince's guard as it charges leaves the charge on its way.
 *
 * <p>The scene: the bottom side's Bandit runs up the left lane at the top side's Knight, held where
 * it stands, and dashes at it. The Knight's row is given a landing area of a radius that reaches
 * the Bandit all along its run and its dash, and a pushback, and the landing area is dealt around
 * the Knight once while the Bandit walks and once on the first step it dashes. The walking Bandit
 * is collected and pushed; the dashing one is passed by. The scene writes every column its outcome
 * is read from - both units' rows and the towers - so what it sees is its own and not a version's.
 */
class BattleDashLandingAreaGateTest {

  /** The level of the towers and of both units. */
  private static final int LEVEL = 1;

  /** Where the Bandit is placed, on the bottom side's half of the left lane. */
  private static final int BANDIT_X = 3500;

  private static final int BANDIT_Y = 16000;

  /** Where the Knight is held. */
  private static final int KNIGHT_Y = 23850;

  /** Long enough for the Bandit's run and its dash. */
  private static final int TICKS = 200;

  /** The radius of the Knight's landing area: it reaches the Bandit from its placement on. */
  private static final int RADIUS = 9000;

  /** What one landing area around the Knight collected and pushed of the Bandit. */
  private record Landing(int state, boolean collected, boolean pushed) {}

  /**
   * Runs the scene to the Bandit's first dashing step, dealing the Knight's landing area around it
   * on the given walking step and on that dashing step, and returns what each did to the Bandit.
   */
  private static List<Landing> run(GameTables tables, int walkingTick) {
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, true);
    List<Landing> landings = new ArrayList<>();
    CharacterEntity bandit =
        match.deploy(0, records.unit("Assassin"), LEVEL, 0, BANDIT_X, BANDIT_Y, "Bandit");
    CharacterEntity knight =
        match.deploy(0, records.unit("Knight"), LEVEL, 1, BANDIT_X, KNIGHT_Y, "Knight");
    knight.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void areaDamaged(
                  int tick, WorldEntity owner, AreaDamage.Area area, AreaDamage.Outcome outcome) {
                if (owner == knight) {
                  landings.add(
                      new Landing(
                          bandit.getView().getState(),
                          contains(outcome.victims(), bandit),
                          contains(outcome.pushed(), bandit)));
                }
              }
            });
    for (int tick = 0; tick < TICKS; tick++) {
      match.getBattle().step();
      int state = bandit.getView().getState();
      boolean due =
          match.getBattle().getTick() == walkingTick
              || (state == GridEntityState.DASHING && landings.size() == 1);
      if (due) {
        match.getWorld().deaths().dashLandingArea(knight, 1, 0);
      }
      if (landings.size() == 2) {
        break;
      }
    }
    assertThat(landings).as("both landing areas dealt").hasSize(2);
    return landings;
  }

  private static boolean contains(List<TargetView> views, WorldEntity entity) {
    return views.stream().anyMatch(view -> view.id() == entity.getId());
  }

  /** The configured tables with the scene's columns written. */
  private static GameTables written(Path folder) throws IOException {
    GameData.altered(
        folder,
        "characters",
        rows -> {
          GameData.columns(rows, "Assassin")
              .put("Hitpoints", 354)
              .put("Damage", 76)
              .put("DashDamage", 152)
              .put("DashCooldown", 800)
              .put("DashImmuneToDamageTime", 100)
              .put("DashMinRange", 3500)
              .put("DashMaxRange", 6000)
              .put("JumpSpeed", 500)
              .put("HitSpeed", 1000)
              .put("LoadTime", 600)
              .put("Speed", 90)
              .put("Mass", 3)
              .put("CollisionRadius", 600)
              .put("Range", 750)
              .put("SightRange", 6000)
              .put("DeployTime", 1000);
          GameData.columns(rows, "Knight")
              .put("Hitpoints", 690)
              .put("Damage", 79)
              .put("HitSpeed", 1200)
              .put("LoadTime", 700)
              .put("Speed", 60)
              .put("Mass", 6)
              .put("CollisionRadius", 500)
              .put("Range", 1200)
              .put("SightRange", 5500)
              .put("DeployTime", 1000)
              .put("DashRadius", RADIUS)
              .put("DashPushBack", 1000);
        });
    GameData.writeTowers(folder);
    return GameTables.load(folder);
  }

  @Test
  @DisplayName(
      "a dash landing's area pushes a walking Bandit and passes by a dashing one, whose row gives"
          + " it a dash immunity")
  void theLandingAreaPassesTheDashingBanditBy(@TempDir Path folder) throws IOException {
    // The walking step: after the Bandit's deploy time of 1000 ms, before its dash.
    List<Landing> landings = run(written(folder), 30);
    assertThat(landings.get(0))
        .as("the walking Bandit")
        .isEqualTo(new Landing(GridEntityState.MOVING, true, true));
    assertThat(landings.get(1))
        .as("the dashing Bandit")
        .isEqualTo(new Landing(GridEntityState.DASHING, false, false));
  }
}
