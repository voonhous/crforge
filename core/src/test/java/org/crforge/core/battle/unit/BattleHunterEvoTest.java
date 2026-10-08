package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.HunterNetAttack;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The evolved Hunter: its starting action throws a net at the nearest enemy in its range on its own
 * cooldown, beside its ordinary attack, and the net's hit snares the target.
 *
 * <p>The scene writes the targets' radii (the Musketeer 500, the Giant 750), which pick the snare's
 * size; the snare's time, the cooldown and the cast are read from the rows.
 */
class BattleHunterEvoTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String NET = "Hunter_EV1_net_attack";

  @TempDir static Path folder;

  /** The configured tables with the targets' radii written, and their records. */
  private static BattleRecords records;

  private static GameTables tables;

  @BeforeAll
  static void writeTheScene() throws IOException {
    tables =
        GameData.altered(
            folder,
            "characters",
            rows -> {
              GameData.columns(rows, "Musketeer").put("CollisionRadius", 500);
              GameData.columns(rows, "Giant").put("CollisionRadius", 750);
            });
    records = new BattleRecords(tables);
  }

  @Test
  @DisplayName(
      "the net is thrown at a Musketeer as it comes into range and snares it with the small snare"
          + " (its radius 500) for the snare's time")
  void theNetSnaresAMusketeerWithTheSmallSnare() {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    Battle battle = match.getBattle();
    CharacterEntity hunter = match.deploy(0, records.unit("Hunter_EV1"), LEVEL, 0, 14500, 16440);
    CharacterEntity musketeer = match.deploy(0, records.unit("Musketeer"), LEVEL, 1, 14500, 21499);
    Map<Integer, Integer> shotAt = new LinkedHashMap<>();
    List<Integer> snared = new ArrayList<>();
    run(battle, hunter, musketeer, "Hunter_EV1_bear_trap_snare_small", shotAt, snared);

    assertThat(shotAt).as("a net thrown").isNotEmpty();
    assertThat(snared).as("the net's hit snares the Musketeer").isNotEmpty();
    assertThat(snared.get(0)).isGreaterThan(shotAt.values().iterator().next());
    // The snare's time in steps, a part step counting as one (3000 ms is 60).
    int snareTime = Shipped.number("Hunter_EV1_apply_snare_small", "SpawnTime");
    assertThat(snared.get(snared.size() - 1) - snared.get(0) + 1)
        .as("for the snare's time, then the snare is gone")
        .isEqualTo((snareTime + 49) / 50);
  }

  @Test
  @DisplayName(
      "a Giant (radius 750) takes the large snare, and the next net waits the cooldown, the attack"
          + " gate and the cast")
  void theNextNetWaitsItsCooldown() {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    Battle battle = match.getBattle();
    CharacterEntity hunter = match.deploy(0, records.unit("Hunter_EV1"), LEVEL, 0, 14500, 16440);
    CharacterEntity giant = match.deploy(0, records.unit("Giant"), LEVEL, 1, 14500, 21499);
    Map<Integer, Integer> shotAt = new LinkedHashMap<>();
    List<Integer> snared = new ArrayList<>();
    HunterNetAttack.Run net =
        run(battle, hunter, giant, "Hunter_EV1_bear_trap_snare_large", shotAt, snared);

    assertThat(net).as("the net attack runs from the start").isNotNull();
    assertThat(shotAt).as("two nets thrown").hasSizeGreaterThanOrEqualTo(2);
    assertThat(snared).as("the first net's hit snares the Giant").isNotEmpty();
    List<Integer> ticks = new ArrayList<>(shotAt.values());
    assertThat(ticks.get(1) - ticks.get(0))
        .as("the cooldown, then the cast of 200 after the gates let it start")
        .isGreaterThanOrEqualTo(
            (Shipped.number(NET, "Cooldown") + Shipped.number(NET, "TrapCastTime")) / 50);
  }

  /**
   * Steps the battle for 200 ticks or until the target dies, recording each net's tick and every
   * tick the target carries the snare; answers the net attack's run.
   */
  private static HunterNetAttack.Run run(
      Battle battle,
      CharacterEntity hunter,
      CharacterEntity target,
      String snare,
      Map<Integer, Integer> shotAt,
      List<Integer> snared) {
    HunterNetAttack.Run net = null;
    for (int tick = 0; tick < 200 && target.getHitPoints().getHitPoints() > 0; tick++) {
      battle.step();
      for (ActionInstance instance : new ArrayList<>(hunter.actionHolder().running())) {
        if (instance instanceof HunterNetAttack.Run run) {
          net = run;
        }
      }
      if (net != null) {
        for (int id : net.shots()) {
          shotAt.putIfAbsent(id, tick);
        }
      }
      if (target.getBuffs().carries(snare)) {
        snared.add(tick);
      }
    }
    return net;
  }
}
