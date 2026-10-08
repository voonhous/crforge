package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.SetIndicatorOnTarget;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Mega Minion hero's mark losing its target: as the marked object leaves the battle the mark
 * schedules its died action on the hero, the hero its cause, and drops the target. The died action
 * is the hero's ready action, which restarts the cooldown of the champion slot that follows the
 * hero at the full cooldown, leaving the charges as they are. A mark that waits out its hero's
 * cooldown does not search while the cooldown runs: its counter runs on, and it searches again once
 * the cooldown is out.
 */
class BattleMegaMinionMarkDiedTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String HERO = "MegaMinionHero";

  private static final String ABILITY = "MegaMinion_Teleport_Ability";

  /** A cooldown three times the mark's search delay, so the pause outlasts the delay. */
  private static final int LONG_COOLDOWN_MS = 3000;

  private static final List<String> DECK =
      List.of(
          "MegaMinion", "Archer", "Knight", "Giant", "Minions", "Musketeer", "Fireball", "Arrows");

  private static final List<String> OTHER =
      List.of(
          "Knight", "Archer", "Giant", "Minions", "Musketeer", "Fireball", "Arrows", "MegaMinion");

  @Test
  @DisplayName(
      "as the marked troop leaves, the mark drops it and runs its died action on the hero, which"
          + " restarts the hero slot's cooldown at the full cooldown and leaves its charges")
  void theDiedActionRestartsTheCooldown() {
    GameTables tables = GameData.tables();
    BattleRecords records = GameData.records();
    Standard1v1Battle battle = heroPlayed(tables, records, "Knight");
    CharacterEntity hero = named(battle, HERO).get(0);
    ChampionController slot = battle.getWorld().kingTower(0).championSlot(1);
    CharacterEntity knight = marked(battle, records, hero, "Knight");
    assertThat(slot.getCooldownMs()).isZero();
    int charges = slot.getCharges();
    battle.getWorld().kill(knight, null);
    // The Knight leaves the battle in the next step: its leave notice drops the mark's target,
    // and the died action waits in the hero's queue for a pending pass.
    int limit = battle.getBattle().getTick() + 5;
    while (mark(hero).target() != null) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      assertThat(slot.getCooldownMs()).isZero();
      step(battle);
    }
    // A pending pass restarts the cooldown at the full cooldown, and the king's run pass of the
    // same step takes its first 50 off.
    while (slot.getCooldownMs() == 0) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      step(battle);
    }
    assertThat(slot.getCooldownMs()).isEqualTo(slot.getCooldownFullMs() - 50);
    assertThat(slot.getCharges()).isEqualTo(charges);
    step(battle);
    assertThat(slot.getCooldownMs()).isEqualTo(slot.getCooldownFullMs() - 100);
  }

  @Test
  @DisplayName(
      "after its target leaves, the mark does not search while the hero slot's restarted"
          + " cooldown runs, past its own search delay, and searches again on the step the"
          + " cooldown is out")
  void thePauseHoldsTheSearchUntilTheCooldownIsOut(@TempDir Path folder) throws IOException {
    GameTables tables =
        GameData.altered(
            folder,
            "character_abilities",
            rows ->
                ((ObjectNode) rows.get(ABILITY).get("columns")).put("Cooldown", LONG_COOLDOWN_MS));
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle battle = heroPlayed(tables, records, "Archer");
    CharacterEntity hero = named(battle, HERO).get(0);
    ChampionController slot = battle.getWorld().kingTower(0).championSlot(1);
    // Two Archers: the mark holds one, and finds the other once it searches again.
    CharacterEntity archer = marked(battle, records, hero, "Archer");
    assertThat(named(battle, "Archer")).hasSize(2);
    battle.getWorld().kill(archer, null);
    int limit = battle.getBattle().getTick() + 5;
    while (slot.getCooldownMs() == 0) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      step(battle);
    }
    assertThat(mark(hero).target()).isNull();
    assertThat(slot.getCooldownMs()).isEqualTo(LONG_COOLDOWN_MS - 50);
    // The search delay passes long before the cooldown is out; the mark waits for the cooldown.
    int steps = 1;
    while (slot.getCooldownMs() > 0) {
      assertThat(mark(hero).target())
          .as("no search with %d ms of cooldown left", slot.getCooldownMs())
          .isNull();
      step(battle);
      steps++;
    }
    assertThat(steps).isEqualTo(LONG_COOLDOWN_MS / 50);
    // The king's run pass, before the hero's, ran the cooldown out: the mark searches this step.
    assertThat(mark(hero).target()).isNotNull();
    assertThat(mark(hero).target().id()).isNotEqualTo(archer.getId());
  }

  /**
   * Plays an enemy troop card and steps until the hero's mark holds one of its troops, a few steps
   * more; the troop it holds.
   */
  private static CharacterEntity marked(
      Standard1v1Battle battle, BattleRecords records, CharacterEntity hero, String card) {
    int tick = battle.getBattle().getTick();
    battle.play(tick, records.card(card), LEVEL, 1, 14500, 25500, "e");
    int limit = tick + 60;
    while (markTarget(hero) == null) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      step(battle);
    }
    for (int i = 0; i < 5; i++) {
      step(battle);
    }
    int id = mark(hero).target().id();
    return named(battle, card).stream()
        .filter(unit -> unit.getId() == id)
        .findFirst()
        .orElseThrow();
  }

  /** The hero's mark run. */
  private static SetIndicatorOnTarget.Run mark(CharacterEntity hero) {
    return hero.actionHolder().running().stream()
        .filter(SetIndicatorOnTarget.Run.class::isInstance)
        .map(SetIndicatorOnTarget.Run.class::cast)
        .findFirst()
        .orElseThrow();
  }

  /**
   * The target of the hero's mark, or null while it has none or the mark has not started yet: the
   * hero's starting group starts it 1500 ms in.
   */
  private static SetIndicatorOnTarget.Candidate markTarget(CharacterEntity hero) {
    return hero.actionHolder().running().stream()
        .filter(SetIndicatorOnTarget.Run.class::isInstance)
        .map(SetIndicatorOnTarget.Run.class::cast)
        .findFirst()
        .map(SetIndicatorOnTarget.Run::target)
        .orElse(null);
  }

  /**
   * A battle of the tables with the hero Mega Minion played at (3500, 14000), one step after, the
   * other side holding the given card.
   */
  private static Standard1v1Battle heroPlayed(
      GameTables tables, BattleRecords records, String enemyCard) {
    Standard1v1Battle battle = null;
    LadderMatch match = null;
    int[] slots = new int[8];
    slots[0] = MatchSide.HERO_SLOT;
    for (int word = 0;
        match == null || !inHand(match, 0, "MegaMinion") || !inHand(match, 1, enemyCard);
        word++) {
      battle = new Standard1v1Battle(tables);
      match = battle.startLadderMatch(DECK, OTHER, word, 0, slots, new int[8]);
    }
    while (match.side(0).wholeElixir() < records.matchCard("MegaMinion").cost()) {
      step(battle);
    }
    battle.play(
        battle.getBattle().getTick(), records.card("MegaMinion"), LEVEL, 0, 3500, 14000, "m");
    int limit = battle.getBattle().getTick() + 200;
    while (named(battle, HERO).isEmpty()) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      step(battle);
    }
    step(battle);
    return battle;
  }

  private static boolean inHand(LadderMatch match, int side, String card) {
    List<MatchCard> deck = match.side(side).deck();
    return Arrays.stream(match.side(side).getHand().slots())
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
