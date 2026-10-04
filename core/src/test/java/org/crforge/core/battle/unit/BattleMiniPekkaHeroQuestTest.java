package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.CountingRun;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The hero Mini Pekka's ability level timer (its starting action,
 * MiniPekkaHero_run_timer_continuous) played with no ability use: the timer's run, started on the
 * hero's first step, counts down its 1000 ms start delay 50 a step, then adds 50 a step, or 8000 on
 * a step that finds the hero's tag from its last hit, and raises the level stack each time the
 * progress reaches 22000, three times at most, after which the run stays listed and counts nothing.
 */
class BattleMiniPekkaHeroQuestTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String HERO = "MiniPekkaHero";

  private static final String TIMER = "MiniPekkaHero_run_timer_continuous";

  /** The Mini Pekka first, in the hero slot, and seven other cards. */
  private static final List<String> HERO_DECK =
      List.of(
          "MiniPekka", "Archer", "Knight", "Giant", "Minions", "Musketeer", "Fireball", "Arrows");

  private static final List<String> KNIGHTS = Collections.nCopies(8, "Knight");

  /** Steps the 1000 ms start delay takes, 50 ms each. */
  private static final int DELAY_STEPS = 1000 / 50;

  /** Steps of 50 ms one 22000 ms interval takes with no upgrade. */
  private static final int INTERVAL_STEPS = 22000 / 50;

  @Test
  @DisplayName(
      "the timer starts on the hero's first step, counts its start delay down for 20 steps, raises"
          + " the level stack every 440 steps after it, three times, and then stays listed")
  void theTimerRaisesTheLevelThreeTimes() {
    Standard1v1Battle battle = playedBattle();
    CharacterEntity hero = playHero(battle);
    // Kept in place and out of reach, so no hit gives it its tag.
    hero.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    int first = battle.getBattle().getTick() - 1;
    int key = battle.getWorld().declaredVariable("MiniPekkaHero_levelStack");

    List<Integer> raised = new ArrayList<>();
    int level = hero.variable(key);
    assertThat(level).isZero();
    int end = first + DELAY_STEPS + 3 * INTERVAL_STEPS + 400;
    while (battle.getBattle().getTick() <= end) {
      int tick = battle.getBattle().getTick();
      battle.getBattle().step();
      if (hero.variable(key) != level) {
        level = hero.variable(key);
        raised.add(tick);
      }
    }

    // The k-th counting step is the (20 + k)-th step of the run; the 440th reaches 22000.
    assertThat(raised)
        .containsExactly(
            first + DELAY_STEPS + INTERVAL_STEPS - 1,
            first + DELAY_STEPS + 2 * INTERVAL_STEPS - 1,
            first + DELAY_STEPS + 3 * INTERVAL_STEPS - 1);
    assertThat(level).isEqualTo(3);
    ActionInstance run = timerRun(hero);
    assertThat(run).as("the run never ends by itself").isNotNull();
    assertThat(((CountingRun) run).counter()).as("the last interval is kept").isEqualTo(22000);
  }

  @Test
  @DisplayName(
      "a counting step that finds the hero's tag from the hit of the step before adds 8000 instead"
          + " of 50, and counts out at most one interval")
  void aTaggedStepAddsTheUpgrade() {
    Standard1v1Battle battle = playedBattle();
    CharacterEntity hero = playHero(battle);
    hero.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    CharacterEntity golem =
        battle.deploy(
            battle.getBattle().getTick(),
            GameData.unit("Golem"),
            LEVEL,
            1,
            hero.getView().getX(),
            hero.getView().getY() + 1200,
            "g");
    golem.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    int first = battle.getBattle().getTick() - 1;
    int key = battle.getWorld().declaredVariable("MiniPekkaHero_levelStack");

    List<Integer> tagged = new ArrayList<>();
    List<Integer> progress = new ArrayList<>();
    List<Integer> raised = new ArrayList<>();
    int level = 0;
    while (battle.getBattle().getTick() <= first + 700) {
      int tick = battle.getBattle().getTick();
      battle.getBattle().step();
      if (hero.getBuffs().carries("MiniPekkaHero_buff_for_tag")) {
        tagged.add(tick);
      }
      progress.add((int) ((CountingRun) timerRun(hero)).counter());
      if (hero.variable(key) != level) {
        level = hero.variable(key);
        raised.add(tick);
      }
    }
    assertThat(tagged).hasSizeGreaterThan(3);

    // The timeline the run must give: the tag folded in on a hit's tick is read by the next step.
    List<Integer> expectedRaised = new ArrayList<>();
    List<Integer> expectedProgress = new ArrayList<>();
    int value = 0;
    for (int tick = first; tick <= first + 700; tick++) {
      int step = tick - first;
      if (step >= DELAY_STEPS && expectedRaised.size() < 3) {
        value += tagged.contains(tick - 1) ? 8000 : 50;
        if (value >= 22000) {
          expectedRaised.add(tick);
          if (expectedRaised.size() < 3) {
            value -= 22000;
          }
        }
      }
      if (tick > first) {
        // Recorded from the step after the hero's first, as the loop above records.
        expectedProgress.add(value);
      }
    }
    assertThat(raised).isNotEmpty().containsExactlyElementsOf(expectedRaised);
    assertThat(raised.get(0))
        .as("the hits bring the first level forward")
        .isLessThan(first + DELAY_STEPS + INTERVAL_STEPS - 1);
    assertThat(progress).containsExactlyElementsOf(expectedProgress);
  }

  /** A ladder battle whose side 0 holds the Mini Pekka in its hero slot and in its hand. */
  private static Standard1v1Battle playedBattle() {
    for (int word = 0; ; word++) {
      Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
      LadderMatch match =
          battle.startLadderMatch(HERO_DECK, KNIGHTS, word, 0, heroFirst(), new int[8]);
      if (inHand(match, "MiniPekka")) {
        int cost = GameData.records().matchCard("MiniPekka").cost();
        while (match.side(0).wholeElixir() < cost) {
          battle.getBattle().step();
        }
        return battle;
      }
    }
  }

  /** Plays the Mini Pekka and steps until its hero is placed; the tick after its first step. */
  private static CharacterEntity playHero(Standard1v1Battle battle) {
    battle.play(
        battle.getBattle().getTick(), GameData.card("MiniPekka"), LEVEL, 0, 3500, 14000, "p");
    int limit = battle.getBattle().getTick() + 100;
    while (true) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      battle.getBattle().step();
      List<CharacterEntity> heroes =
          battle.getWorld().getHolder().entities().stream()
              .filter(CharacterEntity.class::isInstance)
              .map(CharacterEntity.class::cast)
              .filter(unit -> unit.getData().name().equals(HERO))
              .toList();
      if (!heroes.isEmpty()) {
        CharacterEntity hero = heroes.get(0);
        assertThat(timerRun(hero)).as("the timer runs from the hero's first step").isNotNull();
        return hero;
      }
    }
  }

  /** The hero's timer run, or null when it is not listed. */
  private static ActionInstance timerRun(CharacterEntity hero) {
    return hero.actionHolder().running().stream()
        .filter(run -> run.getAction().name().equals(TIMER))
        .findFirst()
        .orElse(null);
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
}
