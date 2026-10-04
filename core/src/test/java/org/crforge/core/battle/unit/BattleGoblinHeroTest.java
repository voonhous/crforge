package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Goblins' hero form: its champion slot follows the banner the card links, the goblins hold the
 * slot's button state while they live, and the last goblin to fall leaves the banner on its spot,
 * which stands for its timer and the wait after it and then goes; an ability command naming the
 * banner has it cast and send the second wave of goblins before it goes.
 */
class BattleGoblinHeroTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String BANNER = "GoblinHero_Flag_Building";

  /** The goblins of the banner's second wave. */
  private static final String DUMMY = "Goblin_dummy";

  /** The Goblins first, in the hero slot, and seven other cards. */
  private static final List<String> GOBLINS_DECK =
      List.of("Goblins", "Archer", "Knight", "Giant", "Minions", "Musketeer", "Fireball", "Arrows");

  private static final List<String> KNIGHTS = Collections.nCopies(8, "Knight");

  /** Steps a banner stands: its timer's 5000 ms, then the 1500 ms its kill waits. */
  private static final int BANNER_STEPS = (5000 + 1500) / 50;

  @Test
  @DisplayName(
      "a hero slot's Goblins make the first champion slot follow the banner the hero form links,"
          + " with the card's deck index")
  void theSlotFollowsTheLinkedBanner() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    battle.startLadderMatch(GOBLINS_DECK, KNIGHTS, 0, 0, heroFirst(), new int[8]);
    ChampionController slot = battle.getWorld().kingTower(0).championSlot(1);
    assertThat(slot.getChampion().name()).isEqualTo(BANNER);
    assertThat(slot.getDeckIndex()).isZero();
    assertThat(battle.getWorld().kingTower(0).championSlot(2).getChampion()).isNull();
  }

  @Test
  @DisplayName(
      "the hero goblins hold the slot's button state not yet available while they live, and the"
          + " play and their start leave the slot one charge; the last to fall leaves the banner on"
          + " its spot, a live copy of the slot, which goes after its timer and the wait")
  void theLastGoblinLeavesTheBanner() {
    Standard1v1Battle battle = null;
    LadderMatch match = null;
    for (int word = 0; match == null || !inHand(match, "Goblins"); word++) {
      battle = new Standard1v1Battle(GameData.tables());
      match = battle.startLadderMatch(GOBLINS_DECK, KNIGHTS, word, 0, heroFirst(), new int[8]);
    }
    ChampionController slot = battle.getWorld().kingTower(0).championSlot(1);
    int cost = GameData.records().matchCard("Goblins").cost();
    while (match.side(0).wholeElixir() < cost) {
      battle.getBattle().step();
    }
    int tick = battle.getBattle().getTick();
    battle.play(tick, GameData.card("Goblins"), LEVEL, 0, 3500, 14000, "g");
    step(battle);
    step(battle);
    assertThat(battle.getPlays().get(0).units())
        .extracting(unit -> unit.getData().name())
        .containsExactly("GoblinHero", "GoblinHero", "GoblinHero", "GoblinHero");
    assertThat(slot.getState()).isEqualTo(ChampionController.NO_YET_AVAILABLE);
    assertThat(slot.getCharges()).isEqualTo(1);

    // Follow the goblins to their last fall: the banner stands where the last one stood.
    int limit = battle.getBattle().getTick() + 1000;
    int lastX = 0;
    int lastY = 0;
    while (!named(battle, "GoblinHero").isEmpty()) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      assertThat(named(battle, BANNER)).isEmpty();
      List<CharacterEntity> goblins = named(battle, "GoblinHero");
      lastX = goblins.get(0).getView().getX();
      lastY = goblins.get(0).getView().getY();
      assertThat(slot.getState()).isEqualTo(ChampionController.NO_YET_AVAILABLE);
      step(battle);
    }
    List<CharacterEntity> banners = named(battle, BANNER);
    assertThat(banners).hasSize(1);
    CharacterEntity banner = banners.get(0);
    assertThat(banner.getView().getX()).isEqualTo(lastX);
    assertThat(banner.getView().getY()).isEqualTo(lastY);
    int planted = battle.getBattle().getTick();

    step(battle);
    assertThat(slot.champions()).containsExactly(banner);
    while (!named(battle, BANNER).isEmpty()) {
      assertThat(battle.getBattle().getTick()).isLessThan(planted + 2 * BANNER_STEPS);
      step(battle);
    }
    assertThat(battle.getBattle().getTick() - planted).isEqualTo(BANNER_STEPS);
  }

  @Test
  @DisplayName(
      "a command naming the banner by its row is refused: a command by name reaches only a unit a"
          + " play made, and the banner is made by the last goblin's fall")
  void theBannerIsNotNamedByItsRow() {
    Standard1v1Battle battle = plantBanner(new LadderMatch[1]);
    CharacterEntity banner = named(battle, BANNER).get(0);
    battle.useAbility(battle.getBattle().getTick(), 0, banner.name(), "a");
    assertThatThrownBy(() -> step(battle))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("which no play of side 0 made");
  }

  @Test
  @DisplayName(
      "a command naming the banner by its game object id pays the ability's elixir and the banner"
          + " casts as a troop does: 500 ms in the casting state, then the second wave, three"
          + " goblins 200 ms apart beside and before it, and the banner goes 50 ms after the last")
  void theBannersAbilitySendsTheSecondWave() {
    LadderMatch[] match = new LadderMatch[1];
    Standard1v1Battle battle = plantBanner(match);
    CharacterEntity banner = named(battle, BANNER).get(0);
    int x = banner.getView().getX();
    int y = banner.getView().getY();
    battle.useAbility(battle.getBattle().getTick(), 0, banner.getId(), "wave");

    // Follow the banner and the second wave one step at a time.
    int span = 24;
    int[] state = new int[span];
    boolean[] standing = new boolean[span];
    int[] wave = new int[span];
    List<CharacterEntity> dummies = List.of();
    for (int k = 1; k < span; k++) {
      step(battle);
      standing[k] = !named(battle, BANNER).isEmpty();
      state[k] = banner.getView().getState();
      dummies = named(battle, DUMMY);
      wave[k] = dummies.size();
      if (k == 1) {
        // The command passes and pays the ability's one elixir; the banner is the one requested.
        assertThat(battle.getAbilityUses()).hasSize(1);
        AbilityCommand.Outcome outcome = battle.getAbilityUses().get(0).outcome();
        assertThat(outcome.code()).isZero();
        assertThat(outcome.elixirBefore() - outcome.elixirAfter()).isEqualTo(KingElixir.SCALE);
        assertThat(outcome.requested()).containsExactly(banner);
      }
      if (k == 10 || k == 18) {
        // The first and the last goblin of the wave stand where their expressions put them: beside
        // the banner toward the middle and a tile before it, then a tile behind it.
        CharacterEntity last = dummies.get(dummies.size() - 1);
        int toMiddle = x > 9000 ? -1000 : 1000;
        assertThat(last.getView().getX()).isEqualTo(k == 10 ? x + toMiddle : x);
        assertThat(last.getView().getY()).isEqualTo(k == 10 ? y + 1000 : y - 1000);
        assertThat(last.side()).isZero();
      }
    }
    // CastTime 500 ms: the casting state over the first nine steps, as the ability's TriggerDelay
    // of 500 ms runs out and fires its activation group.
    for (int k = 1; k <= 9; k++) {
      assertThat(state[k]).as("step %d", k).isEqualTo(GridEntityState.CASTING);
    }
    assertThat(state[10]).isNotEqualTo(GridEntityState.CASTING);
    // The group's spawns at 0, 200 and 400 ms, then its kill at 450 ms takes the banner.
    assertThat(wave[9]).isZero();
    assertThat(wave[10]).isEqualTo(1);
    assertThat(wave[13]).isEqualTo(1);
    assertThat(wave[14]).isEqualTo(2);
    assertThat(wave[17]).isEqualTo(2);
    assertThat(wave[18]).isEqualTo(3);
    assertThat(standing[18]).isTrue();
    assertThat(standing[19]).isFalse();
    assertThat(wave[23]).isEqualTo(3);
  }

  /**
   * A battle in which side 0 played the hero Goblins and its last goblin has just fallen, leaving
   * the banner.
   *
   * @param match receives the battle's match
   */
  private static Standard1v1Battle plantBanner(LadderMatch[] match) {
    Standard1v1Battle battle = null;
    for (int word = 0; match[0] == null || !inHand(match[0], "Goblins"); word++) {
      battle = new Standard1v1Battle(GameData.tables());
      match[0] = battle.startLadderMatch(GOBLINS_DECK, KNIGHTS, word, 0, heroFirst(), new int[8]);
    }
    int cost = GameData.records().matchCard("Goblins").cost();
    while (match[0].side(0).wholeElixir() < cost) {
      step(battle);
    }
    battle.play(battle.getBattle().getTick(), GameData.card("Goblins"), LEVEL, 0, 3500, 14000, "g");
    int limit = battle.getBattle().getTick() + 1000;
    while (named(battle, BANNER).isEmpty()) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      step(battle);
    }
    return battle;
  }

  /** Slot flags with the first card in the hero slot. */
  private static int[] heroFirst() {
    int[] slots = new int[8];
    slots[0] = MatchSide.HERO_SLOT;
    return slots;
  }

  private static boolean inHand(LadderMatch match, String card) {
    List<MatchCard> deck = match.side(0).deck();
    return Arrays.stream(match.side(0).getHand().slots())
        .anyMatch(index -> index >= 0 && deck.get(index).name().equals(card));
  }

  /** The characters of a row the holder lists, in its order. */
  private static List<CharacterEntity> named(Standard1v1Battle battle, String row) {
    return battle.getWorld().getHolder().entities().stream()
        .filter(CharacterEntity.class::isInstance)
        .map(CharacterEntity.class::cast)
        .filter(unit -> unit.getData().name().equals(row))
        .toList();
  }

  private static void step(Standard1v1Battle battle) {
    battle.getBattle().step();
  }
}
