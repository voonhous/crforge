package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A unit whose row attaches its spawner's children makes its riders each time it enters the
 * deploying state. Played without a wait it enters it before it is handed to the holder, so its
 * riders take the lower ids; one that waits its turn first, as a card's delay list makes the Ice
 * Wizard hero of data version 16.402.18 wait, enters it in its state visit once the wait has run
 * out, and its riders come then, after it. Each scene plays a Goblin Giant, whose card the altered
 * tables give a delay list of one tick.
 */
class BattleWaitingRidersTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The tick of the play. */
  private static final int PLAY_TICK = 1;

  /**
   * Each rider attached: the world's tick, its id, its parent's id and state, its state and
   * countdown.
   */
  private static List<String> riders(GameTables tables) {
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, false);
    List<String> out = new ArrayList<>();
    battle
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void riderAttached(
                  int tick, CharacterEntity parent, CharacterEntity rider, int index, int angle) {
                out.add(
                    "%d rider %d parent %d in %d, rider in %d for %d"
                        .formatted(
                            tick,
                            rider.getId(),
                            parent.getId(),
                            parent.getView().getState(),
                            rider.getView().getState(),
                            rider.getView().getDeployCountdown()));
              }
            });
    battle.play(
        PLAY_TICK,
        battle.getWorld().getRecords().card("GoblinGiant"),
        LEVEL,
        0,
        3500,
        10000,
        "Blue");
    while (battle.getBattle().getTick() <= 40) {
      battle.getBattle().step();
    }
    return out;
  }

  @Test
  @DisplayName("A Goblin Giant played at once makes its riders before it is handed to the holder")
  void ridersOfAPlayWithoutWait() {
    // The riders are registered at once, ahead of the Giant, which has no id yet, on the play's
    // tick, which the world's notices count as 0.
    assertThat(riders(GameData.tables()))
        .containsExactly(
            "0 rider 5000006 parent 0 in 4, rider in 4 for 1000",
            "0 rider 5000007 parent 0 in 4, rider in 4 for 1000");
  }

  @Test
  @DisplayName("A Goblin Giant that waits its turn makes its riders as its wait ends, after it")
  void ridersOfAWaitingPlay(@TempDir Path folder) throws IOException {
    GameTables tables =
        GameData.altered(
            folder,
            "spells_characters",
            rows -> {
              ObjectNode card = GameData.columns(rows, "GoblinGiant");
              card.put("SummonCharacter", "");
              card.putNull("SummonNumber");
              card.putArray("SummonCharactersList").add("GoblinGiant");
              card.putArray("SummonCharactersOffsetsX").add(0);
              card.putArray("SummonCharactersOffsetsY").add(0);
              card.putArray("SummonCharactersDelayList").add(50);
            });

    // The Giant took its id as it was queued, waiting; its state visit sets it deploying a tick
    // later, and the riders made then follow it, deploying for its deploy time.
    assertThat(riders(tables))
        .containsExactly(
            "1 rider 5000007 parent 5000006 in 4, rider in 4 for 1000",
            "1 rider 5000008 parent 5000006 in 4, rider in 4 for 1000");
  }
}
