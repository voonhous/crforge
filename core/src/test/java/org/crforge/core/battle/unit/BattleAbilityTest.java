/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.TargetLocks;
import org.crforge.core.battle.action.CollectFriends;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a request for a unit's ability does, and what it refuses. */
class BattleAbilityTest {

  private static Standard1v1Battle passiveTowers() {
    return new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
  }

  @Test
  @DisplayName(
      "a request while the unit deploys is left pending and cast on the visit after the deploy")
  void aRequestWhileDeployingWaits(@TempDir Path folder) throws IOException {
    // The Giant Buffer's collector names a filter of own troops the test writes, so the unit is
    // built whether or not the tables hold the filter row its own collector names.
    GameTables tables = GameData.giantBufferWithTestFilter(folder);
    Standard1v1Battle match = new Standard1v1Battle(tables, Standard1v1Battle.DEFAULT_LEVEL, false);
    // A Giant Buffer alone: its collector finds no friend, so only this request casts.
    CharacterEntity buffer =
        match.deploy(
            0,
            new BattleRecords(tables).unit("GiantBuffer"),
            Standard1v1Battle.DEFAULT_LEVEL,
            0,
            3500,
            9500);
    List<String> log = new ArrayList<>();
    int[] tick = {0};
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void abilityRequested(int t, CharacterEntity unit, boolean now) {
                log.add(t + " requested " + (now ? "now" : "pending"));
              }

              @Override
              public void abilityFired(int t, CharacterEntity unit) {
                log.add(t + " fired");
              }
            });
    for (; tick[0] < 10; tick[0]++) {
      match.getBattle().step();
    }
    buffer.requestAbility();
    assertThat(buffer.getUnit().timers().isAbilityReady()).as("pending").isTrue();

    // The deploy ends in a visit whose own gate still finds the targeting component off.
    int deployEnd = -1;
    while (deployEnd < 0) {
      match.getBattle().step();
      tick[0]++;
      if (buffer.getView().getState() != GridEntityState.DEPLOYING) {
        deployEnd = tick[0];
      }
    }
    assertThat(buffer.getView().getState()).isNotEqualTo(GridEntityState.CASTING);

    match.getBattle().step();
    tick[0]++;
    assertThat(buffer.getView().getState()).as("cast on the next visit").isEqualTo(10);
    assertThat(buffer.getUnit().timers().isAbilityReady()).isFalse();
    int cast = tick[0];

    // It casts for its row's CastTime, in whole visits counting the one it entered in.
    int castTime =
        Shipped.number(
            Shipped.row(
                "character_abilities", Shipped.text(Shipped.unitRow("GiantBuffer"), "Ability")),
            "CastTime");
    while (buffer.getView().getState() == GridEntityState.CASTING) {
      match.getBattle().step();
      tick[0]++;
    }
    assertThat(tick[0] - cast).isEqualTo(castTime / 50 - 1);
    // The battle numbers its first step 0, so the observer's ticks run one behind the steps: the
    // request came after the tenth step, and the effect fired on the visit the cast began.
    assertThat(log).containsExactly("9 requested pending", (cast - 1) + " fired");
  }

  @Test
  @DisplayName(
      "a collector's lock is granted after the phase-3 pass and dropped once its friend has died")
  void aLockGoesWithItsFriend(@TempDir Path folder) throws IOException {
    // The collector names a filter of own troops the test writes. Its first look waits 1000 ms
    // from the unit's start, and it searches 7000 around it, as the test writes them.
    GameTables tables = collectorTables(folder, 2, filter -> {});
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = new Standard1v1Battle(tables, Standard1v1Battle.DEFAULT_LEVEL, false);
    CharacterEntity knight =
        match.deploy(0, records.unit("Knight"), Standard1v1Battle.DEFAULT_LEVEL, 0, 3500, 12500);
    CharacterEntity buffer =
        match.deploy(
            0, records.unit("GiantBuffer"), Standard1v1Battle.DEFAULT_LEVEL, 0, 3500, 9500);
    // The collector first looks on the twenty-first step, and the locks grant in its post-pass.
    for (int step = 0; step < 21; step++) {
      match.getBattle().step();
    }
    BattleWorld world = match.getWorld();
    assertThat(world.locks().claim(buffer.getId(), knight.getId(), 1)).isTrue();

    world.getHits().kill(knight, null);
    // The kill lands at the next step's damage drain and the death's cleanup takes it out of the
    // live list; the pre-pass of the step after drops its lock.
    match.getBattle().step();
    match.getBattle().step();

    assertThat(world.locks().claim(buffer.getId(), knight.getId(), 1)).isFalse();
  }

  @Test
  @DisplayName(
      "a collector whose filter names a row the tables do not hold is refused, naming the row")
  void aCollectorWithAMissingFilterIsRefused(@TempDir Path folder) throws IOException {
    // A name no filter table holds. The game's loader would read it as no filter, whose query
    // lists nothing, where the game's own data may define the row outside the tables: refused
    // rather than read as a collector that never finds a friend.
    String collector = collectorName();
    GameTables tables =
        GameData.altered(
            folder,
            "actions",
            rows -> GameData.fields(rows, collector).put("TargetFilter", "NoSuchFilter"));
    Standard1v1Battle match = new Standard1v1Battle(tables, Standard1v1Battle.DEFAULT_LEVEL, false);
    match.deploy(
        0,
        new BattleRecords(tables).unit("GiantBuffer"),
        Standard1v1Battle.DEFAULT_LEVEL,
        0,
        3500,
        9500);

    // The unit's starting action, the collector, is built as the first step admits it.
    assertThatThrownBy(() -> match.getBattle().step())
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("NoSuchFilter");
  }

  @Test
  @DisplayName(
      "a collector's filter leaves out the friends of the rows it excludes: the nearer friend of"
          + " an excluded row is passed over for the farther one")
  void aCollectorSkipsAnExcludedFriend(@TempDir Path folder) throws IOException {
    // One friend at most, the Archer nearer than the Knight: with no exclusion the collector asks
    // for the Archer; with the Archer's row excluded, for the Knight.
    assertThat(lockedFriends(folder.resolve("plain"), filter -> {}))
        .as("no exclusion")
        .containsExactly("Archer");
    assertThat(
            lockedFriends(
                folder.resolve("excluding"),
                filter -> filter.putArray("ExcludeCharactersWithData").add("Archer")))
        .as("the Archer excluded")
        .containsExactly("Knight");
  }

  @Test
  @DisplayName(
      "hit_timer, asked by the collector's buff action on its walking unit, answers its attack"
          + " timer, 0: a hold on hit_timer != 0, as the Rune Giant's row writes it, lets the unit"
          + " walk on through its buff")
  void hitTimerOnAWalkingCollector(@TempDir Path folder) throws IOException {
    // The collector's action as its delay ends: the variable set to hit_timer + 1000, then a hold
    // of 600 ms that runs only while hit_timer is not 0 and stops once it is above 0.
    String onBuff = "TestOnBuff";
    String record = "TestRecordHitTimer";
    String hold = "TestHoldOnBuff";
    collectorTables(folder, 1, filter -> {});
    GameData.alterLoaded(
        folder,
        "actions",
        rows -> {
          GameData.fields(rows, collectorName()).putObject("OnBuffAction").put("action", onBuff);
          ArrayNode subActions = action(rows, onBuff, "ActionGroup").putArray("SubActions");
          subActions.addObject().put("action", record);
          subActions.addObject().put("action", hold);
          action(rows, record, "ActionSetVariable")
              .put("Variable", GameData.TEST_VARIABLE)
              .put("Value", "hit_timer + 1000");
          action(rows, hold, "ActionWithDuration")
              .put("ActionDuration", 600)
              .put("ExecuteIfTrue", "hit_timer != 0")
              .put("ForceStopIfTrue", "hit_timer > 0")
              .put("GameTagsToSet", "NO_MOVE");
        });
    GameData.addTestVariable(folder);
    GameTables tables = GameTables.load(folder);
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = new Standard1v1Battle(tables, Standard1v1Battle.DEFAULT_LEVEL, false);
    match.deploy(0, records.unit("Knight"), Standard1v1Battle.DEFAULT_LEVEL, 0, 3500, 12500);
    CharacterEntity buffer =
        match.deploy(
            0, records.unit("GiantBuffer"), Standard1v1Battle.DEFAULT_LEVEL, 0, 3500, 9500);
    int key = match.getWorld().variableKey(GameData.TEST_VARIABLE);
    int buffStep = -1;
    List<Integer> still = new ArrayList<>();
    for (int step = 0; step < 60; step++) {
      int y = buffer.getView().getY();
      match.getBattle().step();
      if (buffStep < 0 && buffer.variable(key) != 0) {
        buffStep = step;
      }
      if (buffStep >= 0 && step < buffStep + 12 && buffer.getView().getY() == y) {
        still.add(step);
      }
    }

    assertThat(buffStep).as("the buff's step").isPositive();
    assertThat(buffer.variable(key)).as("hit_timer + 1000").isEqualTo(1000);
    assertThat(still).as("steps it stood in the 600 ms after its buff").isEmpty();
  }

  /** An action row of a class written by the test, its fields to fill. */
  private static ObjectNode action(ObjectNode rows, String name, String type) {
    ObjectNode row = rows.putObject(name);
    row.put("class", "Logic" + type + "Data");
    row.put("ClassType", type);
    ObjectNode fields = row.putObject("fields");
    fields.put("ClassType", type);
    return fields;
  }

  /**
   * The friends a lone Giant Buffer's collector of at most one friend holds a lock on at its first
   * look, with an Archer 1500 ahead of it and a Knight 3000 ahead, its filter written by the test.
   */
  private static List<String> lockedFriends(Path folder, Consumer<ObjectNode> filter)
      throws IOException {
    Files.createDirectories(folder);
    GameTables tables = collectorTables(folder, 1, filter);
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = new Standard1v1Battle(tables, Standard1v1Battle.DEFAULT_LEVEL, false);
    CharacterEntity knight =
        match.deploy(0, records.unit("Knight"), Standard1v1Battle.DEFAULT_LEVEL, 0, 3500, 12500);
    CharacterEntity archer =
        match.deploy(0, records.unit("Archer"), Standard1v1Battle.DEFAULT_LEVEL, 0, 3500, 11000);
    CharacterEntity buffer =
        match.deploy(
            0, records.unit("GiantBuffer"), Standard1v1Battle.DEFAULT_LEVEL, 0, 3500, 9500);
    // The collector first looks on the twenty-first step, and the locks grant in its post-pass.
    for (int step = 0; step < 21; step++) {
      match.getBattle().step();
    }
    TargetLocks locks = match.getWorld().locks();
    List<String> locked = new ArrayList<>();
    for (CharacterEntity friend : List.of(archer, knight)) {
      if (locks.claim(buffer.getId(), friend.getId(), CollectFriends.CHANNEL)) {
        locked.add(friend.name());
      }
    }
    return locked;
  }

  /** The friend collector the Giant Buffer's row starts with. */
  private static String collectorName() {
    return GameData.tables().table("characters").row("GiantBuffer").string("OnStartingAction");
  }

  /**
   * The configured tables copied into a folder with the Giant Buffer's collector naming {@link
   * GameData#TEST_FILTER}, written with the given columns, its first look 1000 ms from the unit's
   * start, its search 7000 around it and the given number of friends at most.
   */
  private static GameTables collectorTables(
      Path folder, int maxFriends, Consumer<ObjectNode> filter) throws IOException {
    String collector = collectorName();
    GameData.altered(
        folder,
        "actions",
        rows ->
            GameData.fields(rows, collector)
                .put("TargetFilter", GameData.TEST_FILTER)
                .put("ActionDelay", 1000)
                .put("DistanceToGetTargets", 7000)
                .put("MaxFriendlyTroops", maxFriends));
    GameData.addTestFilter(folder, filter);
    return GameTables.load(folder);
  }

  @Test
  @DisplayName("a request for an ability with an effect the battle does not model is refused")
  void aRichAbilityIsRefused(@TempDir Path folder) throws IOException {
    // The Monk's ability given a morph, which no shipped ability has.
    GameTables tables =
        GameData.altered(
            folder,
            "character_abilities",
            rows -> GameData.columns(rows, "Deflect").put("MorphTarget", "Knight"));
    Standard1v1Battle match = new Standard1v1Battle(tables, Standard1v1Battle.DEFAULT_LEVEL, false);
    CharacterEntity monk =
        match.deploy(
            0,
            new BattleRecords(tables).unit("Monk"),
            Standard1v1Battle.DEFAULT_LEVEL,
            0,
            3500,
            9500);

    assertThatThrownBy(monk::requestAbility)
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(
            "Monk casts Deflect, which sets columns the battle does not model: [MorphTarget]");
  }

  @Test
  @DisplayName(
      "a request for an ability whose row sets a column the battle neither reads nor classifies is"
          + " refused, naming the column")
  void anAbilityWithAnUnreadColumnIsRefused(@TempDir Path folder) throws IOException {
    // The Monk's ability given a column of the game's ability loader that no shipped ability
    // sets and the battle does not read.
    GameTables tables =
        GameData.altered(
            folder,
            "character_abilities",
            rows -> GameData.columns(rows, "Deflect").put("DashTargetFurthest", true));
    Standard1v1Battle match = new Standard1v1Battle(tables, Standard1v1Battle.DEFAULT_LEVEL, false);
    CharacterEntity monk =
        match.deploy(
            0,
            new BattleRecords(tables).unit("Monk"),
            Standard1v1Battle.DEFAULT_LEVEL,
            0,
            3500,
            9500);

    assertThatThrownBy(monk::requestAbility)
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(
            "Monk casts Deflect, which sets columns the battle does not model:"
                + " [DashTargetFurthest]");
  }
}
