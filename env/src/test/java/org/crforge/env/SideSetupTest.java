/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeSet;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.replay.ReplayScenario;
import org.crforge.core.battle.replay.ScenarioPlan;
import org.crforge.core.battle.replay.Scenarios;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.core.battle.unit.TowerEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A side's setup at reset: its tower troop, its king and princess levels and its card levels. The
 * tower selections, the level bounds and the spawn groups are read from the tables; the towers a
 * setup should stand are held to a battle the core builds directly with the same towers.
 */
class SideSetupTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final List<String> BASIC =
      List.of("Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Arrows", "Zap");

  private static final List<String> OTHER =
      List.of(
          "Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Arrows", "Goblins");

  private static GameTables tables;

  @BeforeAll
  static void loadTables() {
    tables = GameData.tables();
  }

  /** Every tower selection the core builds that the tables hold, other than the princess towers. */
  private static List<String> otherTowerSelections() {
    List<String> selections = new ArrayList<>();
    for (String name : new TreeSet<>(ReplayScenario.BUILT_TOWER_SELECTIONS)) {
      if (!name.equals(Standard1v1Battle.PRINCESS_TOWERS)
          && tables.table("support_cards").has(name)) {
        selections.add(name);
      }
    }
    return selections;
  }

  private static GameRow rarityOf(String table, GameRow row) {
    return tables.table(table).row(row.string("Rarity"));
  }

  /** The first and last level of a rarity, counted from 1 across all rarities. */
  private static int[] levels(GameRow rarity) {
    int relative = rarity.intValue("RelativeLevel");
    return new int[] {relative + 1, relative + rarity.intValue("LevelCount")};
  }

  private static GameRow deckCard(String name) {
    for (String table : Vocabulary.DECK_TABLES) {
      if (tables.table(table).has(name)) {
        return tables.table(table).row(name);
      }
    }
    throw new AssertionError("no deck card " + name);
  }

  /** Each card of a deck at the last level of its rarity. */
  private static int[] topLevels(List<String> deck) {
    int[] levels = new int[deck.size()];
    for (int i = 0; i < deck.size(); i++) {
      levels[i] = levels(rarityOf("rarities", deckCard(deck.get(i))))[1];
    }
    return levels;
  }

  /** The highest summoner level an exp level gives: the top king level. */
  private static int topKingLevel() {
    int top = 0;
    for (GameRow row : tables.table("exp_levels").rows()) {
      top = Math.max(top, row.intValue("SummonerLevel"));
    }
    return top;
  }

  /**
   * A setup of a tower selection at its top level, the top king level, a deck at its top levels.
   */
  private static SideSetup topSetup(String towers, List<String> deck) {
    GameRow selection = tables.table("support_cards").row(towers);
    int towerLevel = levels(rarityOf("support_rarities", selection))[1];
    return new SideSetup(towers, topKingLevel(), towerLevel, topLevels(deck));
  }

  private static VectorEnv vector(EnvConfig config) {
    return VectorEnv.ladder(tables, config, 1, 1);
  }

  private static void reset(VectorEnv env, int seed, SideSetup setup0, SideSetup setup1) {
    env.reset(
        new int[] {seed},
        BASIC.toArray(String[]::new),
        OTHER.toArray(String[]::new),
        null,
        null,
        new SideSetup[] {setup0},
        new SideSetup[] {setup1});
  }

  /** A side's towers as (row name, level), in the battle's order. */
  private static List<String> towers(Standard1v1Battle battle, int side) {
    List<String> out = new ArrayList<>();
    for (BattleEntity entity : battle.getBattle().getHolder().entities()) {
      if (entity instanceof TowerEntity tower && tower.side() == side) {
        out.add(tower.getData().name() + "@" + tower.level());
      }
    }
    return out;
  }

  /** A random open play of a seat, three decisions in ten, else the wait. */
  private static int randomAction(LadderEnv env, int seat, SplittableRandom random) {
    if (random.nextInt(10) >= 3) {
      return Schema.WAIT;
    }
    List<Integer> open = new ArrayList<>();
    for (int a = 1; a < Schema.ACTIONS; a++) {
      if (env.open(seat, a)) {
        open.add(a);
      }
    }
    return open.isEmpty() ? Schema.WAIT : open.get(random.nextInt(open.size()));
  }

  private static void play(LadderEnv env, int decisions, SplittableRandom random) {
    for (int d = 0; d < decisions && !env.done(); d++) {
      env.step(randomAction(env, 0, random), randomAction(env, 1, random));
    }
  }

  /** Both seats' observations, masks and info as an environment last wrote them. */
  private static int[] written(LadderEnv env) {
    int[] all = new int[2 * Schema.SEAT_INTS + 2 * Schema.ACTIONS + Schema.INFO_INTS];
    env.copyObservation(0, all, 0);
    env.copyObservation(1, all, Schema.SEAT_INTS);
    for (int seat = 0; seat < 2; seat++) {
      for (int a = 0; a < Schema.ACTIONS; a++) {
        all[2 * Schema.SEAT_INTS + seat * Schema.ACTIONS + a] = env.open(seat, a) ? 1 : 0;
      }
    }
    env.copyInfo(all, 2 * Schema.SEAT_INTS + 2 * Schema.ACTIONS);
    return all;
  }

  @Test
  void eachTowerSelectionStandsTheTowersTheCoreBuildsFromItsSpawnGroup() {
    List<String> selections = otherTowerSelections();
    assertThat(selections).as("tower troops besides the princess towers").isNotEmpty();
    for (String towers : selections) {
      SideSetup setup = topSetup(towers, OTHER);
      try (VectorEnv env = vector(EnvConfig.DEFAULT)) {
        reset(env, 5, null, setup);
        Standard1v1Battle battle = ((LadderEnv) env.env(0)).battle();

        String spawnGroup = tables.table("support_cards").row(towers).string("SpawnGroup");
        Standard1v1Battle.Towers expected =
            new Standard1v1Battle.Towers(spawnGroup, setup.kingLevel(), setup.towerLevel());
        Standard1v1Battle.Towers standard =
            new Standard1v1Battle.Towers(
                Standard1v1Battle.PRINCESS_TOWERS,
                EnvConfig.DEFAULT.towerLevel(),
                EnvConfig.DEFAULT.towerLevel());
        assertThat(battle.getTowers()).as(towers).containsExactly(standard, expected);
        Standard1v1Battle direct = new Standard1v1Battle(tables, List.of(standard, expected), true);
        assertThat(towers(battle, 1)).as(towers).isEqualTo(towers(direct, 1));
        assertThat(towers(battle, 0)).as(towers).isEqualTo(towers(direct, 0));
      }
    }
  }

  @Test
  void eachPlayOfADeckCardIsGivenAtTheCardsLevelInTheSetup() {
    int[] levels0 = topLevels(BASIC);
    int[] levels1 = topLevels(OTHER);
    // The levels differ from the configuration's, so a play given at it would show.
    assertThat(levels0).doesNotContain(EnvConfig.DEFAULT.level());
    SideSetup setup0 =
        new SideSetup(
            Standard1v1Battle.PRINCESS_TOWERS,
            EnvConfig.DEFAULT.towerLevel(),
            EnvConfig.DEFAULT.towerLevel(),
            levels0);
    SideSetup setup1 =
        new SideSetup(
            Standard1v1Battle.PRINCESS_TOWERS,
            EnvConfig.DEFAULT.towerLevel(),
            EnvConfig.DEFAULT.towerLevel(),
            levels1);
    try (VectorEnv env = vector(EnvConfig.DEFAULT)) {
      reset(env, 9, setup0, setup1);
      LadderEnv ladder = (LadderEnv) env.env(0);
      play(ladder, 600, new SplittableRandom(3));

      List<Standard1v1Battle.Command> plays = new ArrayList<>();
      for (Standard1v1Battle.Command command : ladder.battle().getCommands()) {
        if (command.kind() != Standard1v1Battle.CommandKind.ABILITY) {
          plays.add(command);
        }
      }
      assertThat(plays).as("plays of both sides").hasSizeGreaterThan(4);
      for (Standard1v1Battle.Command command : plays) {
        List<String> deck = command.side() == 0 ? BASIC : OTHER;
        int[] levels = command.side() == 0 ? levels0 : levels1;
        assertThat(command.level())
            .as("%s of side %d", command.card(), command.side())
            .isEqualTo(levels[deck.indexOf(command.card())]);
      }
    }
  }

  @Test
  void aWrittenReplayCarriesEachSidesTowersAndCardLevels() throws Exception {
    String towers = otherTowerSelections().get(0);
    SideSetup setup1 = topSetup(towers, OTHER);
    EnvConfig config = EnvConfig.DEFAULT.withArena(Scenarios.arena(tables));
    try (VectorEnv env = vector(config)) {
      reset(env, 11, null, setup1);
      LadderEnv ladder = (LadderEnv) env.env(0);
      play(ladder, 200, new SplittableRandom(5));

      ScenarioPlan plan = new ReplayScenario(tables).translate(MAPPER.readTree(env.replayJson(0)));

      assertThat(plan.towers()).isEqualTo(ladder.battle().getTowers());
      assertThat(plan.towers().get(1).spawnGroup())
          .isEqualTo(tables.table("support_cards").row(towers).string("SpawnGroup"));
      // Every deck card, played or not, at its setup's level; side 0 at the configuration's.
      int[] standard = new int[BASIC.size()];
      Arrays.fill(standard, config.level());
      assertThat(plan.deckLevels().get(0)).containsExactly(standard);
      assertThat(plan.deckLevels().get(1)).containsExactly(setup1.cardLevels());
    }
  }

  @Test
  void aCopiedForkOfAnEpisodeWithSetupsIsTheBattlePlayedAgainFromItsStart() {
    String towers = otherTowerSelections().get(0);
    try (VectorEnv env = vector(EnvConfig.DEFAULT)) {
      reset(env, 13, topSetup(towers, BASIC), topSetup(Standard1v1Battle.PRINCESS_TOWERS, OTHER));
      LadderEnv original = (LadderEnv) env.env(0);
      SplittableRandom random = new SplittableRandom(17);
      play(original, 250, random);
      assertThat(original.done()).isFalse();

      LadderEnv copied = original.fork(-1);
      LadderEnv replayed = original.replayed(-1);

      assertThat(written(copied)).isEqualTo(written(original));
      assertThat(written(replayed)).isEqualTo(written(original));
      assertThat(replayed.battle().getTowers()).isEqualTo(original.battle().getTowers());
      for (int d = 0; d < 100 && !replayed.done(); d++) {
        int action0 = randomAction(replayed, 0, random);
        int action1 = randomAction(replayed, 1, random);
        copied.step(action0, action1);
        replayed.step(action0, action1);
        assertThat(written(copied)).as("decision %d", d).isEqualTo(written(replayed));
      }
    }
  }

  @Test
  void anEnvironmentResetAgainKeepsItsSetups() {
    String towers = otherTowerSelections().get(0);
    SideSetup setup1 = topSetup(towers, OTHER);
    try (VectorEnv env = vector(EnvConfig.DEFAULT)) {
      reset(env, 21, null, setup1);
      List<Standard1v1Battle.Towers> first = ((LadderEnv) env.env(0)).battle().getTowers();

      env.resetEnv(0, 22);

      LadderEnv again = (LadderEnv) env.env(0);
      assertThat(again.battle().getTowers()).isEqualTo(first);
      assertThat(again.setups()).containsExactly(null, setup1);
    }
  }

  @Test
  void noSetupPlaysThePrincessTowersAtTheConfiguredLevels() {
    EnvConfig config = EnvConfig.DEFAULT;
    try (VectorEnv withNone = vector(config);
        VectorEnv withStandard = vector(config)) {
      reset(withNone, 31, null, null);
      reset(withStandard, 31, SideSetup.standard(config), SideSetup.standard(config));
      LadderEnv none = (LadderEnv) withNone.env(0);
      LadderEnv standard = (LadderEnv) withStandard.env(0);
      assertThat(none.battle().getTowers()).isEqualTo(standard.battle().getTowers());
      SplittableRandom a = new SplittableRandom(4);
      SplittableRandom b = new SplittableRandom(4);
      for (int d = 0; d < 200 && !none.done(); d++) {
        none.step(randomAction(none, 0, a), randomAction(none, 1, a));
        standard.step(randomAction(standard, 0, b), randomAction(standard, 1, b));
        assertThat(written(standard)).as("decision %d", d).isEqualTo(written(none));
      }
    }
  }

  @Test
  void aTowerSelectionTheCoreDoesNotBuildIsRefusedBeforeAnyEnvironmentIsTouched() {
    List<String> unbuilt = new ArrayList<>();
    for (GameRow row : tables.table("support_cards").rows()) {
      if (!ReplayScenario.BUILT_TOWER_SELECTIONS.contains(row.name())) {
        unbuilt.add(row.name());
      }
    }
    unbuilt.add("NoSuchTowers");
    try (VectorEnv env = vector(EnvConfig.DEFAULT)) {
      reset(env, 1, null, null);
      int tick = ((LadderEnv) env.env(0)).tick();
      for (String towers : unbuilt) {
        SideSetup setup = new SideSetup(towers, 1, 1, topLevels(OTHER));
        assertThatThrownBy(() -> reset(env, 2, null, setup))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("environment 0, side 1")
            .hasMessageContaining(towers);
      }
      assertThat(((LadderEnv) env.env(0)).tick()).isEqualTo(tick);
      assertThat(((LadderEnv) env.env(0)).seed()).isEqualTo(1);
    }
  }

  @Test
  void levelsOutsideTheirRaritiesAndAKingLevelNoExpLevelGivesAreRefused() {
    String towers = Standard1v1Battle.PRINCESS_TOWERS;
    SideSetup good = topSetup(towers, OTHER);
    int[] cardRange = levels(rarityOf("rarities", deckCard(OTHER.get(0))));
    int[] towerRange =
        levels(rarityOf("support_rarities", tables.table("support_cards").row(towers)));
    int[] above = good.cardLevels();
    above[0] = cardRange[1] + 1;
    int[] below = good.cardLevels();
    below[0] = cardRange[0] - 1;
    List<SideSetup> refused =
        List.of(
            new SideSetup(towers, good.kingLevel(), good.towerLevel(), above),
            new SideSetup(towers, good.kingLevel(), good.towerLevel(), below),
            new SideSetup(towers, good.kingLevel(), towerRange[1] + 1, good.cardLevels()),
            new SideSetup(towers, good.kingLevel(), towerRange[0] - 1, good.cardLevels()),
            new SideSetup(towers, topKingLevel() + 1, good.towerLevel(), good.cardLevels()),
            new SideSetup(towers, 0, good.towerLevel(), good.cardLevels()),
            new SideSetup(towers, good.kingLevel(), good.towerLevel(), new int[] {cardRange[0]}));
    try (VectorEnv env = vector(EnvConfig.DEFAULT)) {
      for (SideSetup setup : refused) {
        assertThatThrownBy(() -> reset(env, 3, setup, null))
            .as(setup.toString())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("environment 0, side 0");
      }
      reset(env, 3, good, null);
      assertThat(((LadderEnv) env.env(0)).setups()).containsExactly(good, null);
    }
  }

  @Test
  void aSetupIsCopiedSoTheCallersArrayChangesNothing() {
    int[] levels = topLevels(BASIC);
    SideSetup setup = new SideSetup(Standard1v1Battle.PRINCESS_TOWERS, 11, 11, levels);
    levels[0] = 1;
    setup.cardLevels()[1] = 1;
    assertThat(setup.cardLevels()).containsExactly(topLevels(BASIC));
  }

  @Test
  void theSyntheticEnvironmentRefusesASetup() {
    try (VectorEnv env = VectorEnv.synthetic(1, 1, 4, 10)) {
      String[] deck = BASIC.toArray(String[]::new);
      assertThatThrownBy(
              () ->
                  env.reset(
                      new int[] {1},
                      deck,
                      deck,
                      null,
                      null,
                      new SideSetup[] {SideSetup.standard(EnvConfig.DEFAULT)},
                      null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("takes no side setup");
    }
  }
}
