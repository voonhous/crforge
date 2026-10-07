package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.crforge.core.battle.Version16Tables;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Skeleton King's ability on data version 16.402.18. Its area effect SkeletonKingGraveyard is a
 * filter form row (Filter friendly_troop, no hit switches) that makes SkeletonKingSkeleton clones
 * about the King over its life; the ability sizes that life by its ResurrectChargesExpression, the
 * King's variable SkeletonKing_ResurrectCharges, which the souls the King drains raise by one as
 * each arrives, and runs its SpawnCountResetAction, which writes the variable back to 0. A death
 * the King has not drained a soul from by the time the ability fires adds nothing: the older count
 * of deaths on the unit is gone.
 */
class SkeletonKingChargesTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String CHARGES = "SkeletonKing_ResurrectCharges";

  private static final List<String> KING_DECK =
      List.of(
          "SkeletonKing",
          "Archer",
          "Goblins",
          "Giant",
          "Minions",
          "Musketeer",
          "Fireball",
          "Arrows");

  private static final List<String> OTHER_DECK =
      List.of("Knight", "Archer", "Goblins", "Giant", "Minions", "Musketeer", "Fireball", "Arrows");

  /** A 16.402.18 ladder match with the King played for side 0, and what its graveyard makes. */
  private static final class Scene {
    final BattleRecords records;
    final Standard1v1Battle battle;
    final CharacterEntity king;
    final List<CharacterEntity> spawned = new ArrayList<>();

    Scene() {
      GameTables tables = Version16Tables.load();
      records = new BattleRecords(tables);
      int word = 0;
      while (!inHand(
          new Standard1v1Battle(tables).startLadderMatch(KING_DECK, OTHER_DECK, word, 0))) {
        word++;
      }
      battle = new Standard1v1Battle(tables);
      LadderMatch match = battle.startLadderMatch(KING_DECK, OTHER_DECK, word, 0);
      battle
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void areaSpawned(
                    int tick,
                    AreaEffectEntity areaEffect,
                    CharacterEntity child,
                    int retries,
                    int stateAfter) {
                  spawned.add(child);
                }
              });
      int cost = records.matchCard("SkeletonKing").cost();
      while (match.side(0).wholeElixir() < cost) {
        battle.getBattle().step();
      }
      int tick = battle.getBattle().getTick();
      battle.play(tick, records.card("SkeletonKing"), LEVEL, 0, 3500, 4000, "s");
      run(tick + 25);
      king = battle.getPlays().get(0).units().get(0);
      // The ability's two elixir.
      while (match.side(0).wholeElixir() < 2) {
        battle.getBattle().step();
      }
    }

    int charges() {
      return king.variable(battle.getWorld().declaredVariable(CHARGES));
    }

    void run(int lastTick) {
      while (battle.getBattle().getTick() <= lastTick) {
        battle.getBattle().step();
      }
    }

    /** Taps the ability now; it fires nine visits later. */
    int tap() {
      int tick = battle.getBattle().getTick();
      battle.useAbility(tick, 0, king.name(), "a");
      return tick;
    }

    /** An enemy Knight on the other half, deployed by the tick given. */
    CharacterEntity knight(String name) {
      int tick = battle.getBattle().getTick();
      CharacterEntity knight =
          battle.deploy(tick, records.unit("Knight"), LEVEL, 1, 14500, 24000, name);
      run(tick + 25);
      return knight;
    }
  }

  private static boolean inHand(LadderMatch match) {
    List<MatchCard> deck = match.side(0).deck();
    return Arrays.stream(match.side(0).getHand().slots())
        .anyMatch(index -> index >= 0 && deck.get(index).name().equals("SkeletonKing"));
  }

  @Test
  @DisplayName(
      "the graveyard makes the base six skeletons and one for each charge, each a clone at 1 hit"
          + " point, and the reset action writes the charges back to 0")
  void theChargesSizeTheGraveyard() {
    Scene scene = new Scene();
    scene.king.setVariable(scene.battle.getWorld().declaredVariable(CHARGES), 3);
    int tap = scene.tap();
    scene.run(tap + 70);

    assertThat(scene.spawned).hasSize(9);
    assertThat(scene.spawned)
        .allSatisfy(
            skeleton -> {
              assertThat(skeleton.getData().name()).isEqualTo("SkeletonKingSkeleton");
              assertThat(skeleton.isClone()).isTrue();
            });
    assertThat(scene.charges()).isZero();
  }

  @Test
  @DisplayName(
      "a soul drained well before the ability arrives and adds a skeleton; a death between the tap"
          + " and the ability adds none")
  void onlyArrivedSoulsCount() {
    Scene scene = new Scene();
    CharacterEntity early = scene.knight("early");
    scene.battle.getWorld().kill(early, null);
    // The soul's flight is 1450 ms.
    scene.run(scene.battle.getBattle().getTick() + 40);
    assertThat(scene.charges()).isEqualTo(1);
    CharacterEntity late = scene.knight("late");
    int tap = scene.tap();
    scene.run(tap + 3);
    scene.battle.getWorld().kill(late, null);
    scene.run(tap + 70);

    assertThat(scene.spawned).hasSize(7);
  }
}
