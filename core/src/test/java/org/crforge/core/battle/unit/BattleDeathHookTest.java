package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionRow;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.Kill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A dying entity's death hooks: which of its row's two hook actions it schedules on itself, with
 * what as their cause, when they run, and the deaths the battle refuses rather than guesses.
 *
 * <p>Tombstone_crazy_1 is the one shipped unit whose death hook builds: its death action spawns
 * SkeletonKing on it. Where a killed action is needed, the unit is given the same row as its killed
 * action too. The kill is dealt from an observer inside the tick, after its pre-pass and before any
 * pending pass, unless a test says otherwise.
 */
class BattleDeathHookTest {

  private static final String DEATH = "Tombstone_crazy_1_OnDeathAction";

  /** The Tombstone's row with its death action as its killed action too. */
  private static UnitData tombstoneKilledToo() {
    return GameData.unit("Tombstone_crazy_1").toBuilder().onKilledAction(DEATH).build();
  }

  /** A battle with the towers standing still, a Tombstone for the bottom side and a far Knight. */
  private static final class Setup {
    final Standard1v1Battle match =
        new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
    final Battle battle = match.getBattle();
    final CharacterEntity tombstone;
    final CharacterEntity knight;
    final List<String> scheduled = new ArrayList<>();
    final List<String> runs = new ArrayList<>();

    Setup(UnitData tombstoneData, int knightX, int knightY) {
      tombstone = match.deploy(0, tombstoneData, 1, 0, 14500, 17600, "Tombstone");
      knight = match.deploy(0, GameData.unit("Knight"), 11, 1, knightX, knightY, "Knight");
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void deathHooksScheduled(
                    int tick,
                    WorldEntity dying,
                    BattleEntity attacker,
                    int side,
                    List<String> hooks,
                    boolean inPendingPass) {
                  String cause = attacker instanceof WorldEntity w ? w.name() : "none";
                  scheduled.add(
                      "%s by %s side %d %s pending %s"
                          .formatted(dying.name(), cause, side, hooks, inPendingPass));
                }
              });
      battle.step();
      tombstone
          .actionHolder()
          .setListener(
              new ActionHolder.Listener() {
                @Override
                public void started(BattleAction action, int phase) {
                  runs.add(action.name() + " " + phase);
                }
              });
    }

    /** Runs one step, dealing the given blow right after its pre-pass. */
    void stepWith(Consumer<BattleWorld> blow) {
      boolean[] dealt = {false};
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void afterPrePass(int tick, List<WorldEntity> present) {
                  if (!dealt[0]) {
                    dealt[0] = true;
                    blow.accept(match.getWorld());
                  }
                }
              });
      battle.step();
    }
  }

  @Test
  @DisplayName(
      "a unit killed by another schedules its death action, then its killed action, with the"
          + " killer as their cause, and both run in the next pending pass")
  void killedByAnother() {
    Setup s = new Setup(tombstoneKilledToo(), 3500, 25000);
    s.stepWith(world -> world.kill(s.tombstone, s.knight));

    assertThat(s.scheduled)
        .containsExactly(
            "Tombstone by Knight side 1 [%s, %s] pending false".formatted(DEATH, DEATH));
    assertThat(s.runs).containsExactly(DEATH + " 1", DEATH + " 1");
  }

  @Test
  @DisplayName("a unit that kills itself schedules its death action alone")
  void killedByItself() {
    Setup s = new Setup(tombstoneKilledToo(), 3500, 25000);
    s.stepWith(world -> world.kill(s.tombstone, s.tombstone));

    assertThat(s.scheduled)
        .containsExactly("Tombstone by Tombstone side 0 [%s] pending false".formatted(DEATH));
    assertThat(s.runs).containsExactly(DEATH + " 1");
  }

  @Test
  @DisplayName(
      "a unit killed inside a pending pass runs its death action at once, inside that pass")
  void killedInsideAPendingPass() {
    Setup s = new Setup(tombstoneKilledToo(), 3500, 25000);
    s.stepWith(
        world ->
            s.tombstone
                .actionHolder()
                .schedule(
                    new Kill(ActionRow.named("kill"), null),
                    ActionHolder.OWN_DELAY,
                    false,
                    s.knight.actionHolder()));

    assertThat(s.scheduled)
        .containsExactly(
            "Tombstone by Knight side 1 [%s, %s] pending true".formatted(DEATH, DEATH));
    // The holder tells its listener of a run once the action's start returns, so both hooks, which
    // ran inside the kill's start, are told of before the kill itself.
    assertThat(s.runs).containsExactly(DEATH + " 1", DEATH + " 1", "kill 1");
  }

  @Test
  @DisplayName("a death hook with no attacker is refused: its cause would carry a side alone")
  void noAttackerIsRefused() {
    Setup s = new Setup(GameData.unit("Tombstone_crazy_1"), 3500, 25000);
    assertThatThrownBy(
            () -> s.stepWith(world -> world.dealDamage(s.tombstone.getTargetView(), 1000, 0, 1)))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("no attacker");
  }

  @Test
  @DisplayName("a death hook with no pending pass of the tick ahead of it is refused")
  void noPendingPassAheadIsRefused() {
    Setup s = new Setup(GameData.unit("Tombstone_crazy_1"), 3500, 25000);
    assertThatThrownBy(() -> s.match.getWorld().kill(s.tombstone, s.knight))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("pending pass");
  }

  @Test
  @DisplayName("a death's damage lands on an enemy within its radius, before the death hooks")
  void deathDamageLands() {
    // The Knight stands 2500 from the Tombstone, inside its 3000 death damage radius.
    Setup s = new Setup(GameData.unit("Tombstone_crazy_1"), 14500, 20100);
    int before = s.knight.getHitPoints().getHitPoints();
    s.stepWith(world -> world.kill(s.tombstone, s.knight));

    assertThat(s.knight.getHitPoints().getHitPoints())
        .as("the Tombstone's 500 at its level 1")
        .isEqualTo(before - 500);
    assertThat(s.scheduled).hasSize(1);
  }

  @Test
  @DisplayName("the death of a unit whose death spawn pushes its children is refused")
  void anUnmodelledDeathIsRefused() {
    Setup s = new Setup(GameData.unit("Golem"), 3500, 25000);
    assertThatThrownBy(() -> s.stepWith(world -> world.kill(s.tombstone, s.knight)))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("DeathSpawnPushback");
  }

  @Test
  @DisplayName("a building whose deploy ends is refused: a deployed building is not modelled")
  void aDeployedBuildingIsRefused() {
    Setup s = new Setup(GameData.unit("Tombstone_crazy_1"), 3500, 25000);
    assertThatThrownBy(
            () -> {
              for (int tick = 1; tick <= 25; tick++) {
                s.battle.step();
              }
            })
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("Tombstone is a building whose deploy has ended");
  }
}
