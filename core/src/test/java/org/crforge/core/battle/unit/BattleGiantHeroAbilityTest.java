package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

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
 * The Giant hero form's slap, its ability: no cast time and no trigger delay, so the cast fires in
 * the casting state's entry and the Giant goes back to walking at once; the selector it starts
 * waits for an enemy in its circle, stops the Giant for the slap, and pushes the enemy it picked
 * along the width, away from the side of the arena the Giant stands on; the enemy is knocked into
 * the air, stunned, and lands where the push took it, on an area effect of the Giant's that hurts
 * it.
 */
class BattleGiantHeroAbilityTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The Giant first, in the hero slot, and seven other cards. */
  private static final List<String> GIANT_DECK =
      List.of("Giant", "Archer", "Goblins", "Knight", "Minions", "Musketeer", "Fireball", "Arrows");

  private static final List<String> KNIGHTS = Collections.nCopies(8, "Knight");

  /** The tick the ability command runs on. */
  private static final int CAST = 320;

  @Test
  @DisplayName(
      "the slap casts in the casting state's entry and the Giant walks on; it stops for the slap,"
          + " the Knight it picked is pushed 400 ms later along the width, knocked up and stunned,"
          + " and lands on the Giant's area effect, which hurts it")
  void theSlapThrowsTheKnight() {
    Standard1v1Battle battle = null;
    LadderMatch match = null;
    for (int word = 0; match == null || !inHand(match, "Giant"); word++) {
      battle = new Standard1v1Battle(GameData.tables());
      match = battle.startLadderMatch(GIANT_DECK, KNIGHTS, word, 0, heroFirst(), new int[8]);
    }
    battle.play(220, GameData.card("Giant"), LEVEL, 0, 3500, 14000, "g");
    battle.play(260, GameData.card("Knight"), LEVEL, 1, 3500, 22000, "k");
    stepTo(battle, CAST - 20);
    CharacterEntity giant = named(battle, "GiantHero").get(0);
    CharacterEntity knight = named(battle, "Knight").get(0);
    battle.useAbility(CAST, 0, giant.name(), "slap");
    stepTo(battle, CAST);
    int elixir = match.side(0).getElixir();
    int knightX = knight.getView().getX();
    int knightY = knight.getView().getY();
    assertThat(giant.getView().getX()).isLessThan(9000);
    assertThat(knightX).isGreaterThan(giant.getView().getX());

    // Follow both over the slap, one step at a time.
    int span = 42;
    int[] giantState = new int[span];
    int[] giantX = new int[span];
    int[] giantY = new int[span];
    int[] knightXs = new int[span];
    int[] knightYs = new int[span];
    int[] knightHp = new int[span];
    int[] effects = new int[span];
    String effectName = null;
    int effectSide = -1;
    for (int k = 1; k < span; k++) {
      stepTo(battle, CAST + k);
      giantState[k] = giant.getView().getState();
      giantX[k] = giant.getView().getX();
      giantY[k] = giant.getView().getY();
      knightXs[k] = knight.getView().getX();
      knightYs[k] = knight.getView().getY();
      knightHp[k] = knight.getHitPoints().getHitPoints();
      List<AreaEffectEntity> listed = areaEffects(battle);
      effects[k] = listed.size();
      if (!listed.isEmpty()) {
        effectName = listed.get(0).getData().name();
        effectSide = listed.get(0).side();
      }
      if (k == 1) {
        // The command pays, and the cast fires in the casting state's entry: the Giant is back in
        // the state it came from on the same step.
        assertThat(battle.getAbilityUses()).hasSize(1);
        assertThat(battle.getAbilityUses().get(0).outcome().code()).isZero();
        assertThat(match.side(0).getElixir()).isLessThan(elixir);
      }
    }
    assertThat(giantState[1]).isEqualTo(GridEntityState.MOVING);
    // The selector picks the Knight: the Giant stands, unmoved, for the slap's 800 ms.
    for (int k = 2; k <= 19; k++) {
      assertThat(giantState[k]).as("step %d", k).isEqualTo(GridEntityState.STANDING);
      assertThat(giantX[k]).isEqualTo(giantX[2]);
      assertThat(giantY[k]).isEqualTo(giantY[2]);
    }
    assertThat(giantState[20]).isEqualTo(GridEntityState.MOVING);
    // The push comes 400 ms after the pick and moves the Knight along the width only, 250 a step.
    assertThat(knightXs[9]).isEqualTo(knightX);
    for (int k = 10; k <= 20; k++) {
      assertThat(knightXs[k]).as("step %d", k).isEqualTo(knightX + 250 * (k - 9));
      assertThat(knightYs[k]).isEqualTo(knightY);
    }
    // It lands 1500 ms after the push on the Giant's area effect, which hurts it on the next step.
    assertThat(effects[39]).isZero();
    assertThat(effects[40]).isEqualTo(1);
    assertThat(effectName).isEqualTo("GiantHero_LandingAEO");
    assertThat(effectSide).isZero();
    assertThat(knightHp[40]).isEqualTo(knightHp[39]);
    assertThat(knightHp[41]).isLessThan(knightHp[40]);
    assertThat(knightXs[41]).isEqualTo(knightX + 250 * 32);
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

  /** The area effects the holder lists. */
  private static List<AreaEffectEntity> areaEffects(Standard1v1Battle battle) {
    return battle.getWorld().getHolder().entities().stream()
        .filter(AreaEffectEntity.class::isInstance)
        .map(AreaEffectEntity.class::cast)
        .toList();
  }

  /** Steps the battle until its tick is the one given. */
  private static void stepTo(Standard1v1Battle battle, int tick) {
    while (battle.getBattle().getTick() < tick) {
      battle.getBattle().step();
    }
  }
}
