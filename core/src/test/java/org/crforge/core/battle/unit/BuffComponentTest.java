package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.battle.GameData;
import org.crforge.core.pathfinding.combat.PackedLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
  @DisplayName("Poison at level 11 deals 92 a second, and a crown tower 23 a hit")
  void damageOverTime() {
    BuffData poison = GameData.records().buff("Poison");
    int level = PackedLevel.pack(LEVEL_11, poison.rarity());
    assertThat(BuffComponent.damagePerSecond(poison, level)).isEqualTo(92);
    assertThat(BuffComponent.crownTowerDamage(poison, level)).isEqualTo(23);
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
}
