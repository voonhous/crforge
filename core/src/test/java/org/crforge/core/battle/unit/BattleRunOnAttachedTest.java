package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A newer data version's ActionRunOnAttached, as the Ice Wizard hero hands its floating cube the
 * actions of its ability: it schedules its action on each character that rides its owner, built for
 * that rider, and nothing on the owner itself. Each scene plays a Goblin Giant, whose starting
 * action the altered tables make a hand-over of a variable write to its two Spear Goblins.
 */
class BattleRunOnAttachedTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String HAND_OVER = "Test_run_on_attached";

  private static final String WRITE = "Test_set_variable";

  /** The riders the Giant's row attaches, written into it; its card plays one Giant. */
  private static final int RIDERS = 2;

  /**
   * The configured tables with the Giant's starting action handing a write of 7 to its riders, and
   * its rider count and its card's count written.
   */
  private static GameTables handOver(Path folder) throws IOException {
    GameData.altered(
        folder,
        "actions",
        rows -> {
          ObjectNode run = rows.putObject(HAND_OVER);
          run.put("class", "LogicActionRunOnAttachedData");
          run.put("ClassType", "ActionRunOnAttached");
          ObjectNode fields = run.putObject("fields");
          fields.put("ClassType", "ActionRunOnAttached");
          fields.putObject("ActionToRun").put("action", WRITE);
          ObjectNode write = rows.putObject(WRITE);
          write.put("class", "LogicActionSetVariableData");
          write.put("ClassType", "ActionSetVariable");
          ObjectNode columns = write.putObject("fields");
          columns.put("ClassType", "ActionSetVariable");
          columns.put("Variable", "TestVariable");
          columns.put("Value", "7");
        });
    ObjectMapper mapper = new ObjectMapper();
    Path file = folder.resolve("characters.json");
    ObjectNode document = (ObjectNode) mapper.readTree(file.toFile());
    GameData.columns((ObjectNode) document.get("rows"), "GoblinGiant")
        .put("OnStartingAction", HAND_OVER)
        .put("SpawnNumber", RIDERS);
    mapper.writeValue(file.toFile(), document);
    GameData.alterLoaded(
        folder,
        "spells_characters",
        rows -> GameData.columns(rows, "GoblinGiant").put("SummonNumber", 1));
    GameData.addTestVariable(folder);
    return GameTables.load(folder);
  }

  @Test
  @DisplayName(
      "the hand-over writes the variable on each of the Giant's riders and not on the Giant")
  void theActionRunsOnEachRider(@TempDir Path folder) throws IOException {
    Standard1v1Battle battle = new Standard1v1Battle(handOver(folder), LEVEL, false);
    battle.play(
        1, battle.getWorld().getRecords().card("GoblinGiant"), LEVEL, 0, 3500, 10000, "Blue");
    while (battle.getBattle().getTick() <= 60) {
      battle.getBattle().step();
    }
    BattleWorld world = battle.getWorld();
    int key = world.variableKey("TestVariable");
    List<CharacterEntity> giants = characters(world, "GoblinGiant");
    List<CharacterEntity> riders = characters(world, "SpearGoblinGiant");
    assertThat(giants).hasSize(1);
    assertThat(riders).hasSize(RIDERS);
    assertThat(giants.get(0).variable(key)).isZero();
    assertThat(riders).allSatisfy(rider -> assertThat(rider.variable(key)).isEqualTo(7));
  }

  @Test
  @DisplayName(
      "handed over from a wait, whose activation the Giant causes, a hide runs on each rider with"
          + " the Giant as its hider and schedules the next action it chains alongside")
  void aHideHandedOverChainsItsNextAction(@TempDir Path folder) throws IOException {
    handOver(folder);
    GameData.alterLoaded(
        folder,
        "actions",
        rows -> {
          ObjectNode start = rows.putObject("Test_wait");
          start.put("class", "LogicActionWaitToActivateData");
          start.put("ClassType", "ActionWaitToActivate");
          ObjectNode wait = start.putObject("fields");
          wait.put("ClassType", "ActionWaitToActivate");
          wait.put("Condition", "1");
          wait.putObject("OnActivateAction").put("action", HAND_OVER);
          ((ObjectNode) rows.get(HAND_OVER).get("fields"))
              .putObject("ActionToRun")
              .put("action", "Test_hide");
          ObjectNode hide = rows.putObject("Test_hide");
          hide.put("class", "LogicActionHideData");
          hide.put("ClassType", "ActionHide");
          ObjectNode fields = hide.putObject("fields");
          fields.put("ClassType", "ActionHide");
          fields.putObject("NextAction").put("action", WRITE);
        });
    GameData.alterLoaded(
        folder,
        "characters",
        rows -> GameData.columns(rows, "GoblinGiant").put("OnStartingAction", "Test_wait"));
    Standard1v1Battle battle = new Standard1v1Battle(GameTables.load(folder), LEVEL, false);
    battle.play(
        1, battle.getWorld().getRecords().card("GoblinGiant"), LEVEL, 0, 3500, 10000, "Blue");
    while (battle.getBattle().getTick() <= 60) {
      battle.getBattle().step();
    }
    BattleWorld world = battle.getWorld();
    int key = world.variableKey("TestVariable");
    List<CharacterEntity> riders = characters(world, "SpearGoblinGiant");
    assertThat(riders).hasSize(RIDERS);
    assertThat(riders)
        .allSatisfy(
            rider -> {
              assertThat(rider.variable(key)).isEqualTo(7);
              assertThat(rider.hidden()).isTrue();
            });
  }

  private static List<CharacterEntity> characters(BattleWorld world, String row) {
    return world.getHolder().entities().stream()
        .filter(CharacterEntity.class::isInstance)
        .map(CharacterEntity.class::cast)
        .filter(unit -> unit.getData().name().equals(row))
        .toList();
  }
}
