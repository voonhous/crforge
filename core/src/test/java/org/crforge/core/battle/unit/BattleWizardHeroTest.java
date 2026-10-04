package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.target.TargetView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Wizard's hero form played from the hero slot and its ability used. The ability's activation
 * lifts the hero off the ground with a ground-to-air run: it climbs to 3500 over 200 ms, raising
 * FORCE_IS_AIR and NO_ATTACK, turns to the hold at the height, where its path is reset and the
 * row's action at the height is scheduled on it, and is held there for 4600 ms. The activation's
 * instant hit, gated by the hero's target within 5500, raises the hero's instant-hit byte as the
 * run starts, and the hero's next attack visit lands a whole hit at once. The swap to the flying
 * row at the height is refused, so the hold is driven on a copy of the data whose action at the
 * height leaves it out.
 */
class BattleWizardHeroTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String HERO = "WizardHero";

  /** The Wizard first, in the hero slot, and seven other cards. */
  private static final List<String> DECK =
      List.of("Wizard", "Archer", "Knight", "Giant", "Minions", "Valkyrie", "Fireball", "Arrows");

  private static final List<String> KNIGHTS = Collections.nCopies(8, "Knight");

  @Test
  @DisplayName(
      "used while the hero attacks a tower in range, the activation's instant hit raises the"
          + " hero's instant-hit byte in the step its run starts, and the byte is kept through the"
          + " climb")
  void theInstantHitIsSet() {
    Scene scene = new Scene(GameData.tables());
    CharacterEntity hero = scene.abilityUsedAttacking();
    int cast = scene.stepUntilCasting(hero);
    scene.steps(2);
    assertThat(hero.getTargeting().isInstantHit()).isFalse();
    scene.steps(1);
    assertThat(scene.runs).containsExactly((cast + 2) + " start phase 1 counter 200");
    assertThat(hero.getTargeting().isInstantHit()).isTrue();
    // The climb raises NO_ATTACK: no attack visit takes the byte before the turn.
    for (int i = 0; i < 4; i++) {
      scene.steps(1);
      assertThat(hero.getTargeting().isInstantHit()).isTrue();
    }
  }

  @Test
  @DisplayName(
      "used while the hero's target is out of 5500, the instant hit's gate is false and the byte"
          + " is not raised")
  void theInstantHitIsGated() {
    Scene scene = new Scene(GameData.tables());
    CharacterEntity hero = scene.abilityUsed();
    scene.stepUntilCasting(hero);
    scene.steps(3);
    assertThat(scene.runs).hasSize(1);
    TargetView target = hero.getTargeting().getReference();
    assertThat(target).as("a princess tower, out of reach").isNotNull();
    long dx = target.getEntity().getX() - hero.getView().getX();
    long dy = target.getEntity().getY() - hero.getView().getY();
    long reach =
        target.getEntity().getCollisionRadius() + 5500L + hero.getView().getCollisionRadius();
    assertThat(dx * dx + dy * dy).isGreaterThan(reach * reach);
    assertThat(hero.getTargeting().isInstantHit()).isFalse();
  }

  @Test
  @DisplayName(
      "held at the height without the swap, the hero's first attack visit after the instant hit"
          + " rounds its attack time up to a whole hit, which lands at once, and clears the byte")
  void theInstantHitLandsOnTheFirstAttackVisit(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(withoutSwap(folder));
    CharacterEntity hero = scene.abilityUsedAttacking();
    int cast = scene.stepUntilCasting(hero);
    scene.steps(3);
    assertThat(hero.getTargeting().isInstantHit()).isTrue();
    int hitSpeed = hero.getTargeting().getConfig().hitSpeed();
    int before = hero.getTargeting().getAttackTimerMs();
    // The byte waits, through the climb, the turn and the rest of the cast, for the next attack.
    while (hero.getView().getState() != GridEntityState.ATTACKING) {
      assertThat(hero.getTargeting().isInstantHit()).isTrue();
      assertThat(hero.getTargeting().getAttackTimerMs()).isEqualTo(before);
      assertThat(scene.tick()).isLessThan(cast + 40);
      scene.steps(1);
    }
    assertThat(hero.getTargeting().isInstantHit()).isFalse();
    assertThat(hero.getTargeting().isAttackTimeRoundedUp()).isTrue();
    int after = hero.getTargeting().getAttackTimerMs();
    assertThat(after).as("the next whole hit").isEqualTo((before / hitSpeed + 1) * hitSpeed);
    scene.steps(1);
    assertThat(hero.getTargeting().isAttackTimeRoundedUp()).isFalse();
    assertThat(hero.getTargeting().getAttackTimerMs()).as("then one tick").isEqualTo(after + 50);
  }

  @Test
  @DisplayName(
      "the hero climbs to 3500 in five steps from 200 ms after its cast starts, in the air from"
          + " the pre-hook after the first, and the action at the height runs on the turn's step,"
          + " where the swap to the flying row is refused")
  void theHeroClimbs() {
    Scene scene = new Scene(GameData.tables());
    CharacterEntity hero = scene.abilityUsed();
    // The tick count after the step whose state is the cast's first.
    int cast = scene.stepUntilCasting(hero);
    // The trigger delay of 200 ms: the activation starts the run on the third step after it, in a
    // pending pass after that tick's run pass, so its first update is on the next step.
    scene.steps(2);
    assertThat(scene.runs).isEmpty();
    scene.steps(1);
    int start = cast + 2;
    assertThat(scene.runs).containsExactly(start + " start phase 1 counter 200");
    assertThat(hero.flyingHeightOverride()).isEqualTo(3500);
    scene.steps(1);
    assertThat(hero.getView().isAir()).as("FORCE_IS_AIR is raised but not yet in").isFalse();
    scene.steps(1);
    assertThat(hero.getView().isAir()).isTrue();
    assertThat(scene.flags(hero) & scene.world().forceIsAir()).isNotZero();
    scene.steps(2);
    assertThat(scene.runs)
        .containsExactly(
            start + " start phase 1 counter 200",
            (start + 1) + " phase 1 1 counter 200 150 [0]",
            (start + 2) + " phase 1 1 counter 150 100 [875]",
            (start + 3) + " phase 1 1 counter 100 50 [1750]",
            (start + 4) + " phase 1 1 counter 50 0 [2625]");
    assertThat(hero.getTargetView().z()).as("folded a step behind the push").isEqualTo(1750);
    // The turn's step schedules the action at the height, which runs in it: its swap to
    // WizardHero_air, a flying row, is refused. That step would end in the eighth state after the
    // cast's first, where the reference battle shows the hero as WizardHero_air.
    assertThatThrownBy(() -> scene.steps(1))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("taking WizardHero_air asks for a building or a flying row");
    assertThat(scene.tick()).isEqualTo(cast + 7);
  }

  @Test
  @DisplayName(
      "held at the height the hero carries FORCE_IS_AIR and pushes 3500 on each step for 4600 ms;"
          + " the descent that follows is refused")
  void theHeroIsHeld(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(withoutSwap(folder));
    CharacterEntity hero = scene.abilityUsed();
    int turn = scene.stepUntilCasting(hero) + 7;
    scene.steps(8);
    assertThat(scene.runs).last().isEqualTo(turn + " phase 1 2 counter 0 4600 [3500]");
    for (int i = 0; i < 92; i++) {
      scene.steps(1);
      assertThat(scene.runs)
          .last()
          .isEqualTo(
              "%d phase 2 2 counter %d %d [3500]"
                  .formatted(turn + 1 + i, 4600 - 50 * i, 4550 - 50 * i));
      assertThat(hero.getView().isAir()).isTrue();
      assertThat(hero.getTargetView().z()).isEqualTo(3500);
    }
    // The hold's last step finds its counter out: the descent it would start is refused.
    assertThatThrownBy(() -> scene.steps(1))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("WizardHero_ground_to_air brings")
        .hasMessageContaining("back down, which is not modelled");
  }

  /** A copy of the data whose action at the height leaves out the swap to the flying row. */
  private static GameTables withoutSwap(Path folder) throws IOException {
    return GameData.altered(
        folder,
        "actions",
        rows -> {
          ObjectNode reached =
              (ObjectNode) rows.get("WizardHero_on_max_height_reached").get("fields");
          ((ArrayNode) reached.get("SubActions")).remove(1);
          ((ArrayNode) reached.get("SubActionsDelay")).remove(1);
        });
  }

  /** A battle with every start and step of a ground-to-air run recorded. */
  private static final class Scene {
    final Standard1v1Battle battle;
    final LadderMatch match;
    final List<String> runs = new ArrayList<>();

    /** A ladder match on the given data whose first hand holds the hero card. */
    Scene(GameTables tables) {
      Standard1v1Battle candidate = null;
      LadderMatch started = null;
      for (int word = 0; started == null || !inHand(started, "Wizard"); word++) {
        candidate = new Standard1v1Battle(tables);
        started = candidate.startLadderMatch(DECK, KNIGHTS, word, 0, heroFirst(), new int[8]);
      }
      battle = candidate;
      match = started;
      battle
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void groundToAirStarted(
                    int tick,
                    WorldEntity unit,
                    String action,
                    int phase,
                    int runPhase,
                    int counter) {
                  runs.add("%d start phase %d counter %d".formatted(tick, runPhase, counter));
                }

                @Override
                public void groundToAirStepped(
                    int tick,
                    WorldEntity unit,
                    int phaseBefore,
                    int phaseAfter,
                    int counterBefore,
                    int counterAfter,
                    List<Integer> pushes) {
                  runs.add(
                      "%d phase %d %d counter %d %d %s"
                          .formatted(
                              tick, phaseBefore, phaseAfter, counterBefore, counterAfter, pushes));
                }
              });
    }

    /**
     * Plays the hero Wizard at (3500, 14000), as the reference battle does, and uses its ability a
     * second after it deploys, once side 0 holds the ability's cost.
     */
    CharacterEntity abilityUsed() {
      int cost = GameData.records().matchCard("Wizard").cost();
      while (match.side(0).wholeElixir() < cost) {
        steps(1);
      }
      battle.play(tick(), GameData.card("Wizard"), LEVEL, 0, 3500, 14000, "w");
      int limit = tick() + 200;
      while (heroes().isEmpty()) {
        assertThat(tick()).isLessThan(limit);
        steps(1);
      }
      for (int i = 0; i < 40 || match.side(0).wholeElixir() < 1; i++) {
        steps(1);
      }
      CharacterEntity hero = heroes().get(0);
      battle.useAbility(tick(), 0, hero.name(), "a");
      return hero;
    }

    /**
     * Plays the hero Wizard as {@link #abilityUsed} does, and uses its ability on the first step it
     * attacks, as the reference battle does while the hero attacks a princess tower.
     */
    CharacterEntity abilityUsedAttacking() {
      int cost = GameData.records().matchCard("Wizard").cost();
      while (match.side(0).wholeElixir() < cost) {
        steps(1);
      }
      battle.play(tick(), GameData.card("Wizard"), LEVEL, 0, 3500, 14000, "w");
      int limit = tick() + 600;
      while (heroes().isEmpty()
          || heroes().get(0).getView().getState() != GridEntityState.ATTACKING
          || match.side(0).wholeElixir() < 1) {
        assertThat(tick()).isLessThan(limit);
        steps(1);
      }
      CharacterEntity hero = heroes().get(0);
      battle.useAbility(tick(), 0, hero.name(), "a");
      return hero;
    }

    /** Steps until the hero casts, and answers the tick of that step. */
    int stepUntilCasting(CharacterEntity hero) {
      int limit = tick() + 20;
      while (hero.getView().getState() != GridEntityState.CASTING) {
        assertThat(tick()).isLessThan(limit);
        steps(1);
      }
      return tick();
    }

    List<CharacterEntity> heroes() {
      return battle.getWorld().getHolder().entities().stream()
          .filter(CharacterEntity.class::isInstance)
          .map(CharacterEntity.class::cast)
          .filter(unit -> unit.getData().name().equals(HERO))
          .toList();
    }

    BattleWorld world() {
      return battle.getWorld();
    }

    long flags(WorldEntity unit) {
      return unit.getView().getFlags();
    }

    int tick() {
      return battle.getBattle().getTick();
    }

    void steps(int count) {
      for (int i = 0; i < count; i++) {
        battle.getBattle().step();
      }
    }
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
