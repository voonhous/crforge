/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A spell on a unit that still waits its turn to deploy: the hidden test answers yes for a unit in
 * that state, so a projectile's impact, a travelling projectile's hit and an area effect's damage
 * all pass it by. So does the Freeze's area effect, though its filter does not leave hidden units
 * out: the damage entry its hit goes through refuses the unit as hidden.
 *
 * <p>The scene: the top side's Goblins are played on tick 32 in front of its right princess tower;
 * the four of them start deploying one after another, the last on tick 44. Each spell is cast by
 * the bottom side on them so that it acts on tick 41, while the last Goblin still waits.
 *
 * <p>The scene writes what it counts on: the Goblins card summons four Goblin_Stab of 79 hit points
 * 200 ms apart in a circle of 700, each waiting 400 ms and deploying 1000 ms; the Fireball, the
 * Snowball and the Arrows fly at 600, 800 and 1100 and hit in circles of 2500, 2500 and 1400, the
 * Arrows in three waves of ten 200 ms apart over a circle of 3500; the Zap hits once in a circle of
 * 2500; the towers, whose king casts them, stand on their tiles.
 */
class BattleWaitingToDeployHitTest {

  /** The Goblins' level, and the spells', which kill a Goblin of this level with one hit. */
  private static final int GOBLIN_LEVEL = 1;

  private static final int SPELL_LEVEL = 3;

  /** The Goblins' hit points written into their row, their own at their level. */
  private static final int GOBLIN_HIT_POINTS = 79;

  /** The tick each spell acts on. */
  private static final int HIT_TICK = 41;

  @TempDir static Path tablesFolder;

  /** The configured tables with the columns the scenes count on written. */
  private static GameTables tables;

  @BeforeAll
  static void writeTheScene() throws IOException {
    GameData.altered(
        tablesFolder,
        "spells_characters",
        rows ->
            GameData.columns(rows, "Goblins")
                .put("SummonCharacter", "Goblin_Stab")
                .put("SummonNumber", 4)
                .put("SummonDeployDelay", 200)
                .put("SummonRadius", 700));
    GameData.alterLoaded(
        tablesFolder,
        "characters",
        rows ->
            GameData.columns(rows, "Goblin_Stab")
                .put("Hitpoints", GOBLIN_HIT_POINTS)
                .put("DeployDelay", 400)
                .put("DeployTime", 1000));
    GameData.alterLoaded(
        tablesFolder,
        "projectiles",
        rows -> {
          GameData.columns(rows, "FireballSpell").put("Speed", 600).put("Radius", 2500);
          GameData.columns(rows, "SnowballSpell").put("Speed", 800).put("Radius", 2500);
          GameData.columns(rows, "ArrowsSpell").put("Speed", 1100).put("Radius", 1400);
        });
    GameData.alterLoaded(
        tablesFolder,
        "spells_other",
        rows ->
            GameData.columns(rows, "Arrows")
                .put("ProjectileWaves", 3)
                .put("ProjectileWaveInterval", 200)
                .put("MultipleProjectiles", 10)
                .put("Radius", 3500));
    GameData.alterLoaded(
        tablesFolder,
        "area_effect_objects",
        rows -> GameData.columns(rows, "Zap").put("Radius", 2500).put("LifeDuration", 1));
    GameData.alterLoaded(tablesFolder, "spawn_groups", GameData::placeTowers);
    tables = GameTables.load(tablesFolder);
  }

  /** The Goblins played, then the spell cast so that it acts on {@link #HIT_TICK}. */
  private static List<CharacterEntity> goblinsUnder(String spell, int castTick) {
    Standard1v1Battle match = new Standard1v1Battle(tables, 1, false);
    match.play(
        32,
        match.getWorld().getRecords().card("Goblins"),
        GOBLIN_LEVEL,
        1,
        13500,
        22500,
        "Goblins");
    match.play(
        castTick, match.getWorld().getRecords().card(spell), SPELL_LEVEL, 0, 14500, 23000, spell);
    while (match.getBattle().getTick() < HIT_TICK) {
      match.getBattle().step();
    }
    List<CharacterEntity> goblins =
        match.getPlays().stream()
            .filter(play -> play.units().size() == 4)
            .findFirst()
            .orElseThrow()
            .units();
    assertThat(goblins.get(2).getView().getState())
        .as("the third Goblin deploys")
        .isEqualTo(GridEntityState.DEPLOYING);
    assertThat(goblins.get(3).getView().getState())
        .as("the last Goblin still waits")
        .isEqualTo(GridEntityState.WAITING_TO_DEPLOY);
    assertThat(goblins.get(2).getHitPoints().getHitPoints())
        .as("the spell hit the deploying Goblin")
        .isLessThan(GOBLIN_HIT_POINTS);
    return goblins;
  }

  @Test
  @DisplayName("a Zap's area damage passes a unit waiting to deploy by")
  void zapPassesAWaitingUnitBy() {
    CharacterEntity waiting = goblinsUnder("Zap", 40).get(3);
    assertThat(waiting.getHitPoints().getHitPoints()).isEqualTo(GOBLIN_HIT_POINTS);
  }

  @Test
  @DisplayName("a Fireball's impact passes a unit waiting to deploy by")
  void fireballPassesAWaitingUnitBy() {
    CharacterEntity waiting = goblinsUnder("Fireball", 5).get(3);
    assertThat(waiting.getHitPoints().getHitPoints()).isEqualTo(GOBLIN_HIT_POINTS);
  }

  @Test
  @DisplayName("a Snowball's impact passes a unit waiting to deploy by")
  void snowballPassesAWaitingUnitBy() {
    CharacterEntity waiting = goblinsUnder("Snowball", 14).get(3);
    assertThat(waiting.getHitPoints().getHitPoints()).isEqualTo(GOBLIN_HIT_POINTS);
  }

  @Test
  @DisplayName("an arrow of the Arrows' first wave flies over a unit waiting to deploy")
  void arrowsPassAWaitingUnitBy() {
    CharacterEntity waiting = goblinsUnder("Arrows", 22).get(3);
    assertThat(waiting.getHitPoints().getHitPoints()).isEqualTo(GOBLIN_HIT_POINTS);
  }

  /**
   * The Freeze's filter does not leave hidden units out, but the game still leaves a unit waiting
   * to deploy untouched. Battles the game recorded hold it: in 232 battles of the 16.635.8 coverage
   * set an area effect with such a filter (the Freeze, the hero Ice Wizard's freeze and two rage
   * damage areas) had a unit waiting to deploy in its circle, and the battle core, passing it by,
   * agrees with the game in all of them; dealing it the hit instead made 33 of them disagree. Which
   * of the game's tests refuses the unit is not traced.
   */
  @Test
  @DisplayName("a Freeze passes a unit waiting to deploy by")
  void freezePassesAWaitingUnitBy() {
    List<CharacterEntity> goblins = goblinsUnder("Freeze", 40);
    assertThat(goblins.get(3).getHitPoints().getHitPoints()).isEqualTo(GOBLIN_HIT_POINTS);
  }
}
