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
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The target buff of a shot the Monk's Deflect turns around. The Deflect's parent takes the shot's
 * own damage as it is turned, queued for the tick's damage drain with the shot as the hit's source,
 * and the drain applies a shot's after-damage target buff to the victim of each hit it lets
 * through, whatever queued it: so the Monk takes the buff of the shot it turns, at the shot's level
 * and for its buff time, though the shot flies on to its shooter. A shot whose row applies its buff
 * before its damage applies it only at an impact, so its deflection leaves the Monk without it.
 */
class DeflectTargetBuffTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** Long enough for a placed Monk to deploy, cast and deflect a shot. */
  private static final int TICKS = 300;

  /** The hand-written buff the Musketeer's shot carries; no configured table has it. */
  private static final String BUFF = "Test_Deflected_Shot_Buff";

  /** The shot's buff time, in milliseconds, longer than the scene's steps after the turn. */
  private static final int BUFF_TIME_MS = 1000;

  /** The slow the hand-written buff gives, in percent. */
  private static final int SLOW = -50;

  /**
   * The configured tables with the Musketeer's shot given the hand-written buff as its target buff,
   * applied after its damage unless {@code beforeDamage}.
   */
  private static GameTables withShotBuff(Path folder, boolean beforeDamage) throws IOException {
    GameData.altered(
        folder,
        "character_buffs",
        rows -> {
          ObjectNode row = rows.putObject(BUFF);
          row.put("index", rows.size() - 1);
          row.put("class", "LogicCharacterBuffData");
          ObjectNode columns = row.putObject("columns");
          columns.put("SpeedMultiplier", SLOW);
          columns.put("Rarity", "Common");
        });
    String shot = Shipped.text(Shipped.unitRow("Musketeer"), "Projectile");
    GameData.alterLoaded(
        folder,
        "projectiles",
        rows ->
            GameData.columns(rows, shot)
                .put("TargetBuff", BUFF)
                .put("BuffTime", BUFF_TIME_MS)
                .put("ApplyBuffBeforeDamage", beforeDamage));
    return GameTables.load(folder);
  }

  /** A Monk of the bottom side and a Musketeer of the top one in its range, both held in place. */
  private static final class Scene {
    final Standard1v1Battle match;
    final CharacterEntity monk;
    final CharacterEntity musketeer;
    final List<ProjectileEntity> deflected = new ArrayList<>();
    int tick;

    Scene(GameTables tables) {
      BattleRecords records = new BattleRecords(tables);
      match = new Standard1v1Battle(tables, LEVEL, false);
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void projectileDeflected(
                    int t,
                    AreaEffectEntity deflector,
                    ProjectileEntity projectile,
                    WorldEntity parent,
                    WorldEntity source) {
                  deflected.add(projectile);
                }
              });
      monk = match.deploy(0, records.unit("Monk"), LEVEL, 0, 3500, 10000, "monk");
      musketeer = match.deploy(0, records.unit("Musketeer"), LEVEL, 1, 3500, 15500, "musketeer");
    }

    void step() {
      match.getBattle().step();
      tick++;
    }

    /**
     * Holds both where they deployed, asks for the Monk's ability and steps to the end of the tick
     * the first shot is turned around.
     */
    ProjectileEntity deflectFirstShot() {
      while (monk.getView().getState() == GridEntityState.DEPLOYING
          || monk.getView().getState() == GridEntityState.WAITING_TO_DEPLOY
          || monk.getId() == 0) {
        step();
        assertThat(tick).as("the Monk deploys").isLessThan(TICKS);
      }
      monk.setActive(CharacterEntity.MOVEMENT_SLOT, false);
      musketeer.setActive(CharacterEntity.MOVEMENT_SLOT, false);
      monk.requestAbility();
      while (deflected.isEmpty()) {
        step();
        assertThat(tick).as("a shot is deflected").isLessThan(TICKS);
      }
      return deflected.get(0);
    }

    List<BuffInstance> buffs() {
      return monk.getBuffs().items().stream().filter(b -> BUFF.equals(b.getBuff().name())).toList();
    }
  }

  @Test
  @DisplayName(
      "the Monk takes the after-damage target buff of the shot its Deflect turns, at the drain of"
          + " the tick of the turn, for the shot's buff time")
  void theDeflectorTakesTheShotsBuff(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(withShotBuff(folder, false));
    ProjectileEntity shot = scene.deflectFirstShot();
    assertThat(shot.getTarget())
        .as("the shot flies back at the Musketeer")
        .isSameAs(scene.musketeer);

    List<BuffInstance> buffs = scene.buffs();
    assertThat(buffs).as("the Monk carries the shot's buff once").hasSize(1);
    BuffInstance buff = buffs.get(0);
    assertThat(buff.getSource()).as("the shot is the buff's source").isSameAs(shot);
    assertThat(buff.getRemaining())
        .as("its whole buff time at the end of the tick of the turn")
        .isEqualTo(shot.getData().buffTime(shot.packedLevel()));
  }

  @Test
  @DisplayName("a shot that applies its target buff before its damage leaves the Monk without it")
  void aBeforeDamageBuffIsNotTaken(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(withShotBuff(folder, true));
    scene.deflectFirstShot();
    assertThat(scene.buffs()).isEmpty();
  }
}
