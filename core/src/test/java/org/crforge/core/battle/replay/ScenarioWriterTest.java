/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.unit.AreaEffectEntity;
import org.crforge.core.battle.unit.PlayerCommands;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A battle played through its players' commands, written as a replay of the tables' data version
 * and read back: the replay's battle is the same battle, step for step. The decks are the test
 * fixtures' scenario decks, by data id, named from the configured tables; no game value is typed
 * here.
 */
class ScenarioWriterTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static GameTables tables;

  @BeforeAll
  static void loadTables() {
    tables = GameData.tables();
  }

  /** The fixtures' Mirror and Merge Maiden deck for side 0, with a building in side 1's. */
  private static List<List<String>> decks() {
    int[][] ids = {
      {
        Scenarios.KNIGHT,
        26000005,
        Scenarios.ARCHER,
        Scenarios.MERGE_MAIDEN,
        28000008,
        Scenarios.MIRROR,
        26000010,
        26000002
      },
      {
        26000002,
        Scenarios.ARCHER,
        Scenarios.GIANT,
        Scenarios.KNIGHT,
        26000014,
        26000005,
        27000000,
        26000010
      }
    };
    List<List<String>> decks = new ArrayList<>();
    for (int[] deck : ids) {
      decks.add(Arrays.stream(deck).mapToObj(id -> ScenarioItems.row(tables, id).name()).toList());
    }
    return decks;
  }

  /** A Ladder battle as a player's game builds one, both players' data handed over. */
  private static Standard1v1Battle ladder(int seed, int towerLevel) {
    return ladder(seed, towerLevel, new int[8], new int[8]);
  }

  /** {@link #ladder(int, int)} with each deck's slot flags. */
  private static Standard1v1Battle ladder(int seed, int towerLevel, int[] slots0, int[] slots1) {
    Standard1v1Battle battle = new Standard1v1Battle(tables, towerLevel);
    battle.getWorld().seed(seed);
    battle.addPlayerData(1);
    battle.addPlayerData(1);
    List<List<String>> decks = decks();
    battle.startLadderMatch(decks.get(0), decks.get(1), 3, 5, slots0, slots1);
    return battle;
  }

  /**
   * Gives each side a play every few ticks through the players' commands, from the first hand slot
   * whose giving passes, dropped at a point of its own half, for the given number of steps; returns
   * the battle's state after every step.
   */
  private static List<String> play(
      Standard1v1Battle battle,
      PlayerCommands commands,
      int steps,
      int level,
      SplittableRandom random) {
    List<String> states = new ArrayList<>();
    for (int step = 0; step < steps; step++) {
      int tick = battle.getBattle().getTick();
      if (tick % 7 == 0) {
        int side = (tick / 7) % 2;
        int x = 1500 + random.nextInt(15000);
        int y = 2000 + random.nextInt(12000);
        if (side == 1) {
          y = 32000 - y;
        }
        for (int slot = 0; slot < 4; slot++) {
          String name = "cmd" + battle.getCommands().size();
          try {
            if (commands.playCard(side, slot, level, x, y, name).given()) {
              break;
            }
          } catch (UnsupportedOperationException e) {
            // A giving the battle core does not model: nothing is sent.
          }
        }
      }
      battle.getBattle().step();
      states.add(state(battle));
    }
    return states;
  }

  /** Reads a written replay back and holds its battle to the states played, step for step. */
  private static void replays(JsonNode document, List<String> played, int checkedPlays) {
    assertThat(new ReplayScenario(tables).survey(document)).isEmpty();
    ScenarioPlan read = new ReplayScenario(tables).translate(document);
    Standard1v1Battle replayed = ReplayBattle.build(tables, read);
    int checked = 0;
    for (int tick = 0; tick < played.size(); tick++) {
      replayed.getBattle().step();
      checked = ReplayBattle.checkItems(replayed, read, checked);
      assertThat(state(replayed)).as("after step %d", tick + 1).isEqualTo(played.get(tick));
    }
    assertThat(checked).isEqualTo(checkedPlays);
  }

  /** Everything the battle holds that a replay must give again, as text. */
  private static String state(Standard1v1Battle battle) {
    StringBuilder out = new StringBuilder();
    out.append("tick ").append(battle.getBattle().getTick());
    LadderMatch match = battle.getMatch();
    for (int side = 0; side < 2; side++) {
      out.append(" | side ")
          .append(side)
          .append(" elixir ")
          .append(match.side(side).getElixir())
          .append(" crowns ")
          .append(match.crowns(side))
          .append(" hand ")
          .append(Arrays.toString(match.side(side).getHand().slots()));
    }
    out.append(" | over ").append(match.isOver()).append(" winner ").append(match.getWinner());
    for (BattleEntity entity : battle.getBattle().getHolder().entities()) {
      out.append(" | ")
          .append(entity.getClass().getSimpleName())
          .append(' ')
          .append(entity.getId());
      if (entity instanceof WorldEntity world) {
        HitPoints points = world.getHitPoints();
        out.append(" s")
            .append(world.side())
            .append(" ")
            .append(world.getData().name())
            .append(" (")
            .append(world.getView().getX())
            .append(",")
            .append(world.getView().getY())
            .append(") hp ")
            .append(points == null ? "-" : points.getHitPoints() + "/" + points.getShield())
            .append(" st ")
            .append(world.getView().getState())
            .append(" alive ")
            .append(world.getView().isAlive());
      } else if (entity instanceof ProjectileEntity projectile) {
        out.append(" (").append(projectile.x()).append(",").append(projectile.y()).append(")");
      } else if (entity instanceof AreaEffectEntity area) {
        out.append(" (").append(area.getX()).append(",").append(area.getY()).append(")");
      }
    }
    return out.toString();
  }

  /** The written document, as a file holds it: written out and parsed back. */
  private static JsonNode throughText(ObjectNode document) throws Exception {
    return MAPPER.readTree(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(document));
  }

  @Test
  void aBattleGivenThroughThePlayersCommandsIsTheSameBattleStepForStepFromItsWrittenReplay()
      throws Exception {
    int level = Standard1v1Battle.DEFAULT_LEVEL;
    Standard1v1Battle battle = ladder(4242, Standard1v1Battle.DEFAULT_LEVEL);
    PlayerCommands commands = new PlayerCommands(battle);
    SplittableRandom random = new SplittableRandom(7);
    ScenarioWriter writer = new ScenarioWriter(tables);
    List<String> played = new ArrayList<>(play(battle, commands, 700, level, random));
    // Written mid-battle, with a play given and not yet run.
    while (battle.getCommands().stream()
        .noneMatch(command -> command.tick() > battle.getBattle().getTick())) {
      played.addAll(play(battle, commands, 1, level, random));
    }
    int midTick = battle.getBattle().getTick();
    int midPlays = battle.getPlays().size();
    int midCommands = battle.getCommands().size();
    JsonNode mid =
        throughText(writer.write(writer.plan(battle, level, Scenarios.arenas(tables)), midTick));
    List<String> midStates = List.copyOf(played);
    played.addAll(play(battle, commands, 1400 - midTick, level, random));
    // The battle gave plays of every kind its decks hold.
    assertThat(battle.getCommands())
        .extracting(Standard1v1Battle.Command::kind)
        .contains(
            Standard1v1Battle.CommandKind.CARD,
            Standard1v1Battle.CommandKind.MIRROR,
            Standard1v1Battle.CommandKind.VARIANT);

    JsonNode whole =
        throughText(writer.write(writer.plan(battle, level, Scenarios.arenas(tables)), 1400));

    assertThat(ReplayCapture.of(whole))
        .hasValueSatisfying(
            capture -> {
              assertThat(capture.contentSha()).isEqualTo(tables.contentSha());
              assertThat(capture.contentVersion()).isEqualTo(tables.version());
            });
    assertThat(whole.path("endTick").asInt()).isEqualTo(1400);
    assertThat(whole.path("cmd")).hasSize(battle.getCommands().size());
    replays(whole, played, battle.getPlays().size());
    assertThat(mid.path("endTick").asInt()).isEqualTo(midTick);
    assertThat(mid.path("cmd")).hasSize(midCommands);
    replays(mid, midStates, midPlays);
  }

  @Test
  void aBattleWithTowersBelowTheDefaultLevelIsWrittenWithItsKingsAndPrincessesLevels()
      throws Exception {
    int ticks = 400;
    Standard1v1Battle battle = ladder(9, 9);
    List<String> played =
        play(battle, new PlayerCommands(battle), ticks, 9, new SplittableRandom(3));

    ScenarioWriter writer = new ScenarioWriter(tables);
    JsonNode document = throughText(writer.write(writer.plan(battle, 9, Scenarios.arenas(tables))));
    ScenarioPlan read = new ReplayScenario(tables).translate(document);

    assertThat(read.towers())
        .allSatisfy(
            towers -> {
              assertThat(towers.kingLevel()).isEqualTo(9);
              assertThat(towers.level()).isEqualTo(9);
            });
    assertThat(read.deckLevels()).allSatisfy(levels -> assertThat(levels).containsOnly(9));
    Standard1v1Battle replayed = ReplayBattle.build(tables, read);
    for (int tick = 0; tick < ticks; tick++) {
      replayed.getBattle().step();
      assertThat(state(replayed)).as("after step %d", tick + 1).isEqualTo(played.get(tick));
    }
  }

  @Test
  void eachPlayersDataListsNoEmotesBesideItsKingLevelAsTheGamesReplaysWriteIt() throws Exception {
    // The game's replays give every player's data an emote list, here one that lists none; an
    // entry with its king level alone is not an entry the game writes.
    Standard1v1Battle battle = ladder(5, 9);
    play(battle, new PlayerCommands(battle), 40, 9, new SplittableRandom(5));

    ScenarioWriter writer = new ScenarioWriter(tables);
    JsonNode entries =
        throughText(writer.write(writer.plan(battle, 9, Scenarios.arenas(tables))))
            .path("battle")
            .path("hbd");

    assertThat(entries).hasSize(2);
    for (JsonNode entry : entries) {
      assertThat(entry.path("em").toString()).isEqualTo("{\"oe\":[],\"de\":[]}");
      if (ReplayFormat.orShared(tables.version()).kingLevelFromPlayerData()) {
        assertThat(entry.path(ReplayFormat.KING_LEVEL_FIELD).asInt()).isEqualTo(9);
        assertThat(entry.size()).as("the king level and the emotes alone").isEqualTo(2);
      } else {
        assertThat(entry.size()).as("the emotes alone").isEqualTo(1);
      }
    }
  }

  @Test
  void aBattleWithEvolutionSlotsIsWrittenWithItsSlotFlagsAndEachPlaysCount() throws Exception {
    int ticks = 2400;
    int[] slots0 = new int[8];
    int[] slots1 = new int[8];
    // Side 0's Knight and side 1's Archer in their decks' evolution slots.
    slots0[0] = MatchSide.EVOLUTION_SLOT;
    slots1[1] = MatchSide.EVOLUTION_SLOT;
    Standard1v1Battle battle = ladder(77, Standard1v1Battle.DEFAULT_LEVEL, slots0, slots1);
    List<String> played =
        play(
            battle,
            new PlayerCommands(battle),
            ticks,
            Standard1v1Battle.DEFAULT_LEVEL,
            new SplittableRandom(11));

    ScenarioWriter writer = new ScenarioWriter(tables);
    JsonNode document =
        throughText(
            writer.write(
                writer.plan(battle, Standard1v1Battle.DEFAULT_LEVEL, Scenarios.arenas(tables)),
                ticks));

    assertThat(document.path("battle").path("deck0").path("sp").get(0).path("el").asInt())
        .isEqualTo(MatchSide.EVOLUTION_SLOT);
    assertThat(document.path("battle").path("deck1").path("sp").get(1).path("el").asInt())
        .isEqualTo(MatchSide.EVOLUTION_SLOT);
    ScenarioPlan read = new ReplayScenario(tables).translate(document);
    assertThat(read.slotFlags().get(0)).isEqualTo(slots0);
    assertThat(read.slotFlags().get(1)).isEqualTo(slots1);
    // A slot card was played, so its item carries a count.
    assertThat(battle.getPlays())
        .anyMatch(play -> play.evolution() != null && play.evolution().count() > 0);
    replays(document, played, battle.getPlays().size());
  }

  @Test
  void aBattleOutsideAMatchIsRefusedNamingWhy() {
    Standard1v1Battle battle = new Standard1v1Battle(tables);

    assertThatThrownBy(() -> new ScenarioWriter(tables).plan(battle, 11, Scenarios.arenas(tables)))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("a battle outside a match");
  }

  @Test
  void aDeckCardPlayedAtTwoLevelsIsRefusedNamingTheCard() {
    Standard1v1Battle battle = ladder(1, Standard1v1Battle.DEFAULT_LEVEL);
    String knight = ScenarioItems.row(tables, Scenarios.KNIGHT).name();
    battle.play(200, GameData.card(knight), 11, 0, 9000, 6000, "cmd0");
    battle.play(400, GameData.card(knight), 10, 0, 9000, 6000, "cmd1");

    assertThatThrownBy(() -> new ScenarioWriter(tables).plan(battle, 11, Scenarios.arenas(tables)))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining(knight)
        .hasMessageContaining("levels 11 and 10");
  }
}
