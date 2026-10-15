/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
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
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

  /** The packed level the poison is applied at. */
  private static final int POISON_LEVEL = 10;

  /** The Knight's collision radius, written into its row. */
  private static final int KNIGHT_RADIUS = 500;

  /** The radius of the area the death action spawns, written into its row. */
  private static final int DEATH_AREA_RADIUS = 2000;

  @TempDir static Path tablesFolder;

  /**
   * The configured tables with the Knight's radius and the death action's area written: a circle of
   * 2000 dealing 33, which a Knight 2500 away is not within.
   */
  private static GameTables tables;

  @BeforeAll
  static void writeTheRows() throws IOException {
    GameData.altered(
        tablesFolder,
        "characters",
        rows -> GameData.columns(rows, "Knight").put("CollisionRadius", KNIGHT_RADIUS));
    GameData.alterLoaded(
        tablesFolder,
        "area_effect_objects",
        rows -> {
          ObjectNode area = GameData.columns(rows, "IceGolemiteDeathArea");
          area.put("Radius", DEATH_AREA_RADIUS);
          area.putObject("Damage").put("BaseDamage", 33);
        });
    tables = GameTables.load(tablesFolder);
  }

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
        new Standard1v1Battle(tables, Standard1v1Battle.DEFAULT_LEVEL, false);
    final Battle battle = match.getBattle();
    final CharacterEntity tombstone;
    final CharacterEntity knight;
    final List<String> scheduled = new ArrayList<>();
    final List<String> runs = new ArrayList<>();

    Setup(UnitData tombstoneData, int knightX, int knightY) {
      this(tombstoneData, "Knight", knightX, knightY);
    }

    /** The same, with the far unit of the top side of the given row, named by it. */
    Setup(UnitData tombstoneData, String knightRow, int knightX, int knightY) {
      tombstone = match.deploy(0, tombstoneData, 1, 0, 14500, 17600, "Tombstone");
      knight =
          match.deploy(
              0, match.getWorld().getRecords().unit(knightRow), 11, 1, knightX, knightY, knightRow);
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

  /**
   * Gives the Setup's unit 1 hit point and a Poison applied for the top side with no source, and
   * steps until the poison has killed it.
   */
  private static void poisonToDeath(Setup s) {
    poisonToDeath(s, null, 1);
  }

  /**
   * Gives the Setup's unit 1 hit point and a Poison applied by the given source for the given side,
   * and steps until the poison has killed it.
   */
  private static void poisonToDeath(Setup s, SpawnHost source, int side) {
    s.tombstone.getHitPoints().setHitPoints(1);
    s.tombstone
        .getBuffs()
        .apply(s.match.getWorld().getRecords().buff("Poison"), 5000, POISON_LEVEL, source, side);
    for (int i = 0; i < 60 && HitPoints.alive(s.tombstone.getHitPoints()); i++) {
      s.battle.step();
    }
    assertThat(HitPoints.alive(s.tombstone.getHitPoints())).isFalse();
  }

  @Test
  @DisplayName(
      "a unit a buff's damage over time kills schedules its killed action for the buff's side with"
          + " no cause, when its chain spawns from its owner and reads none; the spawn runs for"
          + " the dying unit's side")
  void aSideAloneKillRunsAChainThatReadsNoCause() {
    // The evolved Wall Breaker's own row: its killed action spawns from its owner, and so does the
    // action chained after it.
    UnitData wallBreaker = GameData.unit("Wallbreaker_EV1");
    Setup s = new Setup(wallBreaker, 3500, 25000);
    List<String> made = new ArrayList<>();
    s.match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void characterSpawned(
                  int tick, SpawnHost source, CharacterEntity child, int x, int y) {
                made.add("%s side %d".formatted(child.getData().name(), child.side()));
              }
            });

    poisonToDeath(s);
    s.battle.step();

    assertThat(s.scheduled)
        .containsExactly(
            "Tombstone by none side 1 [%s] pending false".formatted(wallBreaker.onKilledAction()));
    assertThat(s.runs).contains(wallBreaker.onKilledAction() + " 3");
    assertThat(made).isNotEmpty().allMatch(child -> child.endsWith(" side 0"));
  }

  @Test
  @DisplayName(
      "a unit a buff's damage over time kills has the buff's source for its killer: its killed"
          + " action is scheduled with the source as the cause, for the source's side")
  void aBuffKillHasTheBuffsSourceForItsKiller() {
    Setup s = new Setup(tombstoneKilledToo(), 3500, 25000);
    poisonToDeath(s, s.knight, 1);
    s.battle.step();

    assertThat(s.scheduled)
        .containsExactly(
            "Tombstone by Tombstone side 0 [%s] pending false".formatted(DEATH),
            "Tombstone by Knight side 1 [%s] pending false".formatted(DEATH));
    assertThat(s.runs).containsExactly(DEATH + " 3", DEATH + " 3");
  }

  @Test
  @DisplayName(
      "a unit a buff it applied itself, for its own side, kills is no killer of its own: its death"
          + " action runs alone")
  void aBuffKillByItsOwnBuffIsNoKill() {
    Setup s = new Setup(tombstoneKilledToo(), 3500, 25000);
    poisonToDeath(s, s.tombstone, 0);
    s.battle.step();

    assertThat(s.scheduled)
        .containsExactly("Tombstone by Tombstone side 0 [%s] pending false".formatted(DEATH));
  }

  @Test
  @DisplayName(
      "the Boss Bandit a buff's damage over time kills checks the buff's source as its killer:"
          + " its voice line runs when the source is a Bandit, and not for another source")
  void theBossBanditChecksTheBuffsSource() {
    UnitData bossBandit = GameData.unit("BossBandit");
    String voiceLine = "BossBandit_defeated_by_bandit_voiceline";

    // The Bandit's row is Assassin, the name the check matches.
    Setup byBandit = new Setup(bossBandit, "Assassin", 3500, 25000);
    poisonToDeath(byBandit, byBandit.knight, 1);
    byBandit.battle.step();
    assertThat(byBandit.runs)
        .contains(bossBandit.onKilledAction() + " 3")
        .anyMatch(run -> run.startsWith(voiceLine + " "));

    Setup byKnight = new Setup(bossBandit, 3500, 25000);
    poisonToDeath(byKnight, byKnight.knight, 1);
    byKnight.battle.step();
    assertThat(byKnight.runs)
        .contains(bossBandit.onKilledAction() + " 3")
        .noneMatch(run -> run.startsWith(voiceLine + " "));
  }

  @Test
  @DisplayName(
      "a killed action that reads its cause stays refused for a buff with no source, whose cause"
          + " would be a side alone: a spawn from its cause and a check of its killer")
  void aKilledActionThatReadsItsCauseIsRefusedWithNoAttacker() {
    // The death action spawns its area effect from its cause, as it has no ParentGOAsSource.
    Setup spawn = new Setup(tombstoneKilledToo(), 3500, 25000);
    assertThatThrownBy(() -> poisonToDeath(spawn))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("died with no attacker")
        .hasMessageContaining("reads its cause");

    // The Boss Bandit's own row: its killed action checks whether its killer is a Bandit.
    Setup check = new Setup(GameData.unit("BossBandit"), 3500, 25000);
    assertThatThrownBy(() -> poisonToDeath(check))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("died with no attacker")
        .hasMessageContaining("reads its cause");
  }

  @Test
  @DisplayName("a death's damage lands on an enemy within its radius")
  void deathDamageLands() {
    // No configured row deals a death damage; the Tombstone is given 500 over 3000, and the Knight
    // stands 2500 from it: within the death damage's radius, and at the edge of the death area's
    // radius plus its own, which the area does not reach.
    int distance = DEATH_AREA_RADIUS + KNIGHT_RADIUS;
    Setup s =
        new Setup(
            tombstone().toBuilder().deathDamage(500).deathDamageRadius(3000).build(),
            14500,
            17600 + distance);
    int before = s.knight.getHitPoints().getHitPoints();
    s.stepWith(world -> world.kill(s.tombstone, s.knight));
    // The kill lands at the step's damage drain; the death's share, queued as the drain deals the
    // kill, joins the end of the queue the drain is still reading and lands in the same drain.
    assertThat(s.knight.getHitPoints().getHitPoints())
        .as("the Tombstone's 500 at its level 1")
        .isEqualTo(before - 500);
    // Its death action alone: the row has no killed action.
    assertThat(s.scheduled).hasSize(1);
  }

  @Test
  @DisplayName("a death spawn's ring turns by the row's angle shift and the dying unit's facing")
  void aDeathSpawnRingTurnsByTheFacing() {
    // The Battle Ram's row, set to spawn two Barbarians at 600 with an angle shift of 180, facing
    // up the arena (heading 90): the ring angles 180 and 0 turn by 270, to 90 and 270, so one
    // stands in front of it and one behind.
    Setup s =
        new Setup(
            GameData.unit("BattleRam").toBuilder()
                .deathSpawnCharacter("Barbarian")
                .deathSpawnCount(2)
                .deathSpawnCharacter2(null)
                .deathSpawnCount2(0)
                .deathSpawnRadius(600)
                .deathSpawnMinRadius(0)
                .spawnAngleShift(180)
                .build(),
            3500,
            25000);
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
