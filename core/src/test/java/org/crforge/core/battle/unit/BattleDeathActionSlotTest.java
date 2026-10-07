package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where a unit's death action is scheduled, and with what as its cause. The game schedules it from
 * the death slot, with the dying unit as its own cause, so an area effect it spawns is for the
 * dying unit's side; its death handler schedules only the killed action, with the killer as the
 * cause.
 *
 * <p>The scene: the configured tables, with a test death action on the Knight that spawns an area
 * effect without taking its parent as the source; side 0's Knight is killed by side 1's Knight.
 */
class BattleDeathActionSlotTest {

  /** The test death action: an area-effect spawn that takes its side from its cause. */
  private static final String DEATH_ACTION = "Test_Knight_DeathArea";

  /** The area effect it spawns. */
  private static final String AREA = "FreezeIceGolemite";

  @TempDir Path folder;

  /** The configured tables with the test death action on the Knight. */
  private GameTables tables() throws IOException {
    GameData.altered(
        folder,
        "actions",
        rows -> {
          ObjectNode row = rows.putObject(DEATH_ACTION);
          row.put("class", "LogicActionSpawnData");
          row.put("ClassType", "ActionSpawn");
          ObjectNode fields = row.putObject("fields");
          fields.put("ClassType", "ActionSpawn");
          fields.put("SpawnData", AREA);
          fields.put("SpawnType", "AreaEffectType");
        });
    GameData.alterLoaded(
        folder,
        "characters",
        rows -> GameData.columns(rows, "Knight").put("OnDeathAction", DEATH_ACTION));
    return GameTables.load(folder);
  }

  /** What one kill of side 0's Knight by side 1's Knight leaves: the hooks and the area effects. */
  private record Outcome(List<String> scheduled, List<Integer> areaSides) {}

  private static Outcome killTheKnight(GameTables tables) {
    Standard1v1Battle match = new Standard1v1Battle(tables, Standard1v1Battle.DEFAULT_LEVEL, false);
    CharacterEntity dying = match.deploy(0, unit(match), 1, 0, 14500, 17600, "dying");
    CharacterEntity killer = match.deploy(0, unit(match), 11, 1, 3500, 25000, "killer");
    List<String> scheduled = new ArrayList<>();
    boolean[] dealt = {false};
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void deathHooksScheduled(
                  int tick,
                  WorldEntity unit,
                  BattleEntity attacker,
                  int side,
                  List<String> hooks,
                  boolean inPendingPass) {
                String cause = attacker instanceof WorldEntity w ? w.name() : "none";
                scheduled.add("%s by %s side %d %s".formatted(unit.name(), cause, side, hooks));
              }

              @Override
              public void afterPrePass(int tick, List<WorldEntity> present) {
                if (tick > 0 && !dealt[0]) {
                  dealt[0] = true;
                  match.getWorld().kill(dying, killer);
                }
              }
            });
    for (int i = 0; i < 4; i++) {
      match.getBattle().step();
    }
    List<Integer> sides = new ArrayList<>();
    for (BattleEntity entity : match.getWorld().getHolder().entities()) {
      if (entity instanceof AreaEffectEntity area && area.getData().name().equals(AREA)) {
        sides.add(area.side());
      }
    }
    return new Outcome(scheduled, sides);
  }

  private static UnitData unit(Standard1v1Battle match) {
    return match.getWorld().getRecords().unit("Knight");
  }

  @Test
  @DisplayName(
      "the death action is scheduled from the death slot with the dying unit as its cause, and its"
          + " area effect is for the dying unit's side")
  void theDeathActionIsTheDyingUnits() throws IOException {
    Outcome outcome = killTheKnight(tables());
    assertThat(outcome.scheduled())
        .containsExactly("dying by dying side 0 [%s]".formatted(DEATH_ACTION));
    assertThat(outcome.areaSides()).containsExactly(0);
  }
}
