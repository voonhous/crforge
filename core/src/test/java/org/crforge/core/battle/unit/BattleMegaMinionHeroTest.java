package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.SetIndicatorOnTarget;
import org.crforge.core.battle.expression.ExpressionCompiler;
import org.crforge.core.battle.expression.ExpressionEvaluator;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.projectile.ProjectileEntity;
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

  /** Which bit of a tag word each flag is, as the configured tables number the game tags. */
  private static final EntityFlags BITS = EntityFlags.of(GameData.tables());

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String HERO = "MegaMinionHero";

  private static final String MARK = "MegaMinion_hero_mark_target";

  private static final String HAND_OVER = "MegaMinion_hero_ability_action";

  private static final String BOT_BUFF = "MegaMinionHeroBuffForBots";

  private static final String TELEPORT = "MegaMinion_hero_teleport_action";

  private static final String DAMAGE_BUFF = "MegaMinion_hero_Damage_Buff";

  private static final String CROWN_TOWER_BUFF = "MegaMinion_hero_CrownTower_Buff";

  private static final String DOUBLE = "MegaMinionSpit_DoubleDamage";

  private static final String CROWN = "MegaMinionSpit_CrownTowerDamage";

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
      assertThat(hero.getView().getFlags() & BITS.abilityDisabled()).isNotZero();
      // The hand-over writes the disabled state for the step, which wins the working out.
      assertThat(slot.getState()).isEqualTo(ChampionController.DISABLED);
      deploying++;
      step(battle);
    }
    assertThat(deploying).isGreaterThan(10);
    for (int i = 0; i < 80; i++) {
      step(battle);
      assertThat(runs(hero)).contains(MARK, HAND_OVER);
      assertThat(hero.getView().getFlags() & BITS.abilityDisabled()).isNotZero();
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
      assertThat(hero.getView().getFlags() & BITS.abilityDisabled()).isNotZero();
      step(battle);
    }
    CharacterEntity knight = named(battle, "Knight").get(0);
    assertThat(mark(hero).target().id()).isEqualTo(knight.getId());
    // The pick's action runs on the hero in its next pending pass, the Knight its cause, and puts
    // the bot buff on the Knight.
    step(battle);
    assertThat(hero.getView().getFlags() & BITS.abilityDisabled()).isZero();
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
      assertThat(hero.getView().getFlags() & BITS.abilityDisabled()).isZero();
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
          + " target's point, keeps it as its reference and takes its end action's damage buff")
  void theAbilityWarpsTheHeroToItsTarget() {
    Standard1v1Battle battle = heroPlayed();
    CharacterEntity hero = named(battle, HERO).get(0);
    CharacterEntity knight = launchedAtKnight(battle, hero);
    long warpTags =
        BITS.noAttack()
            | BITS.disablePhysical()
            | BITS.noDamage()
            | BITS.untargetable()
            | BITS.warp();
    // The launch step ran the warp's first update: its tags are in the word from the next step.
    assertThat(hero.getView().getPendingFlags() & warpTags).isEqualTo(warpTags);
    step(battle);
    assertThat(hero.getView().getFlags() & warpTags).isEqualTo(warpTags);
    // WARP stops the mark and the hand-over.
    assertThat(runs(hero)).doesNotContain(MARK, HAND_OVER).contains(TELEPORT);
    // The arrival places the hero and keeps its target, then runs the end action, whose first
    // part lists the damage buff.
    int limit = battle.getBattle().getTick() + 40;
    while (!hero.getBuffs().carries(DAMAGE_BUFF)) {
      assertThat(hero.getView().getFlags() & warpTags).isEqualTo(warpTags);
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      step(battle);
    }
    assertThat(hero.getView().getX()).isEqualTo(knight.getView().getX());
    assertThat(hero.getView().getY()).isEqualTo(knight.getView().getY());
    assertThat(hero.getUnit().targeting().getReference()).isSameAs(knight.getTargetView());
  }

  @Test
  @DisplayName(
      "the arrival's damage buff swaps the hero's first spit for the double damage one, which the"
          + " instant hit fires on the next step; the spit's impact removes the buff, whose remove"
          + " action lists the crown tower buff, and the next spit is the crown tower one")
  void theArrivalBuffSwapsTheFirstSpitUntilItLands() {
    Standard1v1Battle battle = heroPlayed();
    CharacterEntity hero = named(battle, HERO).get(0);
    launchedAtKnight(battle, hero);
    int limit = battle.getBattle().getTick() + 40;
    while (!hero.getBuffs().carries(DAMAGE_BUFF)) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      step(battle);
    }
    assertThat(heroSpits(battle)).isEmpty();
    // The instant hit lands the first attack on the next step, with the buff's projectile.
    step(battle);
    assertThat(heroSpits(battle)).extracting(p -> p.getData().name()).containsExactly(DOUBLE);
    assertThat(hero.getBuffs().carries(DAMAGE_BUFF)).isTrue();
    // The buff stays until the spit lands, and goes with its impact.
    limit = battle.getBattle().getTick() + 20;
    while (!heroSpits(battle).isEmpty()) {
      assertThat(hero.getBuffs().carries(DAMAGE_BUFF)).isTrue();
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      step(battle);
    }
    assertThat(hero.getBuffs().carries(DAMAGE_BUFF)).isFalse();
    // The remove action waits its own delay, then lists the crown tower buff.
    assertThat(hero.getBuffs().carries(CROWN_TOWER_BUFF)).isFalse();
    step(battle);
    assertThat(hero.getBuffs().carries(CROWN_TOWER_BUFF)).isTrue();
    limit = battle.getBattle().getTick() + 60;
    while (heroSpits(battle).isEmpty()) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      step(battle);
    }
    assertThat(heroSpits(battle)).extracting(p -> p.getData().name()).containsExactly(CROWN);
    // The crown tower buff is not removed by an attack.
    while (!heroSpits(battle).isEmpty()) {
      step(battle);
    }
    assertThat(hero.getBuffs().carries(CROWN_TOWER_BUFF)).isTrue();
  }

  /**
   * Plays a Knight for the other side, waits for the mark to take it and uses the ability once the
   * side has the elixir; returns the Knight once the hero's warp runs.
   */
  private static CharacterEntity launchedAtKnight(Standard1v1Battle battle, CharacterEntity hero) {
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
    return knight;
  }

  /** The projectiles of the hero's side the holder lists. */
  private static List<ProjectileEntity> heroSpits(Standard1v1Battle battle) {
    return battle.getWorld().getHolder().entities().stream()
        .filter(ProjectileEntity.class::isInstance)
        .map(ProjectileEntity.class::cast)
        .filter(p -> p.side() == 0)
        .toList();
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

  @Test
  @DisplayName(
      "ability_charges_left answers the charges the slot that follows the hero has left, and -1"
          + " for a unit whose row has no ability with charges or for a tower")
  void abilityChargesLeft() {
    Standard1v1Battle battle = heroPlayed();
    CharacterEntity hero = named(battle, HERO).get(0);
    CharacterEntity knight =
        battle.deploy(battle.getBattle().getTick(), GameData.unit("Knight"), LEVEL, 1, 3500, 25000);
    step(battle);

    assertThat(battle.getWorld().kingTower(0).championSlot(1).getCharges()).isEqualTo(1);
    assertThat(chargesLeft(battle, hero)).isEqualTo(1);
    assertThat(chargesLeft(battle, knight)).isEqualTo(-1);
    assertThat(chargesLeft(battle, battle.getWorld().kingTower(0))).isEqualTo(-1);
  }

  /** What ability_charges_left answers for a context. */
  private static int chargesLeft(Standard1v1Battle battle, WorldEntity context) {
    BattleExpressionEnvironment environment =
        new BattleExpressionEnvironment(context, battle.getWorld());
    return ExpressionEvaluator.evaluate(
        ExpressionCompiler.compile("ability_charges_left", environment), environment);
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
