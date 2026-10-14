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
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.move.MovementState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two hits an action's run hands to the damage entry, which queues them for the holder's damage
 * drain after every post-hook rather than dealing them in the run pass: the Goblinstein tether's
 * hit and the evolved Battle Ram's carried push's hit. A unit either of them kills therefore dies
 * at the drain, and its death action runs in the last pending pass of the tick; a kill that action
 * hands on - the evolved Skeleton Army general's kill of the spectral skeletons of its chain - is
 * queued after the drain and lands at the next tick's.
 *
 * <p>The scenes link spectral skeletons into a general's chain by hand, away from the hit, and set
 * the general's hit points and shield so the first hit kills it; the push scene writes the columns
 * its outcome is read from, so its ticks are its own and not a version's.
 */
class BattleQueuedHitDeathActionTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String GENERAL = "SkeletonArmy_EV1_General";

  private static final String SPECTRAL = "SkeletonArmy_EV1_Spectral";

  /** A unit of the given row for side 1 that never walks, at a point. */
  private static CharacterEntity still(
      Standard1v1Battle battle, int tick, String row, int x, int y) {
    CharacterEntity unit =
        battle.deploy(tick, battle.getWorld().getRecords().unit(row), LEVEL, 1, x, y, row);
    unit.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    return unit;
  }

  /** Links the general and then the spectral skeletons into one chain, as a card's group is. */
  private static void chain(CharacterEntity general, List<CharacterEntity> spectrals) {
    CharacterEntity previous = general;
    general.linkAfter(null);
    for (CharacterEntity spectral : spectrals) {
      spectral.linkAfter(previous);
      previous = spectral;
    }
  }

  /** Steps the battle until it has run the given tick. */
  private static void run(Standard1v1Battle battle, int lastTick) {
    while (battle.getBattle().getTick() <= lastTick) {
      battle.getBattle().step();
    }
  }

  /**
   * Steps until the general is dead, at most the given steps, and then once more, checking the
   * spectral skeletons after each of the two last steps: answers whether each was alive after the
   * general's death step, and after the step that followed.
   */
  private static List<Boolean> spectralsAroundTheDeath(
      Standard1v1Battle battle, CharacterEntity general, List<CharacterEntity> spectrals, int max) {
    for (int i = 0; i < max && general.getView().isAlive(); i++) {
      battle.getBattle().step();
    }
    assertThat(general.getView().isAlive()).as("the general was killed").isFalse();
    List<Boolean> alive = new ArrayList<>();
    alive.add(spectrals.stream().allMatch(s -> s.getView().isAlive()));
    battle.getBattle().step();
    alive.add(spectrals.stream().anyMatch(s -> s.getView().isAlive()));
    return alive;
  }

  @Test
  @DisplayName(
      "a general the Goblinstein tether kills dies at the damage drain, so the spectral skeletons"
          + " of its chain live through its death step and die at the next step's drain")
  void theTetherKillLandsAtTheDrain() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    battle.play(0, GameData.card("Goblinstein"), LEVEL, 0, 3500, 12000, "g");
    run(battle, 30);
    CharacterEntity monster = battle.getPlays().get(0).units().get(0);
    CharacterEntity doctor = battle.getPlays().get(0).units().get(1);
    // Neither end moves, so the segment stays where it is.
    for (CharacterEntity end : List.of(monster, doctor)) {
      end.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    }
    int mx = (doctor.getView().getX() + monster.getView().getX()) / 2;
    int my = (doctor.getView().getY() + monster.getView().getY()) / 2;
    int tick = battle.getBattle().getTick();
    CharacterEntity general = still(battle, tick, GENERAL, mx, my);
    // Two spectral skeletons far off the segment, on the arena's right.
    List<CharacterEntity> spectrals =
        List.of(still(battle, tick, SPECTRAL, 14000, my), still(battle, tick, SPECTRAL, 15000, my));
    run(battle, tick + 1);
    chain(general, spectrals);
    // The tether's first pass kills the general: one hit point and no shield as it starts.
    battle
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void goblinsteinStepped(int t, AreaEffectEntity owner, String step) {
                if (step.startsWith("tether_start")) {
                  general.getHitPoints().setHitPoints(1);
                  general.getHitPoints().setShield(0);
                }
              }
            });
    run(battle, 70);
    doctor.requestAbility();

    assertThat(spectralsAroundTheDeath(battle, general, spectrals, 300))
        .as("all alive after the general's death step, none after the next")
        .containsExactly(true, false);
  }

  /**
   * The configured tables with the evolved Battle Ram's charge and push as the test writes them:
   * the ram walks 60 and charges at 200 percent of it, its push waits 825 ms, is centred 800 ahead
   * of it with a radius of 1000, pushes 2000 and deals 83 at the first level.
   */
  private static GameTables pushTables(Path folder) throws IOException {
    GameData.altered(
        folder,
        "actions",
        rows -> {
          ObjectNode push = (ObjectNode) rows.get("BattleRam_EV1_PushBack").get("fields");
          push.put("ActionDelay", 825);
          push.put("PushBackDamage", 83);
          push.put("PushBackRadius", 1000);
          push.put("PushBackStrength", 2000);
          push.put("PushRadiusDirectionalOffset", 800);
          push.put("OnPushEffectMinInterval", 1000);
        });
    GameData.alterLoaded(
        folder,
        "characters",
        rows -> {
          ObjectNode ram = GameData.columns(rows, "BattleRam_EV1");
          ram.put("OnStartChargingAction", "BattleRam_EV1_PushBack");
          ram.put("Rarity", "Common");
          ram.put("Speed", 60);
          ram.put("ChargeSpeedMultiplier", 200);
          ram.put("ChargeRange", 300);
        });
    return GameTables.load(folder);
  }

  @Test
  @DisplayName(
      "a general the evolved Battle Ram's carried push kills dies at the damage drain, so the"
          + " spectral skeletons of its chain live through its death step and die at the next"
          + " step's drain")
  void theCarriedPushKillLandsAtTheDrain(@TempDir Path folder) throws IOException {
    GameTables tables = pushTables(folder);
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, false);
    // Level 3, as a Rare card at level index 0 plays it.
    CharacterEntity ram = battle.deploy(0, records.unit("BattleRam_EV1"), 3, 0, 14500, 9000);
    int completed = -1;
    for (int i = 0; i < 200 && completed < 0; i++) {
      battle.getBattle().step();
      if (ram.getUnit().movement().getChargeProgress() >= MovementState.CHARGE_COMPLETE) {
        completed = battle.getWorld().tick();
      }
    }
    assertThat(completed).isPositive();
    // The ram walks 120 units a step once charged; the push pass is centred 800 ahead of it 16
    // steps later, with a radius of 1000. The general stands 300 to the side of that centre, the
    // spectral skeletons far off on the arena's left.
    int y = ram.getView().getY() + 16 * 120 + 800;
    CharacterEntity general = still(battle, completed + 1, GENERAL, ram.getView().getX() + 300, y);
    List<CharacterEntity> spectrals =
        List.of(
            still(battle, completed + 1, SPECTRAL, 2000, y),
            still(battle, completed + 1, SPECTRAL, 3000, y));
    run(battle, completed + 2);
    chain(general, spectrals);
    general.getHitPoints().setHitPoints(1);
    general.getHitPoints().setShield(0);

    assertThat(spectralsAroundTheDeath(battle, general, spectrals, 40))
        .as("all alive after the general's death step, none after the next")
        .containsExactly(true, false);
  }
}
