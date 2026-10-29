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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An enchanting buff, the Giant Buffer's, on a unit whose projectile deals no damage itself but
 * spawns projectiles where it lands, as the Firecracker's does.
 *
 * <p>The scene: side 1's Firecracker shoots side 0's left princess tower, the towers passive. A
 * hand-written buff that adds damage to every third attack is started on it as it is placed, the
 * Firecracker as its giver. The third attack's projectile carries a copy of the buff, and each
 * projectile it spawns as it lands is handed a copy of that copy, its source being the projectile
 * that spawned it; the copy adds the damage on that projectile's hits, by the multiplier the buff
 * names for the spawned projectile's row.
 */
class BattleEnchantedSpawnTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The unit the scene plays: its projectile spawns projectiles where it lands. */
  private static final String UNIT = "Firecracker";

  /** The hand-written enchanting buff. */
  private static final String ENCHANT = "TestEnchant";

  /** Long enough for the deploy and several attacks. */
  private static final int TICKS = 800;

  /** One impact on the tower: its tick and the hit points the tower lost on it. */
  private record Impact(int tick, int loss) {}

  @Test
  @DisplayName(
      "an enchanted unit's third projectile hands a copy to every projectile it spawns: the"
          + " tower loses more on every third impact, every other impact unchanged")
  void theSpawnedProjectilesOfTheThirdAttackGainTheAddedDamage(@TempDir Path folder)
      throws IOException {
    GameTables tables = tables(folder, null);
    String parent = Shipped.text(Shipped.unitRow(tables, UNIT), "Projectile");
    assertThat(tables.table("projectiles").row(parent).has("Damage"))
        .as("the unit's own projectile deals no damage")
        .isFalse();
    assertThat(tables.table("projectiles").row(parent).has("SpawnProjectile"))
        .as("the unit's own projectile spawns projectiles")
        .isTrue();

    List<Impact> plain = impacts(tables, false);
    List<Impact> enchanted = impacts(tables, true);

    assertThat(enchanted).as("impacts on the tower").hasSizeGreaterThan(6);
    assertThat(enchanted.stream().map(Impact::tick).toList())
        .as("the same impact ticks")
        .isEqualTo(plain.subList(0, enchanted.size()).stream().map(Impact::tick).toList());
    Set<Integer> added = new HashSet<>();
    for (int i = 0; i < enchanted.size(); i++) {
      int gain = enchanted.get(i).loss() - plain.get(i).loss();
      if (i % 3 == 2) {
        added.add(gain);
      } else {
        assertThat(gain).as("impact %d, not a third", i + 1).isZero();
      }
    }
    assertThat(added).as("the added damage of every third impact").hasSize(1);
    assertThat(added.iterator().next()).isPositive();
  }

  @Test
  @DisplayName(
      "the added damage a spawned projectile carries is scaled by the multiplier the buff names"
          + " for the spawned projectile's row, not its parent's")
  void theSpawnedRowsMultiplierScalesTheAddedDamage(@TempDir Path folder) throws IOException {
    GameTables tables = tables(folder.resolve("unnamed"), null);
    String parent = Shipped.text(Shipped.unitRow(tables, UNIT), "Projectile");
    String spawned = tables.table("projectiles").row(parent).string("SpawnProjectile");

    int unnamed = thirdImpactGain(tables);
    int none = thirdImpactGain(tables(folder.resolve("none"), Map.of(spawned, 0)));
    int doubled = thirdImpactGain(tables(folder.resolve("doubled"), Map.of(spawned, 2000)));
    int parentOnly = thirdImpactGain(tables(folder.resolve("parent"), Map.of(parent, 0)));

    assertThat(unnamed).as("no multiplier named").isPositive();
    assertThat(none).as("the spawned row's multiplier 0").isZero();
    assertThat(doubled).as("the spawned row's multiplier 2000").isGreaterThan(unnamed);
    assertThat(parentOnly).as("a multiplier named for the parent row only").isEqualTo(unnamed);
  }

  /** How much more the tower loses on the third impact enchanted than plain. */
  private static int thirdImpactGain(GameTables tables) {
    List<Impact> plain = impacts(tables, false);
    List<Impact> enchanted = impacts(tables, true);
    assertThat(enchanted.get(2).tick()).isEqualTo(plain.get(2).tick());
    return enchanted.get(2).loss() - plain.get(2).loss();
  }

  /**
   * The ticks side 0's left princess tower loses hit points on, enchanted or not, up to the impact
   * that brings it down, whose loss is only what it had left.
   */
  private static List<Impact> impacts(GameTables tables, boolean enchanted) {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    TowerEntity tower = leftTower(match);
    CharacterEntity unit =
        match.deploy(0, match.getWorld().getRecords().unit(UNIT), LEVEL, 1, 3500, 11000, "F");
    if (enchanted) {
      BattleWorld world = match.getWorld();
      unit.actionHolder()
          .start(world.getActions().build(ENCHANT, world.binding(unit)), unit.actionHolder());
    }
    List<Impact> impacts = new ArrayList<>();
    int before = tower.getHitPoints().getHitPoints();
    for (int step = 0; step < TICKS && before > 0; step++) {
      match.getBattle().step();
      int after = tower.getHitPoints().getHitPoints();
      if (after < before && after > 0) {
        impacts.add(new Impact(step, before - after));
      }
      before = after;
    }
    return impacts;
  }

  /** Side 0's left princess tower. */
  private static TowerEntity leftTower(Standard1v1Battle match) {
    return match.getWorld().getHolder().entities().stream()
        .filter(TowerEntity.class::isInstance)
        .map(TowerEntity.class::cast)
        .filter(t -> t.side() == 0 && !t.getData().king() && t.x() < 9000)
        .findFirst()
        .orElseThrow();
  }

  /**
   * The configured tables with the enchanting buff written.
   *
   * @param multipliers the multiplier the buff names per row, in thousandths, or null for none
   */
  private static GameTables tables(Path folder, Map<String, Integer> multipliers)
      throws IOException {
    Files.createDirectories(folder);
    GameData.copyConfigured(folder);
    GameData.alterLoaded(
        folder,
        "actions",
        rows -> {
          ObjectNode row = rows.putObject(ENCHANT);
          row.put("class", "LogicActionGiantBufferBuffData");
          row.put("ClassType", "ActionGiantBufferBuff");
          ObjectNode fields =
              row.putObject("fields")
                  .put("ClassType", "ActionGiantBufferBuff")
                  .put("AttackAmount", 3)
                  .put("AddedDamage", 10)
                  .put("AddedCrownTowerDamage", 10)
                  .put("FinishIfInstigatorDies", -1);
          if (multipliers != null) {
            ArrayNode names = fields.putArray("DamageMultiplierPerUnitNames");
            ArrayNode values = fields.putArray("DamageMultiplierPerUnitValues");
            multipliers.forEach(
                (name, value) -> {
                  names.add(name);
                  values.add(value);
                });
          }
        });
    return GameTables.load(folder);
  }
}
