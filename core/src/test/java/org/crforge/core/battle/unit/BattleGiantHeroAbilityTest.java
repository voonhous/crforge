package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.PackedLevel;
import org.crforge.core.pathfinding.combat.RarityTable;
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

  /** The selector the ability's activation group starts. */
  private static final String SELECTOR = "GiantHero_Target_Selector";

  /** The push the selector runs on the enemy it picks. */
  private static final String PUSH = Shipped.actionNames(SELECTOR, "Actions").get(0);

  /** The knock up the push's success group starts first. */
  private static final String KNOCKBACK =
      Shipped.actionNames(Shipped.actionName(PUSH, "SuccessAction"), "SubActions").get(0);

  /** The step the selector picks the Knight on, the one after the cast. */
  private static final int PICK = 2;

  @Test
  @DisplayName(
      "the slap casts in the casting state's entry and the Giant walks on; it stops for the slap,"
          + " the Knight it picked is pushed its push's delay later along the width, knocked up and"
          + " stunned, and lands on the Giant's area effect, which hurts it")
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
    assertThat(giant.getView().getX()).isLessThan(9000);
    assertThat(knightX).isGreaterThan(giant.getView().getX());

    // The slap holds the Giant for its stop's duration, the push comes its delay after the pick,
    // and the Knight lands the knock up's duration after the push.
    int stop =
        Shipped.number(
            Shipped.actionNames(
                    Shipped.actionName(SELECTOR, "ActionOnSelfWhenTriggeredLeft"), "SubActions")
                .get(1),
            "ActionDuration");
    int lastStanding = PICK + Shipped.ticks(stop) + 1;
    int push = PICK + Shipped.ticks(Shipped.number(PUSH, "PushbackDelay"));
    // The knock up lands on the update that finds its duration out, counted down 50 ms an update.
    int land = push + (Shipped.number(KNOCKBACK, "Duration") + 49) / 50;
    // Follow both over the slap, one step at a time.
    int span = land + 2;
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
    // The selector picks the Knight: the Giant stands, unmoved, for the slap's stop.
    for (int k = PICK; k <= lastStanding; k++) {
      assertThat(giantState[k]).as("step %d", k).isEqualTo(GridEntityState.STANDING);
      assertThat(giantX[k]).isEqualTo(giantX[PICK]);
      assertThat(giantY[k]).isEqualTo(giantY[PICK]);
    }
    assertThat(giantState[lastStanding + 1]).isEqualTo(GridEntityState.MOVING);
    // The Knight, fighting the Giant, stands from the pick on; the push comes its delay after the
    // pick and moves it along the width only, 250 a step, from where it stands.
    int pushedFrom = knightXs[PICK];
    for (int k = PICK; k < push; k++) {
      assertThat(knightXs[k]).as("step %d", k).isEqualTo(pushedFrom);
      assertThat(knightYs[k]).isEqualTo(knightYs[PICK]);
    }
    for (int k = push; k <= push + 10; k++) {
      assertThat(knightXs[k]).as("step %d", k).isEqualTo(pushedFrom + 250 * (k - push + 1));
      assertThat(knightYs[k]).isEqualTo(knightYs[PICK]);
    }
    // It lands the knock up's duration after the push on the Giant's area effect, which hurts it
    // on the next step.
    String landing =
        Shipped.text(
            Shipped.actionNames(Shipped.actionName(KNOCKBACK, "ActionOnLanding"), "SubActions")
                .get(0),
            "SpawnData");
    assertThat(effects[land - 1]).isZero();
    assertThat(effects[land]).isEqualTo(1);
    assertThat(effectName).isEqualTo(landing);
    assertThat(effectSide).isZero();
    assertThat(knightHp[land]).isEqualTo(knightHp[land - 1]);
    // Its hit: the area row's base damage scaled by the Giant's row and level.
    int base =
        Shipped.column(Shipped.row("area_effect_objects", landing), "Damage")
            .path("BaseDamage")
            .asInt();
    assertThat(knightHp[land + 1]).isEqualTo(knightHp[land] - scaled(base, giant));
    assertThat(knightXs[land + 1]).isEqualTo(pushedFrom + 250 * (land + 2 - push));
  }

  /**
   * A damage scaled by a unit's row's rarity at its level: the rarity's multiplier, in hundredths,
   * at the level's step, truncated.
   */
  private static int scaled(int damage, CharacterEntity unit) {
    String name = Shipped.text(Shipped.unitRow(unit.getData().name()), "Rarity");
    RarityTable rarity =
        RarityTable.PUBLISHED.stream()
            .filter(table -> table.name().equals(name))
            .findFirst()
            .orElseThrow();
    int steps = PackedLevel.steps(unit.getPackedLevel());
    return steps == 0 ? damage : damage * rarity.multiplier(steps - 1) / 100;
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
