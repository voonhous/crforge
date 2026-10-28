/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import java.util.ArrayList;
import java.util.List;
import org.crforge.desktop.battle.BattleSession;

/**
 * A crowded arena for the UI smoke: a swarm of Skeletons and a few Barbarians around a Knight on
 * side 0, a Valkyrie and a Giant on side 1 for the role badges, a Goblin Hut and an Elixir
 * Collector for the work icons, and the Tombstone hero's rows on one spot (its health bar, its
 * decoration with none, its ability's timer), all held still so the capture shows the crowd labels,
 * the thin crowd bars, the label culling and the timer.
 */
public final class CrowdSmokeFixture {
  private CrowdSmokeFixture() {}

  /** Places the units and runs them out of their deploy; returns one Skeleton of the swarm. */
  public static CharacterEntity populate(BattleSession session) {
    var battle = session.getBattle();
    var records = battle.getWorld().getRecords();
    List<CharacterEntity> units = new ArrayList<>();
    CharacterEntity first = null;
    for (int row = 0; row < 3; row++) {
      for (int col = 0; col < 5; col++) {
        CharacterEntity skeleton =
            battle.deploy(0, records.unit("Skeleton"), 11, 0, 4000 + col * 700, 11000 + row * 700);
        if (first == null) first = skeleton;
        units.add(skeleton);
      }
    }
    for (int i = 0; i < 4; i++) {
      units.add(battle.deploy(0, records.unit("Barbarian"), 11, 0, 4500 + i * 1000, 9200));
    }
    units.add(battle.deploy(0, records.unit("Knight"), 11, 0, 5400, 11700));
    units.add(battle.deploy(0, records.unit("Valkyrie"), 11, 1, 13500, 19500));
    units.add(battle.deploy(0, records.unit("Giant"), 11, 1, 14000, 22000));
    // The Tombstone hero's three rows on one spot: the building, its decoration (no bar) and the
    // unit holding its ability (a timer).
    for (String row :
        new String[] {
          "TombstoneHero", "TombstoneHero_visual_dummy", "TombstoneHero_Monster_Passive"
        }) {
      units.add(battle.deploy(0, records.unit(row), 11, 0, 14500, 12000));
    }
    // Two buildings that do not attack: a spawner and an elixir collector.
    units.add(battle.deploy(0, records.unit("GoblinHut"), 11, 1, 5000, 21000));
    units.add(battle.deploy(0, records.unit("ElixirCollector"), 11, 1, 9000, 23000));
    for (CharacterEntity unit : units) {
      unit.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    }
    for (int i = 0; i < 40; i++) session.step();
    return first;
  }
}
