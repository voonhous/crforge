/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.crforge.core.battle.Shipped.actionName;
import static org.crforge.core.battle.Shipped.column;
import static org.crforge.core.battle.Shipped.fields;
import static org.crforge.core.battle.Shipped.number;
import static org.crforge.core.battle.Shipped.numbers;
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

  /** A Ladder deck for the tiebreaker cases; no card of it is played. */
  private static final List<String> DECK =
      List.of(
          "Knight",
          "Archer",
          "Giant",
          "MiniPekka",
          "Musketeer",
          "Valkyrie",
          "Barbarians",
          "Minions");

  /** Where the Tesla stands and the Earthquake lands, on side 1's half. */
  private static final int QUAKE_X = 9000;

  private static final int QUAKE_Y = 22000;

  /**
   * The tick the Ladder timeline's time is up on with the crowns equal: the end of its sections,
   * each SectionLength seconds of 20 ticks. The tiebreaker's first step is the one after.
   */
  private static int timeUpTick() {
    GameRow timeline = row("battle_timelines", text(row("game_modes", "Ladder"), "BattleTimeline"));
    return numbers(timeline, "SectionLength").stream().mapToInt(s -> s * 20).sum();
  }

  /** The hidden damage area Earthquake's starting action spawns. */
  private static GameRow hiddenDamageArea() {
    return row(
        "area_effect_objects",
        text(text(row("area_effect_objects", "Earthquake"), "OnStartingAction"), "SpawnData"));
  }

  /** One Earthquake hidden damage hit on the Tesla, with what stood around it as it was dealt. */
  private record QuakeHit(
      int step, int amount, boolean landed, boolean areaListed, int hitPointsBefore) {}

  /**
   * A Ladder match whose time runs out with the crowns equal: side 1's Tesla stands on its half
   * long before, side 0's Earthquake lands on it on a given step, and the battle runs into the
   * tiebreaker. Every hidden damage hit on the Tesla is collected.
   */
  private static List<QuakeHit> quakeIntoTiebreaker(int castStep, int steps) {
    GameTables tables = GameData.tables();
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle battle = new Standard1v1Battle(tables);
    battle.startLadderMatch(DECK, DECK, 0, 0);
    int timeUp = timeUpTick();
    CharacterEntity tesla =
        battle.deploy(timeUp - 100, records.unit("Tesla"), LEVEL, 1, QUAKE_X, QUAKE_Y, "tesla");
    String hiddenArea = hiddenDamageArea().name();
    List<QuakeHit> hits = new ArrayList<>();
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
                if (target != tesla) {
                  return;
                }
                boolean listed =
                    battle.getWorld().getHolder().entities().stream()
                        .anyMatch(
                            e ->
                                e instanceof AreaEffectEntity a
                                    && a.getData().name().equals(hiddenArea));
                hits.add(
                    new QuakeHit(
                        battle.getBattle().getTick(),
                        amount,
                        result.landed(),
                        listed,
                        tesla.getHitPoints().getHitPoints() + result.applied()));
              }
            });
    battle.placeAreaEffect(castStep, "Earthquake", LEVEL, 0, QUAKE_X, QUAKE_Y, "quake");
    while (battle.getBattle().getTick() < steps) {
      battle.getBattle().step();
    }
    return hits;
  }

  @Test
  @DisplayName(
      "Earthquake's hidden damage hit queued on the last step before the tiebreaker, whose area the"
          + " tiebreaker's clearing removes, is dealt with no source and lands nothing")
  void hiddenDamageHitWhoseAreaTheClearingRemovedLandsNothing() {
    int timeUp = timeUpTick();
    // The hidden damage area hits from the post-hook, so its hit is queued after the step's drain
    // and dealt on the next step. Cast so that its last hit is queued on the last step before the
    // tiebreaker: the tiebreaker's first step clears the area (the Earthquake's own area, shorter
    // lived, has left already) before its update drains the hit.
    GameRow area = hiddenDamageArea();
    int speed = number(area, "HitSpeed");
    int lastHit = number(area, "HitSpeedOffset");
    while (lastHit + speed < number(area, "LifeDuration")) {
      lastHit += speed;
    }
    List<QuakeHit> found = null;
    // The cast step is searched rather than worked out: a placed spell's area starts a step or two
    // after the step it is placed on.
    for (int cast = timeUp - lastHit / 50 - 10; cast < timeUp - lastHit / 50 + 10; cast++) {
      List<QuakeHit> hits = quakeIntoTiebreaker(cast, timeUp + 3);
      if (hits.stream().anyMatch(h -> h.step() == timeUp + 1)) {
        found = hits;
        break;
      }
    }
    assertThat(found)
        .as("a cast whose last hit is dealt on the tiebreaker's first step")
        .isNotNull();
    QuakeHit hit = found.stream().filter(h -> h.step() == timeUp + 1).findFirst().orElseThrow();
    assertThat(hit.areaListed())
        .as("the hidden damage area has left as the hit is dealt")
        .isFalse();
    // With its source gone the hit has no level to scale by: the row's amount, the Tesla carrying
    // no protection. The tiebreaker holds every hit, so it lands nothing whatever its amount.
    assertThat(hit.amount()).isEqualTo(baseDamage(text(area, "OnHitAction")));
    assertThat(hit.landed()).isFalse();
    assertThat(hit.hitPointsBefore()).as("the Tesla's hit points before the hit").isPositive();
  }

  @Test
  @DisplayName(
      "an area effect's scaled damage-taking hit drained after the area has left stays refused"
          + " while hits are not held")
  void scaledHitOfALeftAreaOutsideTheHoldIsRefused() {
    GameTables tables = GameData.tables();
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, false);
    CharacterEntity tesla =
        battle.deploy(0, records.unit("Tesla"), LEVEL, 1, QUAKE_X, QUAKE_Y, "tesla");
    battle.placeAreaEffect(5, "Earthquake", LEVEL, 0, QUAKE_X, QUAKE_Y, "quake");
    AreaEffectEntity quake = null;
    while (quake == null) {
      battle.getBattle().step();
      quake =
          battle.getWorld().getHolder().entities().stream()
              .filter(e -> e instanceof AreaEffectEntity)
              .map(e -> (AreaEffectEntity) e)
              .findFirst()
              .orElse(null);
    }
    // No Ladder battle reaches this outside the tiebreaker: an area leaves only at a cleanup after
    // its life, a step after its last hit is drained. It is made here by hand.
    TakeDamage.Damage damage =
        new TakeDamage.Damage(
            baseDamage(text(hiddenDamageArea(), "OnHitAction")),
            -1,
            false,
            false,
            false,
            true,
            true);
    battle.getWorld().getHits().queueActionDamage(quake, tesla, damage, 0);
    battle.getWorld().clearingRemoval(quake);
    assertThat(battle.getWorld().isHitsHeld()).isFalse();
    assertThatThrownBy(() -> battle.getBattle().step())
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("no longer in the battle");
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
