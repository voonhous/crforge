/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.battle.SpawnedChampionTables.ABILITY_COST;
import static org.crforge.core.battle.SpawnedChampionTables.CARD_COST;
import static org.crforge.core.battle.SpawnedChampionTables.CARRIER;
import static org.crforge.core.battle.SpawnedChampionTables.CARRIER_CARD;
import static org.crforge.core.battle.SpawnedChampionTables.CAST_TIME_MS;
import static org.crforge.core.battle.SpawnedChampionTables.CHAMPION;
import static org.crforge.core.battle.SpawnedChampionTables.DROP_CARD;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.SpawnedChampionTables;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A champion an action spawns, on rows the tests write ({@link SpawnedChampionTables}): a unit with
 * a player-triggered ability that enters the battle through an action, a carrier's death action or
 * a thrown projectile's end, not through its own play. No play lists it, so the player's commands
 * find it as the live character the king's champion slot follows and name it by its game object id,
 * as the game's own ability command does; the command passes the run's gates and the champion
 * casts.
 */
class BattleSpawnedChampionTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The point side 0 plays the written cards at, on its own half. */
  private static final int X = 3500;

  private static final int Y = 8000;

  /** Milliseconds a step takes. */
  private static final int STEP_MS = 50;

  /** The most steps a scene waits for anything it waits on. */
  private static final int LIMIT = 400;

  @Test
  @DisplayName(
      "a champion a carrier's death action spawns is the unit of the champion slot it was linked"
          + " to, though no play lists it, and an ability command naming its game object id passes"
          + " the gates, pays the ability's cost and has it cast")
  void aChampionADeathActionSpawnsIsNamedByItsObjectId(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(folder, CARRIER_CARD);
    ChampionController slot = scene.slot();
    assertThat(slot.getChampion().name()).isEqualTo(CHAMPION);
    assertThat(slot.getDeckIndex()).isZero();

    scene.play(CARRIER_CARD);
    CharacterEntity carrier = scene.waitFor(CARRIER);
    // The kill lands at the damage drain of the next step, and the carrier's death action runs.
    scene.battle.getWorld().getHits().kill(carrier, null);
    CharacterEntity champion = scene.waitFor(CHAMPION);
    // The death action spawns it as the carrier leaves, for the carrier's side.
    assertThat(scene.named(CARRIER)).isEmpty();
    assertThat(champion.side()).isZero();

    scene.holdsTheCommandNamingItsObjectId(champion);
  }

  @Test
  @DisplayName(
      "a champion a thrown projectile's end spawns is the unit of the champion slot that follows"
          + " the throw's play, though no play lists it, and an ability command naming its game"
          + " object id passes the gates, pays the ability's cost and has it cast")
  void aChampionAProjectileSpawnsIsNamedByItsObjectId(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(folder, DROP_CARD);
    ChampionController slot = scene.slot();
    assertThat(slot.getChampion().name()).isEqualTo(CHAMPION);
    assertThat(slot.getDeckIndex()).isZero();

    int deployIndex = scene.match.side(0).getDeployCounter();
    scene.play(DROP_CARD);
    assertThat(scene.battle.getPlays().get(0).units()).as("a spell lists no unit").isEmpty();
    CharacterEntity champion = scene.waitFor(CHAMPION);
    // The projectile's end spawns it, carrying the play's deploy count, which the slot follows.
    assertThat(champion.side()).isZero();
    assertThat(champion.getDeployIndex()).isEqualTo(deployIndex);
    assertThat(slot.getDeployIndex()).isEqualTo(deployIndex);

    scene.holdsTheCommandNamingItsObjectId(champion);
  }

  /** A Ladder match of side 0's written card in its hero slot, every other card a filler. */
  private static final class Scene {

    final Standard1v1Battle battle;
    final LadderMatch match;

    /**
     * @param folder the folder the tables are written into
     * @param card the written card, first in side 0's deck, in its hero slot
     */
    Scene(Path folder, String card) throws IOException {
      GameTables tables = SpawnedChampionTables.write(folder);
      int[] slots = new int[8];
      slots[0] = MatchSide.HERO_SLOT;
      battle = new Standard1v1Battle(tables, LEVEL, false);
      match =
          battle.startLadderMatch(
              SpawnedChampionTables.deck(card),
              SpawnedChampionTables.fillers(),
              0,
              0,
              slots,
              new int[8]);
    }

    /** The king's first champion slot of side 0. */
    ChampionController slot() {
      return battle.getWorld().kingTower(0).championSlot(1);
    }

    /** Plays side 0's card once the match lets it and steps until the play has run and passed. */
    void play(String card) {
      while (battle.getBattle().getTick() < match.getStartCooldownTicks()
          || match.side(0).wholeElixir() < CARD_COST) {
        step();
      }
      battle.play(
          battle.getBattle().getTick(),
          battle.getWorld().getRecords().card(card),
          LEVEL,
          0,
          X,
          Y,
          "p");
      int limit = battle.getBattle().getTick() + LIMIT;
      while (battle.getPlays().isEmpty()) {
        assertThat(battle.getBattle().getTick()).isLessThan(limit);
        step();
      }
      assertThat(battle.getPlays().get(0).matchCode()).as("the play passes the match").isZero();
    }

    /** Steps until a character of a row stands, and answers the first. */
    CharacterEntity waitFor(String row) {
      int limit = battle.getBattle().getTick() + LIMIT;
      while (named(row).isEmpty()) {
        assertThat(battle.getBattle().getTick()).isLessThan(limit);
        step();
      }
      return named(row).get(0);
    }

    /**
     * The champion an action spawned, named by its game object id: no play lists it, the slot
     * follows it and the player's commands find it as the slot's unit; the try passes once the
     * elixir is there, and the command given names its id, runs twenty steps later, pays the
     * ability's cost and has it cast.
     */
    void holdsTheCommandNamingItsObjectId(CharacterEntity champion) {
      PlayerCommands commands = new PlayerCommands(battle);
      assertThat(battle.getPlays().stream().flatMap(play -> play.units().stream()))
          .doesNotContain(champion);
      step();
      assertThat(slot().champions()).containsExactly(champion);
      assertThat(commands.championUnit(0, 0)).isSameAs(champion);

      int limit = battle.getBattle().getTick() + LIMIT;
      while (match.side(0).wholeElixir() < ABILITY_COST
          || !commands.tryAbility(0, champion.getId()).given()) {
        assertThat(battle.getBattle().getTick()).isLessThan(limit);
        step();
      }
      PlayerCommands.Giving giving = commands.useAbility(0, champion.getId(), "a");
      assertThat(giving.given()).isTrue();
      assertThat(giving.cost()).isEqualTo(ABILITY_COST);
      assertThat(giving.runTick())
          .isEqualTo(giving.givenTick() + Standard1v1Battle.PLAY_DELAY_TICKS);
      Standard1v1Battle.Command command = battle.getCommands().get(battle.getCommands().size() - 1);
      assertThat(command.kind()).isEqualTo(Standard1v1Battle.CommandKind.ABILITY);
      assertThat(command.side()).isZero();
      assertThat(command.given()).isEqualTo(giving.givenTick());
      assertThat(command.tick()).isEqualTo(giving.runTick());
      assertThat(command.objectId()).isEqualTo(champion.getId());

      while (battle.getAbilityUses().isEmpty()) {
        assertThat(battle.getBattle().getTick()).isLessThan(limit);
        step();
      }
      Standard1v1Battle.AbilityUse use = battle.getAbilityUses().get(0);
      assertThat(use.tick()).isEqualTo(giving.runTick());
      assertThat(use.outcome().code()).isEqualTo(AbilityCommand.OK);
      assertThat(use.outcome().elixirBefore() - use.outcome().elixirAfter())
          .isEqualTo(ABILITY_COST * KingElixir.SCALE);
      assertThat(use.outcome().requested()).containsExactly(champion);
      // Its gate open, it casts at once, and its activation runs on it once within the cast.
      assertThat(champion.getView().getState()).isEqualTo(GridEntityState.CASTING);
      int activations = battle.getWorld().variableKey(GameData.TEST_VARIABLE);
      assertThat(champion.variable(activations)).isZero();
      for (int k = 0; k < CAST_TIME_MS / STEP_MS; k++) {
        step();
      }
      assertThat(champion.variable(activations)).isEqualTo(1);
    }

    /** The characters of a row the holder lists, in its order. */
    List<CharacterEntity> named(String row) {
      return battle.getWorld().getHolder().entities().stream()
          .filter(CharacterEntity.class::isInstance)
          .map(CharacterEntity.class::cast)
          .filter(unit -> unit.getData().name().equals(row))
          .toList();
    }

    void step() {
      battle.getBattle().step();
    }
  }
}
