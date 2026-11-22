/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameAction;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.unit.UnitData;
import org.crforge.core.pathfinding.target.AttackSequenceEntry;

/**
 * The static facts of a data version per vocabulary row ({@link Fields#rowFacts()}), worked out
 * once from the tables the environment loads: the health bar rule as {@link SeatView.Bars} reads
 * it, and the attack timing of each character and building row as the battle core loads the row
 * ({@link BattleRecords#unit}) and builds its targeting from it.
 */
final class RowFacts {

  private static final String CHARACTERS = "characters";
  private static final String BUILDINGS = "buildings";
  private static final String ABILITIES = "character_abilities";

  /** The attack time a tick adds at a step's multiplier of 100 percent: half of it. */
  private static final int PLAIN_MULTIPLIER = AttackSequenceEntry.DEFAULT_HIT_SPEED_MULTIPLIER;

  private final Map<String, int[]> values = new HashMap<>();

  /**
   * The facts of every row of a vocabulary.
   *
   * @param tables the tables the vocabulary numbers the rows of
   * @param vocabulary the vocabulary
   */
  RowFacts(GameTables tables, Vocabulary vocabulary) {
    int count = vocabulary.size();
    int[] bar = new int[count];
    int[] hitSpeed = new int[count];
    int[] loadTime = new int[count];
    int[] step = new int[count];
    int[] startDelay = new int[count];
    int[] traits = new int[count];
    SeatView.Bars bars = new SeatView.Bars(tables);
    BattleRecords records = new BattleRecords(tables);
    Set<String> rolling = rollingRows(tables, bars);
    for (int id = 0; id < count; id++) {
      String name = vocabulary.names().get(id);
      int rule = bars.rule(name);
      if (rule == Fields.BAR_NOT_UNIT) {
        // A card, spell, projectile or area effect row: no unit, every fact 0.
        continue;
      }
      bar[id] =
          rule == Fields.BAR_DAMAGED && rolling.contains(name) ? Fields.BAR_DAMAGED_ROLLING : rule;
      UnitData data;
      try {
        data = records.unit(name);
      } catch (RuntimeException refused) {
        // A row the battle core refuses to load: no unit of the battle is ever of it.
        hitSpeed[id] = -1;
        loadTime[id] = -1;
        step[id] = -1;
        startDelay[id] = -1;
        traits[id] = -1;
        continue;
      }
      hitSpeed[id] = data.hitSpeedMs();
      loadTime[id] = data.loadTimeMs();
      // A crown tower's targeting takes the multipliers of its sequence but neither the load first
      // rule nor a start delay.
      boolean tower = data.king() || data.summonerTower();
      Set<Integer> multipliers = new HashSet<>(List.of(PLAIN_MULTIPLIER));
      Set<Integer> delays = new HashSet<>(List.of(0));
      for (AttackSequenceEntry entry : data.attackSequence().targetingEntries()) {
        multipliers.add(entry.hitSpeedMultiplier());
        if (!tower) {
          delays.add(entry.attackStartDelay());
        }
      }
      // The step is half the multiplier of the step the unit is at, which is not exported: one
      // value only where every step and the plain one agree.
      step[id] = multipliers.size() == 1 ? multipliers.iterator().next() / 2 : -1;
      startDelay[id] = delays.size() == 1 ? delays.iterator().next() : -1;
      int t = 0;
      t |= data.loadFirstHit() && !tower ? Fields.TRAIT_LOAD_FIRST_HIT : 0;
      t |= data.chargeRange() != 0 ? Fields.TRAIT_CHARGE : 0;
      t |=
          data.dashCooldown() > 0
                  || data.dashMinRange() > 0
                  || data.dashMaxRange() >= 1
                  || data.dashCount() > 0
              ? Fields.TRAIT_DASH
              : 0;
      t |= data.specialRange() >= 1 || data.specialLoadTimeMs() >= 1 ? Fields.TRAIT_SPECIAL : 0;
      traits[id] = t;
    }
    values.put("bar_rule", bar);
    values.put("hit_speed_ms", hitSpeed);
    values.put("load_time_ms", loadTime);
    values.put("attack_step_ms", step);
    values.put("attack_start_delay_ms", startDelay);
    values.put("attack_traits", traits);
    for (Fields.RowFact fact : Fields.rowFacts()) {
      if (!values.containsKey(fact.name())) {
        throw new IllegalStateException("no values of the row fact " + fact.name());
      }
    }
  }

  /**
   * A fact's values, one per vocabulary id, a copy.
   *
   * @throws IllegalArgumentException for a name of no fact
   */
  int[] values(String fact) {
    int[] row = values.get(fact);
    if (row == null) {
      throw new IllegalArgumentException("no row fact " + fact);
    }
    return Arrays.copyOf(row, row.length);
  }

  /**
   * The character and building rows a unit of which may run an action that hides its health bar
   * while it rolls: each row whose columns, the actions they name and the ability rows they name
   * reach such an action, and each such row an action reached from one names (a roll's data change
   * leaves the unit of another row inside the roll). Wider rather than narrower: a row named here
   * is one whose missing bar does not tell that it is undamaged.
   */
  private static Set<String> rollingRows(GameTables tables, SeatView.Bars bars) {
    Set<String> actions = new HashSet<>(tables.actionNames());
    Set<String> units = new HashSet<>();
    for (String table : List.of(CHARACTERS, BUILDINGS)) {
      if (tables.tableNames().contains(table)) {
        tables.table(table).rows().forEach(row -> units.add(row.name()));
      }
    }
    Set<String> rolling = new HashSet<>();
    for (String table : List.of(CHARACTERS, BUILDINGS)) {
      if (!tables.tableNames().contains(table)) {
        continue;
      }
      for (GameRow row : tables.table(table).rows()) {
        boolean[] hides = {false};
        walk(tables, actions, bars, row.columns().values(), hidden -> hides[0] = true, name -> {});
        if (hides[0]) {
          rolling.add(row.name());
        }
      }
    }
    // The rows the hiding actions' own reach names.
    for (String action : actions) {
      if (bars.hidesWhileRunning(action) && tables.action(action).fields() != null) {
        walk(
            tables,
            actions,
            bars,
            List.of(tables.action(action).fields()),
            hidden -> {},
            name -> {
              if (units.contains(name)) {
                rolling.add(name);
              }
            });
      }
    }
    return rolling;
  }

  /**
   * Walks the values of a row's columns or an action's fields and every action and ability row
   * their text names, each once, telling of each action met that hides the bar while it rolls (by
   * name or written inline) and of every text met.
   */
  private static void walk(
      GameTables tables,
      Set<String> actions,
      SeatView.Bars bars,
      Collection<JsonNode> start,
      Consumer<String> onHiding,
      Consumer<String> onText) {
    boolean abilities = tables.tableNames().contains(ABILITIES);
    Deque<JsonNode> queue = new ArrayDeque<>(start);
    Set<String> seen = new HashSet<>();
    while (!queue.isEmpty()) {
      JsonNode node = queue.pop();
      if (node == null) {
        continue;
      }
      if (node.isObject()) {
        if (node.path("HideHealthbarWhileRolling").asBoolean(false)) {
          onHiding.accept("inline");
        }
        node.forEach(queue::add);
      } else if (node.isArray()) {
        node.forEach(queue::add);
      } else if (node.isTextual()) {
        String text = node.asText();
        if (!seen.add(text)) {
          continue;
        }
        onText.accept(text);
        if (actions.contains(text)) {
          if (bars.hidesWhileRunning(text)) {
            onHiding.accept(text);
          }
          GameAction action = tables.action(text);
          if (action.fields() != null) {
            queue.add(action.fields());
          }
        } else if (abilities && tables.table(ABILITIES).has(text)) {
          queue.addAll(tables.table(ABILITIES).row(text).columns().values());
        }
      }
    }
  }
}
