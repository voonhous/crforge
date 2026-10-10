/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.crforge.core.battle.BattleRandom;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.ActionRow;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.RowAction;
import org.crforge.core.battle.action.Select;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A select whose condition draws from the battle's random source, not held by a recorded battle:
 * the draw falls as the select is scheduled, even while its own entry waits; a condition the data
 * writes as a list compiles from its first element; and an action owner's expressions answer rand
 * and nothing else.
 */
class BattleSelectDrawTest {

  private static BattleAction part(String name) {
    return new RowAction(ActionRow.named(name)) {
      @Override
      public ActionInstance start(ActionHolder holder) {
        return null;
      }
    };
  }

  @Test
  @DisplayName(
      "a select draws as it is scheduled, while its own entry waits out its delay and its part is"
          + " queued with none")
  void theDrawFallsAtScheduling() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    match.getWorld().seed(0x37);
    ActionOwnerEntity owner = match.addActionOwner("Owner", 0, 14500, 12000, 10);
    List<BattleAction> parts = List.of(part("p0"), part("p1"), part("p2"));
    Select select =
        new Select(
            ActionRow.builder().name("select").delayMs(500).build(),
            parts,
            owner.binding().expression("rand(3)"),
            null,
            false);

    owner.actionHolder().schedule(select, ActionHolder.OWN_DELAY, false, owner.actionHolder());

    BattleRandom expected = new BattleRandom(0x37);
    int drawn = expected.next(3);
    assertThat(match.getWorld().getRandom().getState())
        .as("stepped once, at the scheduling")
        .isEqualTo(expected.getState());
    assertThat(
            owner.actionHolder().queued().stream()
                .map(q -> q.action().name() + " " + q.ticks())
                .toList())
        .containsExactly("select 10", "p" + drawn + " 0");
  }

  @Test
  @DisplayName("a select condition written as a list compiles from its first element")
  void aListConditionTakesItsFirst(@TempDir Path folder) throws IOException {
    // No configured select writes its condition as a list; this one writes ["rand(3)"] over three
    // effects.
    GameTables tables =
        GameData.altered(
            folder,
            "actions",
            rows -> {
              ObjectNode row = rows.putObject("Test_ListSelect");
              row.put("class", "LogicActionSelectData");
              row.put("ClassType", "ActionSelect");
              ObjectNode fields = row.putObject("fields");
              fields.put("ClassType", "ActionSelect");
              fields.putArray("Condition").add("rand(3)");
              ArrayNode parts = fields.putArray("SubActions");
              for (String part :
                  List.of(
                      "Witch_Heal_Effect", "PekkaEV1_SoulArrived_FX", "LittlePrinceMaxSpeedSFX")) {
                parts.addObject().put("action", part);
              }
            });
    Standard1v1Battle match = new Standard1v1Battle(tables);
    match.getWorld().seed(0x37);
    ActionOwnerEntity owner = match.addActionOwner("Owner", 0, 14500, 12000, 10);
    BattleAction select = match.getWorld().getActions().build("Test_ListSelect", owner.binding());

    owner.actionHolder().schedule(select, ActionHolder.OWN_DELAY, false, owner.actionHolder());

    BattleRandom expected = new BattleRandom(0x37);
    expected.next(3);
    assertThat(match.getWorld().getRandom().getState())
        .as("it drew rand(3)")
        .isEqualTo(expected.getState());
    assertThat(owner.actionHolder().queued()).as("the select and the part it chose").hasSize(2);
  }

  @Test
  @DisplayName(
      "an action owner's expression that names anything but rand is refused as it is built")
  void anOwnerAnswersOnlyRand() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    ActionOwnerEntity owner = match.addActionOwner("Owner", 0, 14500, 12000, 10);

    assertThat(owner.binding().expression("rand(1) + 2").getAsInt()).isEqualTo(2);
    assertThatThrownBy(() -> owner.binding().expression("x + 1000"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("answers only rand");
  }
}
