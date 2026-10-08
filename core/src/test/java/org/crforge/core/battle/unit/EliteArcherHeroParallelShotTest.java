package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.math.FixedMath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The hero Elite Archer's ability shot, whose starting action is an
 * ActionCreateParallelProjectiles: as it sets off it makes two side shots of its side row, the
 * action's distance apart across its line, one each side of where it stands, both flying the side
 * row's ProjectileRange along that line.
 */
class EliteArcherHeroParallelShotTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String HERO = "EliteArcherHero";

  /** The ability shot, the attack sequence's second entry. */
  private static final String MIDDLE = "EliteArcherHero_Ability_Power_Shot_Projectile_Middle";

  /** The row of the ability shot's two side shots. */
  private static final String SIDE = "EliteArcherHero_Ability_Triple_Shot_Projectile";

  /** The ability shot's starting action, which makes the side shots. */
  private static final String PARALLEL =
      Shipped.text(Shipped.row("projectiles", MIDDLE), "OnStartingAction");

  /** Long enough for the hero to deploy, use its ability, walk back and fire its ability shot. */
  private static final int LIMIT = 700;

  /** The Elite Archer first, in the hero slot, and seven other cards. */
  private static final List<String> DECK =
      List.of(
          "EliteArcher", "Archer", "Knight", "Giant", "Minions", "Musketeer", "Fireball", "Arrows");

  private static final List<String> KNIGHTS = Collections.nCopies(8, "Knight");

  @Test
  @DisplayName(
      "the hero Elite Archer's ability shot starts two side shots as it sets off: one tick after"
          + " it, before its first step, each half the action's distance to one side of where it"
          + " stands, across its line,"
          + " the first to the right, of its side, level and root")
  void theAbilityShotStartsTwoSideShots() {
    GameTables tables = GameData.tables();
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle battle = null;
    LadderMatch match = null;
    for (int word = 0; match == null || !inHand(match, "EliteArcher"); word++) {
      battle = new Standard1v1Battle(tables);
      match = battle.startLadderMatch(DECK, KNIGHTS, word, 0, heroFirst(), new int[8]);
    }
    int cost = records.matchCard("EliteArcher").cost();
    while (match.side(0).wholeElixir() < cost) {
      step(battle);
    }
    battle.play(
        battle.getBattle().getTick(), records.card("EliteArcher"), LEVEL, 0, 3500, 14000, "e");
    while (named(battle, HERO).isEmpty()) {
      assertThat(battle.getBattle().getTick()).isLessThan(LIMIT);
      step(battle);
    }
    CharacterEntity hero = named(battle, HERO).get(0);
    // The ability once a tower's arrow sets off toward the hero.
    while (!aimedAt(battle, hero)) {
      assertThat(battle.getBattle().getTick()).isLessThan(LIMIT);
      step(battle);
    }
    battle.useAbility(battle.getBattle().getTick(), 0, hero.name(), "a");
    while (shots(battle, MIDDLE).isEmpty()) {
      assertThat(battle.getBattle().getTick()).isLessThan(LIMIT);
      step(battle);
    }
    ProjectileEntity middle = shots(battle, MIDDLE).get(0);
    assertThat(shots(battle, SIDE)).isEmpty();
    int startX = middle.getX();
    int startY = middle.getY();
    int[] line = {middle.getAimX() - startX, middle.getAimY() - startY};
    step(battle);
    List<ProjectileEntity> sides = shots(battle, SIDE);
    assertThat(sides).hasSize(Shipped.number(PARALLEL, "ProjectileCount"));
    ProjectileEntity first = sides.get(0);
    ProjectileEntity second = sides.get(1);
    assertThat(first.getId()).isLessThan(second.getId());
    assertThat(middle.getId()).isLessThan(first.getId());
    // Each side shot stands across the line from where the middle stood, made before its first
    // step and not moved on that tick: the line turned a quarter and scaled, in the game's
    // integer arithmetic, to its offset, from less half the action's distance up by the distance
    // over the count less one; the first to the right of the line as it flies.
    int distance = Shipped.number(PARALLEL, "ProjectileDistance");
    int count = Shipped.number(PARALLEL, "ProjectileCount");
    int offset = -(distance / 2);
    for (ProjectileEntity side : sides) {
      int[] across = across(line, offset);
      assertThat(new int[] {side.getX(), side.getY()})
          .containsExactly(startX + across[0], startY + across[1]);
      offset += distance / (Math.max(count, 2) - 1);
    }
    for (ProjectileEntity side : sides) {
      assertThat(side.side()).isEqualTo(middle.side());
      assertThat(side.level()).isEqualTo(middle.level());
      assertThat(side.getTarget()).isNull();
      assertThat(side.getRoot()).as("the ability shot's root").isSameAs(hero);
      assertThat(side.getZ())
          .as("its constant height")
          .isEqualTo(Shipped.number(Shipped.row("projectiles", SIDE), "ConstantHeight"));
    }
  }

  /**
   * The point across a line at an offset: the line turned a quarter, scaled to the offset with the
   * game's integer normalization.
   */
  private static int[] across(int[] line, int offset) {
    int[] across = {-line[1], line[0]};
    FixedMath.normalize(across, offset);
    return across;
  }

  /** The projectiles of a row the holder lists, in its order. */
  private static List<ProjectileEntity> shots(Standard1v1Battle battle, String row) {
    return battle.getWorld().getHolder().entities().stream()
        .filter(e -> e instanceof ProjectileEntity p && p.getData().name().equals(row))
        .map(ProjectileEntity.class::cast)
        .toList();
  }

  /** Whether a projectile the holder lists is on its way to a unit. */
  private static boolean aimedAt(Standard1v1Battle battle, CharacterEntity unit) {
    return battle.getWorld().getHolder().entities().stream()
        .anyMatch(e -> e instanceof ProjectileEntity p && p.getTarget() == unit);
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
