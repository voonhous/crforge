package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.crforge.core.battle.spawn.SpawnHost;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A dying entity's death hooks: which of its row's two hook actions it schedules on itself, with
 * what as their cause, when they run, and the deaths the battle refuses rather than guesses.
 *
 * <p>The unit is the Tombstone's row given a death action that builds, IceGolemiteDeathExplosion,
 * which spawns an area effect on it, Skeleton Warriors for its spawner and no death spawn. Where a
 * killed action is needed, the unit is given the same row as its killed action too. The kill is
 * dealt from an observer inside the tick, after its pre-pass and before any pending pass, unless a
 * test says otherwise.
 */
class BattleDeathHookTest {

  private static final String DEATH = "IceGolemiteDeathExplosion";

  /** The Tombstone's row with the death action, a spawner of Skeleton Warriors, no death spawn. */
  private static UnitData tombstone() {
    return GameData.unit("Tombstone").toBuilder()
        .onDeathAction(DEATH)
        .spawnCharacter("SkeletonWarrior")
        .deathSpawnCharacter(null)
        .deathSpawnCount(0)
        .build();
  }

  /** The Tombstone's row with its death action as its killed action too. */
  private static UnitData tombstoneKilledToo() {
    return tombstone().toBuilder().onKilledAction(DEATH).build();
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
      "a unit killed by another schedules its death action from its death slot, itself the cause,"
          + " then its killed action, the killer the cause; the kill lands at the damage drain and"
          + " both run in the pending pass after it")
  void killedByAnother() {
    Setup s = new Setup(tombstoneKilledToo(), 3500, 25000);
    s.stepWith(world -> world.kill(s.tombstone, s.knight));

    assertThat(s.scheduled)
        .containsExactly(
            "Tombstone by Tombstone side 0 [%s] pending false".formatted(DEATH),
            "Tombstone by Knight side 1 [%s] pending false".formatted(DEATH));
    assertThat(s.runs).containsExactly(DEATH + " 3", DEATH + " 3");
  }

  @Test
  @DisplayName("a unit that kills itself schedules its death action alone")
  void killedByItself() {
    Setup s = new Setup(tombstoneKilledToo(), 3500, 25000);
    s.stepWith(world -> world.kill(s.tombstone, s.tombstone));

    assertThat(s.scheduled)
        .containsExactly("Tombstone by Tombstone side 0 [%s] pending false".formatted(DEATH));
    assertThat(s.runs).containsExactly(DEATH + " 3");
  }

  @Test
  @DisplayName(
      "a kill action run inside a pending pass lands at the damage drain, and the death's hooks"
          + " run in the pending pass after it")
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
            "Tombstone by Tombstone side 0 [%s] pending false".formatted(DEATH),
            "Tombstone by Knight side 1 [%s] pending false".formatted(DEATH));
    assertThat(s.runs).containsExactly("kill 1", DEATH + " 3", DEATH + " 3");
  }

  @Test
  @DisplayName(
      "a unit killed with no attacker runs its death action from its slot and schedules no killed"
          + " action, as the death has no killing side")
  void noAttackerSchedulesNoKilledAction() {
    Setup s = new Setup(tombstoneKilledToo(), 3500, 25000);
    s.stepWith(world -> world.dealDamage(s.tombstone.getTargetView(), 1000, 0, 1));

    assertThat(s.scheduled)
        .containsExactly("Tombstone by Tombstone side 0 [%s] pending false".formatted(DEATH));
  }

  @Test
  @DisplayName("a death's damage lands on an enemy within its radius")
  void deathDamageLands() {
    // No configured row deals a death damage; the Tombstone is given 500 over 3000, and the Knight
    // stands 2500 from it.
    Setup s =
        new Setup(
            tombstone().toBuilder().deathDamage(500).deathDamageRadius(3000).build(), 14500, 20100);
    int before = s.knight.getHitPoints().getHitPoints();
    s.stepWith(world -> world.kill(s.tombstone, s.knight));
    // The kill lands at the step's damage drain; the death's share, queued as the drain deals the
    // kill, waits for the next step's.
    assertThat(s.knight.getHitPoints().getHitPoints()).isEqualTo(before);
    s.battle.step();

    assertThat(s.knight.getHitPoints().getHitPoints())
        .as("the Tombstone's 500 at its level 1")
        .isEqualTo(before - 500);
    // Its death action alone: the row has no killed action.
    assertThat(s.scheduled).hasSize(1);
  }

  @Test
  @DisplayName("a death spawn's ring turns by the row's angle shift and the dying unit's facing")
  void aDeathSpawnRingTurnsByTheFacing() {
    // The Battle Ram, facing up the arena (heading 90), spawns two Barbarians at 600 with an
    // angle shift of 180: the ring angles 180 and 0 turn by 270, to 90 and 270, so one stands in
    // front of it and one behind.
    Setup s = new Setup(GameData.unit("BattleRam"), 3500, 25000);
    List<int[]> made = new ArrayList<>();
    s.match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void characterSpawned(
                  int tick, SpawnHost source, CharacterEntity child, int x, int y) {
                made.add(new int[] {x, y});
              }
            });
    s.stepWith(world -> world.kill(s.tombstone, s.knight));

    assertThat(made).containsExactly(new int[] {14500, 18200}, new int[] {14500, 17000});
  }

  @Test
  @DisplayName("a spawner's child that carries a shield is made with it full")
  void aShieldedChildStartsWithAFullShield() {
    Setup s = new Setup(tombstone(), 3500, 25000);
    CharacterEntity child = null;
    for (int tick = 1; tick <= 25 && child == null; tick++) {
      s.battle.step();
      for (BattleEntity entity : s.battle.getHolder().entities()) {
        if (entity instanceof CharacterEntity c && c.getData().name().equals("SkeletonWarrior")) {
          child = c;
        }
      }
    }
    assertThat(child).isNotNull();
    assertThat(child.getHitPoints().getShield()).isPositive();
    assertThat(child.getHitPoints().getShield()).isEqualTo(child.getHitPoints().getShieldMaximum());
  }
}
