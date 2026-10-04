package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.SetIndicatorOnTarget;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.pathfinding.EntityFlags;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Mega Minion's hero form played from the hero slot: its mark searches the battle for a target
 * and, with none, disables the ability; while it deploys its hand-over greys the button out. With
 * an enemy troop in the battle it marks the one with the lowest maximum hit points, the furthest
 * from the hero among equals, and gives it the hero's bot buff.
 */
class BattleMegaMinionHeroTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String HERO = "MegaMinionHero";

  private static final String MARK = "MegaMinion_hero_mark_target";

  private static final String HAND_OVER = "MegaMinion_hero_ability_action";

  private static final String BOT_BUFF = "MegaMinionHeroBuffForBots";

  private static final String TELEPORT = "MegaMinion_hero_teleport_action";

  /** The Mega Minion first, in the hero slot, and seven other cards. */
  private static final List<String> DECK =
      List.of(
          "MegaMinion", "Archer", "Knight", "Giant", "Minions", "Musketeer", "Fireball", "Arrows");

  /** The other side's deck, which holds the Knight and the Archers it plays. */
  private static final List<String> OTHER =
      List.of(
          "Knight", "Archer", "Giant", "Minions", "Musketeer", "Fireball", "Arrows", "MegaMinion");

  @Test
  @DisplayName(
      "with no enemy troop the mark finds no target: the hero carries ABILITY_DISABLED, the"
          + " hand-over greys the button out while the hero deploys, and both runs last")
  void withNoTargetTheAbilityIsDisabled() {
    Standard1v1Battle battle = heroPlayed();
    CharacterEntity hero = named(battle, HERO).get(0);
    ChampionController slot = battle.getWorld().kingTower(0).championSlot(1);
    assertThat(slot.follows(hero)).isTrue();
    int deploying = 0;
    while (hero.getView().getState() == GridEntityState.DEPLOYING) {
      assertThat(runs(hero)).contains(MARK, HAND_OVER);
      assertThat(hero.getView().getFlags() & EntityFlags.ABILITY_DISABLED).isNotZero();
      // The hand-over writes the disabled state for the step, which wins the working out.
      assertThat(slot.getState()).isEqualTo(ChampionController.DISABLED);
      deploying++;
      step(battle);
    }
    assertThat(deploying).isGreaterThan(10);
    for (int i = 0; i < 80; i++) {
      step(battle);
      assertThat(runs(hero)).contains(MARK, HAND_OVER);
      assertThat(hero.getView().getFlags() & EntityFlags.ABILITY_DISABLED).isNotZero();
    }
  }

  @Test
  @DisplayName(
      "the mark takes the enemy troop it finds and keeps it: the hero loses ABILITY_DISABLED, the"
          + " troop carries the hero's bot buff with the hero as its source and parent, and the"
          + " hand-over takes the same target")
  void anEnemyTroopIsMarked() {
    Standard1v1Battle battle = heroPlayed();
    CharacterEntity hero = named(battle, HERO).get(0);
    int tick = battle.getBattle().getTick();
    battle.play(tick, GameData.card("Knight"), LEVEL, 1, 14500, 25500, "k");
    int limit = tick + 60;
    while (mark(hero).target() == null) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      assertThat(hero.getView().getFlags() & EntityFlags.ABILITY_DISABLED).isNotZero();
      step(battle);
    }
    CharacterEntity knight = named(battle, "Knight").get(0);
    assertThat(mark(hero).target().id()).isEqualTo(knight.getId());
    // The pick's action runs on the hero in its next pending pass, the Knight its cause, and puts
    // the bot buff on the Knight.
    step(battle);
    assertThat(hero.getView().getFlags() & EntityFlags.ABILITY_DISABLED).isZero();
    List<BuffInstance> buffs =
        knight.getBuffs().items().stream()
            .filter(buff -> buff.getBuff().name().equals(BOT_BUFF))
            .toList();
    assertThat(buffs).hasSize(1);
    assertThat(buffs.get(0).getSource()).isSameAs(hero);
    assertThat(buffs.get(0).getParent()).isSameAs(hero);
    for (int i = 0; i < 40; i++) {
      step(battle);
      assertThat(mark(hero).target().id()).isEqualTo(knight.getId());
      assertThat(hero.getView().getFlags() & EntityFlags.ABILITY_DISABLED).isZero();
      // A stacking buff from the same parent is not listed twice.
      assertThat(
              knight.getBuffs().items().stream().filter(b -> b.getBuff().name().equals(BOT_BUFF)))
          .hasSize(1);
    }
  }

  @Test
  @DisplayName(
      "the mark picks the lowest maximum hit points, and of those the one furthest from the hero:"
          + " of a Knight and two Archers, the Archer further away")
  void theLowestMaximumThenTheFurthestIsMarked() {
    Standard1v1Battle battle = heroPlayed();
    CharacterEntity hero = named(battle, HERO).get(0);
    int tick = battle.getBattle().getTick();
    battle.play(tick, GameData.card("Knight"), LEVEL, 1, 3500, 25500, "k");
    battle.play(tick, GameData.card("Archer"), LEVEL, 1, 14500, 25500, "a");
    int limit = tick + 60;
    while (mark(hero).target() == null) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      step(battle);
    }
    List<CharacterEntity> archers = named(battle, "Archer");
    assertThat(archers).hasSize(2);
    assertThat(named(battle, "Knight")).hasSize(1);
    CharacterEntity further =
        distanceSquared(hero, archers.get(0)) >= distanceSquared(hero, archers.get(1))
            ? archers.get(0)
            : archers.get(1);
    assertThat(distanceSquared(hero, archers.get(0)))
        .isNotEqualTo(distanceSquared(hero, archers.get(1)));
    assertThat(mark(hero).target().id()).isEqualTo(further.getId());
  }

  @Test
  @DisplayName(
      "the ability flies the hero to its marked target: the warp follows the target, gaining 400 a"
          + " step up to 1500 and braking before it; each step raises the warp's tags for the next"
          + " one, which stop the mark and the hand-over; on arrival the hero stands on the"
          + " target's point and keeps it as its reference, and its end action's damage buff is"
          + " refused")
  void theAbilityWarpsTheHeroToItsTarget() {
    Standard1v1Battle battle = heroPlayed();
    CharacterEntity hero = named(battle, HERO).get(0);
    int tick = battle.getBattle().getTick();
    battle.play(tick, GameData.card("Knight"), LEVEL, 1, 14500, 25500, "k");
    int limit = tick + 60;
    while (mark(hero).target() == null) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      step(battle);
    }
    CharacterEntity knight = named(battle, "Knight").get(0);
    LadderMatch match = battle.getMatch();
    for (int i = 0; i < 20 || match.side(0).wholeElixir() < 2; i++) {
      step(battle);
    }
    battle.useAbility(battle.getBattle().getTick(), 0, hero.getId(), "a");
    limit = battle.getBattle().getTick() + 40;
    while (!runs(hero).contains(TELEPORT)) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      step(battle);
    }
    long warpTags =
        EntityFlags.NO_ATTACK
            | EntityFlags.DISABLE_PHYSICAL
            | EntityFlags.NO_DAMAGE
            | EntityFlags.UNTARGETABLE
            | EntityFlags.WARP;
    // The launch step ran the warp's first update: its tags are in the word from the next step.
    assertThat(hero.getView().getPendingFlags() & warpTags).isEqualTo(warpTags);
    step(battle);
    assertThat(hero.getView().getFlags() & warpTags).isEqualTo(warpTags);
    // WARP stops the mark and the hand-over.
    assertThat(runs(hero)).doesNotContain(MARK, HAND_OVER).contains(TELEPORT);
    // The arrival places the hero and keeps its target, then builds the end action, whose damage
    // buff is not modelled.
    Standard1v1Battle flying = battle;
    assertThatThrownBy(
            () -> {
              for (int i = 0; i < 40; i++) {
                step(flying);
                assertThat(hero.getView().getFlags() & warpTags).isEqualTo(warpTags);
              }
            })
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining(
            "MegaMinion_hero_DamageBuff_Spawn spawns MegaMinion_hero_Damage_Buff");
    assertThat(hero.getView().getX()).isEqualTo(knight.getView().getX());
    assertThat(hero.getView().getY()).isEqualTo(knight.getView().getY());
    assertThat(hero.getUnit().targeting().getReference()).isSameAs(knight.getTargetView());
  }

  private static long distanceSquared(CharacterEntity a, CharacterEntity b) {
    long dx = a.getView().getX() - b.getView().getX();
    long dy = a.getView().getY() - b.getView().getY();
    return dx * dx + dy * dy;
  }

  /** The hero's mark run. */
  private static SetIndicatorOnTarget.Run mark(CharacterEntity hero) {
    return hero.actionHolder().running().stream()
        .filter(SetIndicatorOnTarget.Run.class::isInstance)
        .map(SetIndicatorOnTarget.Run.class::cast)
        .findFirst()
        .orElseThrow();
  }

  /** A battle with the hero Mega Minion played at (3500, 14000), one step after it appears. */
  private static Standard1v1Battle heroPlayed() {
    Standard1v1Battle battle = null;
    LadderMatch match = null;
    for (int word = 0; match == null || !inHand(match, "MegaMinion"); word++) {
      battle = new Standard1v1Battle(GameData.tables());
      match = battle.startLadderMatch(DECK, OTHER, word, 0, heroFirst(), new int[8]);
    }
    int cost = GameData.records().matchCard("MegaMinion").cost();
    while (match.side(0).wholeElixir() < cost) {
      step(battle);
    }
    battle.play(
        battle.getBattle().getTick(), GameData.card("MegaMinion"), LEVEL, 0, 3500, 14000, "m");
    int limit = battle.getBattle().getTick() + 200;
    while (named(battle, HERO).isEmpty()) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      step(battle);
    }
    step(battle);
    return battle;
  }

  /** The names of the rows a unit's holder runs. */
  private static List<String> runs(CharacterEntity unit) {
    return unit.actionHolder().running().stream()
        .map(ActionInstance::getAction)
        .map(action -> action.name())
        .toList();
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

  private static void step(Standard1v1Battle battle) {
    battle.getBattle().step();
  }
}
