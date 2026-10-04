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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Goblins' hero form played with no ability use: its champion slot follows the banner the card
 * links, the goblins hold the slot's button state while they live, and the last goblin to fall
 * leaves the banner on its spot, which stands for its timer and the wait after it and then goes.
 */
class BattleGoblinHeroTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String BANNER = "GoblinHero_Flag_Building";

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
      "a request for the banner's ability, a building's cast that no reference holds, is refused;"
          + " a command names only a unit a play made, so none reaches it")
  void theBannersAbilityIsRefused() {
    Standard1v1Battle battle = null;
    LadderMatch match = null;
    for (int word = 0; match == null || !inHand(match, "Goblins"); word++) {
      battle = new Standard1v1Battle(GameData.tables());
      match = battle.startLadderMatch(GOBLINS_DECK, KNIGHTS, word, 0, heroFirst(), new int[8]);
    }
    int cost = GameData.records().matchCard("Goblins").cost();
    while (match.side(0).wholeElixir() < cost) {
      step(battle);
    }
    battle.play(battle.getBattle().getTick(), GameData.card("Goblins"), LEVEL, 0, 3500, 14000, "g");
    int limit = battle.getBattle().getTick() + 1000;
    while (named(battle, BANNER).isEmpty()) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      step(battle);
    }
    CharacterEntity banner = named(battle, BANNER).get(0);
    Standard1v1Battle played = battle;
    played.useAbility(played.getBattle().getTick(), 0, banner.name(), "a");
    assertThatThrownBy(() -> step(played))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("which no play of side 0 made");
    assertThatThrownBy(banner::requestAbility)
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("GoblinHero_Ability as a building, which no reference holds");
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
