/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.battle.GameData.fields;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.deploy.CardPlacement;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.crforge.core.pathfinding.math.FixedMath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Tombstone's hero form: a building card in the hero slot, played as its hero row, which lists
 * three characters - a dummy, the Tombstone building and the passive monster that holds the
 * ability. The passive monster has no speed, so no movement component, and stands. The ability's
 * tap swaps it onto its active row, with a speed and no lifetime: it is given a movement component
 * and walks, and its drain ends. The building's kill check finds the active monster and kills the
 * building, at the damage drain, so the building still pushes the monster on the tick it is killed;
 * the dummy breaks into its broken row.
 *
 * <p>The scene is the recorded hero Tombstone battle's opening: side 0's hero Tombstone placed at
 * (3500, 14000), its ability tapped 60 ticks after the play.
 */
class TombstoneHeroTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The hero form's row. */
  private static final GameRow HERO_FORM = Shipped.row("spells_hero_form", "Tombstone_hero");

  /** The Tombstone first, in the hero slot, and seven other cards. */
  private static final List<String> DECK =
      List.of(
          "Tombstone", "Archer", "Goblins", "Giant", "Minions", "Musketeer", "Fireball", "Arrows");

  private static final List<String> KNIGHTS = Collections.nCopies(8, "Knight");

  /** The hero row's three characters, in its list's order. */
  private static final List<String> LISTED = Shipped.texts(HERO_FORM, "SummonCharactersList");

  /** The passive monster's ability row. */
  private static final GameRow ABILITY =
      Shipped.row("character_abilities", Shipped.text(Shipped.unitRow(LISTED.get(2)), "Ability"));

  /** The elixir side 0 waits for before the play: the play's and, after it, the ability's. */
  private static final int ELIXIR_BEFORE_PLAY =
      Shipped.number(HERO_FORM, "ManaCost") + Shipped.number(ABILITY, "ManaCost");

  /** The row the activation group's first action swaps the passive monster onto. */
  private static final String ACTIVE =
      Shipped.text(
          Shipped.actionNames(Shipped.text(ABILITY, "OnActivationAction"), "SubActions").get(0),
          "NewCharacterData");

  /** The battle and its match, with the Tombstone in side 0's hand. */
  private record Scene(Standard1v1Battle battle, LadderMatch match, BattleRecords records) {}

  /** A Ladder match whose side 0 holds the hero Tombstone in its hand. */
  private static Scene scene() {
    return scene(GameData.tables());
  }

  /** A Ladder match of the given tables whose side 0 holds the hero Tombstone in its hand. */
  private static Scene scene(GameTables tables) {
    BattleRecords records = new BattleRecords(tables);
    int[] heroFirst = new int[8];
    heroFirst[0] = MatchSide.HERO_SLOT;
    for (int word = 0; ; word++) {
      Standard1v1Battle battle = new Standard1v1Battle(tables);
      LadderMatch match = battle.startLadderMatch(DECK, KNIGHTS, word, 0, heroFirst, new int[8]);
      if (inHand(match, "Tombstone")) {
        return new Scene(battle, match, records);
      }
    }
  }

  /** Plays the hero Tombstone at the recorded point and steps the play's tick. */
  private static List<CharacterEntity> play(Scene scene) {
    Standard1v1Battle battle = scene.battle();
    // Enough for the play and, after it, the ability's cost.
    while (scene.match().side(0).wholeElixir() < ELIXIR_BEFORE_PLAY) {
      battle.getBattle().step();
    }
    battle.play(
        battle.getBattle().getTick(),
        scene.records().card("Tombstone"),
        LEVEL,
        0,
        3500,
        14000,
        "t");
    battle.getBattle().step();
    return lastPlay(scene).units();
  }

  /** The last play of the scene's battle. */
  private static Standard1v1Battle.Play lastPlay(Scene scene) {
    List<Standard1v1Battle.Play> plays = scene.battle().getPlays();
    return plays.get(plays.size() - 1);
  }

  @Test
  @DisplayName(
      "a hero slot's Tombstone places its hero row's three characters - the dummy, the building"
          + " and the passive monster - in the list's order at the placed point")
  void theHeroPlayPlacesTheListedCharacters() {
    Scene scene = scene();
    List<CharacterEntity> units = play(scene);
    assertThat(units).extracting(unit -> unit.getData().name()).containsExactlyElementsOf(LISTED);
    assertThat(units.get(0).getId()).isLessThan(units.get(1).getId());
    assertThat(units.get(1).getId()).isLessThan(units.get(2).getId());
    CardPlacement.Result placed = lastPlay(scene).result();
    for (CharacterEntity unit : units) {
      assertThat(unit.getView().getX()).isEqualTo(placed.x());
      assertThat(unit.getView().getY()).isEqualTo(placed.y());
      assertThat(unit.getView().getState()).isEqualTo(GridEntityState.DEPLOYING);
    }
  }

  @Test
  @DisplayName(
      "the passive monster, a character row without a speed, has no movement component and"
          + " stands once it has deployed")
  void thePassiveMonsterStands() {
    Scene scene = scene();
    CharacterEntity passive = play(scene).get(2);
    CardPlacement.Result placed = lastPlay(scene).result();
    assertThat(passive.getView().isMovementComponent()).isFalse();
    for (int i = 0; i < 25; i++) {
      scene.battle().getBattle().step();
    }
    assertThat(passive.getView().getState()).isEqualTo(GridEntityState.STANDING);
    assertThat(passive.getView().getX()).isEqualTo(placed.x());
    assertThat(passive.getView().getY()).isEqualTo(placed.y());
  }

  @Test
  @DisplayName(
      "the tap swaps the passive monster onto its active row, which walks with a new movement"
          + " component; the building it kills dies at the damage drain, so it pushes the monster"
          + " once more on that tick, and the dummy breaks")
  void theTapWakesTheMonster(@TempDir Path folder) throws IOException {
    // The intervals the scene's steps line up with, written into a copy of their rows: the
    // dummy's check for the active monster every 100 ms, the building's kill check every step,
    // and its kill 100 ms after it finds the monster. The building's and the active monster's
    // radii, the monster's mass and speed are written too: with the building of radius 1000 over a
    // monster of radius 750 on the same point, the separation's push is past the clamp.
    GameData.altered(
        folder,
        "actions",
        rows -> {
          fields(rows, "TombstoneHero_check_tomb_interval").put("Interval", 100);
          fields(rows, "Tombstone_hero_kill_check").put("Interval", 50);
          ArrayNode delays = fields(rows, "Tombstone_hero_kill_group").putArray("SubActionsDelay");
          delays.add(0).add(100);
        });
    GameData.alterLoaded(
        folder,
        "buildings",
        rows -> GameData.columns(rows, LISTED.get(1)).put("CollisionRadius", 1000));
    GameData.alterLoaded(
        folder,
        "characters",
        rows -> {
          ObjectNode active = GameData.columns(rows, ACTIVE);
          active.put("CollisionRadius", 750);
          active.put("Mass", 18);
          active.put("Speed", 60);
        });
    GameTables tables = GameTables.load(folder);
    Scene scene = scene(tables);
    Standard1v1Battle battle = scene.battle();
    List<CharacterEntity> units = play(scene);
    CharacterEntity dummy = units.get(0);
    CharacterEntity building = units.get(1);
    CharacterEntity monster = units.get(2);
    int passiveMaximum = monster.getHitPoints().getMaximum();
    int tap = battle.getBattle().getTick() + 59;
    battle.useAbility(tap, 0, monster.name(), "a");
    // The tap's step runs the activation group, whose first action swaps the row.
    while (battle.getBattle().getTick() <= tap) {
      battle.getBattle().step();
    }
    assertThat(monster.getData().name()).isEqualTo(ACTIVE);
    assertThat(monster.getView().isMovementComponent()).isTrue();
    assertThat(monster.getView().getState()).isEqualTo(GridEntityState.MOVING);
    assertThat(monster.getHitPoints().getMaximum()).isGreaterThan(passiveMaximum);
    assertThat(monster.getHitPoints().getHitPoints())
        .isEqualTo(monster.getHitPoints().getMaximum());
    assertThat(monster.getHitPoints().getDecayStep()).as("the drain ended").isZero();

    int x = monster.getView().getX();
    int y = monster.getView().getY();
    battle.getBattle().step();
    assertThat(dummy.getData().name())
        .isEqualTo(Shipped.text("Tombstone_hero_switch_dummy_to_broken_tomb", "NewCharacterData"));
    assertThat(HitPoints.alive(building.getHitPoints())).isTrue();
    // The building, still standing, pushes the monster off its point along the diagonal: the
    // separation's push of one step, held to the clamp of 150 the step applies, scaled with the
    // game's integer normalization.
    int[] push = {PUSH_CLAMP, PUSH_CLAMP};
    FixedMath.normalize(push, PUSH_CLAMP);
    int pushX = push[0];
    int pushY = push[1];
    assertThat(monster.getView().getX()).isEqualTo(x - pushX);
    assertThat(monster.getView().getY()).isEqualTo(y - pushY);

    // The building's kill lands at the drain: it pushes the monster once more, as far, on the tick
    // of its death, and leaves the battle at the next cleanup.
    battle.getBattle().step();
    assertThat(HitPoints.alive(building.getHitPoints())).isFalse();
    assertThat(monster.getView().getX()).isEqualTo(x - 2 * pushX);
    assertThat(monster.getView().getY()).isEqualTo(y - 2 * pushY);
    battle.getBattle().step();
    assertThat(monster.getView().getX()).isEqualTo(x - 2 * pushX);
    assertThat(monster.getView().getY()).isEqualTo(y - 2 * pushY);
  }

  /** The scratch key the written chain hands the building's level over in. */
  private static final String LEVEL_KEY = "test_level";

  /**
   * The building hands its level to the passive monster with the hit points it already hands over,
   * and the monster runs a level change on its parent - itself, the entity whose holder runs it -
   * by the building's level less its own. The chain is written into the scene's rows: the
   * building's hit-point write goes on to write its level into the same scratch and the monster's
   * hit-point read goes on to read it into a variable and change its level.
   */
  @Test
  @DisplayName(
      "the passive monster takes the building's level, which the building hands over with its"
          + " hit points: a level change on the parent, the monster, not on its cause, the"
          + " building")
  void theMonsterTakesTheBuildingsLevel(@TempDir Path folder) throws IOException {
    GameData.altered(
        folder,
        "actions",
        rows -> {
          ObjectNode setHp = fields(rows, "Tombstone_hero_set_hp");
          JsonNode send = setHp.get("NextAction");
          setHp.putObject("NextAction").put("action", "TestSetLevel");
          ObjectNode setLevel = action(rows, "TestSetLevel", "ActionBlackboardSetInt");
          setLevel.put("Key", LEVEL_KEY);
          setLevel.set("NextAction", send);
          setLevel.put("NextActionWait", true);
          setLevel.put("UpdatePhase", "PostGameObjectTick");
          setLevel.put("UseScratch", true);
          setLevel.put("Value", "character_level");

          ObjectNode track = fields(rows, "Tombstone_hero_Monster_TrackHealthValue_Action");
          track.putObject("NextAction").put("action", "TestTrackLevel");
          track.put("NextActionWait", true);
          ObjectNode trackLevel = action(rows, "TestTrackLevel", "ActionContextToVariable");
          trackLevel.put("BlackboardKey", LEVEL_KEY);
          trackLevel.putObject("NextAction").put("action", "TestApplyLevel");
          trackLevel.put("NextActionWait", true);
          trackLevel.put("OutputVariable", GameData.TEST_VARIABLE);
          trackLevel.put("UseScratch", true);
          ObjectNode apply = action(rows, "TestApplyLevel", "ActionSetCharacterLevel");
          apply.put("ExecuteIfTrue", GameData.TEST_VARIABLE + " >= 0");
          apply.put("ExecuteOnParent", true);
          apply.put(
              "RelativeLevelAdjustmentExpression", GameData.TEST_VARIABLE + " - character_level");
        });
    GameData.addTestVariable(folder);
    GameData.alterLoaded(
        folder,
        "variables",
        rows -> GameData.columns(rows, GameData.TEST_VARIABLE).put("DefaultValue", -1));
    Scene scene = scene(GameTables.load(folder));
    List<CharacterEntity> units = play(scene);
    CharacterEntity building = units.get(1);
    CharacterEntity monster = units.get(2);
    for (int i = 0; i < 25; i++) {
      scene.battle().getBattle().step();
    }
    assertThat(building.level()).isEqualTo(LEVEL);
    assertThat(monster.level()).as("the same level changes nothing").isEqualTo(LEVEL);

    // The building falls one level, as a level change on it would leave it.
    building.changeLevel(building.actionPackedLevel() - 1);
    // The building hands its hit points and level over every 100 ms: within the next two steps
    // the monster has taken the new level, and the building, the change's cause, keeps it.
    for (int i = 0; i < 2; i++) {
      scene.battle().getBattle().step();
    }
    assertThat(monster.level()).isEqualTo(LEVEL - 1);
    assertThat(building.level()).as("the cause is left at its level").isEqualTo(LEVEL - 1);
    for (int i = 0; i < 20; i++) {
      scene.battle().getBattle().step();
    }
    assertThat(monster.level()).isEqualTo(LEVEL - 1);
    assertThat(building.level()).isEqualTo(LEVEL - 1);
  }

  /** A hand-written action row of the class, its fields to fill. */
  private static ObjectNode action(ObjectNode rows, String name, String classType) {
    ObjectNode row = rows.putObject(name);
    row.put("class", "Logic" + classType + "Data");
    row.put("ClassType", classType);
    ObjectNode fields = row.putObject("fields");
    fields.put("ClassType", classType);
    return fields;
  }

  /** The length a step's averaged push is clamped to. */
  private static final int PUSH_CLAMP = 150;

  /** True when side 0's hand holds the card. */
  private static boolean inHand(LadderMatch match, String card) {
    List<MatchCard> deck = match.side(0).deck();
    return Arrays.stream(match.side(0).getHand().slots())
        .anyMatch(index -> index >= 0 && deck.get(index).name().equals(card));
  }
}
