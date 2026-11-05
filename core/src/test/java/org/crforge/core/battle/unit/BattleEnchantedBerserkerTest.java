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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.Berserk;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An enchanting buff, the Giant Buffer's, on a unit that flips its attack sequence index as the
 * Berserker does.
 *
 * <p>The scene: the top side's unit walks to the bottom side's left princess tower and attacks it,
 * the towers passive. Each unit tested is given the same three attack sequence entries, each with a
 * damage of its own, so the hit shows the entry it landed with, and the Berserker's starting
 * action, which sets the index to 0 and flips it 0, 1, 0, 1 on every landed attack. Once that
 * action runs, a hand-written buff that adds damage to every third landed hit is started on the
 * unit, the unit as its giver, so its run is listed after the flipping one and hears each attack's
 * end after it.
 *
 * <p>On the attack that ends one short of the count's completion, the buff of a unit whose row name
 * holds "Berserker" stores the index 2, so the hit that completes the count lands with the third
 * entry and the flip then takes the index back to 0. The buff of any other unit leaves the index
 * alone.
 */
class BattleEnchantedBerserkerTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The hand-written enchanting buff. */
  private static final String ENCHANT = "TestEnchant";

  /** The units the scene plays, each given the same sequence and starting action. */
  private static final List<String> UNITS = List.of("Berserker", "BerserkerHero", "Knight");

  /** Long enough for the walk to the tower and a dozen hits. */
  private static final int TICKS = 600;

  /** One landed hit on the tower: the index it landed with and its damage. */
  private record Hit(int index, int damage) {}

  @Test
  @DisplayName(
      "an enchanting buff on a unit whose row name holds Berserker stores the attack sequence"
          + " index 2 on the attack before the count completes, so the completing hit lands with"
          + " the third entry; on any other unit it leaves the index alone")
  void anEnchantedBerserkerLandsTheCompletingHitWithItsThirdEntry(@TempDir Path folder)
      throws IOException {
    GameTables tables = tables(folder);

    for (String unit : UNITS) {
      List<Hit> plain = hits(tables, unit, false);
      assertThat(plain).as("%s's plain hits on the tower", unit).hasSizeGreaterThan(8);
      assertThat(plain.stream().map(Hit::index).toList())
          .as("%s's plain indexes flip 0, 1", unit)
          .isEqualTo(cycle(plain.size(), 0, 1));
    }

    for (String unit : List.of("Berserker", "BerserkerHero")) {
      List<Hit> plain = hits(tables, unit, false);
      List<Hit> enchanted = hits(tables, unit, true);
      assertThat(enchanted).as("%s's enchanted hits on the tower", unit).hasSizeGreaterThan(8);
      assertThat(enchanted.stream().map(Hit::index).toList())
          .as("%s's enchanted indexes: the completing hit with the third entry", unit)
          .isEqualTo(cycle(enchanted.size(), 0, 1, 2));
      // The hits at the first two entries are the plain hits at those entries; every completing
      // hit lands the third entry's damage with the added damage, which no plain hit deals.
      Map<Integer, Integer> plainByIndex = damageByIndex(plain);
      Map<Integer, Integer> enchantedByIndex = damageByIndex(enchanted);
      assertThat(enchantedByIndex.get(0))
          .as("%s at the first entry", unit)
          .isEqualTo(plainByIndex.get(0));
      assertThat(enchantedByIndex.get(1))
          .as("%s at the second entry", unit)
          .isEqualTo(plainByIndex.get(1));
      assertThat(enchantedByIndex.get(2))
          .as("%s's completing hits", unit)
          .isGreaterThan(plainByIndex.get(1))
          .isNotIn(plainByIndex.values());
    }

    List<Hit> knight = hits(tables, "Knight", true);
    assertThat(knight).as("the enchanted Knight's hits on the tower").hasSizeGreaterThan(8);
    assertThat(knight.stream().map(Hit::index).toList())
        .as("the enchanted Knight's indexes still flip 0, 1")
        .isEqualTo(cycle(knight.size(), 0, 1));
  }

  /** The repeating indexes, as many as asked for. */
  private static List<Integer> cycle(int size, Integer... indexes) {
    List<Integer> out = new ArrayList<>();
    for (int i = 0; i < size; i++) {
      out.add(indexes[i % indexes.length]);
    }
    return out;
  }

  /** The one damage the hits at each index dealt; fails when hits at one index differ. */
  private static Map<Integer, Integer> damageByIndex(List<Hit> hits) {
    Map<Integer, Set<Integer>> seen =
        hits.stream()
            .collect(
                Collectors.groupingBy(
                    Hit::index, Collectors.mapping(Hit::damage, Collectors.toSet())));
    Map<Integer, Integer> out = new HashMap<>();
    seen.forEach(
        (index, damages) -> {
          assertThat(damages).as("the damage of the hits at index %d", index).hasSize(1);
          out.put(index, damages.iterator().next());
        });
    return out;
  }

  /**
   * The hits the unit lands on the bottom side's left princess tower, each with the index the
   * flipping action read as the attack ended, enchanted or not.
   */
  private static List<Hit> hits(GameTables tables, String row, boolean enchanted) {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    TowerEntity tower = leftTower(match);
    List<Integer> damages = new ArrayList<>();
    List<Integer> indexes = new ArrayList<>();
    boolean[] flipping = new boolean[1];
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void damageDealt(
                  int tick, WorldEntity target, int damage, DamageResult result) {
                if (target == tower) {
                  damages.add(damage);
                }
              }

              @Override
              public void berserked(
                  int tick, WorldEntity unit, Berserk.Event event, int before, int index) {
                if (event == Berserk.Event.START) {
                  flipping[0] = true;
                } else {
                  // Every attack of the unit is on the tower, so the n-th notice is the n-th hit's,
                  // whose damage the tower takes as the hit queue is drained.
                  indexes.add(before);
                }
              }
            });
    CharacterEntity unit =
        match.deploy(0, match.getWorld().getRecords().unit(row), LEVEL, 1, 3500, 11000, "U");
    boolean started = false;
    for (int step = 0; step < TICKS; step++) {
      match.getBattle().step();
      if (enchanted && flipping[0] && !started) {
        BattleWorld world = match.getWorld();
        unit.actionHolder()
            .start(world.getActions().build(ENCHANT, world.binding(unit)), unit.actionHolder());
        started = true;
      }
    }
    assertThat(flipping[0]).as("%s's flipping action ran", row).isTrue();
    List<Hit> hits = new ArrayList<>();
    for (int i = 0; i < Math.min(indexes.size(), damages.size()); i++) {
      hits.add(new Hit(indexes.get(i), damages.get(i)));
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
   * The tables with each unit's sequence and starting action, written inline and linked to its row,
   * and the enchanting buff written.
   */
  private static GameTables tables(Path folder) throws IOException {
    Files.createDirectories(folder);
    GameData.altered(
        folder,
        "characters",
        rows -> {
          for (String unit : UNITS) {
            ObjectNode columns = GameData.columns(rows, unit);
            columns.putArray("AttackSequence").add(0).add(1).add(2);
            columns.put("AttackSequenceMode", "None");
            ArrayNode list = columns.putArray("AttackSequenceList");
            list.addObject().put("Damage", 40);
            list.addObject().put("Damage", 80);
            list.addObject().put("Damage", 160);
            columns.putObject("OnStartingAction").put("ClassType", "ActionBerserk");
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
          // The row a starting action written inline is linked to.
          for (String unit : UNITS) {
            ObjectNode berserk = rows.putObject(unit + "_OnStartingAction");
            berserk.put("class", "LogicActionBerserkData");
            berserk.put("ClassType", "ActionBerserk");
            berserk.putObject("fields").put("ClassType", "ActionBerserk");
          }
        });
    return GameTables.load(folder);
  }
}
