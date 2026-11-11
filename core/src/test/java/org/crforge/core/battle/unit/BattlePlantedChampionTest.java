/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.crforge.core.battle.HeroFormTables.BANNER;
import static org.crforge.core.battle.HeroFormTables.BANNER_ABILITY_COST;
import static org.crforge.core.battle.HeroFormTables.BANNER_CAST_TIME_MS;
import static org.crforge.core.battle.HeroFormTables.BANNER_CHARGES;
import static org.crforge.core.battle.HeroFormTables.BANNER_EXPIRE_WAIT_MS;
import static org.crforge.core.battle.HeroFormTables.BANNER_TIMER_MS;
import static org.crforge.core.battle.HeroFormTables.BANNER_TRIGGER_DELAY_MS;
import static org.crforge.core.battle.HeroFormTables.CARD_COST;
import static org.crforge.core.battle.HeroFormTables.SQUAD_CARD;
import static org.crforge.core.battle.HeroFormTables.SQUAD_COUNT;
import static org.crforge.core.battle.HeroFormTables.SQUAD_MEMBER;
import static org.crforge.core.battle.HeroFormTables.WAVE_BACK;
import static org.crforge.core.battle.HeroFormTables.WAVE_LEAVE_MS;
import static org.crforge.core.battle.HeroFormTables.WAVE_MEMBER;
import static org.crforge.core.battle.HeroFormTables.WAVE_SECOND_MS;
import static org.crforge.core.battle.HeroFormTables.WAVE_SIDEWAYS;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.HeroFormTables;
import org.crforge.core.battle.SpawnedChampionTables;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.grid.TileMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A champion the last member of a squad plants as it falls, on rows the tests write ({@link
 * HeroFormTables}' squad hero): the members hold the champion slot's button state while they live,
 * the last untagged one to fall leaves the banner on its spot, which stands for its timer and the
 * wait after it and then goes; an ability command naming the banner has it cast and send a wave at
 * the offsets its expressions give; a new play of the card group takes a standing banner and tags
 * the members still alive, whose fall then plants none.
 */
class BattlePlantedChampionTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** Milliseconds a step takes. */
  private static final int STEP_MS = 50;

  /** Steps a banner stands: its timer's interval, then the wait before its kill. */
  private static final int BANNER_STEPS = (BANNER_TIMER_MS + BANNER_EXPIRE_WAIT_MS) / STEP_MS;

  /** The point side 0 plays the squad at, before the river in the left lane. */
  private static final int X = 3500;

  private static final int Y = 14000;

  @Test
  @DisplayName(
      "the squad members hold the slot's button state not yet available while they live, and the"
          + " play and their start leave the slot its charges; the last to fall leaves the banner"
          + " on its spot, a live copy of the slot, which goes after its timer and the wait")
  void theLastMemberLeavesTheBanner(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(folder);
    ChampionController slot = scene.slot();
    scene.play(X, Y, "s");
    scene.step();
    scene.step();
    assertThat(scene.battle.getPlays().get(0).units())
        .extracting(unit -> unit.getData().name())
        .containsExactlyElementsOf(Collections.nCopies(SQUAD_COUNT, SQUAD_MEMBER));
    assertThat(slot.getState()).isEqualTo(ChampionController.NO_YET_AVAILABLE);
    assertThat(slot.getCharges()).isEqualTo(BANNER_CHARGES);

    // Follow the members to their last fall: the banner stands where the last one stood.
    int limit = scene.tick() + 1000;
    int lastX = 0;
    int lastY = 0;
    while (!scene.named(SQUAD_MEMBER).isEmpty()) {
      assertThat(scene.tick()).isLessThan(limit);
      assertThat(scene.named(BANNER)).isEmpty();
      List<CharacterEntity> members = scene.named(SQUAD_MEMBER);
      lastX = members.get(0).getView().getX();
      lastY = members.get(0).getView().getY();
      assertThat(slot.getState()).isEqualTo(ChampionController.NO_YET_AVAILABLE);
      scene.step();
    }
    List<CharacterEntity> banners = scene.named(BANNER);
    assertThat(banners).hasSize(1);
    CharacterEntity banner = banners.get(0);
    assertThat(banner.getView().getX()).isEqualTo(lastX);
    assertThat(banner.getView().getY()).isEqualTo(lastY);
    int planted = scene.tick();

    scene.step();
    assertThat(slot.champions()).containsExactly(banner);
    while (!scene.named(BANNER).isEmpty()) {
      assertThat(scene.tick()).isLessThan(planted + 2 * BANNER_STEPS);
      scene.step();
    }
    assertThat(scene.tick() - planted).isEqualTo(BANNER_STEPS);
  }

  @Test
  @DisplayName(
      "a command naming the banner by its row is refused: a command by name reaches only a unit a"
          + " play made, and the banner is made by the last member's fall")
  void theBannerIsNotNamedByItsRow(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(folder);
    CharacterEntity banner = scene.plantBanner();
    scene.battle.useAbility(scene.tick(), 0, banner.name(), "a");
    assertThatThrownBy(scene::step)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("which no play of side 0 made");
  }

  @Test
  @DisplayName(
      "a command naming the banner by its game object id pays the ability's elixir and the banner"
          + " casts as a troop does: its trigger delay in the casting state, then the wave, two"
          + " members its group's delay apart on either side of it and behind it, and the banner"
          + " goes at its group's kill")
  void theBannersAbilitySendsTheWave(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(folder);
    CharacterEntity banner = scene.plantBanner();
    int x = banner.getView().getX();
    int y = banner.getView().getY();
    scene.battle.useAbility(scene.tick(), 0, banner.getId(), "wave");

    // The activation group's delays from the step the ability's trigger delay runs out: its two
    // spawns of the wave, then its kill of the banner.
    int fire = BANNER_TRIGGER_DELAY_MS / STEP_MS;
    int firstSpawn = fire;
    int secondSpawn = fire + WAVE_SECOND_MS / STEP_MS;
    int kill = fire + WAVE_LEAVE_MS / STEP_MS;
    // Follow the banner and the wave one step at a time.
    int span = kill + 5;
    int[] state = new int[span];
    boolean[] standing = new boolean[span];
    int[] wave = new int[span];
    for (int k = 1; k < span; k++) {
      scene.step();
      standing[k] = !scene.named(BANNER).isEmpty();
      state[k] = banner.getView().getState();
      List<CharacterEntity> members = scene.named(WAVE_MEMBER);
      wave[k] = members.size();
      if (k == 1) {
        // The command passes and pays the ability's elixir; the banner is the one requested.
        assertThat(scene.battle.getAbilityUses()).hasSize(1);
        AbilityCommand.Outcome outcome = scene.battle.getAbilityUses().get(0).outcome();
        assertThat(outcome.code()).isZero();
        assertThat(outcome.elixirBefore() - outcome.elixirAfter())
            .isEqualTo(BANNER_ABILITY_COST * KingElixir.SCALE);
        assertThat(outcome.requested()).containsExactly(banner);
      }
      if (k == firstSpawn || k == secondSpawn) {
        // The two members of the wave stand where their expressions put them: beside the banner
        // toward the middle, then away from it, each behind it (side 0's y direction is -1).
        CharacterEntity last = members.get(members.size() - 1);
        int mapWidth = scene.battle.getWorld().getTileMap().width() * TileMap.CELL_UNITS;
        int toMiddle = x > mapWidth / 2 ? -WAVE_SIDEWAYS : WAVE_SIDEWAYS;
        assertThat(last.getView().getX()).isEqualTo(k == firstSpawn ? x + toMiddle : x - toMiddle);
        assertThat(last.getView().getY()).isEqualTo(y - WAVE_BACK);
        assertThat(last.side()).isZero();
      }
    }
    // The casting state until the ability's cast time runs out, its trigger delay running out
    // within it and firing its activation group.
    int cast = BANNER_CAST_TIME_MS / STEP_MS;
    for (int k = 1; k < cast; k++) {
      assertThat(state[k]).as("step %d", k).isEqualTo(GridEntityState.CASTING);
    }
    assertThat(state[cast]).isNotEqualTo(GridEntityState.CASTING);
    // The group's two spawns, then its kill takes the banner.
    assertThat(wave[firstSpawn - 1]).isZero();
    assertThat(wave[firstSpawn]).isEqualTo(1);
    assertThat(wave[secondSpawn - 1]).isEqualTo(1);
    assertThat(wave[secondSpawn]).isEqualTo(2);
    assertThat(standing[kill - 1]).isTrue();
    assertThat(standing[kill]).isFalse();
    assertThat(wave[span - 1]).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "a new play of the squad's hero form while the banner stands is heard by the banner's"
          + " listener, the hero form tested against its card group's heroes: the banner goes in"
          + " the step the new members appear")
  void aNewHeroPlayTakesTheStandingBanner(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(folder);
    scene.fillElixir();
    scene.playAndRun(SQUAD_CARD, X, Y, "s");
    scene.cycleBackToTheSquad();
    int limit = scene.tick() + 1000;
    while (scene.named(BANNER).isEmpty()) {
      assertThat(scene.tick()).isLessThan(limit);
      scene.step();
    }
    scene.step();
    assertThat(scene.named(BANNER)).hasSize(1);
    assertThat(scene.named(SQUAD_MEMBER)).isEmpty();

    // The banner stands until the play runs and its members are made.
    scene.playAndRun(SQUAD_CARD, X, Y, "t");
    while (scene.named(SQUAD_MEMBER).isEmpty()) {
      assertThat(scene.tick()).isLessThan(limit);
      assertThat(scene.named(BANNER)).hasSize(1);
      scene.step();
    }
    assertThat(scene.named(SQUAD_MEMBER)).hasSize(SQUAD_COUNT);
    assertThat(scene.named(BANNER)).isEmpty();
  }

  @Test
  @DisplayName(
      "a new play of the squad's hero form while members of an earlier play live tags them through"
          + " their own listener: their last fall leaves no banner, and only the new members' last"
          + " fall plants one")
  void aNewHeroPlayTagsTheMembersStillAlive(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(folder);
    scene.fillElixir();
    scene.playAndRun(SQUAD_CARD, X, Y, "s");
    scene.step();
    List<CharacterEntity> earlier = scene.named(SQUAD_MEMBER);
    assertThat(earlier).hasSize(SQUAD_COUNT);
    scene.cycleBackToTheSquad();
    assertThat(scene.named(SQUAD_MEMBER)).containsAnyElementsOf(earlier);
    // The new members behind the earlier ones, which fall first.
    scene.playAndRun(SQUAD_CARD, X, 8000, "t");
    int limit = scene.tick() + 1000;
    while (scene.named(SQUAD_MEMBER).stream().anyMatch(earlier::contains)) {
      assertThat(scene.tick()).isLessThan(limit);
      assertThat(scene.named(BANNER)).isEmpty();
      scene.step();
    }
    assertThat(scene.named(SQUAD_MEMBER)).isNotEmpty();
    while (!scene.named(SQUAD_MEMBER).isEmpty()) {
      assertThat(scene.tick()).isLessThan(limit);
      assertThat(scene.named(BANNER)).isEmpty();
      scene.step();
    }
    assertThat(scene.named(BANNER)).hasSize(1);
  }

  /**
   * A Ladder match of side 0's squad card in its hero slot and seven filler cards, the squad card
   * in the hand; side 1 holds the eight filler cards and plays nothing.
   */
  private static final class Scene {

    final Standard1v1Battle battle;
    final LadderMatch match;

    /**
     * @param folder the folder the tables are written into
     */
    Scene(Path folder) throws IOException {
      GameTables tables = HeroFormTables.write(folder);
      int[] slots = new int[8];
      slots[0] = MatchSide.HERO_SLOT;
      Standard1v1Battle made = null;
      LadderMatch started = null;
      for (int word = 0; started == null || !inHand(started, SQUAD_CARD); word++) {
        // The towers fight: a princess tower's shots take the squad down.
        made = new Standard1v1Battle(tables);
        started =
            made.startLadderMatch(
                SpawnedChampionTables.deck(SQUAD_CARD),
                SpawnedChampionTables.fillers(),
                word,
                0,
                slots,
                new int[8]);
      }
      battle = made;
      match = started;
    }

    /** The king's first champion slot of side 0. */
    ChampionController slot() {
      return battle.getWorld().kingTower(0).championSlot(1);
    }

    int tick() {
      return battle.getBattle().getTick();
    }

    /** Gives side 0's play of the squad card once the match lets it; the play runs later. */
    void play(int x, int y, String name) {
      while (tick() < match.getStartCooldownTicks() || match.side(0).wholeElixir() < CARD_COST) {
        step();
      }
      battle.play(tick(), card(SQUAD_CARD), LEVEL, 0, x, y, name);
    }

    /** Steps until side 0's elixir bar is full. */
    void fillElixir() {
      int full = 10;
      while (match.side(0).wholeElixir() < full) {
        step();
      }
    }

    /**
     * Side 0's squad played once the match lets it, stepped until its last member has fallen and
     * left the banner; answers the banner.
     */
    CharacterEntity plantBanner() {
      play(X, Y, "s");
      int limit = tick() + 1000;
      while (named(BANNER).isEmpty()) {
        assertThat(tick()).isLessThan(limit);
        step();
      }
      return named(BANNER).get(0);
    }

    /**
     * Plays the cheapest other card of side 0's hand at the back of the right lane, as soon as the
     * hand is full and the elixir is there, until the squad card is back in the hand.
     */
    void cycleBackToTheSquad() {
      int limit = tick() + 400;
      int made = 0;
      while (!inHand(match, SQUAD_CARD)) {
        assertThat(tick()).isLessThan(limit);
        List<MatchCard> deck = match.side(0).deck();
        int[] slots = match.side(0).getHand().slots();
        MatchCard cheapest = null;
        boolean full = true;
        for (int index : slots) {
          if (index < 0) {
            full = false;
          } else if (cheapest == null || deck.get(index).cost() < cheapest.cost()) {
            cheapest = deck.get(index);
          }
        }
        if (full && cheapest != null && match.side(0).wholeElixir() >= cheapest.cost()) {
          playAndRun(cheapest.name(), 14500, 4000, "c" + made++);
        } else {
          step();
        }
      }
    }

    /** Gives side 0's play of a card and steps until the play has run, which the match passed. */
    void playAndRun(String card, int x, int y, String name) {
      int before = battle.getPlays().size();
      battle.play(tick(), card(card), LEVEL, 0, x, y, name);
      while (battle.getPlays().size() == before) {
        step();
      }
      assertThat(battle.getPlays().get(before).matchCode()).as(name).isZero();
    }

    DeployCard card(String name) {
      return battle.getWorld().getRecords().card(name);
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

  private static boolean inHand(LadderMatch match, String card) {
    List<MatchCard> deck = match.side(0).deck();
    return Arrays.stream(match.side(0).getHand().slots())
        .anyMatch(index -> index >= 0 && deck.get(index).name().equals(card));
  }
}
