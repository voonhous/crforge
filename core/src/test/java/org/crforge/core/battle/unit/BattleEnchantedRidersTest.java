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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.GiantBufferBuff;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An enchanting buff, the Giant Buffer's, on a unit that carries riders: its start gives each rider
 * a run of its own, which counts the rider's hits and enchants them as the carrier's run does the
 * carrier's.
 *
 * <p>The scene: the top side's Goblin Giant, played by its card so that it makes its riders, walks
 * to the bottom side's left princess tower, the towers passive, and its riders throw their spears
 * at the tower. The buff, a hand-written row that adds damage to every third landed hit, is started
 * on the Giant once its riders are made, the Giant as its giver.
 */
class BattleEnchantedRidersTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The carrier the scene plays. */
  private static final String CARRIER = "GoblinGiant";

  /** The hand-written enchanting buff. */
  private static final String ENCHANT = "TestEnchant";

  /** The tick the carrier's play runs on. */
  private static final int PLAY_TICK = 10;

  /** The tick the buff is started on, once the carrier and its riders are made. */
  private static final int ENCHANT_TICK = 40;

  /** Long enough for the walk to the tower and a good many spears. */
  private static final int TICKS = 900;

  /** One spear's impact on the tower: its tick and its damage. */
  private record Hit(int tick, int damage) {}

  /** What one run of the scene saw. */
  private record Scene(
      Map<Integer, List<Hit>> riderHits, List<Integer> riders, Map<Integer, Integer> runsListed) {}

  @Test
  @DisplayName(
      "an enchanting buff started on a carrier gives each of its riders a run of its own: every"
          + " third spear of each rider gains the same added damage on the tower, the impact"
          + " ticks unchanged")
  void eachRiderTakesARunOfItsOwn(@TempDir Path folder) throws IOException {
    GameTables tables = tables(folder);

    Scene plain = play(tables, false);
    Scene enchanted = play(tables, true);

    assertThat(enchanted.riders()).as("the carrier's riders").hasSizeGreaterThan(1);
    // Each rider lists one run of the buff from the start's step on: its own, not the carrier's.
    for (int rider : enchanted.riders()) {
      assertThat(enchanted.runsListed().get(rider)).as("runs listed on rider %d", rider).isOne();
      assertThat(plain.runsListed().get(rider)).as("no run without the buff").isZero();
    }
    Set<Integer> added = new HashSet<>();
    for (int rider : enchanted.riders()) {
      List<Hit> before = plain.riderHits().get(rider);
      List<Hit> after = enchanted.riderHits().get(rider);
      // The added damage brings the tower down sooner, so the enchanted impacts are the first of
      // the plain ones.
      assertThat(after).as("rider %d's enchanted impacts", rider).hasSizeGreaterThan(5);
      assertThat(after.stream().map(Hit::tick).toList())
          .as("rider %d's impact ticks", rider)
          .isEqualTo(before.subList(0, after.size()).stream().map(Hit::tick).toList());
      for (int i = 0; i < after.size(); i++) {
        int gain = after.get(i).damage() - before.get(i).damage();
        if (i % 3 == 2) {
          added.add(gain);
        } else {
          assertThat(gain).as("rider %d's impact %d, not a third", rider, i + 1).isZero();
        }
      }
    }
    assertThat(added).as("the added damage of every third spear").hasSize(1);
    assertThat(added.iterator().next()).isPositive();
  }

  /**
   * Plays the scene, with the buff started on the carrier or not, and returns each rider's spear
   * impacts on the tower and the runs of the buff each rider lists the step after the start.
   */
  private static Scene play(GameTables tables, boolean enchant) {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    BattleWorld world = match.getWorld();
    TowerEntity tower = leftTower(match);
    Map<Integer, List<Hit>> riderHits = new LinkedHashMap<>();
    world.addObserver(
        new WorldObserver() {
          @Override
          public void projectileImpacted(
              int tick,
              ProjectileEntity projectile,
              WorldEntity target,
              int damage,
              DamageResult result) {
            if (target == tower && projectile.getOwner() instanceof CharacterEntity owner) {
              riderHits
                  .computeIfAbsent(owner.getId(), id -> new ArrayList<>())
                  .add(new Hit(tick, damage));
            }
          }
        });
    match.play(PLAY_TICK, world.getRecords().card(CARRIER), LEVEL, 1, 3500, 17000, "giant");
    CharacterEntity carrier = null;
    List<Integer> riders = new ArrayList<>();
    Map<Integer, Integer> runsListed = new LinkedHashMap<>();
    for (int tick = 1; tick <= TICKS; tick++) {
      if (tick == ENCHANT_TICK) {
        carrier =
            match.getPlays().get(0).units().stream()
                .filter(unit -> !unit.riders().isEmpty())
                .findFirst()
                .orElseThrow();
        for (CharacterEntity rider : carrier.riders()) {
          riders.add(rider.getId());
        }
        if (enchant) {
          carrier
              .actionHolder()
              .start(
                  world.getActions().build(ENCHANT, world.binding(carrier)),
                  carrier.actionHolder());
        }
      }
      match.getBattle().step();
      if (tick == ENCHANT_TICK) {
        for (CharacterEntity rider : carrier.riders()) {
          runsListed.put(rider.getId(), runs(rider));
        }
      }
    }
    for (int rider : riders) {
      riderHits.putIfAbsent(rider, List.of());
    }
    return new Scene(riderHits, riders, runsListed);
  }

  /** The unfinished runs of the enchanting buff a unit lists. */
  private static int runs(CharacterEntity unit) {
    int count = 0;
    for (ActionInstance instance : unit.actionHolder().running()) {
      if (instance instanceof GiantBufferBuff.Run && !instance.isFinished()) {
        count++;
      }
    }
    return count;
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

  /** The tables with the enchanting buff written: no multiplier for any row, no finish time. */
  private static GameTables tables(Path folder) throws IOException {
    return GameData.altered(
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
  }
}
