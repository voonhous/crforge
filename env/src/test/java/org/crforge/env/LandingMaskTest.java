/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.deploy.DeployMask;
import org.crforge.core.battle.deploy.MaskEntity;
import org.crforge.core.battle.deploy.PlacementSearch;
import org.crforge.core.battle.replay.ReplayScenario;
import org.crforge.core.battle.replay.ScenarioPlan;
import org.crforge.core.battle.unit.CharacterEntity;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.core.pathfinding.grid.TileMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The landing mask ({@link Schema#MASK_LANDING_TILES}): of a hand slot the client's try passes,
 * only the tiles the card lands on without being moved are open, from the placement search on the
 * buildings and towers the seat sees. The decks name rows of the tables; which tiles a card takes
 * is read from the battle core's search, never written here.
 */
class LandingMaskTest {

  /** Troops, a troop that may go anywhere, buildings, spells and a spell that spawns a troop. */
  private static final List<String> DECK0 =
      List.of("Miner", "Cannon", "Tesla", "GoblinBarrel", "Fireball", "Knight", "Xbow", "Mortar");

  /** Buildings that hide, buildings that do not, troops and spells. */
  private static final List<String> DECK1 =
      List.of(
          "Tesla", "Cannon", "InfernoTower", "BombTower", "Knight", "Archer", "Fireball", "Log");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static GameTables tables;

  @BeforeAll
  static void loadTables() {
    tables = GameData.tables();
  }

  private static boolean open(VectorEnv env, int seat, int action) {
    return env.masks().get(seat * Schema.ACTIONS + action) == 1;
  }

  /** Whether a seat's player sees a character or tower, as the observation shows it. */
  private static boolean seen(int seat, WorldEntity world) {
    if (world.side() == seat || !(world instanceof CharacterEntity character)) {
      return true;
    }
    return !character.hidden() && !character.invisible() && !character.underground();
  }

  /** The point the screen's search sends a drop at a seat tile's centre to, on a field. */
  private static int[] sent(
      Standard1v1Battle battle, DeployCard card, int seat, int col, int row, List<MaskEntity> on) {
    TileMap map = battle.getWorld().getTileMap();
    return PlacementSearch.find(
        map,
        card,
        Schema.gameX(seat, col),
        Schema.gameY(seat, row),
        seat,
        PlacementSearch.mask(map, card, seat, on),
        Standard1v1Battle.SYMMETRICAL_DEPLOY_SNAP);
  }

  /** Whether a found point lies on the game tile of a seat tile's centre. */
  private static boolean onTile(int[] point, int seat, int col, int row) {
    return point != null
        && Math.floorDiv(point[0], Schema.TILE_UNITS) == Schema.gameX(seat, col) / Schema.TILE_UNITS
        && Math.floorDiv(point[1], Schema.TILE_UNITS)
            == Schema.gameY(seat, row) / Schema.TILE_UNITS;
  }

  @Test
  void anOpenSlotOpensExactlyTheTilesItsCardLandsOnUnmovedAsTheSeatSeesTheField() {
    EnvConfig config = EnvConfig.DEFAULT;
    assertThat(config.maskVersion()).isEqualTo(Schema.MASK_LANDING_TILES);
    try (VectorEnv env = VectorEnv.ladder(tables, config, 1, 1)) {
      env.reset(new int[] {11}, DECK0, DECK1);
      LadderEnv ladder = (LadderEnv) env.env(0);
      SplittableRandom random = new SplittableRandom(5);
      IntBuffer actions = env.actions().asIntBuffer();
      int checked = 0;
      int closed = 0;
      int hiddenFields = 0;
      int plays = 0;
      for (int decision = 0; decision < 900 && env.dones().get(0) == 0; decision++) {
        Standard1v1Battle battle = ladder.battle();
        boolean check = decision % 6 == 0;
        int[] chosen = new int[2];
        for (int seat = 0; seat < 2; seat++) {
          int s = seat;
          List<MaskEntity> all = battle.placementEntities(w -> true);
          List<MaskEntity> visible = battle.placementEntities(w -> seen(s, w));
          boolean hidden =
              !all.stream()
                  .filter(DeployMask::reads)
                  .toList()
                  .equals(visible.stream().filter(DeployMask::reads).toList());
          hiddenFields += check && hidden ? 1 : 0;
          List<Integer> openPlays = new ArrayList<>();
          for (int k = 0; k < Schema.HAND_SLOTS; k++) {
            List<DeployCard> cards = ladder.commands().searchCards(seat, k, config.level());
            boolean tried = ladder.commands().tryCard(seat, k, config.level()).given();
            assertThat(!cards.isEmpty()).isEqualTo(tried);
            for (int row = 0; row < Schema.ROWS; row++) {
              for (int col = 0; col < Schema.COLS; col++) {
                int action = Schema.play(k, col, row);
                boolean isOpen = open(env, seat, action);
                if (isOpen) {
                  openPlays.add(action);
                }
                if (!check) {
                  continue;
                }
                if (cards.isEmpty()) {
                  assertThat(isOpen).as("a closed slot's tile").isFalse();
                  continue;
                }
                boolean lands = true;
                boolean landsAll = true;
                for (DeployCard card : cards) {
                  lands &= onTile(sent(battle, card, seat, col, row, visible), seat, col, row);
                  // The screen's own search, on the whole field.
                  int[] screen =
                      battle.searchPlacement(
                          card, seat, Schema.gameX(seat, col), Schema.gameY(seat, row));
                  landsAll &= onTile(screen, seat, col, row);
                }
                assertThat(isOpen)
                    .as("decision %d seat %d slot %d tile (%d, %d)", decision, seat, k, col, row)
                    .isEqualTo(lands);
                // No tile the card lands on unmoved is closed; a building the seat does not see
                // may move a drop on a tile left open.
                if (landsAll) {
                  assertThat(isOpen).isTrue();
                }
                if (!hidden) {
                  assertThat(lands).isEqualTo(landsAll);
                }
                checked++;
                closed += isOpen ? 0 : 1;
              }
            }
          }
          chosen[seat] =
              random.nextInt(10) < 3 && !openPlays.isEmpty()
                  ? openPlays.get(random.nextInt(openPlays.size()))
                  : Schema.WAIT;
          actions.put(seat, chosen[seat]);
        }
        env.step();
        assertThat(env.infos().asIntBuffer().get(Schema.I_FAULT)).as("no fault").isZero();
        for (int seat = 0; seat < 2; seat++) {
          if (chosen[seat] != Schema.WAIT) {
            plays++;
            assertThat(
                    env.infos()
                        .asIntBuffer()
                        .get(seat == 0 ? Schema.I_ILLEGAL_0 : Schema.I_ILLEGAL_1))
                .as("an open play is given, decision %d seat %d", decision, seat)
                .isZero();
          }
        }
      }
      assertThat(checked).isGreaterThan(10_000);
      assertThat(closed).as("tiles closed in open slots").isPositive();
      assertThat(plays).isGreaterThan(20);
      assertThat(hiddenFields).as("decisions with an enemy building out of view").isPositive();
    }
  }

  @Test
  void aPlayOfAnOpenTileIsSentOnThatTile() {
    try (VectorEnv env = VectorEnv.ladder(tables, EnvConfig.DEFAULT, 1, 1)) {
      env.reset(new int[] {3}, DECK0, DECK1);
      LadderEnv ladder = (LadderEnv) env.env(0);
      SplittableRandom random = new SplittableRandom(9);
      IntBuffer actions = env.actions().asIntBuffer();
      int sent = 0;
      for (int decision = 0; decision < 600 && env.dones().get(0) == 0; decision++) {
        List<Integer> openPlays = new ArrayList<>();
        for (int a = 1; a < Schema.FIRST_ABILITY; a++) {
          if (open(env, 0, a)) {
            openPlays.add(a);
          }
        }
        int action =
            openPlays.isEmpty() || random.nextInt(4) != 0
                ? Schema.WAIT
                : openPlays.get(random.nextInt(openPlays.size()));
        int commandsBefore = ladder.battle().getCommands().size();
        actions.put(0, action);
        actions.put(1, Schema.WAIT);
        env.step();
        if (action == Schema.WAIT) {
          continue;
        }
        assertThat(ladder.battle().getCommands()).hasSize(commandsBefore + 1);
        Standard1v1Battle.Command command = ladder.battle().getCommands().get(commandsBefore);
        int tile = (action - 1) % Schema.TILES;
        assertThat(
                onTile(
                    new int[] {command.x(), command.y()},
                    0,
                    tile % Schema.COLS,
                    tile / Schema.COLS))
            .as("decision %d: %s", decision, command)
            .isTrue();
        sent++;
      }
      assertThat(sent).isGreaterThan(10);
    }
  }

  @Test
  void theOldRuleOpensEveryTileOfAnOpenSlot() {
    EnvConfig old = EnvConfig.DEFAULT.withMaskVersion(Schema.MASK_ALL_TILES);
    try (VectorEnv env = VectorEnv.ladder(tables, old, 1, 1)) {
      env.reset(new int[] {11}, DECK0, DECK1);
      LadderEnv ladder = (LadderEnv) env.env(0);
      IntBuffer actions = env.actions().asIntBuffer();
      actions.put(0, Schema.WAIT);
      actions.put(1, Schema.WAIT);
      int openSlots = 0;
      for (int decision = 0; decision < 60; decision++) {
        env.step();
        for (int k = 0; k < Schema.HAND_SLOTS; k++) {
          boolean tried = ladder.commands().tryCard(0, k, old.level()).given();
          int openTiles = 0;
          for (int t = 0; t < Schema.TILES; t++) {
            openTiles += open(env, 0, 1 + k * Schema.TILES + t) ? 1 : 0;
          }
          assertThat(openTiles).isEqualTo(tried ? Schema.TILES : 0);
          openSlots += tried ? 1 : 0;
        }
      }
      assertThat(openSlots).isPositive();
    }
  }

  @Test
  void theMaskRuleIsVersionedApartFromTheSchemaAndTheConfiguration() throws IOException {
    JsonNode landing = MAPPER.readTree(VectorEnv.metadataJson(EnvConfig.DEFAULT, null));
    JsonNode all =
        MAPPER.readTree(
            VectorEnv.metadataJson(EnvConfig.DEFAULT.withMaskVersion(Schema.MASK_ALL_TILES), null));
    assertThat(landing.path("mask_version").asInt()).isEqualTo(Schema.MASK_LANDING_TILES);
    assertThat(all.path("mask_version").asInt()).isEqualTo(Schema.MASK_ALL_TILES);
    // A model trained under one rule reads the same schema and configuration under the other.
    assertThat(landing.path("schema")).isEqualTo(all.path("schema"));
    assertThat(landing.path("config")).isEqualTo(all.path("config"));
    assertThat(landing.path("schema").path("version").asInt()).isEqualTo(Schema.VERSION);
    assertThat(landing.path("config").has("mask_version")).isFalse();
    assertThat(VectorEnv.metadataJson(null, null)).doesNotContain("mask_version");
    assertThatThrownBy(() -> EnvConfig.DEFAULT.withMaskVersion(3))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aRecordedTapTheScreenMovedIsLabelledWithTheTileItLandedOn() throws IOException {
    // An episode under the old rule: plays dropped on any tile, many of them moved by the screen's
    // search, written as a replay that records where each landed.
    EnvConfig old = EnvConfig.DEFAULT.withDecisionTicks(1).withMaskVersion(Schema.MASK_ALL_TILES);
    String replay;
    try (VectorEnv env = VectorEnv.ladder(tables, old, 1, 1)) {
      env.setAutoreset(false);
      env.reset(new int[] {21}, DECK0, DECK1);
      SplittableRandom random = new SplittableRandom(21);
      IntBuffer actions = env.actions().asIntBuffer();
      for (int d = 0; d < 3000 && env.dones().get(0) == 0; d++) {
        for (int seat = 0; seat < 2; seat++) {
          List<Integer> openPlays = new ArrayList<>();
          for (int a = 1; a < Schema.FIRST_ABILITY; a++) {
            if (open(env, seat, a)) {
              openPlays.add(a);
            }
          }
          actions.put(
              seat,
              openPlays.isEmpty() || random.nextInt(15) != 0
                  ? Schema.WAIT
                  : openPlays.get(random.nextInt(openPlays.size())));
        }
        env.step();
      }
      replay = env.replayJson(0);
    }
    ScenarioPlan plan = new ReplayScenario(tables).translate(MAPPER.readTree(replay));
    assertThat(plan.plays()).hasSizeGreaterThan(20);

    ReplayLabeller labeller = new ReplayLabeller(tables, 1);
    assertThat(labeller.maskVersion()).isEqualTo(Schema.MASK_LANDING_TILES);
    ReplayLabeller.Labels labels = labeller.label(replay);
    assertThat(labels.refusal()).isNull();
    int checked = 0;
    for (ScenarioPlan.Play play : plan.plays()) {
      int row = 2 * play.givenTick() + play.side();
      int label = labels.actions()[row];
      if ((labels.flags()[row] & (ReplayLabeller.COLLISION | ReplayLabeller.INEXPRESSIBLE)) != 0) {
        continue;
      }
      assertThat(label).as("the play given at %d", play.givenTick()).isPositive();
      // The tile the play landed on, which the row's mask opens.
      int tile = (label - 1) % Schema.TILES;
      assertThat(
              onTile(
                  new int[] {play.x(), play.y()},
                  play.side(),
                  tile % Schema.COLS,
                  tile / Schema.COLS))
          .as("the play given at %d on (%d, %d)", play.givenTick(), play.x(), play.y())
          .isTrue();
      assertThat(labels.flags()[row] & ReplayLabeller.LABEL_MASKED).isZero();
      assertThat(labels.masks()[row * Schema.ACTIONS + label]).isEqualTo((byte) 1);
      checked++;
    }
    assertThat(checked).isGreaterThan(20);
    assertThat(labels.counts().labelMasked()).isZero();
  }
}
