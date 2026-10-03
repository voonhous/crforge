package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.crforge.core.battle.BattleRandom;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.ActionRow;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.RowAction;
import org.crforge.core.battle.action.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A select whose condition draws from the battle's random source, in the cases gift_select does not
 * reach: the draw falls as the select is scheduled, even while its own entry waits; a condition the
 * data writes as a list compiles from its first element; and an action owner's expressions answer
 * rand and nothing else.
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
  void aListConditionTakesItsFirst() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    match.getWorld().seed(0x37);
    ActionOwnerEntity owner = match.addActionOwner("Owner", 0, 14500, 12000, 10);
    // BabyDragon_crazy_1_SpawnGroup writes its condition as ["rand(3)"].
    BattleAction select =
        GameData.actions().build("BabyDragon_crazy_1_SpawnGroup", owner.binding());

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
