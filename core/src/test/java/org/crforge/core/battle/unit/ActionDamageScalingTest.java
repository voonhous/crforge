package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.TakeDamage;
import org.crforge.core.battle.data.ActionRows;
import org.crforge.core.battle.data.BattleRecords;
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
    // The row's 123 at level 11 of a Common row: ten steps up, a multiplier of 256.
    assertThat(commonScaled(123, musketeer.getPackedLevel())).isEqualTo(314);
    assertThat(bayonet).allSatisfy(h -> assertThat(h.amount()).isEqualTo(314));
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
    // One hit a second over the hidden damage area's 3050 ms: on its 1000th, 2000th, 3000th ms.
    assertThat(onTesla).as("the hidden damage's hits on the Tesla").hasSize(3);
    assertThat(hiddenAtHit).as("the Tesla is hidden as each hit lands").containsOnly(true);
    // The row's 112 at level 11 of the hidden damage area's Common row.
    assertThat(onTesla).allSatisfy(h -> assertThat(h.amount()).isEqualTo(286));
    assertThat(onTesla).allSatisfy(h -> assertThat(h.landed()).isTrue());
    assertThat(tesla.getHitPoints().getHitPoints()).isLessThanOrEqualTo(before - 3 * 286);
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
}
