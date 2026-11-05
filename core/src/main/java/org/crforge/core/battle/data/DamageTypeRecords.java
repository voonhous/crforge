/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.data;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.battle.data.DamageTypeRow.Form;

/**
 * The one reader of the game's damage type class, which every column holding a damage type shares:
 * the damage types table's rows, and the tables a column writes inline in place of a row's name.
 * Each column's reader refuses what of the type it does not model (see {@link DamageTypeRow}).
 */
final class DamageTypeRecords extends RowReader {

  static final String DAMAGE_TYPES = "damage_types";

  DamageTypeRecords(GameTables tables, BattleRecords records) {
    super(tables, records);
  }

  /**
   * The damage type a column writes: a table written inline, or the name of a damage types row; a
   * name the table has no row of and a value of another form are answered as such, for the column's
   * reader to refuse or read otherwise.
   *
   * @param written the column's value as the row writes it, or null when the row leaves it out
   * @return the type, or null when the column writes none: left out, null or an empty cell
   */
  DamageTypeRow damageType(JsonNode written) {
    if (written == null
        || written.isNull()
        || written.isMissingNode()
        || written.isTextual() && written.asText().isEmpty()) {
      return null;
    }
    if (written.isObject()) {
      List<String> fields = new ArrayList<>();
      Map<String, JsonNode> columns = new LinkedHashMap<>();
      written
          .fields()
          .forEachRemaining(
              field -> {
                fields.add(field.getKey());
                columns.put(field.getKey(), field.getValue());
              });
      return new DamageTypeRow(
          Form.INLINE, null, List.copyOf(fields), Collections.unmodifiableMap(columns));
    }
    if (written.isTextual()) {
      GameTable types = tables.table(DAMAGE_TYPES);
      if (!types.has(written.asText())) {
        return new DamageTypeRow(Form.UNKNOWN_ROW, written.asText(), List.of(), Map.of());
      }
      GameRow row = types.row(written.asText());
      return new DamageTypeRow(
          Form.NAMED,
          row.name(),
          List.copyOf(row.setColumns()),
          Collections.unmodifiableMap(row.columns()));
    }
    return new DamageTypeRow(Form.OTHER, null, List.of(), Map.of());
  }
}
