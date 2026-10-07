package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.Version16Tables;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Ronin's parry (ronin_parry, an ActionCounter of data version 16.402.18): a melee hit on the
 * Ronin while its cooldown is ready deals nothing, sets the cooldown, and runs the reflect group on
 * the attacker: the stun buff, then twice the parried amount as reflected damage. A hit inside the
 * cooldown lands as usual, as does a projectile's hit.
 */
class RoninParryTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** One hit the damage entry was handed: its tick, target, amount and whether it landed. */
  private record Hit(int tick, WorldEntity target, int damage, boolean landed) {}

  @Test
  @DisplayName("a Knight's first hit on the Ronin is parried and struck back twice over")
  void aMeleeHitIsParriedAndReflected() {
    GameTables tables = Version16Tables.load();
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    List<Hit> hits = new ArrayList<>();
    List<String> knightBuffs = new ArrayList<>();
    int[] tick = {0};
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void damageDealt(int at, WorldEntity target, int damage, DamageResult result) {
                hits.add(new Hit(at, target, damage, result.landed()));
              }

              @Override
              public void buffApplied(int at, WorldEntity target, BuffInstance buff) {
                if (target.name().equals("knight")) {
                  knightBuffs.add(buff.getBuff().name());
                }
              }
            });
    CharacterEntity ronin = match.deploy(0, records.unit("Ronin"), LEVEL, 0, 3500, 14000, "ronin");
    CharacterEntity knight =
        match.deploy(0, records.unit("Knight"), LEVEL, 1, 3500, 15500, "knight");
    List<Integer> knightHitPoints = new ArrayList<>();
    for (; tick[0] < 200; tick[0]++) {
      match.getBattle().step();
      knightHitPoints.add(knight.getHitPoints().getHitPoints());
    }

    List<Hit> onRonin = hits.stream().filter(h -> h.target() == ronin).toList();
    assertThat(onRonin).as("the Knight's hits on the Ronin").hasSizeGreaterThanOrEqualTo(2);
    Hit parried = onRonin.get(0);
    assertThat(parried.landed()).as("the first hit is parried").isFalse();
    assertThat(onRonin.get(1).landed()).as("the next, inside the cooldown, lands").isTrue();
    assertThat(knightBuffs).as("the reflect stuns the Knight").contains("ronin_reflect_stun_buff");
    // The reflected damage: twice the parried amount, on top of the Ronin's own hits.
    List<Integer> losses = new ArrayList<>();
    for (int i = 1; i < knightHitPoints.size(); i++) {
      int lost = knightHitPoints.get(i - 1) - knightHitPoints.get(i);
      if (lost > 0) {
        losses.add(lost);
      }
    }
    assertThat(losses).as("what the Knight loses").contains(2 * parried.damage());
    assertThat(ronin.getHitPoints().getHitPoints())
        .as("the Ronin took only the hits that landed")
        .isEqualTo(
            ronin.getHitPoints().getMaximum()
                - onRonin.stream().filter(Hit::landed).mapToInt(Hit::damage).sum());
  }
}
