package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The evolved Minion Horde's minion (MinionHorde_EV1 of data version 16.402.18): its row's
 * OnDamageTakenAction, MinionHorde_EV1_OnDamage_Group, runs on the minion itself after a hit has
 * taken hit points off it. The group's gate lets it through once, while the minion's variable is
 * still 0: it gives the minion MinionHorde_EV1_GhostBuff for its SpawnTime and sets the variable.
 * The buff makes the minion invisible and raises NO_DAMAGE in its tag word, so nothing lands on it
 * while the buff lasts; a hit after it lands again, and gives no second buff.
 */
class MinionHordeEvolutionTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String GHOST_BUFF = "MinionHorde_EV1_GhostBuff";

  /** A buff listed or taken off the minion, at its tick. */
  private record BuffEvent(int tick, String buff, boolean applied) {}

  /** The action that gives the minion its ghost buff. */
  private static final String APPLY_GHOST_BUFF = "MinionHorde_EV1_ApplyGhostBuff";

  @Test
  @DisplayName("the minion's first hit makes it a ghost for its buff's time, and only the first")
  void theFirstHitMakesTheMinionAGhost() {
    GameTables tables = GameData.tables();
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    List<BuffEvent> minionBuffs = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void buffApplied(int at, WorldEntity target, BuffInstance buff) {
                if (target.name().equals("minion")) {
                  minionBuffs.add(new BuffEvent(at, buff.getBuff().name(), true));
                }
              }

              @Override
              public void buffRemoved(int at, WorldEntity target, BuffInstance buff) {
                if (target.name().equals("minion")) {
                  minionBuffs.add(new BuffEvent(at, buff.getBuff().name(), false));
                }
              }
            });
    CharacterEntity minion =
        match.deploy(0, records.unit("MinionHorde_EV1"), LEVEL, 0, 3500, 14000, "minion");
    match.deploy(0, records.unit("Archer"), LEVEL, 1, 3500, 19000, "archer");
    // The ticks on which the minion lost hit points.
    List<Integer> losses = new ArrayList<>();
    int before = -1;
    for (int step = 0; step < 400 && minion.getHitPoints().getHitPoints() > 0; step++) {
      match.getBattle().step();
      int now = minion.getHitPoints().getHitPoints();
      if (before > 0 && now < before) {
        losses.add(match.getWorld().tick());
      }
      before = now;
    }

    assertThat(losses).as("the ticks the minion lost hit points on").hasSizeGreaterThanOrEqualTo(2);
    List<BuffEvent> ghost = minionBuffs.stream().filter(b -> b.buff().equals(GHOST_BUFF)).toList();
    assertThat(ghost)
        .as("the ghost buff, listed once and taken off once")
        .extracting(BuffEvent::applied)
        .containsExactly(true, false);
    int first = losses.get(0);
    int from = ghost.get(0).tick();
    int to = ghost.get(1).tick();
    assertThat(from)
        .as("the buff follows the first loss within a tick")
        .isBetween(first, first + 1);
    // The buff's time counts down 50 a step and it is taken off on the step that ends it.
    int spawnTime = Shipped.number(APPLY_GHOST_BUFF, "SpawnTime");
    assertThat(to - from).as("the buff lasts its SpawnTime").isEqualTo((spawnTime + 49) / 50);
    assertThat(losses.stream().filter(t -> t > first && t <= to))
        .as("nothing lands on the ghost")
        .isEmpty();
    assertThat(losses.get(1)).as("a later hit lands again").isGreaterThan(to);
  }
}
