package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.HunterNetAttack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The evolved Hunter: its starting action throws a net at the nearest enemy in its range on its own
 * cooldown, beside its ordinary attack, and the net's hit snares the target.
 */
class BattleHunterEvoTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  @Test
  @DisplayName(
      "the net is thrown at a Musketeer as it comes into range and snares it with the small snare"
          + " (its radius 500) for 3000")
  void theNetSnaresAMusketeerWithTheSmallSnare() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    Battle battle = match.getBattle();
    CharacterEntity hunter = match.deploy(0, GameData.unit("Hunter_EV1"), LEVEL, 0, 14500, 16440);
    CharacterEntity musketeer = match.deploy(0, GameData.unit("Musketeer"), LEVEL, 1, 14500, 21499);
    Map<Integer, Integer> shotAt = new LinkedHashMap<>();
    List<Integer> snared = new ArrayList<>();
    run(battle, hunter, musketeer, "Hunter_EV1_bear_trap_snare_small", shotAt, snared);

    assertThat(shotAt).as("a net thrown").isNotEmpty();
    assertThat(snared).as("the net's hit snares the Musketeer").isNotEmpty();
    assertThat(snared.get(0)).isGreaterThan(shotAt.values().iterator().next());
    assertThat(snared.get(snared.size() - 1) - snared.get(0) + 1)
        .as("for 3000, then the snare is gone")
        .isEqualTo(60);
  }

  @Test
  @DisplayName(
      "a Giant (radius 750) takes the large snare, and the next net waits the 5000 cooldown, the"
          + " attack gate and the 200 cast")
  void theNextNetWaitsItsCooldown() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    Battle battle = match.getBattle();
    CharacterEntity hunter = match.deploy(0, GameData.unit("Hunter_EV1"), LEVEL, 0, 14500, 16440);
    CharacterEntity giant = match.deploy(0, GameData.unit("Giant"), LEVEL, 1, 14500, 21499);
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
        .isGreaterThanOrEqualTo(100 + 4);
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
