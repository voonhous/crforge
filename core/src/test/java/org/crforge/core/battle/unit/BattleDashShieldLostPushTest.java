/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * A Bandit that dashes into a unit whose shield breaks under the dash: the shield's lost action
 * makes an explosion around the shielded unit that pushes and damages the enemies its filter lists,
 * and the filter's DashImmune kind leaves out a character in its dash and, while the filter's
 * RespectDodgingDamageCooldown is on, one whose immunity after its dash still counts. A row that
 * leaves the column out takes client 16.635.1's loader default, on: the explosion, made after the
 * dash has landed, neither pushes nor damages the Bandit. A row that writes it off keeps client
 * 16.402.17's test, which had no such column: the explosion lists and pushes the Bandit, and only
 * the damage drain, which counts the immunity whatever the filter, refuses the damage.
 *
 * <p>The scene: the bottom side's Bandit walks up the left lane at the top side's evolved Wizard,
 * placed on the bottom half, that neither moves nor attacks; the Bandit dashes into it, its dash
 * breaks the shield, and the shield's lost action spawns its explosion with the Wizard as source.
 * The scene writes every column its outcome is read from: the Bandit's pace, reach and dash, the
 * Wizard's hit points and shield, its lost action and its part, the explosion's radius, push,
 * damage and filter, and the filter's kinds.
 */
class BattleDashShieldLostPushTest {

  /** The level every object is placed at: the first, whose stats are the rows' own. */
  private static final int LEVEL = 1;

  /** Long enough for the Bandit to deploy, wind up, dash in and have the explosion act. */
  private static final int TICKS = 200;

  /** The Bandit's hit points, more than the scene's hits take. */
  private static final int BANDIT_HIT_POINTS = 1_000_000;

  /** The shielded unit, its row taken over by the scene's columns. */
  private static final String SHIELDED = "Wizard_EV1";

  /** The shielded unit's lost action, an action group, and its one part, a spawn. */
  private static final String LOST = "Wizard_EV1_ShieldLost";

  private static final String LOST_SPAWN = "Wizard_EV1_ShieldLostAoE";

  /** The explosion the lost action makes, its row taken over by the scene's columns. */
  private static final String EXPLOSION = "Wizard_EV1_ShieldLostExplosion";

  /** The explosion's filter, its row taken over by the scene's columns. */
  private static final String FILTER = "CommonAreaDamageFilter";

  /**
   * The configured tables with the scene's columns written.
   *
   * @param respect the filter's RespectDodgingDamageCooldown, or null to leave the column out
   */
  private static GameTables written(Path folder, Boolean respect) throws IOException {
    Files.createDirectories(folder);
    GameData.altered(
        folder,
        "characters",
        rows -> {
          GameData.columns(rows, "Assassin")
              .put("Hitpoints", BANDIT_HIT_POINTS)
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
              .put("JumpSpeed", 500);
          ObjectNode shielded =
              GameData.columns(rows, SHIELDED)
                  .put("Hitpoints", 1_000_000)
                  .put("ShieldHitpoints", 50)
                  .put("ShieldLostAction", LOST)
                  .put("CollisionRadius", 500)
                  .put("Mass", 5)
                  .put("DeployTime", 1000);
          // No starting action: the shield's own effect has no part in the push.
          shielded.remove("OnStartingAction");
        });
    GameData.alterLoaded(
        folder,
        "actions",
        rows -> {
          ObjectNode group = GameData.fields(rows, LOST);
          group.removeAll();
          group.put("ClassType", "ActionGroup");
          group.put("AbortIfInstigatorDies", false);
          group.putArray("SubActions").addObject().put("action", LOST_SPAWN);
          group.putArray("SubActionsDelay").add(0);
          ObjectNode spawn = GameData.fields(rows, LOST_SPAWN);
          spawn.removeAll();
          spawn.put("ClassType", "ActionSpawn");
          spawn.put("AbortIfInstigatorDies", false);
          spawn.put("ParentGOAsSource", true);
          spawn.put("SpawnData", EXPLOSION);
          spawn.put("SpawnType", "AreaEffectType");
        });
    GameData.alterLoaded(
        folder,
        "area_effect_objects",
        rows -> {
          ObjectNode columns = GameData.columns(rows, EXPLOSION);
          columns.removeAll();
          columns.put("Radius", 3000);
          columns.put("Pushback", 3000);
          columns.put("LifeDuration", 1);
          columns.put("Filter", FILTER);
          columns.putObject("Damage").put("BaseDamage", 110);
        });
    GameData.alterLoaded(
        folder,
        "game_object_filters",
        rows -> {
          ObjectNode columns = GameData.columns(rows, FILTER);
          columns.removeAll();
          columns.put("MatchTeamEnemy", true);
          columns.put("MatchTypeCharacters", true);
          ArrayNode filters = columns.putArray("Filters");
          filters.add("Hidden");
          filters.add("Underground");
          filters.add("NoHitpointComponent");
          filters.add("DashImmune");
          if (respect != null) {
            columns.put("RespectDodgingDamageCooldown", respect);
          }
        });
    GameData.alterLoaded(
        folder,
        "buildings",
        rows -> {
          GameData.writeTowerColumns(rows);
        });
    GameData.alterLoaded(folder, "projectiles", GameData::writeTowerShots);
    GameData.alterLoaded(folder, "spawn_groups", GameData::placeTowers);
    return GameTables.load(folder);
  }

  /**
   * What the explosion met: the step it acted on, the Bandit's state and immunity left as it did,
   * whether the Bandit was pushed by it, and the hit points the Bandit lost to it.
   */
  private record Seen(
      int explosionTick, int state, int immunityLeft, boolean pushed, int lost, int shield) {}

  /** Plays the scene, the towers passive, until the explosion has acted. */
  private static Seen play(GameTables tables) {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    CharacterEntity wizard =
        match.deploy(
            0, match.getWorld().getRecords().unit(SHIELDED), LEVEL, 1, 3500, 14000, "Wizard");
    CharacterEntity bandit =
        match.deploy(
            0, match.getWorld().getRecords().unit("Assassin"), LEVEL, 0, 3500, 7000, "Bandit");
    int[] made = {-1};
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void areaEffectCreated(
                  int tick, AreaEffectEntity areaEffect, String how, String source) {
                if (areaEffect.getData().name().equals(EXPLOSION)) {
                  made[0] = tick;
                }
              }
            });
    boolean held = false;
    for (int step = 0; step < TICKS; step++) {
      match.getBattle().step();
      // The Wizard stands and never attacks, once it is placed.
      if (!held && wizard.isActive(CharacterEntity.TARGETING_SLOT)) {
        wizard.setActive(CharacterEntity.TARGETING_SLOT, false);
        wizard.setActive(CharacterEntity.MOVEMENT_SLOT, false);
        held = true;
      }
      if (made[0] < 0) {
        continue;
      }
      // The explosion acts on the step after the one that made it.
      int state = bandit.getView().getState();
      int immunity = bandit.getUnit().timers().getDashImmunityRemainingMs();
      int before = bandit.getHitPoints().getHitPoints();
      match.getBattle().step();
      boolean pushed = bandit.getUnit().movement().getPushbackInFlight() != 0;
      return new Seen(
          match.getWorld().tick(),
          state,
          immunity,
          pushed,
          before - bandit.getHitPoints().getHitPoints(),
          wizard.getHitPoints().getShield());
    }
    throw new AssertionError("the explosion was never made");
  }

  @Test
  @DisplayName(
      "under a filter that leaves RespectDodgingDamageCooldown out or writes it on, the explosion of"
          + " a shield the Bandit's dash broke neither pushes nor damages the Bandit, whose immunity"
          + " after its dash still counts")
  void theExplosionLeavesOutTheBandit(@TempDir Path folder) throws IOException {
    for (Boolean respect : new Boolean[] {null, true}) {
      Seen scene = play(written(folder.resolve(String.valueOf(respect)), respect));
      assertThat(scene.shield()).as("the shield broke").isZero();
      assertThat(scene.state()).as("the dash has landed").isNotEqualTo(GridEntityState.DASHING);
      assertThat(scene.immunityLeft()).as("the immunity after the dash").isPositive();
      assertThat(scene.pushed()).as("pushed, column %s", respect).isFalse();
      assertThat(scene.lost()).as("damaged").isZero();
    }
  }

  @Test
  @DisplayName(
      "under a filter that writes RespectDodgingDamageCooldown off, the same explosion lists and"
          + " pushes the Bandit while its immunity after its dash still counts; the damage drain"
          + " refuses its damage")
  void withTheColumnOffTheExplosionPushesTheBandit(@TempDir Path folder) throws IOException {
    Seen scene = play(written(folder, false));
    assertThat(scene.shield()).as("the shield broke").isZero();
    assertThat(scene.state()).as("the dash has landed").isNotEqualTo(GridEntityState.DASHING);
    assertThat(scene.immunityLeft()).as("the immunity after the dash").isPositive();
    assertThat(scene.pushed()).as("pushed").isTrue();
    // The drain asks the untouchable test with the immunity counted, column or not.
    assertThat(scene.lost()).as("damaged").isZero();
  }
}
