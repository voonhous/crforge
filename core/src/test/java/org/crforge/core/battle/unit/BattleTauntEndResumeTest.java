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
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A taunt that gives up the reference of a unit that dashes resumes the unit at once, as any drop
 * of its reference through the setter does.
 *
 * <p>The setter's path for no reference asks a row with a dash cooldown and a dash range to resume,
 * unless it is in a jump. The taunt's end calls that setter itself, in the holder's run pass, after
 * the unit's targeting visit of the step: the resume runs there and then, so a standing dasher
 * whose taunt ends shows the moving state on that same step. Had the request waited for the
 * targeting visit, which clears it as the next step's visit begins, it would be lost and the unit
 * would go on standing.
 *
 * <p>The scene: the bottom side's hero Knight, standing still, taunts the top side's Mega Knight,
 * which stands within its dash range of the Knight and winds its dash up; the Mega Knight is put to
 * standing as the taunt is armed, and the taunt lasts a few steps, shorter than the wind-up. The
 * towers are passive. The scene writes every column its outcome is read from: the units' hit
 * points, movement, range, sight, deploy, attack pace and dash columns, and the taunt's durations.
 */
class BattleTauntEndResumeTest {

  /** The level the scene's units are placed at: the first, whose stats are the rows' own. */
  private static final int LEVEL = 1;

  /** The ability's spawn of the taunting area effect. */
  private static final String SPAWN = "Knight_hero_CreateTauntAEO";

  /** The taunt the area effect's hit group runs. */
  private static final String TAUNT =
      Shipped.actionNames(
              Shipped.text(
                  Shipped.row("area_effect_objects", Shipped.text(SPAWN, "SpawnData")),
                  "OnHitAction"),
              "SubActions")
          .get(0);

  /** How long the taunt holds the Mega Knight, in ms: well within its wind-up. */
  private static final int DURATION = 200;

  /** The Mega Knight's wind-up before a dash, in ms. */
  private static final int DASH_COOLDOWN = 900;

  /** How far the Mega Knight stands from the Knight: their edges within its dash range. */
  private static final int APART = 5500;

  /** The configured tables with the scene's columns written. */
  private static GameTables written(Path folder) throws IOException {
    GameData.copyConfigured(folder);
    GameData.alterLoaded(
        folder,
        "characters",
        rows -> {
          GameData.columns(rows, "MegaKnight")
              .put("Hitpoints", 100_000)
              .put("Speed", 60)
              .put("Mass", 18)
              .put("CollisionRadius", 750)
              .put("Range", 1200)
              .put("SightRange", 5500)
              .put("DeployTime", 1000)
              .put("HitSpeed", 1700)
              .put("LoadTime", 1200)
              .put("Damage", 10)
              .put("DashMinRange", 3500)
              .put("DashMaxRange", 5000)
              .put("DashCooldown", DASH_COOLDOWN)
              .put("DashConstantTime", 800)
              .put("DashLandingTime", 300)
              .put("DashDamage", 10)
              .put("DashRadius", 2200)
              .put("JumpHeight", 3000)
              .put("JumpSpeed", 250);
          GameData.columns(rows, "KnightHero")
              .put("Hitpoints", 100_000)
              .put("Speed", 0)
              .put("Range", 1200)
              .put("SightRange", 5500)
              .put("DeployTime", 50)
              .put("Damage", 10);
        });
    GameData.alterLoaded(
        folder,
        "actions",
        rows ->
            GameData.fields(rows, TAUNT)
                .put("ValidDuration", DURATION)
                .put("CrownTowerDuration", DURATION)
                .put("FalloffDelay", 0)
                .put("ResetOnExpiration", true));
    GameData.writeTowers(folder);
    return GameTables.load(folder);
  }

  @Test
  @DisplayName(
      "a standing Mega Knight winding up its dash moves on the step its taunt ends and gives up its"
          + " reference")
  void aStandingDasherResumesAsItsTauntEnds(@TempDir Path folder) throws IOException {
    Standard1v1Battle match = new Standard1v1Battle(written(folder), LEVEL, false);
    List<Integer> dropped = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void tauntStepped(
                  int tick,
                  WorldEntity unit,
                  WorldEntity forced,
                  int durationMs,
                  int falloffMs,
                  List<String> calls) {
                if (calls.stream().anyMatch(call -> call.startsWith("set_target null"))) {
                  dropped.add(tick);
                }
              }
            });
    CharacterEntity knight =
        match.deploy(
            0, match.getWorld().getRecords().unit("KnightHero"), LEVEL, 0, 9000, 12000, "knight");
    CharacterEntity megaKnight =
        match.deploy(
            0,
            match.getWorld().getRecords().unit("MegaKnight"),
            LEVEL,
            1,
            9000,
            12000 + APART,
            "mk");
    // Both deployed, the Mega Knight winding its dash up at the Knight.
    while (megaKnight.getTargeting().getDashWindupMs() < 1) {
      assertThat(match.getBattle().getTick()).as("the wind-up starts").isLessThan(100);
      match.getBattle().step();
    }

    BattleAction spawn = GameData.actions().build(SPAWN, match.getWorld().binding(knight));
    knight.actionHolder().start(spawn, knight.actionHolder());
    match.getBattle().step();
    assertThat(megaKnight.getBuffs().carries(Shipped.text(TAUNT, "ValidTargetBuff")))
        .as("the Mega Knight is taunted")
        .isTrue();
    megaKnight.requestState(GridEntityState.STANDING);

    while (dropped.isEmpty()) {
      assertThat(megaKnight.getView().getState())
          .as("the taunted Mega Knight stands, tick %d", match.getBattle().getTick())
          .isEqualTo(GridEntityState.STANDING);
      assertThat(match.getBattle().getTick()).as("the taunt ends").isLessThan(100 + DURATION / 50);
      match.getBattle().step();
    }
    assertThat(megaKnight.getTargeting().getDashWindupMs())
        .as("the wind-up is kept and runs on")
        .isGreaterThan(0);
    assertThat(megaKnight.getView().getState())
        .as("the Mega Knight moves on the step its taunt ends, tick %d", dropped.get(0))
        .isEqualTo(GridEntityState.MOVING);
  }
}
