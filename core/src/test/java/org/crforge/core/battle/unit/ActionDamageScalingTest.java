/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.battle.Shipped.actionName;
import static org.crforge.core.battle.Shipped.column;
import static org.crforge.core.battle.Shipped.fields;
import static org.crforge.core.battle.Shipped.number;
import static org.crforge.core.battle.Shipped.row;
import static org.crforge.core.battle.Shipped.text;
import static org.crforge.core.battle.Shipped.unitRow;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.TakeDamage;
import org.crforge.core.battle.data.ActionRows;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.core.pathfinding.combat.PackedLevel;
import org.crforge.core.pathfinding.combat.RarityTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A damage-taking action whose damage does not set NoScaling (data version 16.402.18): the damage
 * drain scales the row's amount by the source's level as card damage - the amount times the source
 * row's rarity multiplier for its step, over 100 - whether the source is a character, as the Three
 * Musketeers' bayonet's is, or an area effect, as Earthquake's hidden damage's is. A damage flagged
 * DamagesHidden reaches a target the damage entry would refuse as hidden, such as a Tesla
 * underground.
 */
class ActionDamageScalingTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** One hit the drain dealt: its tick, source, target, amount and whether it landed. */
  private record Hit(
      int tick, WorldEntity source, WorldEntity target, int amount, boolean landed) {}

  /** Collects every typed hit and damage-taking action's hit the drain deals. */
  private static List<Hit> hits(Standard1v1Battle battle) {
    List<Hit> hits = new ArrayList<>();
    battle
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void typedHitDealt(
                  int tick,
                  WorldEntity source,
                  WorldEntity target,
                  int amount,
                  int damageId,
                  DamageResult result) {
                hits.add(new Hit(tick, source, target, amount, result.landed()));
              }
            });
    return hits;
  }

  /** The row's amount at a packed level of a Common row, as card damage scales. */
  private static int commonScaled(int amount, int packedLevel) {
    int steps = PackedLevel.steps(packedLevel);
    return steps == 0 ? amount : amount * RarityTable.COMMON.multiplier(steps - 1) / 100;
  }

  /** The base damage a damage-taking action row writes. */
  private static int baseDamage(String action) {
    return fields(action).get("Damage").get("BaseDamage").asInt();
  }

  @Test
  @DisplayName("a Three Musketeer's bayonet hit deals its damage scaled by the musketeer's level")
  void bayonetScalesByTheMusketeersLevel() {
    GameTables tables = GameData.tables();
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, false);
    List<Hit> hits = hits(battle);
    CharacterEntity musketeer =
        battle.deploy(0, records.unit("ThreeMusketeer_Rework"), LEVEL, 0, 3500, 14000, "musk");
    CharacterEntity knight =
        battle.deploy(0, records.unit("Knight"), LEVEL, 1, 3500, 15000, "knight");
    for (int tick = 0; tick < 200; tick++) {
      battle.getBattle().step();
    }

    List<Hit> bayonet =
        hits.stream().filter(h -> h.source() == musketeer && h.target() == knight).toList();
    assertThat(bayonet).as("the bayonet's hits on the Knight").isNotEmpty();
    // The bayonet entry's attack action runs a damage-taking action on what it hit: that row's
    // base damage at level 11 of a Common row, ten steps up.
    String attack =
        column(unitRow("ThreeMusketeer_Rework"), "AttackSequenceList")
            .get(1)
            .path("DoAttackAction")
            .asText();
    int scaled =
        commonScaled(baseDamage(actionName(attack, "ActionToExecute")), musketeer.getPackedLevel());
    assertThat(bayonet).allSatisfy(h -> assertThat(h.amount()).isEqualTo(scaled));
    assertThat(bayonet).allSatisfy(h -> assertThat(h.landed()).isTrue());
  }

  @Test
  @DisplayName("Earthquake's hidden damage reaches a hidden Tesla, scaled by the area's level")
  void earthquakeHiddenDamageReachesAHiddenTesla() {
    GameTables tables = GameData.tables();
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, false);
    List<Hit> hits = hits(battle);
    CharacterEntity tesla = battle.deploy(0, records.unit("Tesla"), LEVEL, 1, 9000, 20000, "tesla");
    List<Boolean> hiddenAtHit = new ArrayList<>();
    // Deployed and hidden long before the cast: no enemy comes into its range.
    battle.placeAreaEffect(100, "Earthquake", LEVEL, 0, 9000, 20000, "quake");
    int before = 0;
    for (int tick = 0; tick < 200; tick++) {
      boolean hidden = tesla.hidden();
      int count = hits.size();
      battle.getBattle().step();
      for (int i = count; i < hits.size(); i++) {
        if (hits.get(i).target() == tesla) {
          hiddenAtHit.add(hidden);
        }
      }
      if (tick == 99) {
        before = tesla.getHitPoints().getHitPoints();
      }
    }

    List<Hit> onTesla = hits.stream().filter(h -> h.target() == tesla).toList();
    // The hidden damage area the Earthquake's starting action spawns hits once a HitSpeed from its
    // HitSpeedOffset on, while its life lasts.
    GameRow area =
        row(
            "area_effect_objects",
            text(text(row("area_effect_objects", "Earthquake"), "OnStartingAction"), "SpawnData"));
    int life = number(area, "LifeDuration");
    int count = 0;
    for (int at = number(area, "HitSpeedOffset"); at < life; at += number(area, "HitSpeed")) {
      count++;
    }
    assertThat(onTesla).as("the hidden damage's hits on the Tesla").hasSize(count);
    assertThat(hiddenAtHit).as("the Tesla is hidden as each hit lands").containsOnly(true);
    // Its hit action's base damage at level 11 of the hidden damage area's Common row.
    int scaled =
        commonScaled(
            baseDamage(text(area, "OnHitAction")),
            PackedLevel.fromLevel(LEVEL, RarityTable.COMMON));
    assertThat(onTesla).allSatisfy(h -> assertThat(h.amount()).isEqualTo(scaled));
    assertThat(onTesla).allSatisfy(h -> assertThat(h.landed()).isTrue());
    assertThat(tesla.getHitPoints().getHitPoints()).isLessThanOrEqualTo(before - count * scaled);
  }

  @Test
  @DisplayName("the evolved Electro Giant's pulse damage reads as a damage-taking action")
  void electroGiantPulseDamageReads() {
    GameTables tables = GameData.tables();
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, false);
    CharacterEntity knight =
        battle.deploy(0, records.unit("Knight"), LEVEL, 1, 3500, 15000, "knight");
    BattleAction pulse =
        new ActionRows(tables, records)
            .build("ElectroGiant_EV1_Apply_Pulse_Damage", battle.getWorld().binding(knight));
    assertThat(pulse).isInstanceOf(TakeDamage.class);
  }

  @Test
  @DisplayName(
      "a pulse hit of no amount drained after the evolved Electro Giant's pulse area has left lands"
          + " as nothing")
  void pulseHitOfNoAmountFromAnAreaThatLeftLands() {
    String damageAction = "ElectroGiant_EV1_Apply_Pulse_Damage";
    // The case holds only while the pulse's damage has no amount: 0 scales to 0 at any level.
    assertThat(fields(damageAction).get("Damage").get("BaseDamage").asInt()).isZero();
    assertThat(fields(damageAction).get("Damage").get("TowerDamage").asInt()).isZero();
    String pulseArea = "ElectroGiant_EV1_Pulse_AEO";
    int life = number(row("area_effect_objects", pulseArea), "LifeDuration");
    // A first battle finds the step the first pulse area appears on; the second, the same up to
    // then, puts a knight down beside the giant on the area's last step, so the area's last hit
    // reaches it first and its damage-taking action is drained on the next step, after the area
    // has left the battle.
    int pulseStep = pulseStep(pulseArea);
    int lastStep = pulseStep + life / 50;
    GameTables tables = GameData.tables();
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, false);
    List<Hit> hits = hits(battle);
    CharacterEntity giant =
        battle.deploy(0, records.unit("ElectroGiant_EV1"), LEVEL, 0, 3500, 10000, "giant");
    CharacterEntity knight =
        battle.deploy(lastStep, records.unit("Knight"), LEVEL, 1, 3500, 11500, "knight");
    while (battle.getBattle().getTick() < lastStep + 5) {
      battle.getBattle().step();
      giant.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    }

    List<Hit> onKnight = hits.stream().filter(h -> h.target() == knight).toList();
    assertThat(onKnight).as("the pulse's hit on the knight").isNotEmpty();
    assertThat(onKnight).allSatisfy(h -> assertThat(h.amount()).isZero());
    assertThat(knight.getHitPoints().getHitPoints()).isEqualTo(knight.getHitPoints().getMaximum());
  }

  /** The step an evolved Electro Giant kept in place first has its pulse area in the battle. */
  private static int pulseStep(String pulseArea) {
    GameTables tables = GameData.tables();
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, false);
    CharacterEntity giant =
        battle.deploy(0, records.unit("ElectroGiant_EV1"), LEVEL, 0, 3500, 10000, "giant");
    while (battle.getBattle().getTick() < 400) {
      battle.getBattle().step();
      giant.setActive(CharacterEntity.MOVEMENT_SLOT, false);
      List<Boolean> found = new ArrayList<>();
      battle
          .getBattle()
          .getHolder()
          .entities()
          .forEach(
              e -> {
                if (e instanceof AreaEffectEntity a && a.getData().name().equals(pulseArea)) {
                  found.add(true);
                }
              });
      if (!found.isEmpty()) {
        return battle.getBattle().getTick();
      }
    }
    throw new AssertionError("no pulse area within 400 steps");
  }
}
