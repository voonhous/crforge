/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.data;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A damage type as the game's one damage type class holds it: BaseDamage, TowerDamage, Effect,
 * ActionOnTarget, ActionOnSource and Flags, beside columns only the card's info shows. A column
 * holds one either as the name of a damage types row or as a table written inline, and {@link
 * DamageTypeRecords} reads both into this one form. Each column that holds a damage type - an area
 * effect's Damage, a damage-taking action's Damage, a carried push's PushBackDamage, a damage
 * dealing action's BaseDamageType - then refuses what of it its reader does not model.
 *
 * @param form how the column writes it
 * @param name the damage types row it names, also for a name the table has no row of; null for a
 *     table written inline and a value of another form
 * @param fields the fields it sets, in order: every field of an inline table, the set columns of a
 *     named row (an empty cell, false, 0 or an empty list not counting); none for another form or a
 *     name the table has no row of
 * @param columns its fields by name, the inline table's or the named row's; empty for another form
 *     or a name the table has no row of
 */
record DamageTypeRow(Form form, String name, List<String> fields, Map<String, JsonNode> columns) {

  /** How a column writes its damage type. */
  enum Form {
    /** A table written inline in the column. */
    INLINE,
    /** The name of a row of the damage types table. */
    NAMED,
    /** A name the damage types table has no row of. */
    UNKNOWN_ROW,
    /**
     * Any other value: a number, as a carried push's damage of game client 16.402.17 is, or a list.
     */
    OTHER
  }

  /** True when the type was read: a table written inline or a row the damage types table has. */
  boolean read() {
    return form == Form.INLINE || form == Form.NAMED;
  }

  /** True when the column names a damage types row, whether the table has that row or not. */
  boolean named() {
    return form == Form.NAMED || form == Form.UNKNOWN_ROW;
  }

  /** Its BaseDamage; 0 when it leaves it out. */
  int baseDamage() {
    return amount("BaseDamage", 0);
  }

  /**
   * Its TowerDamage, the amount a crown tower takes.
   *
   * @param none what the reader takes for a type that leaves it out
   */
  int towerDamage(int none) {
    return amount("TowerDamage", none);
  }

  /** Its Flags, written as one comma-separated text or as a list of names; none when left out. */
  Set<String> flags() {
    JsonNode written = columns.get("Flags");
    List<String> names = new ArrayList<>();
    if (written != null && written.isArray()) {
      written.forEach(flag -> names.add(flag.asText()));
    } else if (written != null && !written.isNull()) {
      names.addAll(List.of(written.asText().split(",")));
    }
    Set<String> flags = new HashSet<>();
    for (String flag : names) {
      if (!flag.isBlank()) {
        flags.add(flag.trim());
      }
    }
    return flags;
  }

  /** The action it runs on the hit's source, as the column writes it; null for none. */
  JsonNode actionOnSource() {
    return columns.get("ActionOnSource");
  }

  /** The action it runs on the hit's target, as the column writes it; null for none. */
  JsonNode actionOnTarget() {
    return columns.get("ActionOnTarget");
  }

  /**
   * One of its amounts: the fallback when it leaves the field out or sets an empty cell.
   *
   * @throws UnsupportedOperationException when it holds anything but a whole number
   */
  private int amount(String field, int fallback) {
    JsonNode value = columns.get(field);
    if (value == null || value.isNull() || value.isTextual() && value.asText().isEmpty()) {
      return fallback;
    }
    if (!GameRow.isWhole(value)) {
      throw new UnsupportedOperationException(
          (name == null ? "a damage type written inline" : "the damage type " + name)
              + " sets "
              + field
              + " to "
              + GameRow.shape(value)
              + " where a number is read, which is not modelled");
    }
    return value.asInt();
  }
}
