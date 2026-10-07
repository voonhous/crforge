package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.combat.PackedLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the listed buffs make of an entity's speeds, and the damage over time at a level. */
class BuffComponentTest {

  private static final int LEVEL_11 = 10;

  private static CharacterEntity knight() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    CharacterEntity knight = match.deploy(0, GameData.unit("Knight"), 11, 0, 3500, 10000);
    match.getBattle().step();
    return knight;
  }

  @Test
  @DisplayName("without a buff every scale is the base, and the spawn rate is 100")
  void noBuff() {
    BuffComponent buffs = knight().getBuffs();
    assertThat(buffs.speed(60)).isEqualTo(60);
    assertThat(buffs.hitSpeed(50)).isEqualTo(50);
    assertThat(buffs.spawnRate()).isEqualTo(100);
  }

  @Test
  @DisplayName("the largest boost times what the strongest slow leaves: Rage and Poison make 60 66")
  void boostAndSlowMultiply() {
    CharacterEntity knight = knight();
    BuffComponent buffs = knight.getBuffs();
    buffs.apply(GameData.records().buff("Rage"), 1000, LEVEL_11, null, 0);
    assertThat(buffs.speed(60)).isEqualTo(78);
    assertThat(buffs.hitSpeed(50)).isEqualTo(65);
    assertThat(buffs.spawnRate()).isEqualTo(130);
    buffs.apply(GameData.records().buff("Poison"), 1000, LEVEL_11, null, 1);
    assertThat(buffs.speed(60)).isEqualTo(66);
    assertThat(buffs.hitSpeed(50)).isEqualTo(65);
    assertThat(buffs.items()).hasSize(2);
  }

  @Test
  @DisplayName("a stun of -100 stops every step")
  void aStunStops() {
    BuffComponent buffs = knight().getBuffs();
    buffs.apply(GameData.records().buff("ZapFreeze"), 500, LEVEL_11, null, 1);
    assertThat(buffs.speed(60)).isZero();
    assertThat(buffs.hitSpeed(50)).isZero();
    assertThat(buffs.spawnRate()).isZero();
  }

  @Test
  @DisplayName("Poison at level 11 deals 92 a second, and a crown tower 21 a hit")
  void damageOverTime() {
    BuffData poison = GameData.records().buff("Poison");
    int level = PackedLevel.pack(LEVEL_11, poison.rarity());
    assertThat(BuffComponent.damagePerSecond(poison, level)).isEqualTo(92);
    // Its crown tower percent of -78: 22 percent of 92, rounded up.
    assertThat(BuffComponent.crownTowerDamage(poison, level)).isEqualTo(21);
  }

  @Test
  @DisplayName(
      "two reducing buffs do not add: an arrow of 109 takes 43 under the evolved Knight's Fortify"
          + " alone, and 38 under Fortify and the Monk's 65")
  void theLargestReductionCounts() {
    BuffComponent buffs = knight().getBuffs();
    assertThat(buffs.damageReduction(109)).isEqualTo(109);
    buffs.apply(GameData.records().buff("Knight_Fortify_EV1"), 1000, LEVEL_11, null, 0);
    assertThat(buffs.damageReduction(109)).isEqualTo(43);
    buffs.apply(GameData.records().buff("ShieldBoostMonk"), 1000, LEVEL_11, null, 0);
    assertThat(buffs.damageReduction(109)).isEqualTo(38);
  }

  @Test
  @DisplayName("a re-application of the same row refreshes the instance to the longer time")
  void aRefreshKeepsTheLongerTime() {
    BuffComponent buffs = knight().getBuffs();
    BuffData rage = GameData.records().buff("Rage");
    buffs.apply(rage, 1000, LEVEL_11, null, 0);
    buffs.visit();
    buffs.visit();
    assertThat(buffs.items().get(0).getRemaining()).isEqualTo(900);
    buffs.apply(rage, 500, LEVEL_11, null, 0);
    assertThat(buffs.items().get(0).getRemaining()).isEqualTo(900);
    buffs.apply(rage, 1000, LEVEL_11, null, 0);
    assertThat(buffs.items()).hasSize(1);
    assertThat(buffs.items().get(0).getRemaining()).isEqualTo(1000);
    assertThat(buffs.items().get(0).getTotal()).isEqualTo(1100);
  }

  /** A Little Prince placed and stepped once, whose asks of a life condition go to the log. */
  private static CharacterEntity prince(List<String> asks) {
    return prince(asks, GameData.tables());
  }

  /** {@link #prince(List)} on the given tables. */
  private static CharacterEntity prince(List<String> asks, GameTables tables) {
    Standard1v1Battle match = new Standard1v1Battle(tables);
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void lifeConditionAsked(
                  int tick, WorldEntity carrier, BuffInstance buff, int answer) {
                asks.add(buff.getBuff().name() + " " + buff.getRemaining() + " " + answer);
              }
            });
    CharacterEntity prince = match.deploy(0, GameData.unit("LittlePrince"), 11, 0, 3500, 10000);
    match.getBattle().step();
    return prince;
  }

  @Test
  @DisplayName(
      "a life condition is asked after the step of the time, and an answer of 0 removes the"
          + " instance on that visit")
  void aLifeConditionIsAskedAfterTheStep() {
    List<String> asks = new ArrayList<>();
    BuffComponent buffs = prince(asks).getBuffs();
    // LP_AttackCount is 0 without an attack, so the fastest speed-up's condition answers 0.
    buffs.apply(GameData.records().buff("LittlePrinceLvlMax"), 100, LEVEL_11, null, 0);
    buffs.visit();
    // Asked with the 50 ms the step left it.
    assertThat(asks).containsExactly("LittlePrinceLvlMax 50 0");
    assertThat(buffs.items()).isEmpty();
  }

  @Test
  @DisplayName(
      "a life condition is not asked of an instance whose step spends its time, nor of one that"
          + " never runs out")
  void aLifeConditionNeedsTimeLeft() {
    List<String> asks = new ArrayList<>();
    BuffComponent buffs = prince(asks).getBuffs();
    BuffData lvlMax = GameData.records().buff("LittlePrinceLvlMax");
    buffs.apply(lvlMax, 50, LEVEL_11, null, 0);
    buffs.visit();
    assertThat(buffs.items()).isEmpty();
    buffs.apply(lvlMax, BuffInstance.FOREVER, LEVEL_11, null, 0);
    buffs.visit();
    assertThat(asks).isEmpty();
    assertThat(buffs.items()).hasSize(1);
  }

  @Test
  @DisplayName(
      "a condition that holds keeps the instance: at an LP_AttackCount of 6 the fastest speed-up's"
          + " condition holds, at 5 it does not")
  void aConditionThatHoldsKeepsTheInstance() {
    List<String> asks = new ArrayList<>();
    CharacterEntity prince = prince(asks);
    int count = prince.world().variableKey("LP_AttackCount");
    prince.setVariable(count, 6);
    BuffComponent buffs = prince.getBuffs();
    buffs.apply(GameData.records().buff("LittlePrinceLvlMax"), 1000, LEVEL_11, null, 0);
    buffs.visit();
    prince.setVariable(count, 5);
    buffs.visit();
    assertThat(asks).containsExactly("LittlePrinceLvlMax 950 1", "LittlePrinceLvlMax 900 0");
    assertThat(buffs.items()).isEmpty();
  }

  @Test
  @DisplayName(
      "attack_count is the attack time over the row's hit speed, with the targeting component on"
          + " or off: at 7200 of 1200 a condition of attack_count >= 6 holds and the instance stays")
  void theAttackCountFunction(@TempDir Path folder) throws IOException {
    // No configured row asks attack_count; the fastest speed-up's condition is written with it.
    GameTables tables =
        GameData.altered(
            folder,
            "character_buffs",
            rows ->
                GameData.columns(rows, "LittlePrinceLvlMax")
                    .put("AliveIfTrue", "attack_count >= 6"));
    List<String> asks = new ArrayList<>();
    CharacterEntity prince = prince(asks, tables);
    prince.getTargeting().setAttackTimerMs(7200);
    prince.setActive(CharacterEntity.TARGETING_SLOT, false);
    BuffComponent buffs = prince.getBuffs();
    buffs.apply(prince.world().getRecords().buff("LittlePrinceLvlMax"), 1000, LEVEL_11, null, 0);
    buffs.visit();
    prince.getTargeting().setAttackTimerMs(7199);
    buffs.visit();
    assertThat(asks).containsExactly("LittlePrinceLvlMax 950 1", "LittlePrinceLvlMax 900 0");
    assertThat(buffs.items()).isEmpty();
  }
}
