/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An enchanting buff, the Giant Buffer's, on a unit whose attack sequence replaces its attack.
 *
 * <p>The scene: the top side's Knight, given a continuous-damage ramp as the Inferno Dragon's (its
 * row's damage, then two more stages, each window 2000 of attack time) and a hit speed of 400,
 * walks to the bottom side's left princess tower and attacks it, the towers passive. The buff, a
 * hand-written row that adds damage to every third landed hit, is started on the Knight as it is
 * placed, the Knight as its giver.
 */
class BattleEnchantedSequenceTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The unit the scene plays. */
  private static final String UNIT = "Knight";

  /** The hand-written enchanting buff. */
  private static final String ENCHANT = "TestEnchant";

  /** Long enough for the walk to the tower and a ramp through its three stages. */
  private static final int TICKS = 600;

  /** One landed hit on the tower: its tick and its damage. */
  private record Hit(int tick, int damage) {}

  @Test
  @DisplayName(
      "an enchanting buff on a unit whose sequence is a ramp of damage entries alone runs: every"
          + " third hit on the tower gains the same added damage, the hit ticks and the ramp"
          + " unchanged")
  void anEnchantedRampGainsEveryThirdHit(@TempDir Path folder) throws IOException {
    GameTables tables = tables(folder, null);
    assertThat(new Standard1v1Battle(tables, LEVEL, false).getWorld().getRecords().unit(UNIT))
        .as("a sequence that replaces the attack")
        .extracting(unit -> unit.attackSequence().replacesAttack())
        .isEqualTo(true);

    List<Hit> plain = hits(tables, false);
    List<Hit> enchanted = hits(tables, true);

    // The added damage brings the tower down sooner, so the enchanted hits are the first of the
    // plain ones.
    assertThat(enchanted).as("enchanted hits on the tower").hasSizeGreaterThan(9);
    assertThat(plain.subList(0, enchanted.size()).stream().map(Hit::damage).distinct().count())
        .as("the ramp's stages")
        .isEqualTo(3);
    assertThat(enchanted.stream().map(Hit::tick).toList())
        .as("the same hit ticks")
        .isEqualTo(plain.subList(0, enchanted.size()).stream().map(Hit::tick).toList());
    Set<Integer> added = new HashSet<>();
    for (int i = 0; i < enchanted.size(); i++) {
      int gain = enchanted.get(i).damage() - plain.get(i).damage();
      if (i % 3 == 2) {
        added.add(gain);
      } else {
        assertThat(gain).as("hit %d, not a third", i + 1).isZero();
      }
    }
    assertThat(added).as("the added damage of every third hit").hasSize(1);
    assertThat(added.iterator().next()).isPositive();
  }

  @Test
  @DisplayName(
      "an enchanting buff on a unit whose sequence has an entry that launches a projectile is"
          + " refused")
  void anEnchantedSequenceThatLaunchesIsRefused(@TempDir Path folder) throws IOException {
    // The ramp's second stage fires the Archer's arrow.
    String arrow = Shipped.text(Shipped.unitRow("Archer"), "Projectile");
    GameTables tables = tables(folder, arrow);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    CharacterEntity unit =
        match.deploy(0, match.getWorld().getRecords().unit(UNIT), LEVEL, 1, 3500, 11000, "K");
    BattleWorld world = match.getWorld();
    BattleAction buff = world.getActions().build(ENCHANT, world.binding(unit));

    assertThatThrownBy(() -> unit.actionHolder().start(buff, unit.actionHolder()))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("attack sequence replaces its attack");
  }

  /** The hits the Knight lands on the bottom side's left princess tower, enchanted or not. */
  private static List<Hit> hits(GameTables tables, boolean enchanted) {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    TowerEntity tower = leftTower(match);
    List<Hit> hits = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void damageDealt(
                  int tick, WorldEntity target, int damage, DamageResult result) {
                if (target == tower) {
                  hits.add(new Hit(tick, damage));
                }
              }
            });
    CharacterEntity unit =
        match.deploy(0, match.getWorld().getRecords().unit(UNIT), LEVEL, 1, 3500, 11000, "K");
    if (enchanted) {
      BattleWorld world = match.getWorld();
      unit.actionHolder()
          .start(world.getActions().build(ENCHANT, world.binding(unit)), unit.actionHolder());
    }
    for (int step = 0; step < TICKS; step++) {
      match.getBattle().step();
    }
    return hits;
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
   * The tables with the Knight's ramp and the enchanting buff written.
   *
   * @param secondStageProjectile the projectile the ramp's second stage fires, or null for none
   */
  private static GameTables tables(Path folder, String secondStageProjectile) throws IOException {
    Files.createDirectories(folder);
    GameData.altered(
        folder,
        "characters",
        rows -> {
          ObjectNode columns = GameData.columns(rows, UNIT);
          columns
              .put("Damage", 10)
              .put("VariableDamage2", 30)
              .put("VariableDamage3", 90)
              .put("VariableDamageTime1", 2000)
              .put("VariableDamageTime2", 2000)
              .put("HitSpeed", 400);
          if (secondStageProjectile != null) {
            columns.put("Projectile2", secondStageProjectile);
          }
        });
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
              .put("FinishIfInstigatorDies", -1);
        });
    return GameTables.load(folder);
  }
}
