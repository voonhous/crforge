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
import java.util.List;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The target buff a projectile with a radius applies before its damage, on the circle of its
 * impact: it passes a hidden unit by, as its damage does. A Giant the evolved Goblin Cage holds is
 * hidden on the cage's point, so a spell landing there leaves it without the buff, and it walks on
 * at its own speed once the cage leaves.
 */
class ProjectileBuffHiddenTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The hand-written buff the Fireball's projectile carries; no configured table has it. */
  private static final String BUFF = "Test_Before_Damage_Slow";

  /** The buff time, in milliseconds, longer than the scene's steps after the impact. */
  private static final int BUFF_TIME_MS = 5000;

  /** The cage's point. */
  private static final int CAGE_X = 3500;

  private static final int CAGE_Y = 14500;

  /** Long enough for the cage to capture the Giant and the Fireball to land. */
  private static final int TICKS = 400;

  /**
   * The configured tables with the Fireball's projectile given the hand-written slow as its target
   * buff, applied before its damage.
   */
  private static GameTables withBeforeDamageBuff(Path folder) throws IOException {
    GameData.altered(
        folder,
        "character_buffs",
        rows -> {
          ObjectNode row = rows.putObject(BUFF);
          row.put("index", rows.size() - 1);
          row.put("class", "LogicCharacterBuffData");
          ObjectNode columns = row.putObject("columns");
          columns.put("SpeedMultiplier", -30);
          columns.put("Rarity", "Common");
        });
    String projectile = Shipped.text(Shipped.row("spells_other", "Fireball"), "Projectile");
    GameData.alterLoaded(
        folder,
        "projectiles",
        rows ->
            GameData.columns(rows, projectile)
                .put("TargetBuff", BUFF)
                .put("BuffTime", BUFF_TIME_MS)
                .put("ApplyBuffBeforeDamage", true));
    return GameTables.load(folder);
  }

  private static List<BuffInstance> buffs(CharacterEntity unit) {
    return unit.getBuffs().items().stream().filter(b -> BUFF.equals(b.getBuff().name())).toList();
  }

  /** Steps until the Fireball's buff pass has run: its projectile has left the battle. */
  private static void stepUntilLanded(Battle battle, Standard1v1Battle match, int from) {
    boolean seen = false;
    for (int tick = 0; tick < TICKS; tick++) {
      battle.step();
      boolean flying =
          match.getBattle().getHolder().entities().stream()
              .anyMatch(e -> e instanceof ProjectileEntity);
      if (flying) {
        seen = true;
      } else if (seen) {
        return;
      }
    }
    throw new AssertionError("the Fireball did not land within " + TICKS + " ticks of " + from);
  }

  @Test
  @DisplayName(
      "a projectile's before-damage buff passes by a Giant the evolved cage holds hidden on its"
          + " point")
  void theBuffPassesTheHiddenCaptureBy(@TempDir Path folder) throws IOException {
    GameTables tables = withBeforeDamageBuff(folder);
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    Battle battle = match.getBattle();
    match.deploy(0, records.unit("GoblinCage_EV1_TEMPNAME"), LEVEL, 0, CAGE_X, CAGE_Y);
    CharacterEntity giant = match.deploy(200, records.unit("Giant"), LEVEL, 1, CAGE_X, 19000);

    boolean held = false;
    for (int tick = 0; tick < TICKS && !held; tick++) {
      battle.step();
      held = giant.getView().getX() == CAGE_X && giant.getView().getY() == CAGE_Y && giant.hidden();
    }
    assertThat(held).as("the Giant is held hidden on the cage's point").isTrue();

    match.play(battle.getTick() + 1, records.card("Fireball"), LEVEL, 0, CAGE_X, CAGE_Y, "F");
    stepUntilLanded(battle, match, battle.getTick());

    assertThat(giant.hidden()).as("still held hidden as the Fireball lands").isTrue();
    assertThat(buffs(giant)).as("its before-damage buff passes the hidden Giant by").isEmpty();
  }

  @Test
  @DisplayName("the same buff reaches a Giant standing in the circle unhidden")
  void theBuffReachesAVisibleGiant(@TempDir Path folder) throws IOException {
    GameTables tables = withBeforeDamageBuff(folder);
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    Battle battle = match.getBattle();
    CharacterEntity giant = match.deploy(0, records.unit("Giant"), LEVEL, 1, CAGE_X, 19000);
    for (int tick = 0; tick < 100; tick++) {
      battle.step();
    }
    // Held where it stands, so the Fireball lands on it.
    giant.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    assertThat(giant.hidden()).isFalse();
    match.play(
        battle.getTick() + 1,
        records.card("Fireball"),
        LEVEL,
        0,
        giant.getView().getX(),
        giant.getView().getY(),
        "F");
    stepUntilLanded(battle, match, battle.getTick());

    assertThat(buffs(giant)).as("the buff reaches the visible Giant").hasSize(1);
  }
}
