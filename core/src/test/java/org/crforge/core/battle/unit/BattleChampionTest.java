package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A champion's ability where the reference runs do not take it: a second play of the champion card
 * while the first copy lives, a command outside a match, a clone of a unit carrying a buff that is
 * not cloned, and decks with two and three champion cards.
 */
class BattleChampionTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** An Archer Queen and seven Skeletons, which cycle her back for 1 elixir a play. */
  private static final List<String> QUEEN_SKELETONS =
      List.of(
          "ArcherQueen",
          "Skeletons",
          "Skeletons",
          "Skeletons",
          "Skeletons",
          "Skeletons",
          "Skeletons",
          "Skeletons");

  private static final List<String> KNIGHTS = Collections.nCopies(8, "Knight");

  @Test
  @DisplayName(
      "a second play of the champion card makes its slot follow the new copy, its charge"
          + " refilled; the old copy stays as a plain unit, and a command naming it is refused")
  void aSecondPlayIsFollowed() {
    // The towers stand passive, so the first copy lives on; the first player's word whose shuffle
    // deals her into the opening hand.
    Standard1v1Battle battle = null;
    LadderMatch match = null;
    for (int word = 0; match == null || !inHand(match, "ArcherQueen"); word++) {
      battle = new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
      match = battle.startLadderMatch(QUEEN_SKELETONS, KNIGHTS, word, 0);
    }
    ChampionController slot = battle.getWorld().kingTower(0).championSlot(1);
    assertThat(slot.getChampion().name()).isEqualTo("ArcherQueen");

    playWhenReady(battle, match, "ArcherQueen", 3500, 4000, "q");
    CharacterEntity first = battle.getPlays().get(0).units().get(0);
    assertThat(first.getDeployIndex()).isZero();
    // Kept where she stands, so she takes no tower and the match runs on through the cycle.
    first.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    int used = afterDeployWithElixir(battle, match, "ArcherQueen");
    battle.useAbility(used, 0, "q_0", "a1");
    run(battle, used);
    assertThat(lastUse(battle).outcome().code()).isEqualTo(AbilityCommand.OK);
    // Her ability has no cooldown: the use spends a charge of its row's.
    assertThat(slot.getCharges()).isEqualTo(maxCharges("ArcherQueen") - 1);

    // Four plays of Skeletons cycle her back into the hand; each kept where it stands, so no
    // tower falls and the match runs on.
    for (int i = 0; i < 4; i++) {
      playWhenReady(battle, match, "Skeletons", 14500, 4000, "s" + i);
      battle
          .getPlays()
          .get(battle.getPlays().size() - 1)
          .units()
          .forEach(skeleton -> skeleton.setActive(CharacterEntity.MOVEMENT_SLOT, false));
    }
    assertThat(inHand(match, "ArcherQueen")).isTrue();
    playWhenReady(battle, match, "ArcherQueen", 3500, 4000, "q2");
    CharacterEntity second = battle.getPlays().get(battle.getPlays().size() - 1).units().get(0);
    assertThat(second.getDeployIndex()).isEqualTo(5);
    assertThat(slot.getDeployIndex()).isEqualTo(second.getDeployIndex());
    assertThat(slot.getCharges()).isEqualTo(maxCharges("ArcherQueen"));
    assertThat(battle.getWorld().getHolder().entities()).contains(first);

    int now = afterDeployWithElixir(battle, match, "ArcherQueen");
    battle.useAbility(now, 0, "q_0", "old");
    battle.useAbility(now + 1, 0, "q2_0", "new");
    run(battle, now + 1);
    List<Standard1v1Battle.AbilityUse> uses = battle.getAbilityUses();
    assertThat(uses.get(uses.size() - 2).outcome().code()).isEqualTo(AbilityCommand.NO_CHAMPION);
    assertThat(uses.get(uses.size() - 1).outcome().code()).isEqualTo(AbilityCommand.OK);
    assertThat(uses.get(uses.size() - 1).outcome().requested()).containsExactly(second);
  }

  @Test
  @DisplayName(
      "an ability command naming its unit by game object id answers that live unit; an id no live"
          + " unit holds is refused and spends nothing")
  void aCommandByGameObjectIdAnswersOnlyThatLiveUnit() {
    Standard1v1Battle battle = null;
    LadderMatch match = null;
    for (int word = 0; match == null || !inHand(match, "ArcherQueen"); word++) {
      battle = new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
      match = battle.startLadderMatch(QUEEN_SKELETONS, KNIGHTS, word, 0);
    }
    playWhenReady(battle, match, "ArcherQueen", 3500, 4000, "q");
    CharacterEntity queen = battle.getPlays().get(0).units().get(0);

    int now = afterDeployWithElixir(battle, match, "ArcherQueen");
    battle.useAbility(now, 0, queen.getId() + 1000, "unknown");
    battle.useAbility(now + 1, 0, queen.getId(), "queen");
    run(battle, now + 1);

    List<Standard1v1Battle.AbilityUse> uses = battle.getAbilityUses();
    AbilityCommand.Outcome refused = uses.get(uses.size() - 2).outcome();
    assertThat(refused.code()).isEqualTo(AbilityCommand.NO_CHAMPION);
    assertThat(refused.elixirAfter()).isEqualTo(refused.elixirBefore());
    assertThat(uses.get(uses.size() - 1).outcome().code()).isEqualTo(AbilityCommand.OK);
    assertThat(uses.get(uses.size() - 1).outcome().requested()).containsExactly(queen);
    assertThat(uses.get(uses.size() - 1).unit()).isEqualTo("q_0");
  }

  @Test
  @DisplayName("an ability command outside a match, which has no champion slots, is refused")
  void aCommandOutsideAMatchIsRefused() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());

    assertThatThrownBy(() -> battle.useAbility(10, 0, "q_0", "a"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage("a: an ability command outside a match, which has no champion slots");
  }

  @Test
  @DisplayName(
      "a clone of a unit carrying a buff that is not cloned, as an Archer Queen's ability's is, is"
          + " refused")
  void aCloneOfACarrierOfANotClonedBuffIsRefused() {
    Standard1v1Battle battle =
        new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
    // A clone of the champion herself is refused as it is made; her buff on a Knight reaches the
    // clone's copy of its buffs.
    CharacterEntity knight = battle.deploy(0, GameData.unit("Knight"), LEVEL, 0, 3500, 9500, "k");
    run(battle, 25);
    knight
        .getBuffs()
        .apply(
            battle.getWorld().buffData("ArcherQueenRapid"),
            3500,
            knight.getPackedLevel(),
            knight,
            0);

    battle.play(26, GameData.card("Clone"), LEVEL, 0, 3500, 9500, "c");
    assertThatThrownBy(() -> run(battle, 40))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage("k is cloned carrying ArcherQueenRapid, which is not cloned, not modelled");
  }

  @Test
  @DisplayName(
      "a deck with two champion cards binds both slots, walked from the last card: the later card"
          + " to slot 1, the earlier to slot 2, whose champion's ability then passes")
  void aDeckWithTwoChampionsBindsBothSlots() {
    List<String> deck =
        List.of(
            "ArcherQueen",
            "GoldenKnight",
            "Skeletons",
            "Skeletons",
            "Skeletons",
            "Skeletons",
            "Skeletons",
            "Skeletons");
    Standard1v1Battle battle = null;
    LadderMatch match = null;
    for (int word = 0; match == null || !inHand(match, "ArcherQueen"); word++) {
      battle = new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
      match = battle.startLadderMatch(deck, KNIGHTS, word, 0);
    }
    ChampionController first = battle.getWorld().kingTower(0).championSlot(1);
    ChampionController second = battle.getWorld().kingTower(0).championSlot(2);
    assertThat(first.getChampion().name()).isEqualTo("GoldenKnight");
    assertThat(first.getDeckIndex()).isEqualTo(1);
    assertThat(second.getChampion().name()).isEqualTo("ArcherQueen");
    assertThat(second.getDeckIndex()).isZero();

    // The Archer Queen's play is followed by the second slot, which answers her ability.
    playWhenReady(battle, match, "ArcherQueen", 3500, 4000, "q");
    CharacterEntity queen = battle.getPlays().get(0).units().get(0);
    assertThat(second.getDeployIndex()).isEqualTo(queen.getDeployIndex());
    int used = afterDeployWithElixir(battle, match, "ArcherQueen");
    battle.useAbility(used, 0, "q_0", "a");
    run(battle, used);
    assertThat(lastUse(battle).outcome().code()).isEqualTo(AbilityCommand.OK);
    assertThat(lastUse(battle).outcome().requested()).containsExactly(queen);
    // A charge of hers spent; the Golden Knight's slot, never played, keeps its own.
    assertThat(second.getCharges()).isEqualTo(maxCharges("ArcherQueen") - 1);
    assertThat(first.getCharges()).isNotZero();
  }

  @Test
  @DisplayName("a deck with three champion cards, more than a king's two slots, is refused")
  void aDeckWithThreeChampionsIsRefused() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    List<String> deck =
        List.of(
            "ArcherQueen",
            "GoldenKnight",
            "MightyMiner",
            "Skeletons",
            "Skeletons",
            "Skeletons",
            "Skeletons",
            "Skeletons");

    assertThatThrownBy(() -> battle.startLadderMatch(deck, KNIGHTS, 0, 0))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(
            "side 0's deck holds 3 champion cards, more than the king's two slots, which no"
                + " reference holds");
  }

  /** Whether a card is in one of side 0's hand slots. */
  private static boolean inHand(LadderMatch match, String card) {
    List<MatchCard> deck = match.side(0).deck();
    return Arrays.stream(match.side(0).getHand().slots())
        .anyMatch(index -> index >= 0 && deck.get(index).name().equals(card));
  }

  /**
   * Steps until side 0 holds the card and the elixir for it, then plays it on the next tick and
   * runs that tick; the play must pass.
   */
  private static void playWhenReady(
      Standard1v1Battle battle, LadderMatch match, String card, int x, int y, String name) {
    int cost = GameData.records().matchCard(card).cost();
    int limit = battle.getBattle().getTick() + 1000;
    while (!inHand(match, card) || match.side(0).wholeElixir() < cost) {
      assertThat(battle.getBattle().getTick())
          .as(
              "%s in the hand %s, elixir %d",
              card, Arrays.toString(match.side(0).getHand().slots()), match.side(0).getElixir())
          .isLessThan(limit);
      battle.getBattle().step();
    }
    int tick = battle.getBattle().getTick();
    battle.play(tick, GameData.card(card), LEVEL, 0, x, y, name);
    run(battle, tick);
    Standard1v1Battle.Play play = battle.getPlays().get(battle.getPlays().size() - 1);
    assertThat(play.name()).isEqualTo(name);
    assertThat(play.matchCode()).as("%s passes the match's gates", name).isZero();
  }

  /**
   * Steps past the last play's deploy, the champion row's DeployTime and a tick, until side 0 holds
   * the elixir its ability row costs, and answers the next tick.
   */
  private static int afterDeployWithElixir(
      Standard1v1Battle battle, LadderMatch match, String champion) {
    int deployed =
        battle.getBattle().getTick()
            + Shipped.number(Shipped.unitRow(champion), "DeployTime") / 50
            + 1;
    int cost = Shipped.number(ability(champion), "ManaCost");
    while (battle.getBattle().getTick() < deployed || match.side(0).wholeElixir() < cost) {
      battle.getBattle().step();
    }
    return battle.getBattle().getTick();
  }

  /** The ability row a champion's row names. */
  private static GameRow ability(String champion) {
    return Shipped.row("character_abilities", Shipped.text(Shipped.unitRow(champion), "Ability"));
  }

  /** The charges a champion's ability row gives it. */
  private static int maxCharges(String champion) {
    return Shipped.number(ability(champion), "MaxCharges");
  }

  private static Standard1v1Battle.AbilityUse lastUse(Standard1v1Battle battle) {
    List<Standard1v1Battle.AbilityUse> uses = battle.getAbilityUses();
    return uses.get(uses.size() - 1);
  }

  /** Steps the battle until it has run the given tick. */
  private static void run(Standard1v1Battle battle, int lastTick) {
    while (battle.getBattle().getTick() <= lastTick) {
      battle.getBattle().step();
    }
  }
}
