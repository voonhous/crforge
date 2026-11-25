/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.battle.HeroFormTables.CARD_COST;
import static org.crforge.core.battle.HeroFormTables.DEFLECTOR;
import static org.crforge.core.battle.HeroFormTables.HEAL_PERCENT;
import static org.crforge.core.battle.HeroFormTables.REROLL;
import static org.crforge.core.battle.HeroFormTables.REROLL_DEPLOY_DURATION_MS;
import static org.crforge.core.battle.HeroFormTables.REROLL_OFFSET_Y;
import static org.crforge.core.battle.HeroFormTables.REROLL_PROJECTILE;
import static org.crforge.core.battle.HeroFormTables.REROLL_SPAWN_DELAY_MS;
import static org.crforge.core.battle.HeroFormTables.ROLLING;
import static org.crforge.core.battle.HeroFormTables.ROLL_ABILITY_COST;
import static org.crforge.core.battle.HeroFormTables.ROLL_CARD;
import static org.crforge.core.battle.HeroFormTables.ROLL_DEFLECTED;
import static org.crforge.core.battle.HeroFormTables.ROLL_HERO;
import static org.crforge.core.battle.HeroFormTables.ROLL_HERO_INTERVAL;
import static org.crforge.core.battle.HeroFormTables.ROLL_HERO_LISTEN;
import static org.crforge.core.battle.HeroFormTables.THROW;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.HeroFormTables;
import org.crforge.core.battle.SpawnedChampionTables;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.EntityFlags;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A champion a rolling projectile leaves, on rows the tests write ({@link HeroFormTables}' roll
 * hero): a spell card in the hero slot, cast as its hero row. Its thrown and rolling projectiles
 * hold the champion slot's button state ready while they fly, and the roll's end leaves the hero,
 * which the slot follows by the play and whose starting actions run. A roll a deflection turns
 * leaves the row's deflected spawn for the deflecting side instead. The hero's ability rerolls it:
 * it backs off, rolls forward in a projectile it follows, and stands up again where it ends.
 */
class BattleRolledChampionTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** Milliseconds a step takes. */
  private static final int STEP_MS = 50;

  /** The point side 0 plays the roll at, on its own half in the left lane. */
  private static final int X = 3500;

  private static final int Y = 9000;

  /**
   * How far behind the hero side 0's deflector stands in the twice turned reroll: far enough that
   * the hero backing off and the first roll forward pass it, near enough that the roll turned back
   * reaches its area.
   */
  private static final int TWICE_BEHIND = 1400;

  /** The most deflections a projectile takes (MAX_DEFLECTION_TIMES), as the tests write it. */
  private static final int MOST_DEFLECTIONS = 2;

  @Test
  @DisplayName(
      "the play casts the hero row's projectile, whose flight and roll hold the slot's button"
          + " state ready; the roll leaves the hero, carrying the play, which the slot follows and"
          + " whose starting actions are running")
  void theRollLeavesTheFollowedHero(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(folder);
    ChampionController slot = scene.slot();
    int deployIndex = scene.match.side(0).getDeployCounter();
    scene.play();
    scene.step();
    List<ProjectileEntity> thrown = scene.projectiles(THROW);
    assertThat(thrown).hasSize(1);
    assertThat(thrown.get(0).getDeployIndex()).isEqualTo(deployIndex);

    // While a hero projectile flies or rolls, its starting action writes the ready state.
    int limit = scene.tick() + 200;
    boolean flew = false;
    boolean rolled = false;
    while (scene.named(ROLL_HERO).isEmpty()) {
      assertThat(scene.tick()).isLessThan(limit);
      boolean flying = !scene.projectiles(THROW).isEmpty();
      scene.step();
      List<ProjectileEntity> rolling = scene.projectiles(ROLLING);
      if (flying && !scene.projectiles(THROW).isEmpty()) {
        flew = true;
        assertThat(slot.getState()).isEqualTo(ChampionController.READY);
      }
      if (!rolling.isEmpty()) {
        rolled = true;
        assertThat(rolling.get(0).getDeployIndex()).isEqualTo(deployIndex);
        assertThat(slot.getState()).isEqualTo(ChampionController.READY);
      }
    }
    assertThat(flew).isTrue();
    assertThat(rolled).isTrue();
    CharacterEntity hero = scene.named(ROLL_HERO).get(0);
    assertThat(hero.getDeployIndex()).isEqualTo(deployIndex);
    scene.step();
    scene.step();
    assertThat(slot.champions()).containsExactly(hero);
    assertThat(hero.actionHolder().running())
        .extracting(run -> run.getAction().name())
        .contains(ROLL_HERO_LISTEN, ROLL_HERO_INTERVAL);
  }

  @Test
  @DisplayName(
      "a hero roll turned by an enemy's deflecting area effect rolls back for the deflector's side,"
          + " its starting action's later writes going by that side, and lets out the row's"
          + " deflected spawn for the deflector's side, which no champion slot follows")
  void theTurnedRollLeavesTheDeflectedSpawnForTheDeflector(@TempDir Path folder)
      throws IOException {
    Scene scene = new Scene(folder);
    ChampionController slot = scene.slot();
    scene.waitToPlay();
    // The enemy deflector stands still on the roll's lane, short of where the roll would end,
    // with its deflecting area effect up as the roll is played.
    CharacterEntity deflector =
        scene.battle.deploy(
            scene.tick() + 1,
            scene.battle.getWorld().getRecords().unit(DEFLECTOR),
            LEVEL,
            1,
            X,
            Y + 1500,
            "deflector");
    while (deflector.getId() == 0
        || deflector.getView().getState() == GridEntityState.DEPLOYING
        || deflector.getView().getState() == GridEntityState.WAITING_TO_DEPLOY) {
      scene.step();
    }
    deflector.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    deflector.setActive(CharacterEntity.TARGETING_SLOT, false);
    deflector.requestAbility();
    while (scene.deflectors() == 0) {
      scene.step();
    }
    scene.play();
    int limit = scene.tick() + 200;
    ProjectileEntity roll = null;
    while (roll == null || roll.getDeflections() == 0) {
      assertThat(scene.tick()).isLessThan(limit);
      scene.step();
      List<ProjectileEntity> rolling = scene.projectiles(ROLLING);
      if (roll == null && !rolling.isEmpty()) {
        roll = rolling.get(0);
      }
    }
    assertThat(roll.side()).as("the turned roll is the deflector's").isEqualTo(deflector.side());
    assertThat(roll.getData().deflectedCharacterSpawn()).isEqualTo(ROLL_DEFLECTED);

    // While it rolls back, its starting action writes into the deflector side's slots, none of
    // which follows the hero: the thrower's slot, with no copy of its play, is no longer held
    // ready.
    scene.step();
    while (scene.named(ROLL_DEFLECTED).isEmpty()) {
      assertThat(scene.tick()).isLessThan(limit);
      assertThat(slot.getState()).isEqualTo(ChampionController.ABSENT);
      scene.step();
    }
    assertThat(scene.named(ROLL_HERO)).as("no hero is let out").isEmpty();
    CharacterEntity spawn = scene.named(ROLL_DEFLECTED).get(0);
    assertThat(spawn.side()).as("the deflected spawn fights for the deflector").isEqualTo(1);
    scene.step();
    scene.step();
    assertThat(slot.champions()).isEmpty();
    assertThat(scene.battle.getWorld().kingTower(1).championSlot(1).champions())
        .doesNotContain(spawn);
  }

  @Test
  @DisplayName(
      "the ability's reroll backs the hero off toward its side by the offset's share of a step"
          + " while its spawn delay runs, then rolls it in its projectile from where it stands,"
          + " healed by the variable's percent of its missing hit points; it follows the"
          + " projectile a step behind, taking no damage and untargetable, and deploys for the"
          + " reroll's deploy duration where the projectile ends")
  void theRerollRollsTheHeroForward(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(folder);
    EntityFlags bits = EntityFlags.of(scene.tables);
    scene.play();
    int limit = scene.tick() + 200;
    while (scene.named(ROLL_HERO).isEmpty()) {
      assertThat(scene.tick()).isLessThan(limit);
      scene.step();
    }
    CharacterEntity hero = scene.named(ROLL_HERO).get(0);
    for (int i = 0; i < 40 || scene.match.side(0).wholeElixir() < ROLL_ABILITY_COST; i++) {
      scene.step();
    }
    // Half its hit points gone, so the heal has something to give back.
    hero.getHitPoints().setHitPoints(hero.getHitPoints().getMaximum() / 2);
    scene.battle.useAbility(scene.tick(), 0, hero.getId(), "a");
    limit = scene.tick() + 30;
    while (hero.getView().getState() != GridEntityState.CASTING) {
      assertThat(scene.tick()).isLessThan(limit);
      scene.step();
    }
    // The activation starts the run on the cast's first step; its first update is on the next.
    // Each step takes 50 ms off the spawn delay; while some is left the hero backs off toward its
    // side (down for side 0) by the offset's share of a step.
    int share = Math.abs(REROLL_OFFSET_Y) * STEP_MS / REROLL_SPAWN_DELAY_MS;
    int backSteps = (REROLL_SPAWN_DELAY_MS + STEP_MS - 1) / STEP_MS - 1;
    int x = hero.getView().getX();
    int y = hero.getView().getY();
    for (int i = 1; i <= backSteps; i++) {
      scene.step();
      assertThat(hero.getView().getY()).isEqualTo(y - share * i);
      assertThat(hero.getView().getX()).isEqualTo(x);
      assertThat(scene.projectiles(REROLL_PROJECTILE)).isEmpty();
    }
    int missing = hero.getHitPoints().getMaximum() - hero.getHitPoints().getHitPoints();
    assertThat(missing).isPositive();
    int before = hero.getHitPoints().getHitPoints();
    scene.step();
    // The step that finds the delay out launches the projectile where the hero stands, and the
    // start action heals it by the variable's percent of what it is missing.
    assertThat(hero.getHitPoints().getHitPoints()).isEqualTo(before + missing * HEAL_PERCENT / 100);
    scene.step();
    List<ProjectileEntity> rolling = scene.projectiles(REROLL_PROJECTILE);
    assertThat(rolling).hasSize(1);
    ProjectileEntity barrel = rolling.get(0);
    assertThat(barrel.getOwner()).isSameAs(hero);
    assertThat(hero.getView().getY()).isEqualTo(y - share * backSteps);
    long rollingTags = bits.noDamage() | bits.untargetable() | bits.noAttack();
    int steps = 0;
    int healed = hero.getHitPoints().getHitPoints();
    while (scene.projectiles(REROLL_PROJECTILE).contains(barrel)) {
      assertThat(hero.getView().getFlags() & rollingTags).isEqualTo(rollingTags);
      assertThat(hero.getHitPoints().getHitPoints()).isEqualTo(healed);
      int px = barrel.getX();
      int py = barrel.getY();
      scene.step();
      assertThat(hero.getView().getX()).isEqualTo(px);
      assertThat(hero.getView().getY()).isEqualTo(py);
      assertThat(++steps).isLessThan(40);
    }
    assertThat(hero.getView().getState()).isEqualTo(GridEntityState.DEPLOYING);
    assertThat(hero.getView().getDeployCountdown()).isEqualTo(REROLL_DEPLOY_DURATION_MS);
    scene.step();
    scene.step();
    assertThat(hero.actionHolder().running())
        .extracting(run -> run.getAction().name())
        .doesNotContain(REROLL);
  }

  @Test
  @DisplayName(
      "a reroll an enemy's deflecting area effect turns takes the hero over to the deflector's side"
          + " on the step its run sees the turn, carrying that side's summoner's cast and no play,"
          + " so its own slot no longer follows it; as the turned roll ends the hero takes the"
          + " deflected row for that side and deploys facing that side's forward")
  void theDeflectedRerollTakesTheHeroOverToTheDeflector(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(folder);
    ChampionController slot = scene.slot();
    CharacterEntity hero = scene.heroReady();
    TowerEntity summoner = scene.battle.getWorld().kingTower(1);
    scene.deflectAhead(hero);
    scene.battle.useAbility(scene.tick(), 0, hero.getId(), "a");
    int limit = scene.tick() + 100;
    while (scene.projectiles(REROLL_PROJECTILE).isEmpty()) {
      assertThat(scene.tick()).isLessThan(limit);
      scene.step();
    }
    ProjectileEntity barrel = scene.projectiles(REROLL_PROJECTILE).get(0);
    // The run's step that finds the barrel turned takes the hero over, once it has put the hero on
    // the barrel's point.
    while (hero.side() == 0) {
      assertThat(scene.tick()).isLessThan(limit);
      scene.step();
    }
    assertThat(barrel.getDeflections()).isEqualTo(1);
    assertThat(barrel.side()).isEqualTo(1);
    assertThat(hero.getData().name()).isEqualTo(ROLL_HERO);
    assertThat(hero.castId()).isEqualTo(summoner.castId());
    assertThat(hero.getDeployIndex()).as("no play of the deflector's side").isEqualTo(-1);
    assertThat(hero.getView().getX()).isEqualTo(barrel.getX());
    assertThat(hero.getView().getY()).isEqualTo(barrel.getY());
    scene.step();
    assertThat(slot.champions()).doesNotContain(hero);

    while (scene.projectiles(REROLL_PROJECTILE).contains(barrel)) {
      assertThat(scene.tick()).isLessThan(limit);
      assertThat(hero.side()).isEqualTo(1);
      scene.step();
    }
    // The end: the deploy for the reroll's duration, faced down the arena for side 1, and the
    // deflected action queued on the hero, which takes the deflected row on a later pending pass.
    assertThat(hero.getView().getState()).isEqualTo(GridEntityState.DEPLOYING);
    assertThat(hero.getView().getDeployCountdown()).isEqualTo(REROLL_DEPLOY_DURATION_MS);
    assertThat(hero.getView().getDirY()).isNegative();
    while (!hero.getData().name().equals(ROLL_DEFLECTED)) {
      assertThat(scene.tick()).isLessThan(limit);
      scene.step();
    }
    assertThat(hero.side()).isEqualTo(1);
    scene.step();
    scene.step();
    assertThat(slot.champions()).isEmpty();
    assertThat(summoner.championSlot(1).champions()).doesNotContain(hero);
    assertThat(hero.actionHolder().running())
        .extracting(run -> run.getAction().name())
        .doesNotContain(REROLL);
  }

  @Test
  @DisplayName(
      "a reroll turned over to a side whose champion slot holds the hero's row from the deck but"
          + " follows no play yet is followed by that slot: the hero carries that side's"
          + " summoner's play there, none, the play such a slot follows")
  void aRerollTurnedToASideWithAnIdleSlotIsFollowedThere(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(folder, true);
    TowerEntity summoner = scene.battle.getWorld().kingTower(1);
    ChampionController idle = summoner.championSlotFollowing(ROLL_HERO);
    assertThat(idle).as("side 1's deck gives a slot the hero's row").isNotNull();
    assertThat(idle.getDeployIndex()).as("no play of it yet").isEqualTo(-1);
    CharacterEntity hero = scene.heroReady();
    scene.deflectAhead(hero);
    scene.battle.useAbility(scene.tick(), 0, hero.getId(), "a");
    int limit = scene.tick() + 100;
    while (hero.side() == 0) {
      assertThat(scene.tick()).isLessThan(limit);
      scene.step();
    }
    assertThat(hero.getDeployIndex()).isEqualTo(-1);
    scene.step();
    assertThat(idle.champions()).containsExactly(hero);
    assertThat(scene.slot().champions()).doesNotContain(hero);
    // The roll, turned once, ends with the deflected row, which names no ability: the slot lets
    // the hero go again.
    while (!hero.getData().name().equals(ROLL_DEFLECTED)) {
      assertThat(scene.tick()).isLessThan(limit);
      scene.step();
    }
    scene.step();
    assertThat(idle.champions()).isEmpty();
  }

  @Test
  @DisplayName(
      "a reroll turned over to a side whose champion slot follows a play of the same hero is not"
          + " followed there: the hero carries no play, and the slot keeps following its own"
          + " copy")
  void aRerollTurnedToASideFollowingItsOwnPlayIsNotFollowedThere(@TempDir Path folder)
      throws IOException {
    Scene scene = new Scene(folder, true);
    // Side 1's own roll hero first, so its slot follows a play of the same ability.
    scene.waitToPlay();
    while (scene.match.side(1).wholeElixir() < CARD_COST) {
      scene.step();
    }
    scene.battle.play(
        scene.tick(),
        scene.battle.getWorld().getRecords().card(ROLL_CARD),
        LEVEL,
        1,
        X,
        Y + 14000,
        "r1");
    int limit = scene.tick() + 200;
    while (scene.named(ROLL_HERO).isEmpty()) {
      assertThat(scene.tick()).isLessThan(limit);
      scene.step();
    }
    CharacterEntity own = scene.named(ROLL_HERO).get(0);
    ChampionController followed =
        scene.battle.getWorld().kingTower(1).championSlotFollowing(ROLL_HERO);
    assertThat(followed.getDeployIndex()).isNotNegative();
    CharacterEntity hero = scene.heroReady();
    scene.deflectAhead(hero);
    scene.battle.useAbility(scene.tick(), 0, hero.getId(), "a");
    limit = scene.tick() + 100;
    while (hero.side() == 0) {
      assertThat(scene.tick()).isLessThan(limit);
      scene.step();
    }
    scene.step();
    assertThat(followed.champions()).containsExactly(own);
  }

  @Test
  @DisplayName(
      "a reroll turned by a deflector of each side comes back to the hero's side: the second turn"
          + " takes it over again, carrying its own side's summoner's cast and no play, so its own"
          + " slot no longer follows it; turned a third time, past the most a projectile takes, it"
          + " ends on that step, which the run never sees, so the hero stays on its side and the"
          + " odd count gives it the deflected row there")
  void aRerollTurnedTwiceComesBackWithNoPlay(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(folder, false, MOST_DEFLECTIONS);
    ChampionController slot = scene.slot();
    CharacterEntity hero = scene.heroReady();
    TowerEntity own = scene.battle.getWorld().kingTower(0);
    // Side 0's deflector behind the hero, then side 1's ahead of it.
    scene.deflector(0, hero.getView().getX(), hero.getView().getY() - TWICE_BEHIND);
    scene.deflectAhead(hero);
    scene.battle.useAbility(scene.tick(), 0, hero.getId(), "a");
    int limit = scene.tick() + 100;
    while (scene.projectiles(REROLL_PROJECTILE).isEmpty()) {
      assertThat(scene.tick()).isLessThan(limit);
      scene.step();
    }
    ProjectileEntity barrel = scene.projectiles(REROLL_PROJECTILE).get(0);
    while (hero.side() == 0) {
      assertThat(scene.tick()).isLessThan(limit);
      scene.step();
    }
    assertThat(barrel.getDeflections()).isEqualTo(1);
    while (hero.side() == 1) {
      assertThat(scene.tick()).isLessThan(limit);
      scene.step();
    }
    assertThat(barrel.getDeflections()).isEqualTo(2);
    assertThat(barrel.side()).isZero();
    assertThat(hero.castId()).isEqualTo(own.castId());
    assertThat(hero.getDeployIndex()).as("its own side's summoner's play, none").isEqualTo(-1);
    scene.step();
    assertThat(slot.champions()).doesNotContain(hero);
    while (scene.projectiles(REROLL_PROJECTILE).contains(barrel)) {
      assertThat(scene.tick()).isLessThan(limit);
      assertThat(hero.side()).isZero();
      scene.step();
    }
    // Side 1's deflector turned it a third time, past the most, and its flight ended on that
    // step: the run saw two turns, the count at the end is three.
    assertThat(barrel.getDeflections()).isEqualTo(MOST_DEFLECTIONS + 1);
    assertThat(hero.side()).isZero();
    assertThat(hero.getView().getState()).isEqualTo(GridEntityState.DEPLOYING);
    assertThat(hero.getView().getDirY()).isPositive();
    while (!hero.getData().name().equals(ROLL_DEFLECTED)) {
      assertThat(scene.tick()).isLessThan(limit);
      scene.step();
    }
    assertThat(hero.side()).as("the deflected row on the side it stands on").isZero();
    scene.step();
    assertThat(slot.champions()).doesNotContain(hero);
  }

  /**
   * A Ladder match of side 0's roll card in its hero slot and seven filler cards, the roll card in
   * the hand; side 1 holds the eight filler cards and plays nothing, or the roll card in its hero
   * slot as well. The towers fight.
   */
  private static final class Scene {

    final GameTables tables;
    final Standard1v1Battle battle;
    final LadderMatch match;

    /**
     * @param folder the folder the tables are written into
     */
    Scene(Path folder) throws IOException {
      this(folder, false);
    }

    /**
     * @param folder the folder the tables are written into
     * @param sideOneRolls true to give side 1 the roll card in its hero slot too, in its hand
     */
    Scene(Path folder, boolean sideOneRolls) throws IOException {
      this(folder, sideOneRolls, -1);
    }

    /**
     * @param folder the folder the tables are written into
     * @param sideOneRolls true to give side 1 the roll card in its hero slot too, in its hand
     * @param mostDeflections the most deflections a projectile takes, written into the tables, or
     *     -1 to keep the configured tables' value
     */
    Scene(Path folder, boolean sideOneRolls, int mostDeflections) throws IOException {
      GameTables written = HeroFormTables.write(folder);
      if (mostDeflections >= 0) {
        GameData.alterLoaded(
            folder,
            "globals",
            rows ->
                ((ObjectNode) rows.get("MAX_DEFLECTION_TIMES").get("columns"))
                    .put("NumberValue", mostDeflections));
        written = GameTables.load(folder);
      }
      tables = written;
      int[] slots = new int[8];
      slots[0] = MatchSide.HERO_SLOT;
      Standard1v1Battle made = null;
      LadderMatch started = null;
      for (int word = 0;
          started == null
              || !inHand(started, 0, ROLL_CARD)
              || (sideOneRolls && !inHand(started, 1, ROLL_CARD));
          word++) {
        made = new Standard1v1Battle(tables);
        started =
            made.startLadderMatch(
                SpawnedChampionTables.deck(ROLL_CARD),
                sideOneRolls
                    ? SpawnedChampionTables.deck(ROLL_CARD)
                    : SpawnedChampionTables.fillers(),
                word,
                word,
                slots,
                sideOneRolls ? slots : new int[8]);
      }
      battle = made;
      match = started;
    }

    /**
     * Plays side 0's roll card and steps until the hero it leaves has stood a while and its side
     * can pay for its ability.
     */
    CharacterEntity heroReady() {
      play();
      int limit = tick() + 200;
      while (named(ROLL_HERO).stream().noneMatch(unit -> unit.side() == 0)) {
        assertThat(tick()).isLessThan(limit);
        step();
      }
      CharacterEntity hero =
          named(ROLL_HERO).stream().filter(unit -> unit.side() == 0).findFirst().orElseThrow();
      for (int i = 0; i < 40 || match.side(0).wholeElixir() < ROLL_ABILITY_COST; i++) {
        step();
      }
      return hero;
    }

    /**
     * Puts side 1's deflector down a little ahead of the hero, in its lane, held still, and steps
     * until its deflecting area effect is up.
     */
    void deflectAhead(CharacterEntity hero) {
      deflector(1, hero.getView().getX(), hero.getView().getY() + 2500);
    }

    /**
     * Puts a side's deflector down at a point, held still, and steps until its deflecting area
     * effect is up.
     */
    void deflector(int side, int x, int y) {
      long before = deflectors();
      CharacterEntity deflector =
          battle.deploy(
              tick() + 1,
              battle.getWorld().getRecords().unit(DEFLECTOR),
              LEVEL,
              side,
              x,
              y,
              "deflector" + side);
      int limit = tick() + 100;
      while (deflector.getId() == 0
          || deflector.getView().getState() == GridEntityState.DEPLOYING
          || deflector.getView().getState() == GridEntityState.WAITING_TO_DEPLOY) {
        assertThat(tick()).isLessThan(limit);
        step();
      }
      deflector.setActive(CharacterEntity.MOVEMENT_SLOT, false);
      deflector.setActive(CharacterEntity.TARGETING_SLOT, false);
      deflector.requestAbility();
      while (deflectors() == before) {
        assertThat(tick()).isLessThan(limit);
        step();
      }
    }

    /** The king's first champion slot of side 0. */
    ChampionController slot() {
      return battle.getWorld().kingTower(0).championSlot(1);
    }

    int tick() {
      return battle.getBattle().getTick();
    }

    /** Steps until the match lets side 0 play the roll card. */
    void waitToPlay() {
      while (tick() < match.getStartCooldownTicks() || match.side(0).wholeElixir() < CARD_COST) {
        step();
      }
    }

    /** Gives side 0's play of the roll card once the match lets it; the play runs later. */
    void play() {
      waitToPlay();
      battle.play(tick(), battle.getWorld().getRecords().card(ROLL_CARD), LEVEL, 0, X, Y, "r");
    }

    /** The characters of a row the holder lists, in its order. */
    List<CharacterEntity> named(String row) {
      return battle.getWorld().getHolder().entities().stream()
          .filter(CharacterEntity.class::isInstance)
          .map(CharacterEntity.class::cast)
          .filter(unit -> unit.getData().name().equals(row))
          .toList();
    }

    /** The area effects the holder lists that deflect projectiles. */
    long deflectors() {
      return battle.getWorld().getHolder().entities().stream()
          .filter(AreaEffectEntity.class::isInstance)
          .map(AreaEffectEntity.class::cast)
          .filter(area -> area.getData().deflectsProjectiles())
          .count();
    }

    /** The projectiles of a row the holder lists, in its order. */
    List<ProjectileEntity> projectiles(String row) {
      return battle.getWorld().getHolder().entities().stream()
          .filter(ProjectileEntity.class::isInstance)
          .map(ProjectileEntity.class::cast)
          .filter(projectile -> projectile.getData().name().equals(row))
          .toList();
    }

    void step() {
      battle.getBattle().step();
    }
  }

  private static boolean inHand(LadderMatch match, int side, String card) {
    List<MatchCard> deck = match.side(side).deck();
    return Arrays.stream(match.side(side).getHand().slots())
        .anyMatch(index -> index >= 0 && deck.get(index).name().equals(card));
  }
}
