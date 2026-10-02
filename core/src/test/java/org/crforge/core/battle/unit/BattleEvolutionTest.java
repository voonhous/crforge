package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.EvolutionItem;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.match.MirrorItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Evolution and hero slots where the reference runs do not take them: a card in both slots, the
 * hero form's champion and its Mirror, and the slots and plays a match refuses.
 */
class BattleEvolutionTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /**
   * Two cards and six Mirrors. The shuffle holds the Mirrors back while it draws the first four, so
   * the opening hand is the two cards and then two Mirrors.
   */
  private static final List<String> GIANT_MIRRORS =
      List.of("Giant", "Archer", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror");

  private static final List<String> KNIGHT_MIRRORS =
      List.of("Knight", "Archer", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror");

  private static final List<String> KNIGHTS = Collections.nCopies(8, "Knight");

  private static final int[] NO_SLOTS = new int[8];

  /** Slot flags with the first card's set to the given flags. */
  private static int[] first(int flags) {
    int[] slots = new int[8];
    slots[0] = flags;
    return slots;
  }

  @Test
  @DisplayName(
      "a hero slot's Giant is played as its hero form for that row's cost, its champion the one"
          + " the king's first slot follows from the deck pass")
  void aHeroSlotPlaysTheHeroForm() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    LadderMatch match =
        battle.startLadderMatch(GIANT_MIRRORS, KNIGHTS, 0, 0, first(MatchSide.HERO_SLOT), NO_SLOTS);
    assertThat(battle.getWorld().kingTower(0).championSlot(1).getChampion().name())
        .isEqualTo("GiantHero");
    battle.play(20, GameData.card("Giant"), LEVEL, 0, 3500, 10000, "g");
    run(battle, 20);

    Standard1v1Battle.Play play = battle.getPlays().get(0);
    EvolutionItem item = play.evolution();
    assertThat(item.field()).isEqualTo(EvolutionItem.HERO);
    assertThat(item.spell().name()).isEqualTo("Giant_hero");
    assertThat(item.cost()).isEqualTo(5);
    assertThat(play.units()).extracting(unit -> unit.getData().name()).containsExactly("GiantHero");
    assertThat(match.side(0).getSpent()).isEqualTo(5 * MatchSide.SCALE);
    // The hand cycles the deck card, and the last card kept is the Giant.
    assertThat(match.side(0).lastPlayed().name()).isEqualTo("Giant");
  }

  @Test
  @DisplayName(
      "a hero play is gated on and spends its row's cost and starts its row's production stop,"
          + " which no shipped row sets apart from its card's")
  void aHeroPlayCostsItsRow(@TempDir Path folder) throws IOException {
    GameTables tables =
        GameData.altered(
            folder,
            "spells_hero_form",
            rows ->
                GameData.columns(rows, "Giant_hero")
                    .put("ManaCost", 6)
                    .put("ElixirProductionStopTime", 1000));
    Standard1v1Battle battle = new Standard1v1Battle(tables);
    LadderMatch match =
        battle.startLadderMatch(GIANT_MIRRORS, KNIGHTS, 0, 0, first(MatchSide.HERO_SLOT), NO_SLOTS);
    battle.play(20, battle.getWorld().getRecords().card("Giant"), LEVEL, 0, 3500, 10000, "g");
    run(battle, 20);

    assertThat(battle.getPlays().get(0).evolution().cost()).isEqualTo(6);
    assertThat(match.side(0).getSpent()).isEqualTo(6 * MatchSide.SCALE);
    // The stop the play started, less the 50 its king's visit of the same tick took off.
    assertThat(match.side(0).getProductionStopMs()).isEqualTo(1000 - 50);
  }

  @Test
  @DisplayName(
      "a Mirror after a hero play repeats the hero row for its cost plus the Mirror's, and is"
          + " refused as a Mirror of a champion")
  void aMirrorAfterAHeroPlayRepeatsTheHeroRow() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    LadderMatch match =
        battle.startLadderMatch(GIANT_MIRRORS, KNIGHTS, 0, 0, first(MatchSide.HERO_SLOT), NO_SLOTS);
    battle.play(20, GameData.card("Giant"), LEVEL, 0, 3500, 10000, "g");
    run(battle, 20);

    MirrorItem item = match.mirrorItem(0, match.deckIndex(0, "Mirror"), LEVEL);
    assertThat(item.repeats().name()).isEqualTo("Giant_hero");
    assertThat(item.cost()).isEqualTo(1 + 5);
    battle.playMirror(300, "Mirror", LEVEL, 0, 14500, 10000, "m");
    assertThatThrownBy(() -> run(battle, 300))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("a Mirror of the champion Giant_hero");
  }

  @Test
  @DisplayName(
      "a card in both slots is played as its hero form while its count is short, and that play"
          + " resets the count, so it never evolves")
  void aCardInBothSlotsNeverEvolves() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    LadderMatch match =
        battle.startLadderMatch(
            KNIGHT_MIRRORS,
            KNIGHTS,
            0,
            0,
            first(MatchSide.EVOLUTION_SLOT | MatchSide.HERO_SLOT),
            NO_SLOTS);
    battle.play(20, GameData.card("Knight"), LEVEL, 0, 3500, 10000, "k");
    run(battle, 20);

    EvolutionItem played = battle.getPlays().get(0).evolution();
    assertThat(played.field()).isEqualTo(EvolutionItem.HERO);
    assertThat(played.spell().name()).isEqualTo("Knight_hero");
    assertThat(battle.getPlays().get(0).units())
        .extracting(unit -> unit.getData().name())
        .containsExactly("KnightHero");
    assertThat(match.side(0).evolutionCount(0)).isZero();
    assertThat(match.item(0, 0).field()).isEqualTo(EvolutionItem.HERO);
  }

  @Test
  @DisplayName(
      "a card's slot outside a match's troop cards, and two copies of an evolution slot's card,"
          + " are refused")
  void theSlotsNoReferenceHoldsAreRefused() {
    List<String> zaps =
        List.of("Zap", "Archer", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror");
    assertThatThrownBy(
            () ->
                new Standard1v1Battle(GameData.tables())
                    .startLadderMatch(
                        zaps, KNIGHTS, 0, 0, first(MatchSide.EVOLUTION_SLOT), NO_SLOTS))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("Zap in an evolution or hero slot");
    int[] mirrorSlot = new int[8];
    mirrorSlot[2] = MatchSide.HERO_SLOT;
    assertThatThrownBy(
            () ->
                new Standard1v1Battle(GameData.tables())
                    .startLadderMatch(KNIGHT_MIRRORS, KNIGHTS, 0, 0, mirrorSlot, NO_SLOTS))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("Mirror in an evolution or hero slot");
    assertThatThrownBy(
            () ->
                new Standard1v1Battle(GameData.tables())
                    .startLadderMatch(
                        KNIGHTS, KNIGHTS, 0, 0, first(MatchSide.EVOLUTION_SLOT), NO_SLOTS))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("two copies of Knight");
  }

  @Test
  @DisplayName(
      "a play of an evolution slot's card while another play of it is due, whose item the client"
          + " builds from the count before that play, is refused")
  void aPlayWhileAnotherOfTheCardIsDueIsRefused() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    battle.startLadderMatch(
        KNIGHT_MIRRORS, KNIGHTS, 0, 0, first(MatchSide.EVOLUTION_SLOT), NO_SLOTS);
    battle.play(20, GameData.card("Knight"), LEVEL, 0, 3500, 10000, "k1");
    battle.play(30, GameData.card("Knight"), LEVEL, 0, 3500, 10000, "k2");

    assertThatThrownBy(() -> run(battle, 30))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("k2: a play of Knight, an evolution slot's card");
  }

  /** Steps the battle through the given tick. */
  private static void run(Standard1v1Battle battle, int lastTick) {
    while (battle.getBattle().getTick() <= lastTick) {
      battle.getBattle().step();
    }
  }
}
