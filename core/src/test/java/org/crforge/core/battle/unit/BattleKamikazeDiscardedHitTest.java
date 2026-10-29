/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The end of a Kamikaze unit's hit that was discarded because its target left the attack range
 * during the wind-up. An evolved Wall Breaker hooked by a Fisherman while it attacks a building is
 * pulled out of range and let go with its attack running on, so the hit it then makes is discarded.
 * Under the rules of game client 16.402.17 the end of that hit still destroys it, with no explosion
 * and none of its death actions, as it destroyed itself; client 16.635.1 skips the end of a
 * discarded hit under the published global LOGIC_SKIP_KAMIKAZE_ON_DISCARDED_HIT, so the unit lives
 * on and, killed by the Fisherman, runs its death actions: the death damage the game's patch notes
 * give back to the evolved Wall Breakers pulled by a Fisherman. The 16.402 data versions did not
 * publish the global and kept the old rule; each case here sets the global up itself.
 */
class BattleKamikazeDiscardedHitTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The global that skips the end of a Kamikaze unit's discarded hit; 16.402 tables had none. */
  private static final String SKIP = "LOGIC_SKIP_KAMIKAZE_ON_DISCARDED_HIT";

  /** The Kamikaze unit: the evolved Wall Breaker, whose killed action makes its death damage. */
  private static final String WALL_BREAKER = "Wallbreaker_EV1";

  private static final GameRow FISHERMAN = Shipped.unitRow("Fisherman");

  private static final GameRow BREAKER = Shipped.unitRow(WALL_BREAKER);

  /** The projectile the Wall Breaker's own hit launches. */
  private static final String OWN_PROJECTILE = Shipped.text(BREAKER, "Projectile");

  /** What one battle showed of the Wall Breaker. */
  private static final class Run {
    /**
     * The tick the hook took the Wall Breaker while it was attacking, its hit not yet made, or -1.
     */
    int hookedAttacking = -1;

    /** Each end of the Wall Breaker's hit: the tick and whether it killed it. */
    final List<String> hitEnds = new ArrayList<>();

    /** Each projectile its own hit launched: the tick. */
    final List<Integer> ownLaunches = new ArrayList<>();

    /** Each projectile of its side launched after the hook other than its own hit's. */
    final List<String> sideLaunches = new ArrayList<>();

    /** Characters it spawned. */
    final List<String> spawned = new ArrayList<>();

    /** Its death hooks scheduled: the tick and the side of what killed it. */
    final List<String> deathHooks = new ArrayList<>();

    /** The tick it left the battle, or -1. */
    int removed = -1;
  }

  /**
   * One battle with the towers passive: a Fisherman that never walks in the middle, an evolved Wall
   * Breaker of the other side in his special's ring below him, and, placed at the given tick, a
   * Cannon of the Fisherman's side below the Wall Breaker within half its range, the only building
   * it attacks.
   */
  private static Run battle(GameTables tables, int cannonAt) {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    Run run = new Run();
    int radius = Shipped.number(BREAKER, "CollisionRadius");
    int x = 9000;
    int fy = 13000;
    int wy = fy - radius - Shipped.number(FISHERMAN, "SpecialMinRange") - 1500;
    int cy =
        wy
            - radius
            - Shipped.number(Shipped.unitRow("Cannon"), "CollisionRadius")
            - Shipped.number(BREAKER, "Range") / 2;
    still(match, 0, 0, "Fisherman", x, fy, "F");
    CharacterEntity breaker = still(match, 0, 1, WALL_BREAKER, x, wy, "W");
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void dragStateSet(
                  int t, ProjectileEntity projectile, WorldEntity unit, int oldState, int now) {
                // A hook taking it on the tick its hit ended finds it destroying itself already.
                if (unit == breaker
                    && oldState == GridEntityState.ATTACKING
                    && !breaker.isKamikazeHitEnded()
                    && run.hookedAttacking < 0) {
                  run.hookedAttacking = t;
                }
              }

              @Override
              public void kamikazeHitEnded(int t, WorldEntity unit, boolean kills) {
                if (unit == breaker) {
                  run.hitEnds.add(t + " " + kills);
                }
              }

              @Override
              public void projectileLaunched(int t, ProjectileEntity projectile) {
                String name = projectile.getData().name();
                if (projectile.getSide() != 1) {
                  return;
                }
                if (name.equals(OWN_PROJECTILE)) {
                  run.ownLaunches.add(t);
                } else if (run.hookedAttacking >= 0) {
                  run.sideLaunches.add(t + " " + name);
                }
              }

              @Override
              public void characterSpawned(
                  int t, SpawnHost source, CharacterEntity child, int createdX, int createdY) {
                if (source == breaker) {
                  run.spawned.add(t + " " + child.name());
                }
              }

              @Override
              public void deathHooksScheduled(
                  int t,
                  WorldEntity dying,
                  BattleEntity attacker,
                  int side,
                  List<String> hooks,
                  boolean inPendingPass) {
                if (dying == breaker) {
                  run.deathHooks.add(t + " " + side);
                }
              }

              @Override
              public void entityRemoved(int t, WorldEntity removed) {
                if (removed == breaker) {
                  run.removed = t;
                }
              }
            });
    still(match, cannonAt, 0, "Cannon", x, cy, "C");
    for (int t = 0; t < 200 && run.removed < 0; t++) {
      match.getBattle().step();
    }
    return run;
  }

  /** A unit placed at a tick that never walks, under a name of its own. */
  private static CharacterEntity still(
      Standard1v1Battle match, int at, int side, String row, int x, int y, String name) {
    CharacterEntity unit =
        match.deploy(at, match.getWorld().getRecords().unit(row), LEVEL, side, x, y, name);
    unit.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    return unit;
  }

  /**
   * The first battle, over the Cannon's placing tick, in which the hook takes the Wall Breaker
   * while it attacks the Cannon, before its hit, so that it is let go with its attack running on.
   * The global plays no part before the hook, so the same tick is found on every table set.
   */
  private static Run hookedMidAttack(GameTables tables) {
    for (int at = 0; at < 120; at++) {
      Run run = battle(tables, at);
      if (run.hookedAttacking >= 0) {
        return run;
      }
    }
    throw new AssertionError("no placing of the Cannon has the Wall Breaker hooked mid-attack");
  }

  /**
   * The configured tables with the skip global published, set as the test asks: its row's value
   * changed where the tables publish it, else a row added for it.
   */
  private static GameTables withSkip(Path folder, boolean set) throws IOException {
    return GameData.altered(
        folder,
        "globals",
        rows -> {
          if (rows.has(SKIP)) {
            ((ObjectNode) rows.get(SKIP).get("columns")).put("BooleanValue", set);
            return;
          }
          int index = rows.size();
          ObjectNode row = rows.putObject(SKIP);
          row.put("index", index);
          row.put("class", "LogicData");
          ObjectNode columns = row.putObject("columns");
          columns.put("Name", SKIP);
          columns.put("BooleanValue", set);
        });
  }

  /** The configured tables with no skip global published, as the 16.402 tables had none. */
  private static GameTables withoutSkip(Path folder) throws IOException {
    return GameData.altered(folder, "globals", rows -> rows.remove(SKIP));
  }

  /** The old rule: the discarded hit's end destroys the Wall Breaker, with no death damage. */
  private static void assertDestroyedByItsDiscardedHit(Run run) {
    assertThat(run.hookedAttacking).isGreaterThanOrEqualTo(0);
    // Its hit after the pull launched nothing: the target was out of range, the hit discarded.
    assertThat(run.ownLaunches).isEmpty();
    // Its end killed it all the same, after the hook.
    assertThat(run.hitEnds).hasSize(1);
    assertThat(run.hitEnds.get(0)).endsWith(" true");
    assertThat(Integer.parseInt(run.hitEnds.get(0).split(" ")[0]))
        .isGreaterThan(run.hookedAttacking);
    // As it destroyed itself, its killed action did not run: no death damage, no spawn.
    assertThat(run.deathHooks).isEmpty();
    assertThat(run.spawned).isEmpty();
    assertThat(run.sideLaunches).isEmpty();
    assertThat(run.removed).isGreaterThan(run.hookedAttacking);
  }

  @Test
  @DisplayName(
      "tables that do not publish the skip global are refused, naming it: the game reads the"
          + " global's row with no test for a missing one, so a missing row is not a clear flag")
  void withoutTheGlobal(@TempDir Path folder) throws IOException {
    GameTables tables = withoutSkip(folder);
    assertThatThrownBy(() -> new Standard1v1Battle(tables, LEVEL, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(SKIP);
  }

  @Test
  @DisplayName("with the skip global published clear, the discarded hit's end destroys it as well")
  void withTheGlobalClear(@TempDir Path folder) throws IOException {
    assertDestroyedByItsDiscardedHit(hookedMidAttack(withSkip(folder, false)));
  }

  @Test
  @DisplayName(
      "with the skip global set, the discarded hit leaves the hooked evolved Wall Breaker alive, and"
          + " killed by the Fisherman's side it runs its death actions, its death damage among them")
  void withTheGlobalSet(@TempDir Path folder) throws IOException {
    Run run = hookedMidAttack(withSkip(folder, true));
    assertThat(run.hookedAttacking).isGreaterThanOrEqualTo(0);
    assertThat(run.ownLaunches).isEmpty();
    // No end of a hit: neither the discarded one nor any after it destroyed it.
    assertThat(run.hitEnds).isEmpty();
    // The Fisherman's side killed it, and its killed action ran: what it spawns, and a projectile
    // of its side launched from its death tick on, its death damage.
    assertThat(run.deathHooks).hasSize(1);
    assertThat(run.deathHooks.get(0)).endsWith(" 0");
    int died = Integer.parseInt(run.deathHooks.get(0).split(" ")[0]);
    assertThat(died).isGreaterThan(run.hookedAttacking);
    assertThat(run.spawned).isNotEmpty();
    assertThat(run.sideLaunches).isNotEmpty();
    assertThat(Integer.parseInt(run.sideLaunches.get(0).split(" ")[0]))
        .isGreaterThanOrEqualTo(died);
  }
}
