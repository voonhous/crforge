/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.data;

import static org.crforge.core.util.ValidationUtils.checkArgument;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.battle.match.BattleTimeline;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.unit.AreaEffectData;
import org.crforge.core.battle.unit.BuffData;
import org.crforge.core.battle.unit.UnitData;

/**
 * The battle's records built from the game's own rows.
 *
 * <p>Units, projectiles and troop cards. Every field is the column of the same name, in the
 * column's own units - milliseconds, game units, the published speed - and nothing is converted or
 * chosen. A column the row leaves empty is 0 or false. Only six fields are not a column: a unit
 * carries its row's global id; a unit flies when its flying height is above 0; a unit is a champion
 * when its ability row makes it one; a unit lists the columns of its death that its row sets and
 * the battle does not model; a projectile's damage scaling rule is named by its scaling mode
 * column, the king tower's or the princess towers', and is the card rule otherwise; and a rarity is
 * the published row of that name.
 *
 * <p>Each table family has its reader - {@link UnitRecords}, {@link AreaEffectRecords}, {@link
 * BuffRecords}, {@link ProjectileRecords}, {@link FilterRecords} and {@link CardRecords}, on {@link
 * RowReader} - which this facade fronts; a reader that needs another table's record asks the
 * facade.
 */
public final class BattleRecords {

  private static final String GAME_MODES = "game_modes";
  private static final String BATTLE_TIMELINES = "battle_timelines";
  private static final String GLOBALS = "globals";
  private static final String LOCATIONS = "locations";

  /** The section types of a battle timeline by name; the order is their number. */
  private static final List<String> SECTION_TYPES = List.of("Normal", "Overtime", "BonusTime");

  private final GameTables tables;

  // The readers of each table, which ask this facade for a record another reader builds.
  private final UnitRecords unitRecords;
  private final AreaEffectRecords areaEffectRecords;
  private final BuffRecords buffRecords;
  private final ProjectileRecords projectileRecords;
  private final FilterRecords filterRecords;
  private final CardRecords cardRecords;

  /**
   * @param tables the game tables of one data version
   */
  public BattleRecords(GameTables tables) {
    this.tables = tables;
    this.unitRecords = new UnitRecords(tables, this);
    this.areaEffectRecords = new AreaEffectRecords(tables, this);
    this.buffRecords = new BuffRecords(tables, this);
    this.projectileRecords = new ProjectileRecords(tables, this);
    this.filterRecords = new FilterRecords(tables, this);
    this.cardRecords = new CardRecords(tables, this);
  }

  /**
   * The game tables these records read, for a reader of columns the battle itself never reads, such
   * as the visualizer's view columns.
   */
  public GameTables tables() {
    return tables;
  }

  /**
   * A unit or a building as the battle reads it, from the characters table or, for a building, the
   * buildings table (see {@link UnitRecords#unit}).
   */
  public UnitData unit(String name) {
    return unitRecords.unit(name);
  }

  /**
   * The cards a card group lists for a play of a card in any form but the hero form, from the card
   * groups table: its playable cards (see {@link CardRecords#cardGroup}).
   */
  public List<String> cardGroup(String name) {
    return cardRecords.cardGroup(name);
  }

  /**
   * The cards a card group lists for a play of a row in the hero form, from the card groups table:
   * its heroes, empty for a group that lists none (see {@link CardRecords#cardGroupHeroes}).
   */
  public List<String> cardGroupHeroes(String name) {
    return cardRecords.cardGroupHeroes(name);
  }

  /**
   * A game object filter as the battle reads it, from the game object filters table (see {@link
   * FilterRecords#filter}).
   */
  public GameObjectFilter filter(String name) {
    return filterRecords.filter(name);
  }

  /** The radius of a shape row that is a circle (see {@link AreaEffectRecords#circleRadius}). */
  public int circleRadius(String name) {
    return areaEffectRecords.circleRadius(name);
  }

  /**
   * Whether a shape row that is a circle checks its origin (CheckOrigin): the shape's narrowing
   * then keeps only what its query found whose centre lies within its radius (see {@link
   * AreaEffectRecords#circleChecksOrigin}).
   */
  public boolean circleChecksOrigin(String name) {
    return areaEffectRecords.circleChecksOrigin(name);
  }

  /**
   * An area effect as the battle reads it, from the area effect objects table (see {@link
   * AreaEffectRecords#areaEffect}).
   */
  public AreaEffectData areaEffect(String name) {
    return areaEffectRecords.areaEffect(name);
  }

  /**
   * A character buff as the battle reads it, from the character buffs table, or the buff row a buff
   * spawn row writes inline under that name (see {@link BuffRecords#buff}).
   */
  public BuffData buff(String name) {
    return buffRecords.buff(name);
  }

  /**
   * A projectile as the battle reads it, from the projectiles table (see {@link
   * ProjectileRecords#projectile}).
   */
  public ProjectileData projectile(String name) {
    return projectileRecords.projectile(name);
  }

  /**
   * A troop or building card's placement as the battle reads it, from the spells characters table
   * or the spells buildings table: the units it summons, built from their own rows, how many, its
   * formation and where it may be placed (see {@link CardRecords#card}).
   */
  public DeployCard card(String name) {
    return cardRecords.card(name);
  }

  /**
   * A card as a play whose item names another card places it: the card's units, formation and list,
   * with the staggers between the units of its two groups that the named card's row sets (0 where
   * it sets none) (see {@link CardRecords#withStaggerOfCard}).
   */
  public DeployCard withStaggerOfCard(DeployCard card, String named) {
    return cardRecords.withStaggerOfCard(card, named);
  }

  /**
   * The champion a champion slot finds for a card: its linked champion character when that is a
   * champion, which only a hero form names - the Goblins' names the banner its last goblin leaves -
   * else the champion the card summons ({@link DeployCard#champion()}); null for none (see {@link
   * CardRecords#cardChampion}).
   */
  public UnitData cardChampion(String name) {
    return cardRecords.cardChampion(name);
  }

  /**
   * The row of a unit: the characters table's, else the buildings table's (see {@link
   * UnitRecords#unitGlobalId}).
   */
  public Integer unitGlobalId(String name) {
    return unitRecords.unitGlobalId(name);
  }

  /**
   * What the match reads of a card: its cost, its two opening-hand columns, its production stop,
   * whether it is the Mirror, the options a variant card is played as, whether a Mirror repeats it
   * as the card itself or as the row its play was cast as, and the rows it is played as in other
   * forms: each its own match card, with its form and DarkElixirCost (see {@link
   * CardRecords#matchCard}).
   */
  public MatchCard matchCard(String name) {
    return cardRecords.matchCard(name);
  }

  /**
   * Whether a card is a troop card: a row of the characters' cards (see {@link
   * CardRecords#troopCard}).
   */
  public boolean troopCard(String name) {
    return cardRecords.troopCard(name);
  }

  /**
   * Whether a card is a spell card: a row of the other spells' cards (see {@link
   * CardRecords#spellCard}).
   */
  public boolean spellCard(String name) {
    return cardRecords.spellCard(name);
  }

  /**
   * Whether a card is a spell or building card: a row of the other spells' or the buildings' cards
   * (see {@link CardRecords#spellOrBuildingCard}).
   */
  public boolean spellOrBuildingCard(String name) {
    return cardRecords.spellOrBuildingCard(name);
  }

  /** The mass a unit's or building's row is loaded at (see {@link UnitRecords#loadedMass}). */
  static int loadedMass(int mass, int collisionRadius) {
    return UnitRecords.loadedMass(mass, collisionRadius);
  }

  /** The speed a unit's row is loaded at (see {@link UnitRecords#loadedSpeed}). */
  static int loadedSpeed(int speed, int stopMovementAfterMs, int waitMs) {
    return UnitRecords.loadedSpeed(speed, stopMovementAfterMs, waitMs);
  }

  /**
   * The battle timeline of a game mode: its row's BattleTimeline, read from the battle timelines
   * table. Each array column is as long as the row gives it; section flags left out are 0.
   *
   * @param gameMode the game mode row's name
   */
  public BattleTimeline gameModeTimeline(String gameMode) {
    GameTable modes = tables.table(GAME_MODES);
    checkArgument(modes.has(gameMode), () -> "the game tables have no game mode " + gameMode);
    String name = modes.row(gameMode).string("BattleTimeline");
    GameTable timelines = tables.table(BATTLE_TIMELINES);
    checkArgument(timelines.has(name), () -> "the game tables have no battle timeline " + name);
    GameRow row = timelines.row(name);
    List<Integer> types = new ArrayList<>();
    for (String type : row.strings("SectionType")) {
      int number = SECTION_TYPES.indexOf(type);
      checkArgument(number >= 0, () -> name + " has a section of type " + type);
      types.add(number);
    }
    List<Boolean> notify = new ArrayList<>();
    for (JsonNode element : RowReader.arrayOf(row, "ElixirNotifyChange")) {
      notify.add(row.boolElement("ElixirNotifyChange", element));
    }
    return new BattleTimeline(
        row.name(),
        row.intValue("StartingElixir"),
        RowReader.ints(row, "SectionLength"),
        types,
        RowReader.ints(row, "SectionFlags"),
        RowReader.ints(row, "ElixirRateLength"),
        RowReader.ints(row, "ElixirFullBarMS"),
        RowReader.ints(row, "ElixirRateVisible"),
        notify,
        RowReader.ints(row, "NextSpellCooldownLength"),
        RowReader.ints(row, "NextSpellCooldownMS"),
        RowReader.ints(row, "EventTime"));
  }

  /**
   * A game mode's start cooldown in milliseconds: how long from the battle's start a card play is
   * refused. The game mode row's BattleStartCooldown, or, where the row leaves it empty, the global
   * LOGIC_BATTLE_START_COOLDOWN. The game picks another global, LOGIC_BATTLE_START_COOLDOWN_2V2,
   * for a 2v2 battle, which the battle core does not play.
   *
   * @param gameMode the game mode row's name
   */
  public int battleStartCooldownMs(String gameMode) {
    GameTable modes = tables.table(GAME_MODES);
    checkArgument(modes.has(gameMode), () -> "the game tables have no game mode " + gameMode);
    int own = modes.row(gameMode).intValue("BattleStartCooldown");
    return own != 0 ? own : globalNumber("LOGIC_BATTLE_START_COOLDOWN");
  }

  /**
   * The end screen's delay, in milliseconds: how long a battle goes on after its end before it
   * stops. It is the location's, and every location has the same one; the battle's location is not
   * read, so one that differed would be refused here.
   */
  public int endScreenDelayMs() {
    Set<Integer> delays = new TreeSet<>();
    for (GameRow row : tables.table(LOCATIONS).rows()) {
      delays.add(row.intValue("EndScreenDelay"));
    }
    checkArgument(delays.size() == 1, () -> "the locations' end screen delays differ: " + delays);
    return delays.iterator().next();
  }

  /**
   * A published global's number.
   *
   * @param name the global's name
   */
  public int globalNumber(String name) {
    GameTable globals = tables.table(GLOBALS);
    checkArgument(globals.has(name), () -> "the game tables have no global " + name);
    return globals.row(name).intValue("NumberValue");
  }

  /**
   * A published global's flag.
   *
   * @param name the global's name
   */
  public boolean globalBoolean(String name) {
    GameTable globals = tables.table(GLOBALS);
    checkArgument(globals.has(name), () -> "the game tables have no global " + name);
    return globals.row(name).bool("BooleanValue");
  }

  /**
   * A published global's text.
   *
   * @param name the global's name
   */
  public String globalText(String name) {
    GameTable globals = tables.table(GLOBALS);
    checkArgument(globals.has(name), () -> "the game tables have no global " + name);
    return globals.row(name).string("TextValue");
  }
}
