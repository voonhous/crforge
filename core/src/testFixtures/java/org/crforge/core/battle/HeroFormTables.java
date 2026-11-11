/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import org.crforge.core.battle.data.GameTables;

/**
 * Hand-written rows for the tests of two hero forms built of actions, beside {@link
 * SpawnedChampionTables}' rows (whose filler cards fill the decks):
 *
 * <ul>
 *   <li>A squad hero: {@link #SQUAD_CARD}'s hero form summons {@link #SQUAD_COUNT} of {@link
 *       #SQUAD_MEMBER} as a group and links {@link #BANNER}. Each member holds the banner's slot
 *       not yet available and refills its charges as it starts, and listens for a new play of its
 *       card group, which tags it. The last untagged member to fall plants the banner where it
 *       fell. The banner makes its slot ready, stands for {@link #BANNER_TIMER_MS} and then {@link
 *       #BANNER_EXPIRE_WAIT_MS} more, and goes at a new play of the group unless it casts or is
 *       tagged. Its ability tags it, sends a wave of two {@link #WAVE_MEMBER} at offsets its
 *       expressions give, and kills it.
 *   <li>A roll hero: {@link #ROLL_CARD}'s hero form throws {@link #THROW}, which becomes {@link
 *       #ROLLING}, whose end leaves {@link #ROLL_HERO} (or, turned by a deflection, {@link
 *       #ROLL_DEFLECTED}). Both projectiles hold the hero's slot ready while they fly. The hero's
 *       ability is {@link #REROLL}, a reroll in {@link #REROLL_PROJECTILE} that heals it by {@link
 *       #HEAL_PERCENT_VARIABLE}'s percent of its missing hit points.
 *   <li>{@link #DEFLECTOR}, a walker whose ability spawns {@link #DEFLECT_AREA}, an area effect
 *       that follows it and deflects projectiles.
 *   <li>{@link #PLAIN_SPELL} and {@link #PLAIN_BUILDING}, a spell and a building card with no hero
 *       form.
 * </ul>
 *
 * <p>Every value a test expects is one of the constants here. Every row is new: the configured
 * tables are copied and the rows added, none altered.
 */
public final class HeroFormTables {

  /** Every written card's cost, in whole elixir: the filler cards' cost. */
  public static final int CARD_COST = SpawnedChampionTables.CARD_COST;

  /** A game tag the rows set and test: a squad member a new play tagged, a banner that casts. */
  public static final String TAG = "UNIT_CUSTOM_TAG_1";

  // The squad hero.

  /** The troop card of the squad, whose hero form links the banner. */
  public static final String SQUAD_CARD = "Test_Squad_Card";

  /** The card group of the squad card and its hero form, which the listeners name. */
  public static final String SQUAD_GROUP = "Test_Squad_Group";

  /** The squad's walker. */
  public static final String SQUAD_MEMBER = "Test_Squad_Member";

  /** How many members the hero form summons. */
  public static final int SQUAD_COUNT = 3;

  /** A member's hit points: few, so a princess tower's shots take the squad down. */
  public static final int SQUAD_MEMBER_HIT_POINTS = 100;

  /** A member's starting action: the listener and the slot's two overrides. */
  public static final String SQUAD_START = "Test_Squad_Start";

  /** The member's listener of a new play of the squad group. */
  public static final String SQUAD_LISTEN = "Test_Squad_Listen";

  /** What a new play does to a listening member, and the banner's activation to the banner. */
  public static final String SQUAD_TAG = "Test_Squad_Tag";

  /** The override that holds the banner's slot not yet available. */
  public static final String SQUAD_HOLD_BUTTON = "Test_Squad_Hold_Button";

  /** The override that refills the banner slot's charges. */
  public static final String SQUAD_RESET_CHARGES = "Test_Squad_Reset_Charges";

  /** A member's death action: the group check, run only for an untagged member. */
  public static final String SQUAD_CHECK_LAST = "Test_Squad_Check_Last";

  /** The plant of the banner, the group check's action when no other member is left. */
  public static final String SQUAD_PLANT = "Test_Squad_Plant";

  /** The banner, the squad's champion: a building character with an ability. */
  public static final String BANNER = "Test_Banner";

  /** The banner's ability row. */
  public static final String BANNER_ABILITY = "Test_Banner_Ability";

  /** The banner ability's cost, in whole elixir. */
  public static final int BANNER_ABILITY_COST = 1;

  /** The banner ability's charges, which the squad's reset refills. */
  public static final int BANNER_CHARGES = 2;

  /** How long the banner casts its ability, in milliseconds. */
  public static final int BANNER_CAST_TIME_MS = 600;

  /** How long into the cast its activation fires, in milliseconds. */
  public static final int BANNER_TRIGGER_DELAY_MS = 300;

  /** The banner's timer, in milliseconds: its one interval. */
  public static final int BANNER_TIMER_MS = 3000;

  /** The wait after the timer's interval before the banner goes, in milliseconds. */
  public static final int BANNER_EXPIRE_WAIT_MS = 1000;

  /** The banner's activation group: the tag, the wave's two spawns and the kill. */
  public static final String BANNER_ACTIVATION = "Test_Banner_Activation";

  /** The walker of the banner's wave. */
  public static final String WAVE_MEMBER = "Test_Wave_Member";

  /** How far sideways of the banner the wave's two members stand, in game units. */
  public static final int WAVE_SIDEWAYS = 1500;

  /** How far the wave's members stand from the banner along the team's y direction. */
  public static final int WAVE_BACK = 700;

  /** The delay of the wave's second spawn from the activation, in milliseconds. */
  public static final int WAVE_SECOND_MS = 250;

  /** The delay of the banner's kill from the activation, in milliseconds. */
  public static final int WAVE_LEAVE_MS = 600;

  // The roll hero.

  /** The spell card of the roll, whose hero form links the roll hero. */
  public static final String ROLL_CARD = "Test_Roll_Card";

  /** The card group of the roll card and its hero form, which the hero's listener names. */
  public static final String ROLL_GROUP = "Test_Roll_Group";

  /** The thrown projectile, which becomes the rolling one. */
  public static final String THROW = "Test_Roll_Throw";

  /** The rolling projectile, whose end leaves the hero. */
  public static final String ROLLING = "Test_Roll_Rolling";

  /** The roll's range, in game units. */
  public static final int ROLL_RANGE = 3000;

  /** The projectiles' starting action, which holds the hero's slot ready. */
  public static final String READY_BUTTON = "Test_Roll_Ready_Button";

  /** The hero the roll leaves, the champion the hero form links. */
  public static final String ROLL_HERO = "Test_Roll_Hero";

  /** What a roll turned by a deflection leaves instead, for the deflecting side. */
  public static final String ROLL_DEFLECTED = "Test_Roll_Deflected";

  /** The hero's starting action: the listener and the interval. */
  public static final String ROLL_HERO_START = "Test_Roll_Hero_Start";

  /** The hero's listener of a new play of the roll group. */
  public static final String ROLL_HERO_LISTEN = "Test_Roll_Hero_Listen";

  /** The hero's interval, which counts itself every step. */
  public static final String ROLL_HERO_INTERVAL = "Test_Roll_Hero_Interval";

  /** What the hero's listener and interval run: one more of {@link GameData#TEST_VARIABLE}. */
  public static final String ROLL_HERO_COUNT = "Test_Roll_Hero_Count";

  /** The hero's ability row. */
  public static final String ROLL_ABILITY = "Test_Roll_Ability";

  /** The roll ability's cost, in whole elixir. */
  public static final int ROLL_ABILITY_COST = 1;

  /** The hero's reroll, the ability's activation. */
  public static final String REROLL = "Test_Roll_Reroll";

  /** The reroll's projectile, which the hero follows. */
  public static final String REROLL_PROJECTILE = "Test_Roll_Reroll_Projectile";

  /** How far back the reroll backs the hero off over its spawn delay, in game units. */
  public static final int REROLL_OFFSET_Y = -800;

  /** How long the hero backs off before the reroll's projectile, in milliseconds. */
  public static final int REROLL_SPAWN_DELAY_MS = 300;

  /** How long the hero deploys where the reroll's projectile ends, in milliseconds. */
  public static final int REROLL_DEPLOY_DURATION_MS = 800;

  /** The reroll's starting action, the heal. */
  public static final String REROLL_HEAL = "Test_Roll_Heal";

  /** The variable whose value is the percent of its missing hit points the heal gives back. */
  public static final String HEAL_PERCENT_VARIABLE = "Test_Roll_Heal_Percent";

  /** The heal's percent, the variable's default. */
  public static final int HEAL_PERCENT = 40;

  // The deflector and the plain cards.

  /** A walker whose ability spawns a deflecting area effect that follows it. */
  public static final String DEFLECTOR = "Test_Deflector";

  /** The deflector's ability row. */
  public static final String DEFLECT_ABILITY = "Test_Deflect_Ability";

  /** The deflector ability's activation, the spawn of the area effect. */
  public static final String DEFLECT_SPAWN = "Test_Deflect_Spawn";

  /** The deflecting area effect. */
  public static final String DEFLECT_AREA = "Test_Deflect_Area";

  /** The filter of the deflecting area effect: the enemy's characters. */
  public static final String ENEMY_FILTER = "Test_Enemy_Characters";

  /** A spell card with no hero form. */
  public static final String PLAIN_SPELL = "Test_Plain_Spell";

  /** A building card with no hero form. */
  public static final String PLAIN_BUILDING = "Test_Plain_Building";

  private HeroFormTables() {
    // Utility class
  }

  /**
   * The configured tables copied into a folder with {@link SpawnedChampionTables}' rows and these
   * written.
   *
   * @param folder the folder to copy them into
   * @return the tables with the rows
   */
  public static GameTables write(Path folder) throws IOException {
    SpawnedChampionTables.writeRows(folder);
    GameData.addTestFilter(folder, columns -> {});
    GameData.alterLoaded(folder, "characters", HeroFormTables::writeCharacters);
    GameData.alterLoaded(folder, "character_abilities", HeroFormTables::writeAbilities);
    GameData.alterLoaded(folder, "actions", HeroFormTables::writeActions);
    GameData.alterLoaded(folder, "projectiles", HeroFormTables::writeProjectiles);
    GameData.alterLoaded(folder, "area_effect_objects", HeroFormTables::writeAreaEffect);
    GameData.alterLoaded(
        folder,
        "variables",
        rows -> WrittenRows.variable(rows, HEAL_PERCENT_VARIABLE, HEAL_PERCENT));
    GameData.alterLoaded(
        folder,
        "game_object_filters",
        rows -> {
          ObjectNode columns = WrittenRows.row(rows, ENEMY_FILTER, "GameObjectFilter");
          columns.put("MatchTeamEnemy", true).put("MatchTypeCharacters", true);
          columns.putArray("Filters").add("Buildings").add("Hidden").add("NoHitpointComponent");
        });
    GameData.alterLoaded(
        folder,
        "card_groups",
        rows -> {
          WrittenRows.cardGroup(rows, SQUAD_GROUP, SQUAD_CARD);
          WrittenRows.cardGroup(rows, ROLL_GROUP, ROLL_CARD);
        });
    GameData.alterLoaded(
        folder,
        "spells_characters",
        rows -> {
          WrittenRows.card(rows, SQUAD_CARD, CARD_COST)
              .put("SummonCharacter", SpawnedChampionTables.FILLER);
          WrittenRows.heroForms(rows, SQUAD_CARD);
        });
    GameData.alterLoaded(
        folder,
        "spells_other",
        rows -> {
          WrittenRows.card(rows, ROLL_CARD, CARD_COST).put("Projectile", THROW);
          WrittenRows.heroForms(rows, ROLL_CARD);
          WrittenRows.card(rows, PLAIN_SPELL, CARD_COST).put("Projectile", THROW);
        });
    GameData.alterLoaded(
        folder,
        "spells_buildings",
        rows ->
            WrittenRows.card(rows, PLAIN_BUILDING, CARD_COST)
                .put("SummonCharacter", SpawnedChampionTables.FILLER));
    GameData.alterLoaded(
        folder,
        "spells_hero_form",
        rows -> {
          WrittenRows.heroForm(rows, SQUAD_CARD, CARD_COST, BANNER)
              .put("SummonCharacter", SQUAD_MEMBER)
              .put("SummonNumber", SQUAD_COUNT)
              .put("SummonRadius", 700)
              .put("IsAGroup", true);
          WrittenRows.heroForm(rows, ROLL_CARD, CARD_COST, ROLL_HERO).put("Projectile", THROW);
        });
    return GameTables.load(folder);
  }

  /** The squad, the banner, the wave, the roll hero, its deflected spawn and the deflector. */
  private static void writeCharacters(ObjectNode rows) {
    WrittenRows.walker(rows, SQUAD_MEMBER)
        .put("Hitpoints", SQUAD_MEMBER_HIT_POINTS)
        .put("Speed", 120)
        .put("OnStartingAction", SQUAD_START)
        .put("OnDeathAction", SQUAD_CHECK_LAST);
    // A building that neither attacks nor takes damage, so it stands its timer out.
    WrittenRows.character(rows, BANNER)
        .put("Hitpoints", 1000)
        .put("IsBuilding", true)
        .put("LifeTime", 99999)
        .put("GameTagsToSet", "NO_DAMAGE,UNTARGETABLE")
        .put("Ability", BANNER_ABILITY)
        .put("OnStartingAction", "Test_Banner_Start");
    WrittenRows.walker(rows, WAVE_MEMBER);
    WrittenRows.walker(rows, ROLL_HERO)
        .put("Ability", ROLL_ABILITY)
        .put("OnStartingAction", ROLL_HERO_START);
    WrittenRows.walker(rows, ROLL_DEFLECTED);
    WrittenRows.walker(rows, DEFLECTOR).put("Ability", DEFLECT_ABILITY);
  }

  /** The banner's, the roll hero's and the deflector's abilities. */
  private static void writeAbilities(ObjectNode rows) {
    WrittenRows.row(rows, BANNER_ABILITY, "CharacterAbility")
        .put("Name", BANNER_ABILITY)
        .put("IsChampion", true)
        .put("ManaCost", BANNER_ABILITY_COST)
        .put("MaxCharges", BANNER_CHARGES)
        .put("CastTime", BANNER_CAST_TIME_MS)
        .put("TriggerDelay", BANNER_TRIGGER_DELAY_MS)
        .put("OnActivationAction", BANNER_ACTIVATION);
    WrittenRows.row(rows, ROLL_ABILITY, "CharacterAbility")
        .put("Name", ROLL_ABILITY)
        .put("ManaCost", ROLL_ABILITY_COST)
        .put("MaxCharges", 1)
        .put("CastTime", 900)
        .put("TriggerDelay", 50)
        .put("OnActivationAction", REROLL);
    WrittenRows.row(rows, DEFLECT_ABILITY, "CharacterAbility")
        .put("Name", DEFLECT_ABILITY)
        .put("ManaCost", 1)
        .put("MaxCharges", 1)
        .put("CastTime", 300)
        .put("TriggerDelay", 100)
        .put("OnActivationAction", DEFLECT_SPAWN);
  }

  /** Every action the rows name. */
  private static void writeActions(ObjectNode rows) {
    // The squad.
    WrittenRows.group(
        rows,
        SQUAD_START,
        new int[] {0, 0, 0},
        SQUAD_LISTEN,
        SQUAD_HOLD_BUTTON,
        SQUAD_RESET_CHARGES);
    listener(rows, SQUAD_LISTEN, SQUAD_GROUP, SQUAD_TAG);
    WrittenRows.action(rows, SQUAD_TAG, "ActionWithDuration")
        .put("ActionDuration", 99999)
        .put("GameTagsToSet", TAG);
    buttonState(rows, SQUAD_HOLD_BUTTON, BANNER).put("StateToSet", "NoYetAvailable");
    buttonState(rows, SQUAD_RESET_CHARGES, BANNER)
        .put("Persistent", false)
        .put("ResetCharges", true);
    WrittenRows.action(rows, SQUAD_CHECK_LAST, "ActionRunActionIfUnitGroupContains")
        .put("ObjectFilter", GameData.TEST_FILTER)
        .put("ExecuteIfTrue", "!" + TAG)
        .put("ActionIfNoMatch", SQUAD_PLANT);
    WrittenRows.action(rows, SQUAD_PLANT, "ActionSpawnToLocation")
        .put("AddToSourceGroup", true)
        .put("SpawnData", BANNER)
        .put("SpawnType", "CharacterType");
    // The banner.
    WrittenRows.group(
        rows,
        "Test_Banner_Start",
        new int[] {0, 0, 0},
        "Test_Banner_Listen",
        "Test_Banner_Timer",
        "Test_Banner_Ready");
    listener(rows, "Test_Banner_Listen", SQUAD_GROUP, "Test_Banner_Leave_Unless_Held");
    WrittenRows.action(rows, "Test_Banner_Leave_Unless_Held", "ActionKill")
        .put("ExecuteIfTrue", "!" + TAG + " && !CASTING_ABILITY");
    WrittenRows.action(rows, "Test_Banner_Timer", "ActionTimerQuest")
        .put("IntervalStartAt", 0)
        .put("MaxResets", 1)
        .put("OnIntervalReachedAction", "Test_Banner_Expire")
        .putArray("Intervals")
        .add(BANNER_TIMER_MS);
    WrittenRows.group(
        rows, "Test_Banner_Expire", new int[] {BANNER_EXPIRE_WAIT_MS}, "Test_Banner_Leave");
    WrittenRows.action(rows, "Test_Banner_Leave", "ActionKill");
    buttonState(rows, "Test_Banner_Ready", BANNER)
        .put("StateToSet", "Ready")
        .put("Persistent", false)
        .put("ApplyImmediate", true);
    WrittenRows.group(
        rows,
        BANNER_ACTIVATION,
        new int[] {0, 0, WAVE_SECOND_MS, WAVE_LEAVE_MS},
        SQUAD_TAG,
        "Test_Wave_Toward_Middle",
        "Test_Wave_Away_From_Middle",
        "Test_Banner_Leave");
    wave(rows, "Test_Wave_Toward_Middle", "+");
    wave(rows, "Test_Wave_Away_From_Middle", "-");
    // The roll hero.
    buttonState(rows, READY_BUTTON, ROLL_HERO).put("StateToSet", "Ready");
    WrittenRows.group(
        rows, ROLL_HERO_START, new int[] {0, 0}, ROLL_HERO_LISTEN, ROLL_HERO_INTERVAL);
    listener(rows, ROLL_HERO_LISTEN, ROLL_GROUP, ROLL_HERO_COUNT);
    WrittenRows.action(rows, ROLL_HERO_INTERVAL, "ActionInterval")
        .put("Interval", 50)
        .put("ActionToExecute", ROLL_HERO_COUNT);
    WrittenRows.action(rows, ROLL_HERO_COUNT, "ActionSetVariable")
        .put("Variable", GameData.TEST_VARIABLE)
        .put("Value", GameData.TEST_VARIABLE + " + 1");
    WrittenRows.action(rows, REROLL, "ActionBarbBarrelHeroReRoll")
        .put("DeployDuration", REROLL_DEPLOY_DURATION_MS)
        .put("OffsetY", REROLL_OFFSET_Y)
        .put("SpawnDelay", REROLL_SPAWN_DELAY_MS)
        .put("ReRollProjectile", REROLL_PROJECTILE)
        .put("OnReRollStartAction", REROLL_HEAL)
        .put("GameTagsToSetWhileOnReRolling", "NO_ATTACK,UNTARGETABLE,NO_DAMAGE");
    WrittenRows.action(rows, REROLL_HEAL, "ActionHeal")
        .put("Value", "(max_hp - hp) * " + HEAL_PERCENT_VARIABLE + " / 100");
    // The deflector.
    WrittenRows.action(rows, DEFLECT_SPAWN, "ActionSpawn")
        .put("SpawnData", DEFLECT_AREA)
        .put("SpawnType", "AreaEffectType");
  }

  /** A listener of a new play of a card group, the played card tested against the group. */
  private static void listener(ObjectNode rows, String name, String group, String onPlay) {
    WrittenRows.action(rows, name, "ActionActivateOnCardDeploy")
        .put("CardGroup", group)
        .put("EvaluateDeployedCard", true)
        .put("OnActivateAction", onPlay);
  }

  /** An override of a champion slot's button state, its fields to add to. */
  private static ObjectNode buttonState(ObjectNode rows, String name, String champion) {
    return WrittenRows.action(rows, name, "ActionOverrideAbilityButtonState")
        .put("ChampionCharacterData", champion);
  }

  /**
   * One spawn of the banner's wave: a wave member {@link #WAVE_SIDEWAYS} toward the middle of the
   * arena ({@code +}) or away from it ({@code -}), {@link #WAVE_BACK} along the team's y direction.
   */
  private static void wave(ObjectNode rows, String name, String sign) {
    WrittenRows.action(rows, name, "ActionSpawnToLocation")
        .put("AddToSourceGroup", true)
        .put("SpawnData", WAVE_MEMBER)
        .put("SpawnType", "CharacterType")
        .put("UseDeploy", true)
        .put(
            "XPositionExpression",
            "x " + sign + " (" + WAVE_SIDEWAYS + " * select(x > (map_width / 2), -1, 1))")
        .put("YPositionExpression", "y + (" + WAVE_BACK + " * team_y_direction(team_index))");
  }

  /**
   * The thrown, rolling and rerolling projectiles. A deflection does not turn the throw, only the
   * rolls.
   */
  private static void writeProjectiles(ObjectNode rows) {
    WrittenRows.row(rows, THROW, "Projectile")
        .put("Name", THROW)
        .put("Rarity", "Common")
        .put("Speed", 500)
        .put("DeflectBehaviour", "NoDeflect")
        .put("SpawnProjectile", ROLLING)
        .put("OnStartingAction", READY_BUTTON);
    rolling(rows, ROLLING, ROLL_RANGE)
        .put("SpawnCharacter", ROLL_HERO)
        .put("SpawnCharacterDeployTime", 1000)
        .put("DeflectedCharacterSpawn", ROLL_DEFLECTED)
        .put("OnStartingAction", READY_BUTTON);
    rolling(rows, REROLL_PROJECTILE, ROLL_RANGE);
  }

  /** A rolling projectile of a range, which a deflection turns, its columns to add to. */
  private static ObjectNode rolling(ObjectNode rows, String name, int range) {
    return WrittenRows.row(rows, name, "Projectile")
        .put("Name", name)
        .put("Rarity", "Common")
        .put("Speed", 200)
        .put("Damage", 10)
        .put("OnlyEnemies", true)
        .put("AoeToGround", true)
        .put("ProjectileRange", range)
        .put("ProjectileRadius", 1000)
        .put("DeflectBehaviour", "InvertDirection");
  }

  /** The deflector's area effect: it follows the deflector and deflects projectiles. */
  private static void writeAreaEffect(ObjectNode rows) {
    WrittenRows.row(rows, DEFLECT_AREA, "AreaEffectObject")
        .put("Name", DEFLECT_AREA)
        .put("Rarity", "Common")
        .put("DeflectProjectilesEnabled", true)
        .put("Filter", ENEMY_FILTER)
        .put("FollowBehaviour", "FollowParent")
        .put("LifeDuration", 4000)
        .put("Radius", 1500);
  }
}
