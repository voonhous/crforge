package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.pathfinding.EntityFlags;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Mega Minion's hero form played from the hero slot: its mark searches the battle for a target
 * and, with none, disables the ability; while it deploys its hand-over greys the button out. A mark
 * that finds a target is refused, as is a use of the ability.
 */
class BattleMegaMinionHeroTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String HERO = "MegaMinionHero";

  private static final String MARK = "MegaMinion_hero_mark_target";

  private static final String HAND_OVER = "MegaMinion_hero_ability_action";

  /** The Mega Minion first, in the hero slot, and seven other cards. */
  private static final List<String> DECK =
      List.of(
          "MegaMinion", "Archer", "Knight", "Giant", "Minions", "Musketeer", "Fireball", "Arrows");

  private static final List<String> KNIGHTS = Collections.nCopies(8, "Knight");

  @Test
  @DisplayName(
      "with no enemy troop the mark finds no target: the hero carries ABILITY_DISABLED, the"
          + " hand-over greys the button out while the hero deploys, and both runs last")
  void withNoTargetTheAbilityIsDisabled() {
    Standard1v1Battle battle = heroPlayed();
    CharacterEntity hero = named(battle, HERO).get(0);
    ChampionController slot = battle.getWorld().kingTower(0).championSlot(1);
    assertThat(slot.follows(hero)).isTrue();
    int deploying = 0;
    while (hero.getView().getState() == GridEntityState.DEPLOYING) {
      assertThat(runs(hero)).contains(MARK, HAND_OVER);
      assertThat(hero.getView().getFlags() & EntityFlags.ABILITY_DISABLED).isNotZero();
      // The hand-over writes the disabled state for the step, which wins the working out.
      assertThat(slot.getState()).isEqualTo(ChampionController.DISABLED);
      deploying++;
      step(battle);
    }
    assertThat(deploying).isGreaterThan(10);
    for (int i = 0; i < 80; i++) {
      step(battle);
      assertThat(runs(hero)).contains(MARK, HAND_OVER);
      assertThat(hero.getView().getFlags() & EntityFlags.ABILITY_DISABLED).isNotZero();
    }
  }

  @Test
  @DisplayName("a mark that finds an enemy troop to mark is refused")
  void aTargetToMarkIsRefused() {
    Standard1v1Battle battle = heroPlayed();
    int tick = battle.getBattle().getTick();
    battle.play(tick, GameData.card("Knight"), LEVEL, 1, 14500, 25500, "k");
    assertThatThrownBy(
            () -> {
              for (int i = 0; i < 120; i++) {
                step(battle);
              }
            })
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining(MARK)
        .hasMessageContaining("Knight");
  }

  /** A battle with the hero Mega Minion played at (3500, 14000), one step after it appears. */
  private static Standard1v1Battle heroPlayed() {
    Standard1v1Battle battle = null;
    LadderMatch match = null;
    for (int word = 0; match == null || !inHand(match, "MegaMinion"); word++) {
      battle = new Standard1v1Battle(GameData.tables());
      match = battle.startLadderMatch(DECK, KNIGHTS, word, 0, heroFirst(), new int[8]);
    }
    int cost = GameData.records().matchCard("MegaMinion").cost();
    while (match.side(0).wholeElixir() < cost) {
      step(battle);
    }
    battle.play(
        battle.getBattle().getTick(), GameData.card("MegaMinion"), LEVEL, 0, 3500, 14000, "m");
    int limit = battle.getBattle().getTick() + 200;
    while (named(battle, HERO).isEmpty()) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      step(battle);
    }
    step(battle);
    return battle;
  }

  /** The names of the rows a unit's holder runs. */
  private static List<String> runs(CharacterEntity unit) {
    return unit.actionHolder().running().stream()
        .map(ActionInstance::getAction)
        .map(action -> action.name())
        .toList();
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
