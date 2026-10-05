package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.EntityFlags;
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
          + " tick, for the ten ticks the buff is listed")
  void theNotPushedTagLastsAsLongAsItsBuff() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables(), LEVEL, true);
    CharacterEntity valkyrie = valkyrieAmongBarbarians(battle);
    List<Integer> tagged = new ArrayList<>();
    while (battle.getBattle().getTick() <= 40) {
      int tick = battle.getBattle().getTick();
      battle.getBattle().step();
      if ((valkyrie.getView().getFlags() & BITS.noPushedByEnemy()) != 0) {
        tagged.add(tick);
      }
    }

    // The first hit, on 26, lists the buff in its pending pass; the pre-hook of 27 folds it in,
    // and the buff leaves in pass 3 of 36, after that tick's pre-hook.
    assertThat(tagged).containsExactly(27, 28, 29, 30, 31, 32, 33, 34, 35, 36);
  }

  @Test
  @DisplayName("an area effect whose pull is tested against the angle of its move is refused")
  void aPullWithinAnAngleIsRefused(@TempDir Path folder) throws IOException {
    GameTables tables =
        GameData.altered(
            folder,
            "character_buffs",
            rows -> GameData.columns(rows, "Valkyrie_MiniTornado_EV1").put("AttractMaxAngle", 90));
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
