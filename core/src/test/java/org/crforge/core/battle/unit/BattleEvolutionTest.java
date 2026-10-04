package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.match.EvolutionItem;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.match.MirrorItem;
import org.crforge.core.battle.projectile.ProjectileEntity;
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

  /** Eight different cards of at most three elixir: a spell and a building first. */
  private static final List<String> CHEAP_DECK =
      List.of("Zap", "Cannon", "Skeletons", "IceSpirits", "Goblins", "Bats", "Knight", "Archer");

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
      "an evolution slot's spell and building cards count their plays as a troop card does and are"
          + " cast as their evolved rows once the count reaches the evolved row's DarkElixirCost:"
          + " Zap and Cannon plain twice, evolved on the third play, plain again on the fourth")
  void anEvolutionSlotsSpellAndBuildingEvolve() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    int[] slots = new int[8];
    slots[0] = MatchSide.EVOLUTION_SLOT;
    slots[1] = MatchSide.EVOLUTION_SLOT;
    LadderMatch match = battle.startLadderMatch(CHEAP_DECK, KNIGHTS, 0, 0, slots, NO_SLOTS);
    List<Standard1v1Battle.Play> zaps = new ArrayList<>();
    List<Standard1v1Battle.Play> cannons = new ArrayList<>();
    playThrough(battle, match, 4, zaps, cannons);

    assertThat(zaps).extracting(play -> play.evolution().field()).containsExactly(0, 0, 1, 0);
    assertThat(zaps)
        .extracting(play -> play.evolution().spell().name())
        .containsExactly("Zap", "Zap", "Zap_EV1", "Zap");
    assertThat(zaps).extracting(play -> play.evolution().count()).containsExactly(0, 1, 2, 0);
    assertThat(cannons).extracting(play -> play.evolution().field()).containsExactly(0, 0, 1, 0);
    assertThat(cannons)
        .extracting(play -> play.units().get(0).getData().name())
        .containsExactly("Cannon", "Cannon", "Cannon_EV1", "Cannon");
    // Every play was placed: none was turned away by a gate.
    assertThat(battle.getPlays()).allSatisfy(play -> assertThat(play.matchCode()).isZero());
  }

  @Test
  @DisplayName(
      "the evolved Goblin Barrel's cast runs its mirrored extra spell on the king at once: a decoy"
          + " barrel from the barrel's start to the barrel's aim turned over across the arena's"
          + " width, made right after it, whose impact makes three GoblinDummy in that lane")
  void anEvolvedGoblinBarrelCastsItsDecoy() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    List<String> deck = new ArrayList<>(CHEAP_DECK);
    deck.set(0, "GoblinBarrel");
    LadderMatch match =
        battle.startLadderMatch(deck, KNIGHTS, 0, 0, first(MatchSide.EVOLUTION_SLOT), NO_SLOTS);
    MatchSide side = match.side(0);
    int barrels = 0;
    int tick = 20;
    while (barrels < 3) {
      tick += 200;
      run(battle, tick - 1);
      int pick = -1;
      for (int index : side.getHand().slots()) {
        if (index == 0) {
          pick = 0;
          break;
        }
        if (pick < 0) {
          pick = index;
        }
      }
      DeployCard card = battle.getWorld().getRecords().card(side.deck().get(pick).name());
      boolean barrel = pick == 0;
      battle.play(
          tick, card, LEVEL, 0, barrel ? 14500 : 3500, barrel ? 23000 : 10000, card.name() + tick);
      run(battle, tick);
      if (barrel) {
        barrels++;
      }
    }
    assertThat(battle.getPlays())
        .filteredOn(play -> play.name().startsWith("GoblinBarrel"))
        .extracting(play -> play.evolution().spell().name())
        .containsExactly("GoblinBarrel", "GoblinBarrel", "GoblinBarrel_EV1");

    List<ProjectileEntity> barrelsInFlight = new ArrayList<>();
    for (BattleEntity entity : battle.getBattle().getHolder().entities()) {
      if (entity instanceof ProjectileEntity projectile
          && projectile.getData().name().startsWith("GoblinBarrelSpell")) {
        barrelsInFlight.add(projectile);
      }
    }
    assertThat(barrelsInFlight).hasSize(2);
    ProjectileEntity real = barrelsInFlight.get(0);
    ProjectileEntity decoy = barrelsInFlight.get(1);
    assertThat(decoy.getId()).isEqualTo(real.getId() + 1);
    assertThat(decoy.side()).isEqualTo(real.side());
    assertThat(decoy.getPackedLevel()).isEqualTo(real.getPackedLevel());
    // The placement takes the barrel's point to a tile's centre; the decoy's is that turned over.
    assertThat(real.getAimX()).isEqualTo(14500);
    assertThat(List.of(decoy.getAimX(), decoy.getAimY()))
        .containsExactly(18000 - real.getAimX(), real.getAimY());
    assertThat(List.of(decoy.getStartX(), decoy.getStartY(), decoy.getStartZ()))
        .containsExactly(real.getStartX(), real.getStartY(), real.getStartZ());
    assertThat(decoy.getDelayMs()).isZero();

    run(battle, tick + 56);
    List<int[]> dummies = new ArrayList<>();
    for (BattleEntity entity : battle.getBattle().getHolder().entities()) {
      if (entity instanceof CharacterEntity unit
          && unit.getData().name().equals("GoblinDummy")
          && unit.side() == 0) {
        dummies.add(new int[] {unit.getView().getX(), unit.getView().getY()});
      }
    }
    assertThat(dummies).hasSize(3);
    assertThat(dummies).allSatisfy(at -> assertThat(at[0]).isBetween(2000, 5000));
  }

  @Test
  @DisplayName(
      "a spell in a hero slot, the Mirror in either slot, and two copies of an evolution slot's"
          + " card are refused")
  void theSlotsNoReferenceHoldsAreRefused() {
    List<String> zaps =
        List.of("Zap", "Archer", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror");
    assertThatThrownBy(
            () ->
                new Standard1v1Battle(GameData.tables())
                    .startLadderMatch(zaps, KNIGHTS, 0, 0, first(MatchSide.HERO_SLOT), NO_SLOTS))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("Zap in an evolution or hero slot, which is not a troop card");
    int[] mirrorSlot = new int[8];
    mirrorSlot[2] = MatchSide.HERO_SLOT;
    assertThatThrownBy(
            () ->
                new Standard1v1Battle(GameData.tables())
                    .startLadderMatch(KNIGHT_MIRRORS, KNIGHTS, 0, 0, mirrorSlot, NO_SLOTS))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("Mirror in an evolution or hero slot");
    mirrorSlot[2] = MatchSide.EVOLUTION_SLOT;
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

  /**
   * Plays side 0's cards 200 ticks apart, from tick 20, until the first two deck cards have each
   * been played the given number of times: the first deck card whenever it is in the hand, else the
   * second, else the first other card in hand order. Spells go to (9000, 9000), other cards to
   * (3500, 10000).
   */
  private static void playThrough(
      Standard1v1Battle battle,
      LadderMatch match,
      int times,
      List<Standard1v1Battle.Play> first,
      List<Standard1v1Battle.Play> second) {
    MatchSide side = match.side(0);
    String firstName = side.deck().get(0).name();
    String secondName = side.deck().get(1).name();
    int[] played = new int[2];
    for (int tick = 20; played[0] < times || played[1] < times; tick += 200) {
      run(battle, tick - 1);
      int pick = -1;
      for (int index : side.getHand().slots()) {
        if (index == 0 && played[0] < times) {
          pick = 0;
          break;
        }
        if (index == 1 && played[1] < times) {
          pick = 1;
        } else if (pick < 0 && index >= 2) {
          pick = index;
        }
      }
      DeployCard card = battle.getWorld().getRecords().card(side.deck().get(pick).name());
      boolean spell = card.spell();
      String name = card.name() + "-" + tick;
      battle.play(tick, card, LEVEL, 0, spell ? 9000 : 3500, spell ? 9000 : 10000, name);
      run(battle, tick);
      Standard1v1Battle.Play play = battle.getPlays().get(battle.getPlays().size() - 1);
      if (pick < 2) {
        played[pick]++;
        (pick == 0 ? first : second).add(play);
      }
    }
    assertThat(firstName).isNotEqualTo(secondName);
  }

  /** Steps the battle through the given tick. */
  private static void run(Standard1v1Battle battle, int lastTick) {
    while (battle.getBattle().getTick() <= lastTick) {
      battle.getBattle().step();
    }
  }
}
