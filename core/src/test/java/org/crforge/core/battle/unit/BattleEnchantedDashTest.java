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
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An enchanting buff, the Giant Buffer's, on a unit that dashes, as the Bandit and the Boss Bandit
 * do. The landing of a dash is one of the unit's attacks for its listening actions: its damage
 * passes their damage hooks under the landing's hit id, and once the landing is done they are told
 * the attack ended, whether or not its hit landed. So a dash counts toward the enchant's count, and
 * a dash that completes the count gains the added damage.
 *
 * <p>The scene: the bottom side's Bandit walks up the left lane and dashes at the top side's Giant
 * coming down it, then hits it every second; the Giant, which targets buildings only and has hit
 * points no hit takes, walks on, the towers passive. Both units are placed at the first level,
 * where their stats are their rows' own, and the scene writes every column its outcome is read
 * from. The buff, a hand-written row, is started on the Bandit as it is placed, the Bandit as its
 * giver.
 */
class BattleEnchantedDashTest {

  /** The level both units are placed at: the first, whose stats are the rows' own. */
  private static final int LEVEL = 1;

  /** The hand-written enchanting buff. */
  private static final String ENCHANT = "TestEnchant";

  private static final int DASH_DAMAGE = 152;

  private static final int HIT_DAMAGE = 76;

  /** The damage the buff adds to the hit that completes its count, at the first level. */
  private static final int ADDED_DAMAGE = 50;

  /** Long enough for the dash and eight hits after it. */
  private static final int TICKS = 260;

  /** One hit on the Giant: its tick, its damage and whether it was the dash's landing. */
  private record Hit(int tick, int damage, boolean dash) {}

  @Test
  @DisplayName(
      "a dash's landing counts as one of the Bandit's attacks: with a count of three, the second"
          + " hit after the dash completes it and gains the added damage")
  void theDashCountsTowardTheEnchant(@TempDir Path folder) throws IOException {
    List<Hit> hits = hits(tables(folder, 3), true);

    assertThat(hits).as("the dash and the hits after it").hasSizeGreaterThan(7);
    assertThat(hits.get(0).dash()).as("the first hit is the dash's landing").isTrue();
    assertThat(hits.get(0).damage())
        .as("the dash's damage, not the count's third")
        .isEqualTo(DASH_DAMAGE);
    List<Integer> after = hits.subList(1, hits.size()).stream().map(Hit::damage).toList();
    // The dash is the count's first attack, so the hits after it complete the count on the second,
    // the fifth and the eighth.
    for (int i = 0; i < after.size(); i++) {
      assertThat(after.get(i))
          .as("hit %d after the dash", i + 1)
          .isEqualTo(i % 3 == 1 ? HIT_DAMAGE + ADDED_DAMAGE : HIT_DAMAGE);
    }
  }

  @Test
  @DisplayName(
      "a dash's landing damage passes the enchant's damage hook: with a count of one, every"
          + " attack completes it, the dash's landing among them")
  void theDashThatCompletesTheCountGainsTheAddedDamage(@TempDir Path folder) throws IOException {
    List<Hit> hits = hits(tables(folder, 1), true);

    assertThat(hits).as("the dash and the hits after it").hasSizeGreaterThan(3);
    assertThat(hits.get(0).dash()).as("the first hit is the dash's landing").isTrue();
    assertThat(hits.get(0).damage()).isEqualTo(DASH_DAMAGE + ADDED_DAMAGE);
    for (Hit hit : hits.subList(1, hits.size())) {
      assertThat(hit.damage()).as("the hit on %d", hit.tick()).isEqualTo(HIT_DAMAGE + ADDED_DAMAGE);
    }
  }

  @Test
  @DisplayName("without the enchant the dash and every hit deal their rows' damage")
  void withoutTheEnchantNothingIsAdded(@TempDir Path folder) throws IOException {
    List<Hit> hits = hits(tables(folder, 3), false);

    assertThat(hits).hasSizeGreaterThan(7);
    assertThat(hits.get(0)).extracting(Hit::damage, Hit::dash).containsExactly(DASH_DAMAGE, true);
    assertThat(hits.subList(1, hits.size())).allMatch(h -> h.damage() == HIT_DAMAGE && !h.dash());
  }

  /** The hits the Bandit lands on the Giant, enchanted or not, in order. */
  private static List<Hit> hits(GameTables tables, boolean enchanted) {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    BattleWorld world = match.getWorld();
    CharacterEntity bandit =
        match.deploy(0, world.getRecords().unit("Assassin"), LEVEL, 0, 3500, 10000, "Bandit");
    CharacterEntity giant =
        match.deploy(0, world.getRecords().unit("Giant"), LEVEL, 1, 3500, 18000, "Giant");
    if (enchanted) {
      bandit
          .actionHolder()
          .start(world.getActions().build(ENCHANT, world.binding(bandit)), bandit.actionHolder());
    }
    List<Hit> hits = new ArrayList<>();
    int[] landing = {-1};
    world.addObserver(
        new WorldObserver() {
          @Override
          public void dashLanded(
              int tick, CharacterEntity unit, WorldEntity hit, int damage, boolean area) {
            if (unit == bandit && hit == giant) {
              landing[0] = tick;
            }
          }

          @Override
          public void damageDealt(int tick, WorldEntity target, int damage, DamageResult result) {
            if (target == giant) {
              // The landing's hit is dealt at the drain of the landing's tick.
              hits.add(new Hit(tick, damage, tick == landing[0]));
            }
          }
        });
    for (int step = 0; step < TICKS; step++) {
      match.getBattle().step();
    }
    return hits;
  }

  /** The tables with the scene's columns and the enchanting buff written. */
  private static GameTables tables(Path folder, int attackAmount) throws IOException {
    Files.createDirectories(folder);
    GameData.altered(
        folder,
        "characters",
        rows -> {
          GameData.columns(rows, "Assassin")
              .put("Hitpoints", 354)
              .put("Damage", HIT_DAMAGE)
              .put("HitSpeed", 1000)
              .put("LoadTime", 600)
              .put("Speed", 90)
              .put("Mass", 3)
              .put("CollisionRadius", 600)
              .put("Range", 750)
              .put("SightRange", 6000)
              .put("DeployTime", 1000)
              .put("DashDamage", DASH_DAMAGE)
              .put("DashMinRange", 3500)
              .put("DashMaxRange", 6000)
              .put("DashCooldown", 800)
              .put("DashImmuneToDamageTime", 150)
              .put("JumpSpeed", 500);
          GameData.columns(rows, "Giant")
              .put("Hitpoints", 1_000_000)
              .put("Damage", 99)
              .put("HitSpeed", 1500)
              .put("LoadTime", 1000)
              .put("Speed", 45)
              .put("StopMovementAfterMS", 640)
              .put("WaitMS", 100)
              .put("Mass", 18)
              .put("CollisionRadius", 750)
              .put("Range", 1200)
              .put("SightRange", 7500)
              .put("SightClip", 2000)
              .put("SightClipSide", 2000)
              .put("DeployTime", 1000)
              .put("ProjectileStartRadius", 450)
              .put("ProjectileStartZ", 450);
        });
    GameData.writeTowers(folder);
    GameData.alterLoaded(
        folder,
        "actions",
        rows -> {
          ObjectNode row = rows.putObject(ENCHANT);
          row.put("class", "LogicActionGiantBufferBuffData");
          row.put("ClassType", "ActionGiantBufferBuff");
          row.putObject("fields")
              .put("ClassType", "ActionGiantBufferBuff")
              .put("AttackAmount", attackAmount)
              .put("AddedDamage", ADDED_DAMAGE)
              .put("AddedCrownTowerDamage", 70)
              .put("FinishIfInstigatorDies", -1);
        });
    return GameTables.load(folder);
  }
}
