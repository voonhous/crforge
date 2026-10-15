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
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The area of a character's direct hit collects its victims as a projectile's area and a dash
 * landing's area do: it passes by a unit the untouchable test refuses as the area collects, the
 * immunity left after a dash counted. A dasher whose own visit of the tick counts that immunity
 * down to 0 after the hit is still passed by, so it takes no share at the damage drain: a Valkyrie
 * spinning as a Bandit's dash ends beside it leaves the Bandit's hit points as they were.
 *
 * <p>The scene: the bottom side's Bandit runs up the left lane at the top side's Knight, held where
 * it stands, and dashes at it. The Knight's row is given an area on its hit, and a load time that
 * lands its first hit on the step the Bandit's dash ends, while the immunity its row gives it after
 * the dash still counts. The scene writes every column its outcome is read from - both units' rows
 * and the towers - so what it sees is its own and not a version's.
 */
class BattleDirectHitAreaGateTest {

  /** The level of the towers and of both units. */
  private static final int LEVEL = 1;

  /** Where the Bandit is placed, on the bottom side's half of the left lane. */
  private static final int BANDIT_X = 3500;

  private static final int BANDIT_Y = 16000;

  /** Where the Knight is held. */
  private static final int KNIGHT_Y = 23850;

  /** Long enough for the Bandit's run, its dash and three of the Knight's hits. */
  private static final int TICKS = 100;

  /** What one area of the Knight's hit saw of the Bandit as it collected. */
  private record Area(int tick, boolean dashing, boolean untouchable, boolean collected) {}

  /**
   * What the scene saw: the Knight's areas, and the ticks its hits' shares landed on the Bandit.
   */
  private record Seen(List<Area> areas, List<Integer> landed) {}

  private static Seen run(GameTables tables) {
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, true);
    CharacterEntity bandit =
        match.deploy(0, records.unit("Assassin"), LEVEL, 0, BANDIT_X, BANDIT_Y, "Bandit");
    CharacterEntity knight =
        match.deploy(0, records.unit("Knight"), LEVEL, 1, BANDIT_X, KNIGHT_Y, "Knight");
    knight.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    List<Area> areas = new ArrayList<>();
    List<Integer> landed = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void areaDamaged(
                  int tick, WorldEntity owner, AreaDamage.Area area, AreaDamage.Outcome outcome) {
                if (owner == knight) {
                  areas.add(
                      new Area(
                          tick,
                          bandit.getView().getState() == GridEntityState.DASHING,
                          bandit.untouchable(true),
                          outcome.victims().stream()
                              .anyMatch(view -> view.id() == bandit.getId())));
                }
              }

              @Override
              public void areaHit(
                  int tick,
                  WorldEntity attacker,
                  WorldEntity victim,
                  int damage,
                  int hitId,
                  DamageResult result) {
                if (attacker == knight && victim == bandit && result.landed()) {
                  landed.add(tick);
                }
              }
            });
    for (int tick = 0; tick < TICKS; tick++) {
      match.getBattle().step();
    }
    return new Seen(areas, landed);
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
              .put("AreaDamageRadius", 2000)
              .put("HitSpeed", 800)
              .put("LoadTime", 700)
              .put("Speed", 60)
              .put("Mass", 6)
              .put("CollisionRadius", 500)
              .put("Range", 1200)
              .put("SightRange", 5500)
              .put("DeployTime", 1000);
        });
    GameData.writeTowers(folder);
    return GameTables.load(folder);
  }

  @Test
  @DisplayName(
      "the area of a direct hit passes by a Bandit whose dash immunity still counts as the area"
          + " collects, and the Bandit takes no share of it")
  void theHitAreaPassesTheBanditBy(@TempDir Path folder) throws IOException {
    Seen seen = run(written(folder));
    assertThat(seen.areas()).as("the Knight's areas").hasSizeGreaterThanOrEqualTo(3);
    Area first = seen.areas().get(0);
    assertThat(first)
        .as("the first area, on the step the Bandit's dash ends: the immunity still counts")
        .extracting(Area::dashing, Area::untouchable)
        .containsExactly(false, true);
    assertThat(first.collected()).as("the first area collects the Bandit").isFalse();
    assertThat(seen.areas().subList(1, seen.areas().size()))
        .as("the later areas, the immunity spent")
        .allSatisfy(area -> assertThat(area.collected()).isTrue());
    assertThat(seen.landed())
        .as("the ticks the Knight's areas landed on the Bandit")
        .hasSize(seen.areas().size() - 1)
        .allSatisfy(tick -> assertThat(tick).isGreaterThan(first.tick()));
  }
}
