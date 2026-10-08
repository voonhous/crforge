package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.crforge.core.battle.Shipped.actionName;
import static org.crforge.core.battle.Shipped.number;
import static org.crforge.core.battle.Shipped.text;
import static org.crforge.core.battle.Shipped.unitRow;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.EntityFlags;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The action a unit's row runs as it attacks, where the reference runs do not take it: the tag its
 * buff sets, read tick by tick, and the pull a following area effect may not make.
 */
class BattleAttackActionTest {

  /** Which bit of a tag word each flag is, as the configured tables number the game tags. */
  private static final EntityFlags BITS = EntityFlags.of(GameData.tables());

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** An evolved Valkyrie and three Barbarians walking into it, as valkyrie_ev1_barbarians. */
  private static CharacterEntity valkyrieAmongBarbarians(Standard1v1Battle battle) {
    CharacterEntity valkyrie =
        battle.deploy(0, unit(battle, "Valkyrie_EV1"), LEVEL, 0, 3500, 16000, "v");
    battle.deploy(0, unit(battle, "Barbarian"), LEVEL, 1, 2500, 18500, "b0");
    battle.deploy(0, unit(battle, "Barbarian"), LEVEL, 1, 4500, 18500, "b1");
    battle.deploy(0, unit(battle, "Barbarian"), LEVEL, 1, 3500, 20500, "b2");
    return valkyrie;
  }

  private static UnitData unit(Standard1v1Battle battle, String row) {
    return battle.getWorld().getRecords().unit(row);
  }

  @Test
  @DisplayName(
      "the buff the evolved Valkyrie's hit gives it keeps enemies from pushing it from the next"
          + " tick, for the ticks the buff is listed")
  void theNotPushedTagLastsAsLongAsItsBuff() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables(), LEVEL, true);
    CharacterEntity valkyrie = valkyrieAmongBarbarians(battle);
    List<Integer> hits = new ArrayList<>();
    battle
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void areaHit(
                  int tick,
                  WorldEntity attacker,
                  WorldEntity victim,
                  int damage,
                  int hitId,
                  DamageResult result) {
                if (attacker == valkyrie && (hits.isEmpty() || hits.get(hits.size() - 1) != tick)) {
                  hits.add(tick);
                }
              }
            });
    List<Integer> tagged = new ArrayList<>();
    // Run through the second hit, both found by running the battle.
    while (battle.getBattle().getTick() < 400 && hits.size() < 2) {
      int tick = battle.getBattle().getTick();
      battle.getBattle().step();
      if ((valkyrie.getView().getFlags() & BITS.noPushedByEnemy()) != 0) {
        tagged.add(tick);
      }
    }

    // The buff the hit's attack action gives next, for its SpawnTime: one visit per 50 ms begun.
    String attackAction = text(unitRow("Valkyrie_EV1"), "OnAttackAction");
    int visits = (number(actionName(attackAction, "NextAction"), "SpawnTime") + 49) / 50;
    // The first hit lists the buff in its pending pass; the pre-hook of the next tick folds it in,
    // and the buff leaves in pass 3 of its last listed tick, after that tick's pre-hook.
    assertThat(hits).as("the Valkyrie's first two hits").hasSize(2);
    int first = hits.get(0);
    List<Integer> expected = new ArrayList<>();
    for (int tick = first + 1; tick <= first + visits; tick++) {
      expected.add(tick);
    }
    assertThat(tagged.stream().filter(tick -> tick < hits.get(1)))
        .as("tagged between the first hit and the second")
        .containsExactlyElementsOf(expected);
  }

  @Test
  @DisplayName("an area effect whose pull is tested against the angle of its move is refused")
  void aPullWithinAnAngleIsRefused(@TempDir Path folder) throws IOException {
    GameTables tables =
        GameData.altered(
            folder,
            "character_buffs",
            rows ->
                GameData.columns(rows, "Valkyrie_MiniTornado_EV1_BUFF").put("AttractMaxAngle", 90));
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, true);
    valkyrieAmongBarbarians(battle);

    assertThatThrownBy(
            () -> {
              while (battle.getBattle().getTick() <= 40) {
                battle.getBattle().step();
              }
            })
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("Valkyrie_MiniTornado_EV1 pulls only within an angle of its move");
  }
}
