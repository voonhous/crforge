package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.math.FixedMath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Balloon hero's ability (BalloonHero_Ability): the tap starts a shape selector that, a step
 * later, picks the enemy closest to the hero in its circle and runs the activation group on the
 * hero itself, the hero its own cause. The group makes a context, writes the enemy its resolver
 * finds into it, and 50 ms later launches the skeleton trooper's projectile at that enemy, its
 * start moved the spawn's start offset toward it. The projectile speeds up on an interval by a
 * speed override its variable feeds, and where it lands a Skeleton Trooper is spawned, whose
 * landing area effect hurts what stands there. With nobody in the circle the selector finishes
 * without a pick, and its finishing action's failsafe launches the projectile with no context, at
 * no target.
 *
 * <p>Each scene plays one hero, the hero form's summon count written to one, and the speed-up rows
 * the projectile's interval runs are written by the test, so the overrides it pins are the ones it
 * works out from its own rows.
 */
class BalloonHeroAbilityTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The hero's ability row. */
  private static final String ABILITY = "BalloonHero_Ability";

  /** The unit the trooper's projectile spawns where it lands. */
  private static final String TROOPER = "SkeletonTrooper";

  /** The spawn of the skeleton trooper's projectile. */
  private static final String SPAWNER = "BalloonHero_Skeletrooper_Spawner";

  private static final String PROJECTILE = Shipped.text(SPAWNER, "SpawnData");

  /** The interval row the projectile runs as it starts, which speeds it up. */
  private static final String SPEED_UP_INTERVAL =
      Shipped.text(Shipped.row("projectiles", PROJECTILE), "OnStartingAction");

  /** The speed-up interval written: a run every 150 ms, the first 50 ms in. */
  private static final int SPEED_UP_EVERY_MS = 150;

  private static final int SPEED_UP_FIRST_MS = 50;

  /** What each speed-up adds to the variable. */
  private static final int RAMP_STEP = 2;

  /** The least the variable's term counts as, and the divisor of the override written. */
  private static final int RAMP_FLOOR = 5;

  private static final int OVERRIDE_DIVISOR = 80;

  /** The Balloon first, in the hero slot, and seven other cards. */
  private static final List<String> HERO_DECK =
      List.of(
          "Balloon", "Archer", "Goblins", "Knight", "Minions", "Musketeer", "Fireball", "Arrows");

  /** The other side's cards, the Knight and the Giant among them. */
  private static final List<String> OTHER_DECK =
      List.of("Knight", "Giant", "Archer", "Goblins", "Minions", "Musketeer", "Fireball", "Arrows");

  /** The tick the ability command runs on. */
  private static final int CAST = 320;

  /** An earlier tick, for the trooper's long flight: the hero has only just deployed. */
  private static final int EARLY_CAST = 262;

  /**
   * What a launch of the trooper's projectile was: its tick, start, target, owner's point and the
   * target's point as it was launched.
   */
  private record Launch(
      int tick,
      int x,
      int y,
      WorldEntity target,
      int ownerX,
      int ownerY,
      int targetX,
      int targetY) {}

  /**
   * The configured tables with one hero to a play of the hero form, and the projectile's speed-up
   * rows written: on the interval the variable grows by {@link #RAMP_STEP}, and the override is the
   * natural logarithm in ten-thousandths of the greater of {@link #RAMP_FLOOR} and the variable
   * less one, over {@link #OVERRIDE_DIVISOR}.
   */
  private static GameTables tables(Path folder) throws IOException {
    GameData.altered(
        folder,
        "spells_hero_form",
        rows -> GameData.columns(rows, "Balloon_hero").put("SummonNumber", 1));
    GameData.addTestVariable(folder);
    String variable = GameData.TEST_VARIABLE;
    GameData.alterLoaded(
        folder,
        "actions",
        rows -> {
          ObjectNode interval = GameData.fields(rows, SPEED_UP_INTERVAL);
          interval.put("Interval", SPEED_UP_EVERY_MS);
          interval.put("StartCounterAt", SPEED_UP_FIRST_MS);
          interval.putObject("ActionToExecute").put("action", "Test_Speed_Up_Group");
          rows.set(
              "Test_Speed_Up_Group",
              action(
                  "LogicActionGroupData",
                  "ActionGroup",
                  f -> {
                    ArrayNode parts = f.putArray("SubActions");
                    parts.addObject().put("action", "Test_Speed_Increment");
                    parts.addObject().put("action", "Test_Speed_Up");
                    f.putArray("SubActionsDelay").add(0).add(0);
                  }));
          rows.set(
              "Test_Speed_Increment",
              action(
                  "LogicActionSetVariableData",
                  "ActionSetVariable",
                  f -> {
                    f.put("Value", variable + " + " + RAMP_STEP);
                    f.put("Variable", variable);
                  }));
          rows.set(
              "Test_Speed_Up",
              action(
                  "LogicActionOverrideProjectileSpeedData",
                  "ActionOverrideProjectileSpeed",
                  f ->
                      f.put(
                          "SpeedOverride",
                          "logX10000(max("
                              + RAMP_FLOOR
                              + ", "
                              + variable
                              + " - 1)) / "
                              + OVERRIDE_DIVISOR)));
        });
    return GameTables.load(folder);
  }

  /** An action row of a class with its fields. */
  private static ObjectNode action(String className, String classType, Consumer<ObjectNode> edit) {
    ObjectNode row = new ObjectMapper().createObjectNode();
    row.put("class", className);
    row.put("ClassType", classType);
    ObjectNode fields = row.putObject("fields");
    fields.put("ClassType", classType);
    edit.accept(fields);
    return row;
  }

  /**
   * The override the written speed-up gives for a term: the natural logarithm in ten-thousandths,
   * rounded, over the divisor, worked out in the test.
   */
  private static int override(int term) {
    return (int) Math.round(Math.log(term) * 10000) / OVERRIDE_DIVISOR;
  }

  /** A battle with the Balloon hero form in side 0's hand, recording every trooper launch. */
  private static Standard1v1Battle battle(GameTables tables, List<Launch> launches) {
    Standard1v1Battle battle = null;
    LadderMatch match = null;
    for (int word = 0; match == null || !inHand(match, "Balloon"); word++) {
      battle = new Standard1v1Battle(tables);
      match = battle.startLadderMatch(HERO_DECK, OTHER_DECK, word, 0, heroFirst(), new int[8]);
    }
    battle
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void actionProjectileLaunched(
                  int tick,
                  WorldEntity owner,
                  String action,
                  int phase,
                  ProjectileEntity projectile) {
                if (projectile.getData().name().equals(PROJECTILE)) {
                  launches.add(
                      new Launch(
                          tick,
                          projectile.getX(),
                          projectile.getY(),
                          projectile.getTarget(),
                          owner.getView().getX(),
                          owner.getView().getY(),
                          projectile.getTarget() == null
                              ? 0
                              : projectile.getTarget().getView().getX(),
                          projectile.getTarget() == null
                              ? 0
                              : projectile.getTarget().getView().getY()));
                }
              }
            });
    return battle;
  }

  @Test
  @DisplayName(
      "the trooper falls on the enemy closest to the hero, not the one of the most hit points: its"
          + " projectile starts its spawn's start offset toward it, speeds up every 150 ms and"
          + " spawns the trooper where it lands, whose landing hurts the enemy")
  void theTrooperFallsOnTheClosestEnemy(@TempDir Path folder) throws IOException {
    GameTables tables = tables(folder);
    BattleRecords records = new BattleRecords(tables);
    List<Launch> launches = new ArrayList<>();
    Standard1v1Battle battle = battle(tables, launches);
    battle.play(220, records.card("Balloon"), LEVEL, 0, 3500, 14000, "g");
    // The Knight close by, and an enemy Giant of far more hit points a little farther.
    battle.play(225, records.card("Knight"), LEVEL, 1, 3500, 21000, "k");
    battle.play(226, records.card("Giant"), LEVEL, 1, 5000, 21200, "eg");
    stepTo(battle, EARLY_CAST - 20);
    CharacterEntity hero = named(battle, "BalloonHero").get(0);
    CharacterEntity knight = named(battle, "Knight").get(0);
    LadderMatch match = battle.getMatch();
    int spent = match.side(0).getSpent();
    battle.useAbility(EARLY_CAST, 0, hero.name(), "a");
    stepTo(battle, EARLY_CAST);
    List<Integer> speeds = new ArrayList<>();
    List<Integer> knightHp = new ArrayList<>();
    ProjectileEntity flying = null;
    for (int k = 1; k <= 60; k++) {
      stepTo(battle, EARLY_CAST + k);
      knightHp.add(knight.getHitPoints().getHitPoints());
      for (ProjectileEntity projectile : projectiles(battle)) {
        flying = projectile;
        speeds.add(projectile.getSpeedOverride());
      }
    }
    assertThat(battle.getAbilityUses()).hasSize(1);
    // The ability's cost paid, in ten-thousandths of an elixir.
    assertThat(match.side(0).getSpent() - spent)
        .isEqualTo(Shipped.number(Shipped.row("character_abilities", ABILITY), "ManaCost") * 10000);
    assertThat(flying).isNotNull();
    // One launch, at the Knight, its start the spawn's offset from the hero's point toward it.
    assertThat(launches).hasSize(1);
    Launch launch = launches.get(0);
    assertThat(launch.target()).isSameAs(knight);
    // The start is the hero's point moved toward the Knight's point at the launch, the line
    // scaled to the offset with the game's integer normalization.
    int[] toward = {launch.targetX() - launch.ownerX(), launch.targetY() - launch.ownerY()};
    FixedMath.normalize(toward, Shipped.number(SPAWNER, "ProjectileStartOffset"));
    assertThat(new int[] {launch.x(), launch.y()})
        .containsExactly(launch.ownerX() + toward[0], launch.ownerY() + toward[1]);
    // No override on the launch step; then the written override, at the floor while the variable
    // less one stays below it, and grown once the variable less one passes it.
    List<Integer> distinct = new ArrayList<>();
    for (int speed : speeds) {
      if (distinct.isEmpty() || distinct.get(distinct.size() - 1) != speed) {
        distinct.add(speed);
      }
    }
    assertThat(distinct).startsWith(0, override(RAMP_FLOOR), override(RAMP_FLOOR + RAMP_STEP));
    // Where it lands the trooper is spawned and its landing area effect hurts the Knight first.
    assertThat(named(battle, TROOPER)).hasSize(1);
    assertThat(named(battle, TROOPER).get(0).side()).isZero();
    String landing =
        Shipped.text(Shipped.text(Shipped.unitRow(TROOPER), "OnStartingAction"), "SpawnData");
    GameRow area = Shipped.row("area_effect_objects", landing);
    int landingDamage =
        Shipped.scaled(Shipped.column(area, "Damage").path("BaseDamage").asInt(), area, LEVEL);
    int full = knight.getHitPoints().getMaximum();
    assertThat(knightHp.stream().distinct().limit(2)).containsExactly(full, full - landingDamage);
  }

  @Test
  @DisplayName(
      "with nobody in the circle the selector finishes without a pick, and its finishing action's"
          + " failsafe launches the projectile from the hero's own point at no target, once")
  void theFailsafeLaunchesAtNoTarget(@TempDir Path folder) throws IOException {
    GameTables tables = tables(folder);
    BattleRecords records = new BattleRecords(tables);
    List<Launch> launches = new ArrayList<>();
    Standard1v1Battle battle = battle(tables, launches);
    battle.play(220, records.card("Balloon"), LEVEL, 0, 3500, 14000, "g");
    stepTo(battle, CAST - 20);
    CharacterEntity hero = named(battle, "BalloonHero").get(0);
    battle.useAbility(CAST, 0, hero.name(), "a");
    stepTo(battle, CAST + 80);
    assertThat(battle.getAbilityUses()).hasSize(1);
    assertThat(launches).hasSize(1);
    Launch launch = launches.get(0);
    assertThat(launch.target()).isNull();
    assertThat(launch.x()).isEqualTo(launch.ownerX());
    assertThat(launch.y()).isEqualTo(launch.ownerY());
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

  /** The trooper's projectiles the holder lists. */
  private static List<ProjectileEntity> projectiles(Standard1v1Battle battle) {
    return battle.getWorld().getHolder().entities().stream()
        .filter(ProjectileEntity.class::isInstance)
        .map(ProjectileEntity.class::cast)
        .filter(projectile -> projectile.getData().name().equals(PROJECTILE))
        .toList();
  }

  /** Steps the battle until its tick is the one given. */
  private static void stepTo(Standard1v1Battle battle, int tick) {
    while (battle.getBattle().getTick() < tick) {
      battle.getBattle().step();
    }
  }
}
