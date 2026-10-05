package org.crforge.parity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.unit.AreaEffectEntity;
import org.crforge.core.battle.unit.CharacterEntity;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Valkyrie hero form's ability (ValkyrieHero_Ability of data version 16.402.18): the tap picks
 * the closest enemy ground character in the hero's circle and runs the whirlwind on the hero
 * itself. Its charge buff sets ABILITY_PENDING, which only the health bar shows; the attack chain
 * sends the hero at the enemy under the chain's phase buff, faster than the charge buff alone, and
 * once the hero reaches it the chain has nothing left to reach: the phase buff comes off and the
 * hero holds still at its reference, spinning, while the interval spawns the whirlwind's area
 * effect at the hero's point every 250 ms. The spin lasts 3500 ms, fourteen area effects; then the
 * chain completes, and its finishing action keeps the hero from attacking for 400 ms.
 */
class ValkyrieHeroAbilityTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String AREA_EFFECT = "ValkyrieHero_AEO";

  private static final String PHASE_BUFF = "ValkyrieHero_AttackChain";

  private static final String FORBID_BUFF = "ValkyrieHero_Forbid_Attack_After_Whirlwind_Buff";

  /** The Valkyrie first, in the hero slot, and seven other cards. */
  private static final List<String> HERO_DECK =
      List.of(
          "Valkyrie", "Archer", "Goblins", "Knight", "Minions", "Musketeer", "Fireball", "Arrows");

  /** The other side's cards, the Musketeer among them. */
  private static final List<String> OTHER_DECK =
      List.of("Knight", "Giant", "Archer", "Goblins", "Minions", "Musketeer", "Fireball", "Arrows");

  /** The tick the ability command runs on. */
  private static final int CAST = 300;

  /** A battle with the Valkyrie hero form in side 0's hand. */
  private static Standard1v1Battle battle(GameTables tables) {
    Standard1v1Battle battle = null;
    LadderMatch match = null;
    for (int word = 0; match == null || !inHand(match, "Valkyrie"); word++) {
      battle = new Standard1v1Battle(tables);
      match = battle.startLadderMatch(HERO_DECK, OTHER_DECK, word, 0, heroFirst(), new int[8]);
    }
    return battle;
  }

  @Test
  @DisplayName(
      "the hero charges the closest enemy under the phase buff, then holds still at it while the"
          + " whirlwind's area effect spawns at its point every 250 ms, fourteen in all, and the"
          + " finishing action forbids its attack")
  void theWhirlwindChargesThenSpinsInPlace() {
    GameTables tables = Version16Tables.load();
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle battle = battle(tables);
    battle.play(200, records.card("Valkyrie"), LEVEL, 0, 3500, 15500, "v");
    // A Musketeer that stands its ground, within the hero's circle but out of its reach.
    battle.play(205, records.card("Musketeer"), LEVEL, 1, 3500, 20500, "m");
    stepTo(battle, CAST - 20);
    CharacterEntity hero = named(battle, "ValkyrieHero").get(0);
    battle.useAbility(CAST, 0, hero.name(), "a");
    stepTo(battle, CAST);
    Set<Integer> areaEffects = new LinkedHashSet<>();
    List<Integer> steps = new ArrayList<>();
    List<Boolean> phaseBuff = new ArrayList<>();
    List<Integer> spawnTicks = new ArrayList<>();
    boolean forbidden = false;
    int lastY = hero.getView().getY();
    for (int k = 1; k <= 120; k++) {
      stepTo(battle, CAST + k);
      int y = hero.getView().getY();
      steps.add(Math.abs(y - lastY));
      lastY = y;
      phaseBuff.add(hero.getBuffs().carries(PHASE_BUFF));
      forbidden |= hero.getBuffs().carries(FORBID_BUFF);
      for (AreaEffectEntity area : areaEffects(battle)) {
        if (areaEffects.add(area.getId())) {
          spawnTicks.add(CAST + k);
          // Each spawns at the hero's point.
          assertThat(area.getX()).isEqualTo(hero.getView().getX());
          assertThat(area.getY()).isEqualTo(hero.getView().getY());
        }
      }
    }
    assertThat(battle.getAbilityUses()).hasSize(1);
    // The phase buff is on while the hero moves toward the Musketeer, and comes off once it is
    // reached; the hero moves faster under it (250 against the charge buff's 200).
    int firstPhase = phaseBuff.indexOf(true);
    assertThat(firstPhase).isNotNegative();
    int phaseEnd = phaseBuff.subList(firstPhase, phaseBuff.size()).indexOf(false) + firstPhase;
    assertThat(phaseEnd).isGreaterThan(firstPhase);
    assertThat(phaseBuff.subList(phaseEnd, phaseBuff.size())).doesNotContain(true);
    // The buff is listed and removed in a step after its movement: the step after the listing is
    // the faster one, and the step after the reach removed it is the charge buff's alone.
    assertThat(steps.get(firstPhase + 1)).isEqualTo(150);
    assertThat(steps.get(phaseEnd + 1)).isEqualTo(120);
    // Fourteen area effects 250 ms apart after the first, the hero still from the second step
    // after the chain's reach.
    assertThat(spawnTicks).hasSize(14);
    for (int i = 2; i < spawnTicks.size(); i++) {
      assertThat(spawnTicks.get(i) - spawnTicks.get(i - 1)).isEqualTo(5);
    }
    int lastSpawn = spawnTicks.get(spawnTicks.size() - 1) - CAST - 1;
    assertThat(steps.subList(phaseEnd + 2, lastSpawn)).containsOnly(0);
    // The chain completes and its finishing action forbids the attack for 400 ms.
    assertThat(forbidden).isTrue();
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

  /** The whirlwind's area effects the holder lists. */
  private static List<AreaEffectEntity> areaEffects(Standard1v1Battle battle) {
    return battle.getWorld().getHolder().entities().stream()
        .filter(AreaEffectEntity.class::isInstance)
        .map(AreaEffectEntity.class::cast)
        .filter(area -> area.getData().name().equals(AREA_EFFECT))
        .toList();
  }

  /** Steps the battle until its tick is the one given. */
  private static void stepTo(Standard1v1Battle battle, int tick) {
    while (battle.getBattle().getTick() < tick) {
      battle.getBattle().step();
    }
  }
}
