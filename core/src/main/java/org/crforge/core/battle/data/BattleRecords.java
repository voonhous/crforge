package org.crforge.core.battle.data;

import static org.crforge.core.util.ValidationUtils.checkArgument;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.battle.match.BattleTimeline;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.SpellVariant;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.unit.AbilityData;
import org.crforge.core.battle.unit.AreaDamageType;
import org.crforge.core.battle.unit.AreaEffectData;
import org.crforge.core.battle.unit.AttackSequence;
import org.crforge.core.battle.unit.BuffData;
import org.crforge.core.battle.unit.UnitData;
import org.crforge.core.pathfinding.combat.RarityTable;
import org.crforge.core.pathfinding.combat.ScalingMode;
import org.crforge.core.pathfinding.target.TargetingConfig;

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
 */
public final class BattleRecords {

  private static final String CHARACTERS = "characters";
  private static final String BUILDINGS = "buildings";
  private static final String PROJECTILES = "projectiles";
  private static final String SPELLS_CHARACTERS = "spells_characters";
  private static final String SPELLS_OTHER = "spells_other";
  private static final String SPELLS_BUILDINGS = "spells_buildings";
  private static final String SPELLS_EVOLVED = "spells_evolved";
  private static final String SPELLS_HERO_FORM = "spells_hero_form";

  /** The card tables a card row is looked up in, in turn. */
  private static final List<String> CARD_TABLES =
      List.of(SPELLS_CHARACTERS, SPELLS_BUILDINGS, SPELLS_OTHER, SPELLS_EVOLVED, SPELLS_HERO_FORM);

  /** The card forms by name; a form's number is the one the evolution field matches. */
  private static final Map<String, Integer> CARD_FORMS =
      Map.of("BasicForm", 0, "EvoForm", 1, "HeroForm", 2, "AutoChessForm", 3, "FlexSlot", 5);

  private static final String GAME_MODES = "game_modes";
  private static final String BATTLE_TIMELINES = "battle_timelines";
  private static final String CARD_GROUPS = "card_groups";
  private static final String GLOBALS = "globals";
  private static final String LOCATIONS = "locations";

  /** The class a card row names to be played as one of its options. */
  private static final String SPELL_VARIANT_CLASS = "LogicBattleSpellVariantData";

  /** How many ten-thousandths an option's trigger is stored as, for each unit of the column. */
  private static final int TRIGGER_SCALE = 10;

  /** The section types of a battle timeline by name; the order is their number. */
  private static final List<String> SECTION_TYPES = List.of("Normal", "Overtime", "BonusTime");

  /**
   * The columns of a spell card the cast does not model yet: a Mirror, which a match plays as the
   * card it repeats instead, a first projectile of its own, and a variant card's class and its
   * projected summon, which a match plays as the option picked instead. A spell that sets one is
   * refused. The action run as the spell is cast, which only the evolved Goblin Barrel sets (its
   * decoy barrel), is read: the cast runs it, and its row is built or refused then.
   */
  private static final List<String> UNMODELLED_SPELL_COLUMNS =
      List.of("Mirror", "CustomFirstProjectile", "CustomClassType", "UseProjectedTimeSummon");

  /**
   * The columns of a projectile the impact does not model: spawned projectiles laid along the
   * width, and the push's floor and a push along the flight. A spell whose projectile, or the
   * projectile that one spawns, sets one is refused as it is cast or spawned, and a unit's shot as
   * it is fired. The area effect it spawns is modelled unless its row is refused.
   */
  private static final List<String> UNMODELLED_PROJECTILE_COLUMNS =
      List.of("SpawnAxisX", "MinPushback", "DoDirectionalPushback");

  /** The target limit the loader stores for a projectile row that leaves it empty. */
  private static final int DEFAULT_MAXIMUM_TARGETS = 1000;

  private static final String CHARACTER_ABILITIES = "character_abilities";
  private static final String GAME_OBJECT_FILTERS = "game_object_filters";
  private static final String AREA_EFFECT_OBJECTS = "area_effect_objects";
  private static final String CHARACTER_BUFFS = "character_buffs";
  private static final String SHAPES = "shapes";

  /** The columns of a buff the battle reads. */
  private static final Set<String> MODELLED_BUFF_COLUMNS =
      Set.of(
          "Name",
          "Rarity",
          "Invisible",
          // Read only by the clone creator, which leaves such a buff off a clone; a clone of a
          // carrier is refused.
          "NotCloned",
          "HealPerSecond",
          "AllowedOverHealPerc",
          "SpeedMultiplier",
          "HitSpeedMultiplier",
          "SpawnSpeedMultiplier",
          "HitFrequency",
          "DamagePerSecond",
          "CrownTowerDamagePerHit",
          "CrownTowerDamagePercent",
          "BuildingDamagePercent",
          "HitTickFromSource",
          "AttractPercentage",
          "LateralPushPercentage",
          "PushMassFactor",
          "PushSpeedFactor",
          "AttractMinAngle",
          "AttractMaxAngle",
          "ControlledByParent",
          "EnableStacking",
          "PlayerSpecificBuff",
          "NoEffectToCrownTowers",
          "IgnoreBuildings",
          "DeathSpawn",
          "DeathSpawnCount",
          "DeathSpawnRadius",
          "DeathSpawnSameLocation",
          "DeathSpawnIsEnemy",
          "DeathSpawnDeployDelay",
          "OtherBuffDeathSpawnAllowed",
          "LockTarget",
          "AddAsIndividualBuff",
          "AliveIfTrue",
          // Read by the damage reduction, at the hit-points entry, the pending lethal test, a typed
          // hit and the damage over time; and by the pushback request.
          "DamageReduction",
          "IgnorePushBack",
          // Read only by the apply's hand-over to a parent's riders: a Clone buff is kept from
          // them,
          // and a buff naming another hands them that one.
          "Clone",
          "AttachedInheritAs",
          // Read by the charge reset as an instance is listed, and by the post-move charge for a
          // unit whose row has no charge range.
          "OverrideChargeRange",
          // Read by the targeting visit's hit, whose projectiles it replaces, and by the attacker's
          // hit counter, which removes the instances of a row that sets RemoveOnAttack.
          "OverrideProjectile",
          "RemoveOnAttack",
          // Read by an instance's spawner, in the buff visit, and by the cleanup's fold, which
          // admits a child of a spawner that must be alive only while it is.
          "SpawnObject",
          "SpawnStartTime",
          "SpawnInterval",
          "SpawnLimit",
          "SpawnNumber",
          "SpawnPauseTime",
          "SpawnerAliveRequired",
          // Resolved when the tables are derived: a row that names a base already carries every
          // column it inherits.
          "Base");

  /** The columns of a buff that only show something: its effects, icons, filters and sounds. */
  private static final Set<String> PRESENTATION_BUFF_COLUMNS =
      Set.of(
          "AudioPitchModifier",
          "ContinuousEffect",
          "DeathEffectOverride",
          "Effect",
          "EffectScale",
          "FilterAffectsTransformation",
          "FilterExportName",
          "FilterFile",
          "FilterInheritLifeDuration",
          "HideEffectWhenUnderground",
          "HitEffect",
          "IconExportName",
          "IconFileName",
          "LoopContinuousEffect",
          "MarkEffect",
          "PreContinuousEffect",
          "PreContinuousEffectExclusiveTime",
          "ProjectileEffect",
          "RemoveEffect",
          "Scale",
          "ShadowAlpha",
          "StatsTags",
          "SwitchTeamContinuosEffect",
          "TID",
          "TopEffect",
          "TopEffectDisabledForAttachedCharacters",
          "TopEffectVerticalOffset",
          "UNUSED0");

  /**
   * The columns of an area effect the battle does not model: a row that sets one is refused as the
   * area effect is created. A buff that boosts one target or lasts longer by level, the life
   * condition, the tags other than the one that hides the pushback's presentation, the per-level
   * lifetime and the push's floor; its gate lift only in the filter form, whose load reads it. Its
   * projectile is modelled, but not a launch from its source or a spread one; for a row with hit
   * switches its hit action only for a Clone, as a buff spawn, a group of buff spawns and a taunt,
   * and on a shaped row as a choice by team, one hit per target only with a hit action, and neither
   * the hit action on itself nor the end on its first hit (both only in the filter form); following
   * only its parent; its spawns only in a shuffled order; and its shape only as a rectangle with a
   * filter whose hits do nothing but their hit action.
   */
  private static final List<String> UNMODELLED_AREA_EFFECT_COLUMNS =
      List.of(
          "Boost",
          "BuffTimeIncreasePerLevel",
          "BuffTimeIncreaseAfterTournamentCap",
          "AliveIfTrue",
          "Tags",
          "LifeDurationIncreasePerLevel",
          "LifeDurationIncreaseAfterTournamentCap",
          "MinPushback");

  private static final String GAME_TAGS = "game_tags";

  private static final String DAMAGE_TYPES = "damage_types";

  /**
   * The fields of a damage type the filter form of an area effect deals as it is held for: its two
   * amounts and the effect the view shows.
   */
  private static final Set<String> DAMAGE_TYPE_FIELDS =
      Set.of("BaseDamage", "TowerDamage", "Effect");

  /**
   * The columns of what a unit does as it dies that the battle does not model: a unit whose row
   * sets one is refused when it dies. The elixir a death gives is not among them: the death handler
   * pays a player's unit's ManaOnDeathForOpponent to the side that killed it in a match, and pays
   * ManaOnDeath only for a neutral object, which the battle has none of. Nor is StartingBuff, whose
   * instances the death slot takes off where the dying unit is their parent.
   */
  private static final List<String> UNMODELLED_DEATH_COLUMNS =
      List.of("DeathSpawnCharacter3", "DeathSpawnIsSameUnit");

  /**
   * The columns that change where a unit's death spawn stands or what its children take, which the
   * battle does not model: refused only for a unit that spawns on its death. The inherited ignore
   * list is not among them: the id lists it joins are written only by such a death spawn and by a
   * rider let go under a parent that sets it, which is refused, so every list stays empty and the
   * column changes nothing.
   */
  private static final List<String> UNMODELLED_DEATH_SPAWN_COLUMNS = List.of("SpawnLimit");

  /**
   * The columns of a unit the battle does not model, whatever it does: a unit whose row sets one is
   * refused as it is created. A shield's push as it breaks, hiding before the first hit, a buff at
   * a share of its hit points, a dash's contact damage, fixed distance, area effect and closing
   * action, a limit on the elixir a collector makes, and a spawner's launches. The action a
   * completed charge runs is refused apart, unless it is of a class whose run is established.
   */
  private static final List<String> UNMODELLED_UNIT_COLUMNS =
      List.of(
          "ShieldDiePushback",
          "HideBeforeFirstHit",
          "BuffOnXHP",
          "DashingDamage",
          "DashDistance",
          "AreaEffectOnDash",
          "OnAfterDashAction",
          "ManaGenerateLimit",
          "SpawnProjectile");

  /**
   * The columns of a spawner the battle does not model, refused only for a unit whose spawner makes
   * characters: its push on its children, and a fixed priority for them, which is held only for a
   * death spawn.
   */
  private static final List<String> UNMODELLED_SPAWNER_COLUMNS =
      List.of("SpawnPushback", "SpawnConstPriority");

  /**
   * The tags a unit's own row may set, each read where the battle reads the tag word: those of the
   * Phoenix's egg, and those of the Goblins hero's banner - no damage taken (the damage entry), no
   * contact (the push pass and its gate, and a filter's excluded tags) and no targeting (the
   * targeting and its validator); and the Elite Archer hero's decoy's no attack, read where the
   * battle reads NO_ATTACK for its other holders, the targeting visit clearing the attack each
   * step. A row that sets any other is refused as the unit is created.
   */
  private static final Set<String> MODELLED_ROW_TAGS =
      Set.of(
          "NO_GIANTBUFFER_CHEF_ENCHANTMENT",
          "AVOIDANCE_AS_OBSTACLE",
          "NO_MOVE_ALLOW_ATTRACT",
          "NO_DAMAGE",
          "NO_CHECKCOLLISIONS",
          "UNTARGETABLE",
          "NO_ATTACK");

  /**
   * The tags a buff may set: the two the push pass reads, which keep the carrier's enemies, or its
   * own side, from pushing it, and are the only code that tests either; and UNIT_CUSTOM_TAG_1,
   * which no battle code and no filter tests, read only by the expressions of the carrier's own
   * action rows, which read the tag word the buff is folded into. A buff that sets any other is
   * refused.
   */
  private static final Set<String> MODELLED_BUFF_TAGS =
      Set.of("NO_PUSHED_BY_ENEMY", "NO_PUSHED_BY_ALLY", "UNIT_CUSTOM_TAG_1");

  /**
   * The actions a buff schedules on its carrier as an instance is listed and removed: read when
   * they name an action row, listed as not modelled when written inline.
   */
  private static final Set<String> BUFF_HOOK_COLUMNS = Set.of("OnStartAction", "OnRemoveAction");

  /**
   * The columns of a unit's row that only show something: its art, texts, effects, shadows,
   * animation, skin and health bar. They name client assets rather than rows of the battle's
   * tables, and no traced battle path reads one; they are classified by what they name, not each by
   * a trace. The effect columns the record has followed (DashStartEffect, LandingEffect) reach only
   * the entity's view object, and the custom dummy labels are read only by the character's view, as
   * the labels of its animation.
   */
  private static final Set<String> PRESENTATION_UNIT_COLUMNS =
      Set.of(
          // The table of stats the card's info shows, as a buff's and an action row's are.
          "StatsTags",
          "AbilityPendingEffect",
          "AppearEffect",
          "AttackStartEffect",
          "AttackStartEffect2",
          "BlueExportName",
          "BlueShieldExportName",
          "BlueTopExportName",
          "BuffWhenNotAttackingEffect",
          "BuffWhenNotAttackingRemoveEffect",
          "ChargeEffect",
          "ContinuousEffect",
          "CrowdEffects",
          "CustomDummyObjectLabelEnd",
          "CustomDummyObjectLabelStart",
          "DamageEffect",
          "DamageEffectSpecial",
          "DamageExportName",
          "DashEffect",
          "DashHitEffect",
          "DashStartEffect",
          "DeathEffect",
          "DeathSpawnDeployBaseAnim",
          "DeployAnimationOverride",
          "DeployBaseAnimExportName",
          "DestroyAtLimitEffect",
          "FileName",
          "HealthBar",
          "HealthBarOffsetY",
          "HealthBarOffsetY2v2Blue",
          "HealthBarOffsetYBlue",
          "HealthBarOffsetYRed",
          "HideEffect",
          "HideHealthbar",
          "IngamePathfindEffect",
          "IngamePathfindStartEffect",
          "IngamePathfindStopEffect",
          "KamikazeEffect",
          "LandingEffect",
          "LoopMoveEffect",
          "MoveEffect",
          "NewHealthBarOffsetXBlue",
          "NewHealthBarOffsetXRed",
          // Rows of the client's own action table (its health bar parts, visual layers and bars),
          // which the table of the client's data loads and only the unit's view object runs as
          // the view is built.
          "OnStartingClientActions",
          "PrestigeExportName2",
          "PrestigeExportName3",
          "PrestigeRedExportName",
          "PrestigeRedExportName2",
          "PrestigeRedExportName3",
          "PrefabAsset",
          "PrestigeSWF",
          "ProjectileEffect",
          "ProjectileEffectSpecial",
          "RedExportName",
          "RedShieldExportName",
          "RedTopExportName",
          "ReflectedAttackEffect",
          "ReflectedAttackTargetedEffect",
          "ReflectedAttackTargetedEffectSources",
          "Scale",
          "ShaderFXBase",
          "ShaderFXTop",
          "ShadowCustom",
          "ShadowCustomLow",
          "ShadowScaleX",
          "ShadowScaleY",
          "ShadowSkew",
          "ShadowX",
          "ShadowY",
          "ShieldLostEffect",
          "ShowHealthNumber",
          "SkinType",
          "SpawnCharacterEffect",
          "SpawnDeployBaseAnim",
          "SpawnEffect",
          "SpawnEffectOnce",
          "SpawnPathfindEffect",
          "SpecialAttackRangeForStats",
          "TID",
          "UseAnimator");

  /**
   * The columns of a unit's row the record shows no battle logic reads, or reads to no effect for
   * every row the battle builds while another mechanic stays refused.
   */
  private static final Set<String> INERT_UNIT_COLUMNS =
      Set.of(
          // The row's own name, read as the row's key.
          "Name",
          // Read only by the placement, into a slot of the entity's view object.
          "DeployDelay",
          // Presentation only, by the attack sequence's getter scan.
          "LoadAttackEffect1",
          "LoadAttackEffect2",
          "LoadAttackEffect3",
          "LoadAttackEffectReady",
          "FlameEffect1",
          "FlameEffect2",
          "FlameEffect3",
          "TargettedDamageEffect1",
          "TargettedDamageEffect2",
          "TargettedDamageEffect3",
          "DamageLevelTransitionEffect12",
          "DamageLevelTransitionEffect23",
          "VisualHitSpeed",
          "VisualHitSpeed2",
          "VisualHitSpeed3",
          // Handed by the hit application to slots of the entity's view object.
          "TargetedHitEffect",
          "TargetEffectY",
          "TargetedEffectVisualPushback",
          // No reader found.
          "VisualActions",
          "SpawnAreaObjectLevelIndex",
          // Read by client code only.
          "DashFilter",
          // Resolved when the tables are derived: the row already carries what it inherits.
          "Base",
          // Paid only for an entity of side 100, which a battle of two players never has.
          "ManaOnDeath",
          // Gates only the statistics calls of the buff add.
          "AvoidCountingForBuffAmountStats",
          // A later entry's columns load into no entry without an order or VariableDamageTime1; a
          // row that builds its entries reads them. A row with an AttackSequenceList loads its
          // entries from the list alone, and no other reader takes the two times either.
          "VariableDamage2",
          "VariableDamage3",
          "VariableDamageTime1",
          "VariableDamageTime2",
          "MeleePushback2",
          "MeleePushback3",
          "IsMeleePushbackAll2",
          "IsMeleePushbackAll3",
          // Read only by the character view: the walk animation's rate, the sprite's rotation, the
          // attack animation's choice and states, the sprite's shake, the filters its sprite and
          // its card show, and the move animation.
          "WalkingSpeedTweakPercentage",
          "RotateAngleSpeed",
          "HasRotationOnTimeline",
          "AttackStateCount",
          "TryToFinishAttackAnimation",
          // Whether the attack animation is sped up to end before the next attack: the same
          // view function reads it, beside TryToFinishAttackAnimation, and nothing else does.
          "TryToFinishAttackAnimationBeforeNextAttack",
          "DontStopMoveAnim",
          "AttackShakeTime",
          "LoopingFilter",
          "CustomSpawnFilter",
          "CustomCloneFilter",
          // Read only by the character view and its range: whether an animation keeps its last
          // frame.
          "AnimationsKeepLastFrame",
          // No battle logic reads it: an object of the view only, of which no entity is made, and
          // a building fires its own shots.
          "AttachedCharacter",
          "AttachedCharacterHeight",
          // Read only by the character view, where it offsets the attached object's sprite, and by
          // a by-name getter nothing calls.
          "AttachedCharacterOffsetY",
          // The same offset per side, stored beside it and read with it by the same view function;
          // the battle loads of the same offsets are another object's serialised fields.
          "AttachedCharacterOffsetYBlue",
          "AttachedCharacterOffsetYRed",
          // The name suffix of the Royal Chef tower's attack animation: outside its row's loader,
          // constructor, destructor and by-name getter, no battle code reaches its field.
          "CustomAnimationPostfix",
          // Stored and never read.
          "TurretMovement",
          // The evolved Cannon's shadows: the by-name getter that answers them runs in no battle
          // step, and every battle load of their offsets has a global base, not a row.
          "BlueShadowExportName",
          "RedShadowExportName",
          // Times only the attack's turn toward its target and a call of the view, 150 ms before
          // the period's boundary for the Bats; the turn is modelled for no unit, and no hit, timer
          // or readiness reads it.
          "AttackDashTime",
          // Read once, outside the battle's logic, beside the deploy time's conversion for the
          // deploy animation; the state visit's deploy step does not read it, and a guard that
          // sets it deploys natively as the battle deploys it.
          "DeployTimeChangesDeployAnim",
          // The evolved Pekka's soul: on a kill of a character, TempResurrect hands the kill to
          // the battle's presentation listener, which changes nothing the battle reads; the
          // parameters, effects and filter of the soul's flight are read by no battle logic. The
          // kill's heal is the row's killed-done action, modelled apart.
          "TempResurrect",
          "ResurrectParameters",
          "ResurrectFlyingEffect",
          "ResurrectGainChargeEffect",
          "ResurrectChargeFilter",
          // Read only by the direct hit as it hands a melee area hit to the area damage, where it
          // skips one call of the battle's presentation listener, whose answer the area damage
          // does not read; the targets, damage and pushes of the area are the same either way.
          "DisableMeleeAeoDamageEffect");

  /**
   * The columns of a unit's row whose role in the battle is not yet established, carried unread
   * until a trace settles them: none now.
   */
  private static final Set<String> PENDING_UNIT_COLUMNS = Set.of();

  /**
   * The columns of a projectile's row that only show something: its art, effects, sounds, shadow
   * and the shakes it makes, classified by what they name, not each by a trace.
   */
  private static final Set<String> PRESENTATION_PROJECTILE_COLUMNS =
      Set.of(
          // The table of stats the card's info shows, as a buff's and an action row's are.
          "StatsTags",
          "AlwaysResetAnimation",
          "DeathEffect",
          "DragEffect",
          "ExportName",
          "FileName",
          "HitEffect",
          "HitSoundWhenParentAlive",
          "PingpongDeathEffect",
          "PrefabAsset",
          "PrestigeExportName",
          "PrestigeExportName2",
          "PrestigeExportName3",
          "PrestigeRedExportName2",
          "PrestigeRedExportName3",
          "PrestigeSWF",
          "RedExportName",
          "RedShadowExportName",
          "Scale",
          "ShadowDisableRotate",
          "ShadowExportName",
          "ShakesShooter",
          "ShakesTargets",
          "SpawnDeployBaseAnim",
          "TargettedEffect",
          "TrailEffect",
          // The filtered hit effects: the hit-effect chooser tries the filters in order for the
          // entity hit and the first that accepts names the effect shown, the shooter's own target
          // keeping HitEffect under the last; its answer only goes to the effect display.
          "FilteredHitEffects",
          "HitEffectFilters",
          "FilteredHitEffectOnlyIfNotShootersTarget",
          // Read only by the projectile view, for the angle it draws the projectile at.
          "MinimumLengthForVisualAngleCalculation",
          // Read only where the impact hands its hit effect to the projectile's presentation
          // object: whether the effect starts from the stored source point, and that point's height
          // correction. The source point itself is read nowhere else.
          "UseFixedEffectSourcePosition",
          "DoEffectSourcePositionHeightCorrection");

  /** The columns of a projectile's row the record shows no battle logic reads to any effect. */
  private static final Set<String> INERT_PROJECTILE_COLUMNS =
      Set.of(
          "Name",
          "Base",
          // Nothing in the battle logic reads it; carried as presentation.
          "PingpongMovingShooter",
          // Read only by the projectile view: its frame set and whether it shows while delayed.
          "use360Frames",
          "HideWhenDelayed");

  /** The columns of a projectile's row whose role is not yet established: none now. */
  private static final Set<String> PENDING_PROJECTILE_COLUMNS = Set.of();

  /**
   * The columns of an area effect's row that only show something: its effects and art, classified
   * by what they name.
   */
  private static final Set<String> PRESENTATION_AREA_EFFECT_COLUMNS =
      Set.of(
          // The table of stats the card's info shows, as a buff's and an action row's are.
          "StatsTags",
          "DeflectedProjectileEffect",
          "DeflectionFBEffect",
          // The art the evolved Cannon's crosshair shows.
          "ExportName",
          "FileName",
          // Handed to the view's listener at each hit an update makes, which shows it and nothing
          // more.
          "HitEffect",
          "LoopingEffect",
          // The effect a following area effect shows while it follows nothing.
          "NoFollowObjectEffect",
          // Whether the view shows the looping effect on the area effect itself or on its parent;
          // only the area effect's view reads it.
          "ParentLoopingEffectToSelf",
          "OneShotEffect",
          "ScaledEffect",
          "ScaledEffectFollowAeO",
          "SpawnDeployBaseAnim",
          "SpawnEffect");

  /**
   * The columns of an area effect's row the record shows no battle logic reads. BuffNumber and
   * SpawnMaxRadius are declared by the table and never looked up: the spawner places its characters
   * within the radius of its hits.
   */
  private static final Set<String> INERT_AREA_EFFECT_COLUMNS =
      Set.of("Name", "Base", "BuffNumber", "SpawnMaxRadius");

  /** The columns of an area effect's row whose role is not yet established: none now. */
  private static final Set<String> PENDING_AREA_EFFECT_COLUMNS = Set.of();

  private final GameTables tables;

  /**
   * @param tables the game tables of one data version
   */
  public BattleRecords(GameTables tables) {
    this.tables = tables;
  }

  /**
   * A unit or a building as the battle reads it, from the characters table or, for a building, the
   * buildings table.
   *
   * @param name the row's name
   */
  public UnitData unit(String name) {
    GameRow row = unitRow(name).tracking();
    String deathSpawn = row.string("DeathSpawnCharacter");
    String deathProjectile = row.string("DeathSpawnProjectile");
    UnitData data =
        UnitData.builder()
            .name(row.name())
            .speed(
                loadedSpeed(
                    row.intValue("Speed"),
                    row.intValue("StopMovementAfterMS"),
                    row.intValue("WaitMS")))
            .range(row.intValue("Range"))
            .sightRange(row.intValue("SightRange"))
            .collisionRadius(row.intValue("CollisionRadius"))
            .mass(loadedMass(row.intValue("Mass"), row.intValue("CollisionRadius")))
            .hitSpeedMs(row.intValue("HitSpeed"))
            .loadTimeMs(row.intValue("LoadTime"))
            .deployTimeMs(row.intValue("DeployTime"))
            .attacksGround(row.bool("AttacksGround"))
            .attacksAir(row.bool("AttacksAir"))
            .air(row.intValue("FlyingHeight") > 0)
            .building(row.bool("IsBuilding"))
            .king(row.bool("IsSummoner"))
            .summonerTower(row.bool("IsSummonerTower"))
            .hitpoints(row.intValue("Hitpoints"))
            .damage(row.intValue("Damage"))
            .crownTowerDamagePercent(row.intValue("CrownTowerDamagePercent"))
            .rarity(rarity(row.string("Rarity")))
            .projectile(
                row.string("Projectile").isEmpty() ? null : projectile(row.string("Projectile")))
            .customFirstProjectile(
                row.string("CustomFirstProjectile").isEmpty()
                    ? null
                    : projectile(row.string("CustomFirstProjectile")))
            .projectileStartRadius(row.intValue("ProjectileStartRadius"))
            .projectileStartZ(row.intValue("ProjectileStartZ"))
            .projectileYOffset(row.intValue("ProjectileYOffset"))
            .multipleProjectiles(row.intValue("MultipleProjectiles"))
            .areaDamageRadius(row.intValue("AreaDamageRadius"))
            .selfAsAoeCenter(row.bool("SelfAsAoeCenter"))
            .overrideAttackFinishTime(row.bool("OverrideAttackFinishTime"))
            .attackFinishTimeMs(row.intValue("AttackFinishTime"))
            .resetHitTimerWhenNoTarget(row.bool("ResetHitTimerWhenNoTarget"))
            .spawnRadius(row.intValue("SpawnRadius"))
            .spawnAngleShift(row.intValue("SpawnAngleShift"))
            .flyingHeight(row.intValue("FlyingHeight"))
            .flyDirectPaths(row.bool("FlyDirectPaths"))
            .spawnPathfindSpeed(row.intValue("SpawnPathfindSpeed"))
            .ingamePathfindSpeed(row.intValue("IngamePathfindSpeed"))
            .ingamePathfindVisible(row.bool("IngamePathfindVisible"))
            // Only whether the animation is named counts: an arrival with one deploys again.
            .ingamePathfindStopDeploys(set(row, "IngamePathfindStopDeployBaseAnim"))
            .groupProjectiles(row.bool("GroupProjectiles"))
            .spawnPathfindMorph(
                row.string("SpawnPathfindMorph").isEmpty()
                    ? null
                    : row.string("SpawnPathfindMorph"))
            .spawnAreaObject(
                row.string("SpawnAreaObject").isEmpty() ? null : row.string("SpawnAreaObject"))
            .spawnPushback(row.intValue("SpawnPushback"))
            .spawnPushbackRadius(row.intValue("SpawnPushbackRadius"))
            .tileSizeOverride(row.intValue("TileSizeOverride"))
            .noDeploySizeW(row.intValue("NoDeploySizeW"))
            .noDeploySizeH(row.intValue("NoDeploySizeH"))
            .attackPushBack(row.intValue("AttackPushBack"))
            .ignorePushback(row.bool("IgnorePushback"))
            .onStartingAction(startingActionName(row))
            .onDeathAction(actionName(row, "OnDeathAction"))
            .onKilledAction(actionName(row, "OnKilledAction"))
            .onKilledDoneAction(actionName(row, "OnKilledDoneAction"))
            .deathDamage(row.intValue("DeathDamage"))
            .deathDamageRadius(row.intValue("DeathDamageRadius"))
            .deathPushBack(row.intValue("DeathPushBack"))
            .deathSpawnCharacter(deathSpawn.isEmpty() ? null : deathSpawn)
            // The loader keeps at least one for a row that spawns or launches on its death. For
            // any other row it reads no death spawn count and stores 0, whatever the row sets, as
            // the evolved Skeleton Balloon's count inherited from its base row.
            .deathSpawnCount(
                deathSpawn.isEmpty() && deathProjectile.isEmpty()
                    ? notLoaded(row, "DeathSpawnCount")
                    : Math.max(row.intValue("DeathSpawnCount"), 1))
            .deathSpawnCharacter2(
                row.string("DeathSpawnCharacter2").isEmpty()
                    ? null
                    : row.string("DeathSpawnCharacter2"))
            // Kept as written: a count below 1 makes nothing of the second row.
            .deathSpawnCount2(row.intValue("DeathSpawnCount2"))
            .deathSpawnRadius(row.intValue("DeathSpawnRadius"))
            .deathSpawnDeployTimeMs(row.intValue("DeathSpawnDeployTime"))
            .deathAreaEffect(
                row.string("DeathAreaEffect").isEmpty() ? null : row.string("DeathAreaEffect"))
            .deathSpawnPushback(row.bool("DeathSpawnPushback"))
            .spawnConstPriority(row.bool("SpawnConstPriority"))
            .deathSpawnMinRadius(row.intValue("DeathSpawnMinRadius"))
            .deathSpawnProjectile(deathProjectile.isEmpty() ? null : projectile(deathProjectile))
            .unmodelledDeathColumns(unmodelledDeathColumns(row, !deathSpawn.isEmpty()))
            .champion(champion(row))
            .ability(ability(row))
            .globalId(row.globalId())
            .lifeTimeMs(row.intValue("LifeTime"))
            .targetOnlyBuildings(row.bool("TargetOnlyBuildings"))
            .attackSequence(attackSequence(row))
            .onStartingAttackAction(actionName(row, "OnStartingAttackAction"))
            .onAttackAction(actionName(row, "OnAttackAction"))
            .onStartChargingAction(actionName(row, "OnStartChargingAction"))
            .shieldLostAction(actionName(row, "ShieldLostAction"))
            .onAttackSelfAction(actionName(row, "OnAttackSelfAction"))
            .onHitTargetAction(actionName(row, "OnHitTargetAction"))
            .minimumRange(row.intValue("MinimumRange"))
            .sightClip(sightClip(row))
            .sightClipSide(row.intValue("SightClipSide"))
            .loadFirstHit(row.bool("LoadFirstHit"))
            .spawnCharacter(
                row.string("SpawnCharacter").isEmpty() ? null : row.string("SpawnCharacter"))
            .spawnCharacter2(
                row.string("SpawnCharacter2").isEmpty() ? null : row.string("SpawnCharacter2"))
            .spawnCharacter3(
                row.string("SpawnCharacter3").isEmpty() ? null : row.string("SpawnCharacter3"))
            .spawnNumber(row.intValue("SpawnNumber"))
            .spawnIntervalMs(row.intValue("SpawnInterval"))
            .spawnPauseTimeMs(row.intValue("SpawnPauseTime"))
            .spawnStartTimeMs(row.intValue("SpawnStartTime"))
            .spawnLimit(row.intValue("SpawnLimit"))
            .destroyAtLimit(row.bool("DestroyAtLimit"))
            .spawnCharacterWithDeploy(row.bool("SpawnCharacterWithDeploy"))
            .untargetableWhenSpawned(row.bool("UntargetableWhenSpawned"))
            .gameTagsToSet(tagBits(row.string("GameTagsToSet")))
            .manaCollectAmount(row.intValue("ManaCollectAmount"))
            .manaGenerateTimeMs(row.intValue("ManaGenerateTimeMs"))
            .manaOnDeathForOpponent(row.intValue("ManaOnDeathForOpponent"))
            .ignoreBuffs(namesOf(row, "IgnoreBuff"))
            .shieldHitpoints(row.intValue("ShieldHitpoints"))
            .stopMovementAfterMs(row.intValue("StopMovementAfterMS"))
            .waitMs(row.intValue("WaitMS"))
            .deathInheritIgnoreList(row.bool("DeathInheritIgnoreList"))
            .spawnAttach(row.bool("SpawnAttach"))
            .spawnMaxAngle(row.intValue("SpawnMaxAngle"))
            .spawnAttachMaxRotation(row.intValue("SpawnAttachMaxRotation"))
            .chargeRange(row.intValue("ChargeRange"))
            .chargeSpeedMultiplier(row.intValue("ChargeSpeedMultiplier"))
            .damageSpecial(row.intValue("DamageSpecial"))
            .keepChargingAfterAttack(row.bool("KeepChargingAfterAttack"))
            .jumpEnabled(row.bool("JumpEnabled"))
            .jumpHeight(row.intValue("JumpHeight"))
            .jumpSpeed(row.intValue("JumpSpeed"))
            .kamikaze(row.bool("Kamikaze"))
            .kamikazeTimeMs(row.intValue("KamikazeTime"))
            .multipleTargets(row.intValue("MultipleTargets"))
            .allTargetsHit(row.bool("AllTargetsHit"))
            .uniqueMultipleTargets(row.bool("UniqueMultipleTargets"))
            .rememberMultipleTargets(row.bool("RememberMultipleTargets"))
            .buffOnDamage(set(row, "BuffOnDamage") ? row.string("BuffOnDamage") : null)
            .buffOnDamageTimeMs(row.intValue("BuffOnDamageTime"))
            .groupMaxSize(row.intValue("GroupMaxSize"))
            .buffAfterHits(namesOf(row, "BuffAfterHits"))
            .buffAfterHitsCounts(intsOf(row, "BuffAfterHitsCount"))
            .buffAfterHitsTimesMs(intsOf(row, "BuffAfterHitsTime"))
            .dashCooldown(row.intValue("DashCooldown"))
            .dashMinRange(row.intValue("DashMinRange"))
            .dashMaxRange(row.intValue("DashMaxRange"))
            .dashDamage(row.intValue("DashDamage"))
            .dashRadius(row.intValue("DashRadius"))
            .dashPushBack(row.intValue("DashPushBack"))
            .dashLandingTimeMs(row.intValue("DashLandingTime"))
            .dashConstantTimeMs(row.intValue("DashConstantTime"))
            .dashImmuneToDamageTimeMs(row.intValue("DashImmuneToDamageTime"))
            .dashToTargetRadius(row.bool("DashToTargetRadius"))
            .dashCount(row.intValue("DashCount"))
            .dashSecondaryRange(row.intValue("DashSecondaryRange"))
            .backDashRadius(row.intValue("BackDashRadius"))
            .targetOnlyTroops(row.bool("TargetOnlyTroops"))
            .ignoreTargetsWithBuff(
                row.string("IgnoreTargetsWithBuff").isEmpty()
                    ? null
                    : row.string("IgnoreTargetsWithBuff"))
            .deprioritizeTargetsWithBuff(row.bool("DeprioritizeTargetsWithBuff"))
            .hovering(row.bool("Hovering"))
            .buffWhenNotAttacking(
                set(row, "BuffWhenNotAttacking") ? row.string("BuffWhenNotAttacking") : null)
            .buffWhenNotAttackingTimeMs(row.intValue("BuffWhenNotAttackingTime"))
            .buffWhenNotAttackingUseAttackRange(row.bool("BuffWhenNotAttackingUseAttackRange"))
            // The loader's default is true: only a row that sets it false starts without the buff.
            .startWithBuffWhenNotAttacking(
                !row.has("StartWithBuffWhenNotAttacking")
                    || row.bool("StartWithBuffWhenNotAttacking"))
            // Applied by the level setter's tail as the unit is made, for its own time.
            .startingBuff(set(row, "StartingBuff") ? row.string("StartingBuff") : null)
            .startingBuffTimeMs(row.intValue("StartingBuffTime"))
            .allowAreaDamageWhenInvisible(row.bool("AllowAreaDmgWhenInvisible"))
            .areaEffectOnHit(set(row, "AreaEffectOnHit") ? row.string("AreaEffectOnHit") : null)
            // The loader's default is true: only a row that sets it false lets its target go.
            .keepTargetWithPendingDamage(
                !row.has("KeepTargetWithPendingDamage") || row.bool("KeepTargetWithPendingDamage"))
            .hidesWhenNotAttacking(row.bool("HidesWhenNotAttacking"))
            .hideTimeMs(row.intValue("HideTimeMs"))
            .upTimeMs(row.intValue("UpTimeMs"))
            .onAppearAction(actionName(row, "OnAppearAction"))
            .onDisappearAction(actionName(row, "OnDisappearAction"))
            .ignoreClone(row.bool("IgnoreClone"))
            .ignoreResurrect(row.bool("IgnoreResurrect"))
            // Read only by the entity's occlusion query, for the routing overlay.
            .occluder(row.bool("IsOccluder"))
            .reflectedAttackBuff(
                set(row, "ReflectedAttackBuff") ? row.string("ReflectedAttackBuff") : null)
            .reflectedAttackBuffDurationMs(row.intValue("ReflectedAttackBuffDuration"))
            .reflectedAttackRadius(row.intValue("ReflectedAttackRadius"))
            .reflectedAttackDamage(row.intValue("ReflectedAttackDamage"))
            .reflectAttackCrownTowerDamage(row.intValue("ReflectAttackCrownTowerDamage"))
            .clonedVersion(set(row, "ClonedVersion") ? row.string("ClonedVersion") : null)
            .specialRange(row.intValue("SpecialRange"))
            .specialMinRange(row.intValue("SpecialMinRange"))
            .specialLoadTimeMs(row.intValue("SpecialLoadTime"))
            .projectileSpecial(
                set(row, "ProjectileSpecial") ? projectile(row.string("ProjectileSpecial")) : null)
            .specialIgnoreBuildings(row.bool("SpecialIgnoreBuildings"))
            .unmodelledColumns(withChargeAction(withAttackAction(unmodelledColumns(row), row), row))
            .build();
    return data.toBuilder()
        .unmodelledColumns(
            withUnread(
                data.unmodelledColumns(),
                row,
                PRESENTATION_UNIT_COLUMNS,
                INERT_UNIT_COLUMNS,
                PENDING_UNIT_COLUMNS))
        .build();
  }

  /**
   * The unmodelled columns a row already lists, followed by every other column it sets that its
   * loader never read and that is neither inert nor pending a trace, in name order.
   *
   * @param listed the columns already listed as not modelled
   * @param row the tracking view the row was loaded through
   * @param presentation the columns that only show something
   * @param inert the columns the record shows no battle logic reads
   * @param pending the columns whose role is not yet established, carried unread until it is
   */
  private static List<String> withUnread(
      List<String> listed,
      GameRow row,
      Set<String> presentation,
      Set<String> inert,
      Set<String> pending) {
    List<String> unread = new ArrayList<>();
    for (String column : row.columns().keySet()) {
      if (!row.read().contains(column)
          && !listed.contains(column)
          && !presentation.contains(column)
          && !inert.contains(column)
          && !pending.contains(column)
          && sets(row, column)) {
        unread.add(column);
      }
    }
    if (unread.isEmpty()) {
      return listed;
    }
    Collections.sort(unread);
    List<String> all = new ArrayList<>(listed);
    all.addAll(unread);
    return all;
  }

  /**
   * The columns a unit's row sets that the battle does not model: those of any unit, those of a
   * spawner for a row that spawns characters, and a spawner with neither a count nor an interval,
   * which spawns once as its deploy ends.
   */
  /** A column of row names, written as one name or a list of them. */
  private static List<String> namesOf(GameRow row, String column) {
    if (row.has(column) && row.value(column).isTextual()) {
      String name = row.string(column);
      return name.isEmpty() ? List.of() : List.of(name);
    }
    return row.strings(column);
  }

  /** A column of numbers, written as one number or a list of them; empty when the row sets none. */
  private static List<Integer> intsOf(GameRow row, String column) {
    JsonNode value = row.value(column);
    if (value == null) {
      return List.of();
    }
    if (!value.isArray()) {
      return List.of(row.intValue(column));
    }
    return row.ints(column);
  }

  /** The classes of the actions a unit's hits run whose runs are established. */
  private static final Set<String> ATTACK_ACTION_CLASSES =
      Set.of(
          "ActionSpawn",
          "ActionMegaKnightUppercut",
          "ActionSpawnResetableAeO",
          "ActionSetVariable",
          "ActionGroup");

  /** The classes of the actions a completed charge runs whose runs are established. */
  private static final Set<String> CHARGE_ACTION_CLASSES = Set.of("ActionDamagingPushBack");

  /**
   * The unmodelled columns with OnStartChargingAction added when the row names an action a
   * completed charge runs whose run is not established: only the evolved Battle Ram's push is.
   */
  private List<String> withChargeAction(List<String> columns, GameRow row) {
    if (!sets(row, "OnStartChargingAction")
        || CHARGE_ACTION_CLASSES.contains(
            tables.action(row.string("OnStartChargingAction")).classType())) {
      return columns;
    }
    List<String> out = new ArrayList<>(columns);
    out.add("OnStartChargingAction");
    return out;
  }

  /**
   * The unmodelled columns with OnAttackAction added when the row names an action its hits run
   * whose run is not established: every hit schedules the row alike, but only a spawn's, the
   * evolved Mega Knight's uppercut, the evolved Baby Dragon's wind, a variable's write (the evolved
   * Inferno Dragon's attack count, kept in the attacker's own variable) and a group's (the evolved
   * Royal Hog's fall, whose parts are each built from their own rows and refused there) are. An
   * attack sequence entry's CustomOnAttackAction of another class adds AttackSequenceList.
   */
  private List<String> withAttackAction(List<String> columns, GameRow row) {
    List<String> out = new ArrayList<>(columns);
    if (sets(row, "OnAttackAction")
        && !ATTACK_ACTION_CLASSES.contains(
            tables.action(row.string("OnAttackAction")).classType())) {
      out.add("OnAttackAction");
    }
    // An attack sequence entry's CustomOnAttackAction runs where the row's would, so it is held to
    // the same classes.
    JsonNode entries = row.value("AttackSequenceList");
    if (entries != null && entries.isArray() && !out.contains("AttackSequenceList")) {
      for (JsonNode entry : entries) {
        String custom =
            actionName(row.name(), "CustomOnAttackAction", entry.path("CustomOnAttackAction"));
        if (custom != null && !ATTACK_ACTION_CLASSES.contains(tables.action(custom).classType())) {
          out.add("AttackSequenceList");
          break;
        }
      }
    }
    return out;
  }

  private static List<String> unmodelledColumns(GameRow row) {
    List<String> columns = new ArrayList<>();
    for (String column : UNMODELLED_UNIT_COLUMNS) {
      if (sets(row, column)) {
        columns.add(column);
      }
    }
    // The special attack is modelled in one shape: a troop that loads it in its ring and fires its
    // special projectile. A ring without a projectile is a special direct hit, and a building's
    // special one whose owner has no movement component. A special projectile without a ring is
    // never fired: only the ring loads a special hit, the other special columns are set by no row,
    // and the charged shot that would also take it is refused for a unit that fires, so the row's
    // own projectile is every shot, as the evolved Firecracker's are. A special that lists its
    // targets, which the ring would not re-arm on, is refused as a column nothing reads.
    boolean ring = sets(row, "SpecialRange");
    boolean special = sets(row, "ProjectileSpecial");
    if (ring && (!special || row.bool("IsBuilding"))) {
      columns.add("SpecialRange");
    }
    if (!row.string("SpawnCharacter").isEmpty()) {
      for (String column : UNMODELLED_SPAWNER_COLUMNS) {
        if (sets(row, column)) {
          columns.add(column);
        }
      }
      if (row.intValue("SpawnNumber") == 0 && row.intValue("SpawnInterval") == 0) {
        columns.add("SpawnCharacter");
      }
      // The waves take the rows in turn, as many turns as rows are set besides the first; a third
      // row without a second would make the second wave of no row, which no row sets.
      if (sets(row, "SpawnCharacter3") && !sets(row, "SpawnCharacter2")) {
        columns.add("SpawnCharacter3");
      }
    } else {
      // A second or third row without a first, which no row sets, is not held.
      for (String column : List.of("SpawnCharacter2", "SpawnCharacter3")) {
        if (sets(row, column)) {
          columns.add(column);
        }
      }
    }
    for (String tag : row.string("GameTagsToSet").split(",")) {
      if (!tag.isBlank() && !MODELLED_ROW_TAGS.contains(tag.trim())) {
        columns.add("GameTagsToSet");
        break;
      }
    }
    // An attack sequence entry that sets a field its entry does not read is not modelled.
    JsonNode entries = row.value("AttackSequenceList");
    if (entries != null && entries.isArray()) {
      for (JsonNode entry : entries) {
        if (entry.isObject() && unreadEntryField(entry)) {
          columns.add("AttackSequenceList");
          break;
        }
      }
    }
    return columns;
  }

  /**
   * The fields of an attack sequence entry its loader reads: its damage, projectile, timing, range
   * overrides, push and attack action, and those of a newer data version: its first projectile, its
   * number of targets and whether it remembers them, the action its hit runs in place of the row's
   * attack action, and its start delay.
   */
  private static final Set<String> SEQUENCE_ENTRY_FIELDS =
      Set.of(
          "Damage",
          "Projectile",
          "VariableDamageTime",
          "HitSpeedMultiplier",
          "CustomRange",
          "CustomSightRange",
          "CustomMinimunRange",
          "CustomProjectileStartZ",
          "CustomProjectileStartRadius",
          "MeleePushback",
          "IsMeleePushbackAll",
          "DoAttackAction",
          "CustomFirstProjectile",
          "CustomMultipleTargets",
          "CustomRememberMultipleTargets",
          "CustomOnAttackAction",
          "AttackStartDelay");

  /**
   * The fields of an attack sequence entry that only show something: its effects, the stats its
   * card's info shows, the hit speed its animation shows, carried unread as before, classified by
   * what they name, and DisableAttackAnimationFrameMatching, whose only reader is the unit's
   * animation.
   */
  private static final Set<String> PRESENTATION_SEQUENCE_ENTRY_FIELDS =
      Set.of(
          "AttackStartEffect",
          "DamageEffect",
          "DisableAttackAnimationFrameMatching",
          "FlameEffect",
          "StatsTags",
          "TargettedDamageEffect",
          "VisualHitSpeed");

  /** Whether an attack sequence entry sets a field that is neither read nor presentation. */
  private static boolean unreadEntryField(JsonNode entry) {
    for (Iterator<String> it = entry.fieldNames(); it.hasNext(); ) {
      String field = it.next();
      if (!SEQUENCE_ENTRY_FIELDS.contains(field)
          && !PRESENTATION_SEQUENCE_ENTRY_FIELDS.contains(field)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The cards a card group lists for a plain card's play, from the card groups table: its playable
   * cards. Its support cards, heroes and augments answer only a play of such a card, which the
   * battle does not make.
   *
   * @param name the row's name
   * @return the playable cards, in the row's order
   */
  public List<String> cardGroup(String name) {
    GameTable table = tables.table(CARD_GROUPS);
    checkArgument(table.has(name), () -> "the game tables have no card group " + name);
    return table.row(name).strings("PlayableCards");
  }

  /**
   * A game object filter as the battle reads it, from the game object filters table. Every column
   * is its field of the same name; the dead are filtered unless the row says not; the tags it
   * excludes, written as names separated by commas, are the bits the game tags table gives them;
   * the text the game shows for it is not read. The kinds of object it leaves out are its Filter
   * switches, or the names of its Filters list, which a newer data version writes in their place;
   * MatchSelf, also of the newer version, passes only the object that asks, and its two buff
   * checkers keep or drop an object by the buffs the asker applied to it. A row that sets any other
   * column, as a filter it builds on, is refused: read without it, the filter would match what the
   * row does not.
   *
   * @param name the row's name
   */
  public GameObjectFilter filter(String name) {
    GameTable table = tables.table(GAME_OBJECT_FILTERS);
    checkArgument(table.has(name), () -> "the game tables have no game object filter " + name);
    GameRow row = table.row(name).tracking();
    GameObjectFilter filter = filterOf(row);
    boolean baseResolved = baseResolved(table, row);
    List<String> unread = new ArrayList<>();
    for (String column : row.columns().keySet()) {
      if (!row.read().contains(column)
          && !PRESENTATION_FILTER_COLUMNS.contains(column)
          && !(column.equals("Base") && baseResolved)
          && sets(row, column)) {
        unread.add(column);
      }
    }
    if (!unread.isEmpty()) {
      Collections.sort(unread);
      throw new UnsupportedOperationException(
          "the game object filter " + name + " sets columns not modelled: " + unread);
    }
    return filter;
  }

  /**
   * Whether a filter row's Base, a newer data version's "FILTER.name", is already resolved in the
   * row: the base row is a filter of this table and every column it sets the row sets to the same
   * value, so the row read alone is the filter the base and the row make together. Any other Base
   * is left unread, and refused.
   */
  private static boolean baseResolved(GameTable table, GameRow row) {
    // Read from the raw columns, so that a Base not resolved stays unread and is refused.
    JsonNode value = row.columns().get("Base");
    if (value == null || !value.isTextual() || !value.asText().startsWith("FILTER.")) {
      return false;
    }
    String baseName = value.asText().substring("FILTER.".length());
    if (!table.has(baseName)) {
      return false;
    }
    for (Map.Entry<String, JsonNode> column : table.row(baseName).columns().entrySet()) {
      if (!column.getValue().equals(row.columns().get(column.getKey()))) {
        return false;
      }
    }
    return true;
  }

  /** The columns of a game object filter's row that only show something: the text it shows. */
  private static final Set<String> PRESENTATION_FILTER_COLUMNS = Set.of("FilterDescriptionTID");

  /**
   * The kinds of object a game object filter's Filters list may name, each with the switch of the
   * same test: a filter that lists a kind leaves out what the switch leaves out. A newer data
   * version writes the kinds as this list in place of the switches; the filter's test reads each
   * listed kind as the very check the switch asks (the same object queries, and the same character
   * row columns for the pushback and dash checks). A kind the switches have no test for (Self, the
   * instigator itself; Kamikaze and IgnoreResurrect, characters whose row sets the column of that
   * name) is refused.
   */
  private static final Map<String, String> FILTER_LIST_SWITCHES =
      Map.ofEntries(
          Map.entry("Hidden", "FilterHidden"),
          Map.entry("Invisible", "FilterInvisible"),
          Map.entry("Underground", "FilterUnderground"),
          Map.entry("Buildings", "FilterBuildings"),
          Map.entry("Towers", "FilterTowers"),
          Map.entry("Summoner", "FilterSummoner"),
          Map.entry("Flying", "FilterFlying"),
          Map.entry("Jumping", "FilterJumping"),
          Map.entry("DashImmune", "FilterDashImmune"),
          Map.entry("Dragging", "FilterDragging"),
          Map.entry("Cloning", "FilterCloning"),
          Map.entry("NoHitpointComponent", "FilterIfNoHitpointComponent"),
          Map.entry("PushbackIgnore", "FilterPushbackIgnore"),
          Map.entry("SameObjects", "FilterSameObjects"),
          Map.entry("PrincessTowers", "FilterPrincessTowers"),
          Map.entry("Clones", "FilterClones"));

  /**
   * Whether a game object filter leaves out the kind of object a switch names: the switch's own
   * column, or, for a row that writes the kinds as a Filters list, the list naming the kind. A row
   * writes one form or the other; one that writes both, or lists a kind no switch tests, is
   * refused.
   *
   * @param row the filter's row
   * @param listed the kinds its Filters list names, or null for a row without the list
   * @param column the switch's column
   */
  private static boolean filterSwitch(GameRow row, Set<String> listed, String column) {
    if (listed == null) {
      return row.bool(column);
    }
    if (row.bool(column)) {
      throw new UnsupportedOperationException(
          "the game object filter "
              + row.name()
              + " writes both a Filters list and the switch "
              + column
              + ", which no data version writes together");
    }
    return listed.contains(column);
  }

  /**
   * The switches a filter's Filters list names, or null for a row without the list. A row may write
   * the list as a single text, which the game reads as a list of that one kind.
   *
   * @throws UnsupportedOperationException for a kind no switch tests
   */
  private static Set<String> listedSwitches(GameRow row) {
    if (!row.has("Filters")) {
      return null;
    }
    List<String> kinds =
        row.value("Filters").isTextual() ? List.of(row.string("Filters")) : row.strings("Filters");
    Set<String> switches = new HashSet<>();
    for (String kind : kinds) {
      String column = FILTER_LIST_SWITCHES.get(kind);
      if (column == null) {
        throw new UnsupportedOperationException(
            "the game object filter "
                + row.name()
                + " lists the kind "
                + kind
                + " in Filters, which is not modelled");
      }
      switches.add(column);
    }
    return switches;
  }

  /**
   * A game object filter's fields, read from its row's columns of the same names, or, for the kinds
   * it leaves out, from its Filters list.
   */
  private GameObjectFilter filterOf(GameRow row) {
    Set<String> listed = listedSwitches(row);
    return GameObjectFilter.builder()
        .matchTeamOwn(row.bool("MatchTeamOwn"))
        .matchTeamEnemy(row.bool("MatchTeamEnemy"))
        .matchTypeCharacters(row.bool("MatchTypeCharacters"))
        .matchTypeBuildings(row.bool("MatchTypeBuildings"))
        .matchTypeProjectiles(row.bool("MatchTypeProjectiles"))
        .matchTypeAoe(row.bool("MatchTypeAoe"))
        .matchTypeGoblinRef(row.bool("MatchTypeGoblinRef"))
        .matchTowers(row.bool("MatchTowers"))
        .filterHidden(filterSwitch(row, listed, "FilterHidden"))
        .filterInvisible(filterSwitch(row, listed, "FilterInvisible"))
        .filterUnderground(filterSwitch(row, listed, "FilterUnderground"))
        .filterBuildings(filterSwitch(row, listed, "FilterBuildings"))
        .filterTowers(filterSwitch(row, listed, "FilterTowers"))
        .filterSummoner(filterSwitch(row, listed, "FilterSummoner"))
        .filterFlying(filterSwitch(row, listed, "FilterFlying"))
        .filterJumping(filterSwitch(row, listed, "FilterJumping"))
        .filterDashImmune(filterSwitch(row, listed, "FilterDashImmune"))
        .filterDragging(filterSwitch(row, listed, "FilterDragging"))
        .filterCloning(filterSwitch(row, listed, "FilterCloning"))
        .filterIfNoHitpointComponent(filterSwitch(row, listed, "FilterIfNoHitpointComponent"))
        .filterPushbackIgnore(filterSwitch(row, listed, "FilterPushbackIgnore"))
        .matchAttachedChildren(row.bool("MatchAttachedChildren"))
        .filterSameObjects(filterSwitch(row, listed, "FilterSameObjects"))
        .filterTags(tagBits(row.string("FilterTags")))
        .filterPrincessTowers(filterSwitch(row, listed, "FilterPrincessTowers"))
        .filterDead(!row.has("FilterDead") || row.bool("FilterDead"))
        .filterClones(filterSwitch(row, listed, "FilterClones"))
        .matchSelf(row.bool("MatchSelf"))
        .includeCharactersWithData(Set.copyOf(row.strings("IncludeCharactersWithData")))
        .excludeCharactersWithData(Set.copyOf(row.strings("ExcludeCharactersWithData")))
        .requireBuffsFromAsker(Set.copyOf(row.strings("FilterIfNotBuffedByChecker")))
        .refuseBuffsFromAsker(Set.copyOf(row.strings("FilterIfBuffedByChecker")))
        .build();
  }

  /**
   * The radius of a shape row that is a circle. A shape of any other class, or none, is refused.
   *
   * @param name the shape row's name
   */
  public int circleRadius(String name) {
    GameTable table = tables.table(SHAPES);
    if (!table.has(name)) {
      throw new UnsupportedOperationException("the shape " + name + " is not modelled");
    }
    GameRow row = table.row(name);
    if (!row.string("ClassType").equals("Circle")) {
      throw new UnsupportedOperationException(
          "the shape " + name + " is a " + row.string("ClassType") + ", which is not modelled");
    }
    return row.intValue("Radius");
  }

  /** The bits of game tags written as names separated by commas; none for an empty text. */
  /** The deflection flags a projectile row names, separated by commas, as their bits. */
  private static int deflectBehaviour(String names) {
    int bits = 0;
    for (String name : names.split(",")) {
      bits |=
          switch (name.trim()) {
            case "" -> 0;
            case "NoDeflect" -> ProjectileData.NO_DEFLECT;
            case "InvertDirection" -> ProjectileData.INVERT_DIRECTION;
            case "CheckOnlyTargetPosition" -> ProjectileData.CHECK_ONLY_TARGET_POSITION;
            case "UseSpellsTowerDamageMul" -> ProjectileData.USE_SPELLS_TOWER_DAMAGE_MUL;
            case "IgnoreHeight" -> ProjectileData.IGNORE_HEIGHT;
            default ->
                throw new IllegalArgumentException("an unknown deflection flag: " + name.trim());
          };
    }
    return bits;
  }

  private long tagBits(String names) {
    long bits = 0;
    for (String name : names.split(",")) {
      String tag = name.trim();
      if (tag.isEmpty()) {
        continue;
      }
      GameTable tags = tables.table(GAME_TAGS);
      checkArgument(tags.has(tag), () -> "the game tables have no game tag " + tag);
      bits |= 1L << tags.row(tag).index();
    }
    return bits;
  }

  /**
   * A row's sight clip as the loader leaves it after its post-load pass: 1000 for a row that leaves
   * it 0, and 0 for a building, whatever the row says.
   */
  private static int sightClip(GameRow row) {
    if (row.bool("IsBuilding")) {
      return 0;
    }
    int clip = row.intValue("SightClip");
    return clip == 0 ? TargetingConfig.STANDARD_SIGHT_CLIP : clip;
  }

  /** The attack sequence modes by name; any other name is 0. */
  private static final Map<String, Integer> SEQUENCE_MODES =
      Map.of("None", 0, "StaticLoop", 1, "HittimeLoop", 2, "Hittime", 3, "Manual", 4);

  /**
   * A row's attack sequence as the loader builds it. The order is the AttackSequence column, and
   * the mode is read only when the order has an element, an empty name there giving 1. With an
   * AttackSequenceList, one entry per element, and the order a single 0 when the row has none.
   * Without a list, entry 0 comes from the row's own columns; with an order, entries 1 and 2 come
   * from the columns numbered 2 and 3; without one, the order is a single 0, and while the last
   * entry has a variable damage time the next numbered entry joins it and the order, the first of
   * them making the mode 3.
   */
  private AttackSequence attackSequence(GameRow row) {
    List<Integer> order = new ArrayList<>(row.ints("AttackSequence"));
    int mode = 0;
    if (!order.isEmpty()) {
      String name = row.string("AttackSequenceMode");
      mode = name.isEmpty() ? 1 : SEQUENCE_MODES.getOrDefault(name, 0);
    }
    List<AttackSequence.Entry> entries = new ArrayList<>();
    JsonNode list = row.value("AttackSequenceList");
    if (list != null && list.isArray() && !list.isEmpty()) {
      for (JsonNode element : list) {
        entries.add(listEntry(row, element));
      }
      if (order.isEmpty()) {
        order.add(0);
      }
      return new AttackSequence(mode, order, entries);
    }
    entries.add(
        entry(
            row.intValue("Damage"),
            row.string("Projectile"),
            row.intValue("VariableDamageTime1"),
            row.intValue("MeleePushback"),
            row.bool("IsMeleePushbackAll")));
    if (!order.isEmpty()) {
      entries.add(numbered(row, 2));
      entries.add(numbered(row, 3));
      return new AttackSequence(mode, order, entries);
    }
    order.add(0);
    for (int n = 2; n <= 3; n++) {
      if (entries.get(entries.size() - 1).variableDamageTime() < 1) {
        break;
      }
      entries.add(numbered(row, n));
      order.add(n - 1);
      if (n == 2) {
        mode = 3;
      }
    }
    return new AttackSequence(mode, order, entries);
  }

  /** The entry the columns numbered n make; the third has no variable damage time. */
  private AttackSequence.Entry numbered(GameRow row, int n) {
    return entry(
        row.intValue("VariableDamage" + n),
        row.string("Projectile" + n),
        n < 3 ? row.intValue("VariableDamageTime" + n) : 0,
        row.intValue("MeleePushback" + n),
        row.bool("IsMeleePushbackAll" + n));
  }

  /** An entry of the five columns the row's own and numbered entries carry, the rest defaults. */
  private AttackSequence.Entry entry(
      int damage,
      String projectile,
      int variableDamageTime,
      int meleePushback,
      boolean meleePushbackAll) {
    return new AttackSequence.Entry(
        damage,
        projectile.isEmpty() ? null : projectile(projectile),
        variableDamageTime,
        100,
        -1,
        -1,
        -1,
        -1,
        -1,
        meleePushback,
        meleePushbackAll,
        null,
        null,
        -1,
        -1,
        null,
        0);
  }

  /** An entry of an AttackSequenceList element, with the entry columns' defaults. */
  private AttackSequence.Entry listEntry(GameRow row, JsonNode element) {
    String column = "AttackSequenceList";
    row.tableElement(column, element);
    String projectile = row.textField(column, element, "Projectile");
    String first = row.textField(column, element, "CustomFirstProjectile");
    return new AttackSequence.Entry(
        row.intField(column, element, "Damage", 0),
        projectile.isEmpty() ? null : projectile(projectile),
        row.intField(column, element, "VariableDamageTime", 0),
        row.intField(column, element, "HitSpeedMultiplier", 100),
        row.intField(column, element, "CustomRange", -1),
        row.intField(column, element, "CustomSightRange", -1),
        row.intField(column, element, "CustomMinimunRange", -1),
        row.intField(column, element, "CustomProjectileStartZ", -1),
        row.intField(column, element, "CustomProjectileStartRadius", -1),
        row.intField(column, element, "MeleePushback", 0),
        row.boolField(column, element, "IsMeleePushbackAll", false),
        actionName(row.name(), "DoAttackAction", element.path("DoAttackAction")),
        first.isEmpty() ? null : projectile(first),
        row.intField(column, element, "CustomMultipleTargets", -1),
        row.intField(column, element, "CustomRememberMultipleTargets", -1),
        actionName(row.name(), "CustomOnAttackAction", element.path("CustomOnAttackAction")),
        row.intField(column, element, "AttackStartDelay", 0));
  }

  /** The columns of a row's death that are not modelled, those of its death spawn only with one. */
  /**
   * A column the row's loader skips, so whatever the row sets stays out of the battle: marked as
   * seen, and 0.
   */
  private static int notLoaded(GameRow row, String column) {
    row.has(column);
    return 0;
  }

  private static List<String> unmodelledDeathColumns(GameRow row, boolean spawns) {
    List<String> columns = new ArrayList<>();
    for (String column : UNMODELLED_DEATH_COLUMNS) {
      if (sets(row, column)) {
        columns.add(column);
      }
    }
    if (spawns) {
      for (String column : UNMODELLED_DEATH_SPAWN_COLUMNS) {
        if (sets(row, column)) {
          columns.add(column);
        }
      }
    }
    // A second death spawn row is held on a plain ring or in front: not for a row whose children
    // fly back to the ring, take a fixed priority or draw their ring radius, and not without a
    // first row, which makes the slot skip both. No row sets any of these.
    if (sets(row, "DeathSpawnCharacter2")
        && (!spawns
            || row.bool("DeathSpawnPushback")
            || row.bool("SpawnConstPriority")
            || sets(row, "DeathSpawnMinRadius"))) {
      columns.add("DeathSpawnCharacter2");
    }
    return columns;
  }

  /**
   * Whether every tag an area effect's row lists only keeps its pushback from being shown: the
   * update hands that tag to the area damage, which reads it only to skip the pushback's
   * presentation.
   */
  private static boolean presentationTags(GameRow row) {
    for (String tag : row.string("Tags").split(",")) {
      if (!tag.isBlank() && !tag.trim().equals("NO_AOE_PUSHBACK_VFX")) {
        return false;
      }
    }
    return true;
  }

  /**
   * An area effect as the battle reads it, from the area effect objects table. A starting or
   * life-end action written inline, as Dark Magic's are, is the actions table's row named after the
   * area effect and the column.
   *
   * @param name the row's name
   */
  public AreaEffectData areaEffect(String name) {
    GameTable table = tables.table(AREA_EFFECT_OBJECTS);
    checkArgument(table.has(name), () -> "the game tables have no area effect " + name);
    GameRow row = table.row(name).tracking();
    List<String> unmodelled = new ArrayList<>();
    for (String column : UNMODELLED_AREA_EFFECT_COLUMNS) {
      if (sets(row, column) && !(column.equals("Tags") && presentationTags(row))) {
        unmodelled.add(column);
      }
    }
    // The filter form: a row without a shape that names a filter and neither hit switch, as the
    // area effect class of a newer data version writes every row, which has no hit switches. It
    // chooses what it reaches by the filter alone and deals its damage as a damage type.
    boolean filterHits =
        !sets(row, "Shape")
            && sets(row, "Filter")
            && !row.bool("HitsAir")
            && !row.bool("HitsGround");
    AreaEffectData data =
        AreaEffectData.builder()
            .name(row.name())
            .rarity(rarity(row.string("Rarity")))
            .lifeDurationMs(row.intValue("LifeDuration"))
            .radius(row.intValue("Radius"))
            .maxRadius(row.intValue("MaxRadius"))
            .hitSpeedMs(row.intValue("HitSpeed"))
            .hitSpeedOffsetMs(row.intValue("HitSpeedOffset"))
            .damage(filterHits ? 0 : row.intValue("Damage"))
            .crownTowerDamagePercent(row.intValue("CrownTowerDamagePercent"))
            .hitsAir(row.bool("HitsAir"))
            .hitsGround(row.bool("HitsGround"))
            .onlyEnemies(row.bool("OnlyEnemies"))
            .ignoreBuildings(row.bool("IgnoreBuildings"))
            .affectsHidden(row.bool("AffectsHidden"))
            .controlsBuff(row.bool("ControlsBuff"))
            .pushback(row.intValue("Pushback"))
            .pushbackAll(row.bool("PushbackAll"))
            .relativePushback(row.bool("RelativePushback"))
            .continuousPushback(row.bool("ContinuousPushback"))
            .maximumTargets(row.intValue("MaximumTargets"))
            .sharedDamage(row.bool("SharedDamage"))
            .onStartingAction(inlineActionName(row, "OnStartingAction"))
            .onLifeTimeEndAction(inlineActionName(row, "OnLifeTimeEndAction"))
            .buff(row.string("Buff").isEmpty() ? null : row.string("Buff"))
            .buffTimeMs(row.intValue("BuffTime"))
            .capBuffTimeToAreaEffectTime(row.bool("CapBuffTimeToAreaEffectTime"))
            .onlyOwnTroops(row.bool("OnlyOwnTroops"))
            .spawnAreaEffectObject(
                row.string("SpawnAreaEffectObject").isEmpty()
                    ? null
                    : row.string("SpawnAreaEffectObject"))
            .projectile(row.string("Projectile").isEmpty() ? null : row.string("Projectile"))
            .hitBiggestTargets(row.bool("HitBiggestTargets"))
            .projectileStartHeight(row.intValue("ProjectileStartHeight"))
            // On unless the row turns it off.
            .targetProjectiles(!row.has("TargetProjectiles") || row.bool("TargetProjectiles"))
            .cloning(row.bool("Clone"))
            .onHitAction(actionName(row, "OnHitAction"))
            .oneHitPerTarget(row.bool("OneHitPerTarget"))
            .onHitSelfAction(actionName(row, "OnHitSelfAction"))
            .expireOnTrigger(row.bool("ExpireOnTrigger"))
            .followsParent(row.string("FollowBehaviour").equals("FollowParent"))
            .followsTarget(row.string("FollowBehaviour").equals("FollowTarget"))
            .deflectsProjectiles(row.bool("DeflectProjectilesEnabled"))
            .spawnCharacter(set(row, "SpawnCharacter") ? row.string("SpawnCharacter") : null)
            .spawnIntervalMs(row.intValue("SpawnInterval"))
            .spawnInitialDelayMs(row.intValue("SpawnInitialDelay"))
            .spawnTimeMs(row.intValue("SpawnTime"))
            .spawnMaxCount(row.intValue("SpawnMaxCount"))
            .spawnMinRadius(row.intValue("SpawnMinRadius"))
            .spawnRandomizeSequence(row.bool("SpawnRandomizeSequence"))
            .spawnClones(row.bool("SpawnClones"))
            .stayAfterParentDies(row.bool("StayAfterParentDies"))
            .linkToInstigatorLife(row.bool("LinkToInstigatorLife"))
            .shaped(sets(row, "Shape"))
            .filter(
                (sets(row, "Shape") || filterHits) && sets(row, "Filter")
                    ? row.string("Filter")
                    : null)
            .filterHits(filterHits)
            .typedDamage(filterHits ? areaDamageType(row, unmodelled) : null)
            .unmodelledColumns(unmodelled)
            .build();
    if (data.shaped()) {
      data = shaped(data, row.string("Shape"), unmodelled);
      // A shaped row's damage type is read only by its hits' damage, which a row without damage
      // never deals; a circle's damage is queued with it as a typed hit.
      if (data.damage() == 0) {
        row.has("DamageType");
      } else if (data.shapeRadius() >= 1 && sets(row, "DamageType")) {
        data = data.toBuilder().damageType(row.string("DamageType")).build();
      } else if (data.shapeRadius() >= 1) {
        // A circle's damage without a damage type is not held.
        unmodelled.add("Shape");
      }
    }
    // For a row with hit switches, the hit action is modelled for a Clone, a Clone row whose hit
    // action clones, and which neither deals damage nor applies a buff, as the shipped Clone does;
    // and for a row that is not a Clone's whose hit action is a buff spawn, as the evolved Tesla's
    // ring's is, a group of buff spawns, as the Goblin Curse's base is, a taunt, as the Goblin
    // Demolisher's is, a group of taunts, as the hero Knight's is, or the evolved Dart Goblin's
    // poison damage. The filter form's hit pass schedules any hit action on each object it lists,
    // as it is built for that object, so it takes every one.
    boolean cloning =
        data.onHitAction() != null
            && tables.action(data.onHitAction()).classType().equals("ActionClone");
    boolean buffSpawns = data.onHitAction() != null && buffSpawnGroup(data.onHitAction());
    boolean taunt = data.onHitAction() != null && tauntGroup(data.onHitAction());
    // The evolved Dart Goblin's poison areas start its poison damage on what they reach.
    boolean poison =
        data.onHitAction() != null
            && tables
                .action(data.onHitAction())
                .classType()
                .equals("ActionBlowdartGoblinEvoDamage");
    // A shaped row's hit pass schedules a choice by team, as the evolved Baby Dragon's wind does.
    boolean byTeam =
        data.onHitAction() != null
            && data.shaped()
            && tables.action(data.onHitAction()).classType().equals("ActionFilterByEnemy");
    // A circle's hit pass schedules a choice between buff spawns, as the Ice Golemite hero form's
    // slow circle does; the circle's load refuses anything else it would schedule.
    boolean circleBuffs =
        data.onHitAction() != null
            && data.shaped()
            && data.shapeRadius() >= 1
            && buffSelect(data.onHitAction());
    if (data.onHitAction() != null
        && !data.filterHits()
        && !(data.cloning() && cloning)
        && !(!data.cloning() && !data.shaped() && (buffSpawns || taunt || poison))
        && !byTeam
        && !circleBuffs) {
      unmodelled.add("OnHitAction");
    }
    // For a row with hit switches one hit per target is read by the hit action's loop; whether
    // anything else reads it is not established. The filter form's hit pass reads it for every
    // object it lists.
    if (data.oneHitPerTarget() && data.onHitAction() == null && !data.filterHits()) {
      unmodelled.add("OneHitPerTarget");
    }
    // The hit action on itself and the end on its first hit are read only by the filter form's
    // hit pass.
    if (data.onHitSelfAction() != null && !data.filterHits()) {
      unmodelled.add("OnHitSelfAction");
    }
    if (data.expireOnTrigger() && !data.filterHits()) {
      unmodelled.add("ExpireOnTrigger");
    }
    // FollowParent follows its parent; FollowTarget the target of the projectile whose impact made
    // it, the only maker that hands it one. Any other behaviour is not modelled.
    if (sets(row, "FollowBehaviour") && !data.followsParent() && !data.followsTarget()) {
      unmodelled.add("FollowBehaviour");
    }
    // The Filter is read only by the Shape path, which a row without a Shape never enters: a row
    // without one hits by its air and ground switches, whatever filter it names, as the taunt
    // cancelling rows do. A row that sets neither switch and names a filter would choose what it
    // reaches by the filter alone, which is not modelled; read as its switches, it would reach
    // nothing.
    if (!data.shaped() && !data.filterHits()) {
      if (sets(row, "Filter") && !data.hitsAir() && !data.hitsGround()) {
        unmodelled.add("Filter");
      }
    }
    if (data.filterHits()) {
      filterForm(data, unmodelled);
    } else {
      // The push columns beyond its distance are read only by the filter form's hit pass: a row
      // with hit switches pushes by its own path, which lifting the gates, the separation and
      // keeping the longer push are not held for.
      if (data.pushbackAll()) {
        unmodelled.add("PushbackAll");
      }
      if (data.relativePushback()) {
        unmodelled.add("RelativePushback");
      }
      if (data.continuousPushback()) {
        unmodelled.add("ContinuousPushback");
      }
    }
    // The filter form's hit pass does not read the Clone switch: what a Clone reaches is its
    // filter's choice, and what it does its hit action's.
    if (data.cloning()
        && !data.filterHits()
        && (!cloning || data.damage() != 0 || data.buff() != null)) {
      unmodelled.add("Clone");
    }
    // Whether a projectile goes onto each object hit is read only by the filter form's hit pass.
    if (!data.targetProjectiles() && !data.filterHits()) {
      unmodelled.add("TargetProjectiles");
    }
    if (data.projectile() != null && !data.filterHits()) {
      // A start height of -1 launches from the area effect's source at height 1000, which no row
      // carried here does.
      if (data.projectileStartHeight() == -1) {
        unmodelled.add("ProjectileStartHeight");
      }
      // Without HitBiggestTargets every projectile from the second hit on is spread about the
      // point by two battle draws; no shipped row has two hits without it.
      int lifeHits = data.hitSpeedMs() == 0 ? 1 : data.lifeDurationMs() / data.hitSpeedMs();
      if (!data.hitBiggestTargets() && lifeHits >= 2) {
        unmodelled.add("Projectile");
      }
    }
    // Without SpawnRandomizeSequence the spawner turns each direction by a fixed step from the side
    // the area effect is on, which no reference holds.
    if (data.spawnCharacter() != null && !data.spawnRandomizeSequence()) {
      unmodelled.add("SpawnCharacter");
    }
    return data.toBuilder()
        .unmodelledColumns(
            withUnread(
                unmodelled,
                row,
                PRESENTATION_AREA_EFFECT_COLUMNS,
                INERT_AREA_EFFECT_COLUMNS,
                PENDING_AREA_EFFECT_COLUMNS))
        .build();
  }

  /**
   * Refuses, for the filter form, what each object it lists would get beyond its push, its pull (a
   * buff that attracts), its damage, its hit action, its buff and its projectile, and what the pass
   * does beyond passing by an object it has reached for a row that hits each once, its target
   * limit, its biggest targets first, its hit action on itself and its end on its first hit: a
   * launch from the area effect's source (a start height below 0), a spawner and a deflection, none
   * of which the filter form's hit pass is held for. Its push is held for with its gates in place
   * or lifted (PushbackAll), the separation taken off (RelativePushback) and the longer push kept
   * (ContinuousPushback). A damage type that names a column the pass is not held for is refused by
   * its Damage column.
   */
  private void filterForm(AreaEffectData data, List<String> unmodelled) {
    if (data.projectile() != null && data.projectileStartHeight() < 0) {
      unmodelled.add("ProjectileStartHeight");
    }
    if (data.spawnCharacter() != null && !unmodelled.contains("SpawnCharacter")) {
      unmodelled.add("SpawnCharacter");
    }
    if (data.deflectsProjectiles()) {
      unmodelled.add("DeflectProjectilesEnabled");
    }
  }

  /**
   * The damage type a filter form row deals, from its Damage column: a table written inline or the
   * name of a damage types row, each giving BaseDamage (0 when left out) and TowerDamage (none when
   * left out), and an Effect, which only the view shows. A row without Damage deals none. A number,
   * or a type that sets anything else (its Flags, an action on its source or its target, or a field
   * the type has no column for), is refused by the Damage column.
   */
  private AreaDamageType areaDamageType(GameRow row, List<String> unmodelled) {
    JsonNode value = row.value("Damage");
    if (value == null || value.isTextual() && value.asText().isEmpty()) {
      return null;
    }
    if (value.isObject()) {
      for (Iterator<String> it = value.fieldNames(); it.hasNext(); ) {
        if (!DAMAGE_TYPE_FIELDS.contains(it.next())) {
          unmodelled.add("Damage");
          return null;
        }
      }
      return new AreaDamageType(
          null,
          row.intField("Damage", value, "BaseDamage", 0),
          row.intField("Damage", value, "TowerDamage", AreaDamageType.NO_TOWER_DAMAGE));
    }
    if (value.isTextual()) {
      GameTable types = tables.table(DAMAGE_TYPES);
      if (!types.has(value.asText())) {
        unmodelled.add("Damage");
        return null;
      }
      GameRow type = types.row(value.asText());
      for (String column : type.setColumns()) {
        if (!DAMAGE_TYPE_FIELDS.contains(column)) {
          unmodelled.add("Damage");
          return null;
        }
      }
      return new AreaDamageType(
          type.name(),
          type.intValue("BaseDamage"),
          type.has("TowerDamage") ? type.intValue("TowerDamage") : AreaDamageType.NO_TOWER_DAMAGE);
    }
    unmodelled.add("Damage");
    return null;
  }

  /**
   * A shaped row with its rectangle read. Refused, by its Shape column: a shape of another class or
   * none, a rectangle without a filter, and one whose hits would do more than schedule their hit
   * action - a damage, a buff, a push, a launch, a spawner, a growth or one hit per target - none
   * of which the shape's hit pass is held for.
   */
  private AreaEffectData shaped(AreaEffectData data, String shape, List<String> unmodelled) {
    GameTable table = tables.table(SHAPES);
    if (table.has(shape) && table.row(shape).string("ClassType").equals("Circle")) {
      return circle(data, table.row(shape), unmodelled);
    }
    if (!table.has(shape) || !table.row(shape).string("ClassType").equals("Rectangle")) {
      unmodelled.add("Shape");
      return data;
    }
    GameRow row = table.row(shape);
    if (data.filter() == null
        || data.damage() != 0
        || data.buff() != null
        || data.pushback() != 0
        || data.projectile() != null
        || data.spawnCharacter() != null
        || data.maxRadius() != 0
        || data.oneHitPerTarget()) {
      unmodelled.add("Shape");
    }
    return data.toBuilder()
        .shapeWidth(row.intValue("Width"))
        .shapeHeight(row.intValue("Height"))
        .build();
  }

  /**
   * A shaped row with its circle read, as the Giant hero form's landing and the Ice Golemite hero
   * form's damage, knockback and slow circles have: a filter, a hit action that chooses a buff to
   * spawn, a damage queued through a damage type as a typed hit, a crown tower taking its share of
   * it, a push away from its point, and nothing else a hit would do. Refused, by its Shape column:
   * a circle without a filter, with neither a hit action, damage nor a push, with damage but no
   * damage type, with any other hit action, a buff, a launch, a spawner, a growth, one hit per
   * target or shared damage, none of which the circle's hit pass is held for. The push's floor and
   * gate lift are refused for every area effect.
   */
  private AreaEffectData circle(AreaEffectData data, GameRow row, List<String> unmodelled) {
    boolean buffSelect = data.onHitAction() != null && buffSelect(data.onHitAction());
    if (data.filter() == null
        || (data.damage() == 0 && data.pushback() < 1 && !buffSelect)
        || (data.onHitAction() != null && !buffSelect)
        || data.buff() != null
        || data.projectile() != null
        || data.spawnCharacter() != null
        || data.maxRadius() != 0
        || data.oneHitPerTarget()
        || data.sharedDamage()) {
      unmodelled.add("Shape");
    }
    return data.toBuilder().shapeRadius(row.intValue("Radius")).build();
  }

  /**
   * Whether a hit action is a taunt, or a group whose every part is a taunt: the hero Knight's
   * checks its start gate on the unit it reaches, then schedules its taunt there.
   */
  private boolean tauntGroup(String action) {
    GameAction row = tables.action(action);
    if (row.classType().equals("ActionTaunt")) {
      return true;
    }
    if (!row.classType().equals("ActionGroup")) {
      return false;
    }
    for (JsonNode part : row.fields().path("SubActions")) {
      if (!tables.action(part.path("action").asText()).classType().equals("ActionTaunt")) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether an action row spawns buffs only: a buff spawn, as the evolved Tesla's ring runs, or a
   * group whose every part spawns a buff.
   */
  private boolean buffSpawnGroup(String action) {
    GameAction row = tables.action(action);
    if (row.classType().equals("ActionSpawn")) {
      return row.fields().path("SpawnType").asText("").equals("BuffType");
    }
    if (!row.classType().equals("ActionGroup")) {
      return false;
    }
    for (JsonNode part : row.fields().path("SubActions")) {
      GameAction sub = tables.action(part.path("action").asText());
      if (!sub.classType().equals("ActionSpawn")
          || !sub.fields().path("SpawnType").asText("").equals("BuffType")) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether a hit action is a choice between buff spawns, as the Ice Golemite hero form's slow
   * circle has: a select whose every part spawns a buff. Scheduled on the object a hit reaches, its
   * conditions are read on that object, and the part it chooses is scheduled there.
   */
  private boolean buffSelect(String action) {
    GameAction row = tables.action(action);
    if (!row.classType().equals("ActionSelect") || row.fields().path("SubActions").isEmpty()) {
      return false;
    }
    for (JsonNode part : row.fields().path("SubActions")) {
      GameAction sub = tables.action(part.path("action").asText());
      if (!sub.classType().equals("ActionSpawn")
          || !sub.fields().path("SpawnType").asText("").equals("BuffType")) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether a buff column is the action a reduced hit schedules on the buffed unit and that action
   * only plays an effect for as long as it plays: such a row makes no run, so scheduling it changes
   * nothing in the battle, and it is not scheduled. The evolved Knight's protection effect is one.
   */
  private boolean inertDamageReductionAction(GameRow row, String column) {
    return column.equals("OnDamageReductionAction")
        && ActionRows.inertEffect(tables.action(row.string(column)));
  }

  /**
   * Whether a column's value names an action row, by reference or by its name, rather than writing
   * one inline.
   */
  private static boolean namedAction(JsonNode value) {
    if (value == null || value.isNull() || value.isMissingNode()) {
      return false;
    }
    return value.isTextual() || value.isObject() && value.has("action");
  }

  /**
   * A buff's start or remove action: the row the column names, or the row of an inline group of
   * named rows; null for none and for any other one written inline, which is listed as not modelled
   * instead.
   */
  private String hookAction(GameRow row, String column) {
    if (inlineNamedGroup(row, column)) {
      return inlineActionName(row, column);
    }
    return namedAction(row.value(column)) ? actionName(row, column) : null;
  }

  /**
   * Whether the battle reads a buff's start or remove action: one that names a row, or a group of
   * named rows written inline, as the Royal Chef's level-up buff's is, which is the actions table's
   * row named after the buff and the column.
   */
  private boolean readHook(GameRow row, String column) {
    return namedAction(row.value(column)) || inlineNamedGroup(row, column);
  }

  /** Whether a column holds an inline group of named rows that the actions table has a row for. */
  private boolean inlineNamedGroup(GameRow row, String column) {
    JsonNode value = row.value(column);
    return value != null
        && value.isObject()
        && !value.has("action")
        && value.path("ClassType").asText().equals("ActionGroup")
        && namesOnly(value.path("SubActions"))
        && tables.actionNames().contains(row.name() + "_" + column);
  }

  /**
   * Whether a buff column is its tags and every tag it sets is one the battle reads where it reads
   * the tag word of the buff's carrier: the one that keeps enemies from pushing it, and the custom
   * tag only expressions read.
   */
  private static boolean modelledBuffTags(GameRow row, String column) {
    if (!column.equals("GameTagsToSet")) {
      return false;
    }
    for (String tag : row.string(column).split(",")) {
      if (!tag.isBlank() && !MODELLED_BUFF_TAGS.contains(tag.trim())) {
        return false;
      }
    }
    return true;
  }

  /**
   * A character buff as the battle reads it, from the character buffs table, or the buff row a buff
   * spawn row writes inline under that name. Every column it sets that is neither read nor only
   * shows something is listed as not modelled, and so is a start or remove action written inline
   * rather than naming a row.
   *
   * @param name the row's name
   */
  public BuffData buff(String name) {
    GameTable table = tables.table(CHARACTER_BUFFS);
    GameRow row = table.has(name) ? table.row(name) : tables.inlineBuff(name);
    checkArgument(row != null, () -> "the game tables have no buff " + name);
    List<String> unmodelled = new ArrayList<>();
    for (String column : row.columns().keySet()) {
      if (!MODELLED_BUFF_COLUMNS.contains(column)
          && !PRESENTATION_BUFF_COLUMNS.contains(column)
          && sets(row, column)
          && !inertDamageReductionAction(row, column)
          && !modelledBuffTags(row, column)
          && !(BUFF_HOOK_COLUMNS.contains(column) && readHook(row, column))) {
        unmodelled.add(column);
      }
    }
    // A start or remove action written inline is a row of its own, which the battle does not read
    // unless it is a group of named rows the actions table holds under the buff's and column's
    // name.
    for (String column : BUFF_HOOK_COLUMNS) {
      JsonNode value = row.value(column);
      if (value != null && value.isObject() && !readHook(row, column)) {
        unmodelled.add(column);
      }
    }
    Collections.sort(unmodelled);
    return BuffData.builder()
        .name(row.name())
        .rarity(rarity(row.string("Rarity")))
        .speedMultiplier(row.intValue("SpeedMultiplier"))
        .hitSpeedMultiplier(row.intValue("HitSpeedMultiplier"))
        .spawnSpeedMultiplier(row.intValue("SpawnSpeedMultiplier"))
        .hitFrequency(row.intValue("HitFrequency"))
        .damagePerSecond(row.intValue("DamagePerSecond"))
        .crownTowerDamagePerHit(row.intValue("CrownTowerDamagePerHit"))
        .crownTowerDamagePercent(row.intValue("CrownTowerDamagePercent"))
        .buildingDamagePercent(row.intValue("BuildingDamagePercent"))
        .hitTickFromSource(row.bool("HitTickFromSource"))
        .attractPercentage(row.intValue("AttractPercentage"))
        .lateralPushPercentage(row.intValue("LateralPushPercentage"))
        .pushMassFactor(row.intValue("PushMassFactor"))
        .pushSpeedFactor(row.intValue("PushSpeedFactor"))
        .attractMinAngle(row.intValue("AttractMinAngle"))
        .attractMaxAngle(row.intValue("AttractMaxAngle"))
        .controlledByParent(row.bool("ControlledByParent"))
        .enableStacking(row.bool("EnableStacking"))
        .playerSpecificBuff(row.bool("PlayerSpecificBuff"))
        .noEffectToCrownTowers(row.bool("NoEffectToCrownTowers"))
        .ignoreBuildings(row.bool("IgnoreBuildings"))
        .deathSpawn(sets(row, "DeathSpawn") ? row.string("DeathSpawn") : null)
        .deathSpawnCount(row.intValue("DeathSpawnCount"))
        .deathSpawnRadius(row.intValue("DeathSpawnRadius"))
        .deathSpawnSameLocation(row.bool("DeathSpawnSameLocation"))
        .deathSpawnIsEnemy(row.bool("DeathSpawnIsEnemy"))
        .deathSpawnDeployDelay(row.bool("DeathSpawnDeployDelay"))
        .otherBuffDeathSpawnAllowed(row.bool("OtherBuffDeathSpawnAllowed"))
        .invisible(row.bool("Invisible"))
        .notCloned(row.bool("NotCloned"))
        .healPerSecond(row.intValue("HealPerSecond"))
        .allowedOverHealPercent(row.intValue("AllowedOverHealPerc"))
        .lockTarget(row.bool("LockTarget"))
        .addAsIndividualBuff(row.bool("AddAsIndividualBuff"))
        .aliveIfTrue(sets(row, "AliveIfTrue") ? row.string("AliveIfTrue") : null)
        .damageReduction(row.intValue("DamageReduction"))
        .ignorePushBack(row.bool("IgnorePushBack"))
        .cloneBuff(row.bool("Clone"))
        .attachedInheritAs(sets(row, "AttachedInheritAs") ? row.string("AttachedInheritAs") : null)
        .gameTagsToSet(tagBits(row.string("GameTagsToSet")))
        .overrideChargeRange(row.intValue("OverrideChargeRange"))
        .overrideProjectile(
            sets(row, "OverrideProjectile") ? row.string("OverrideProjectile") : null)
        .removeOnAttack(row.bool("RemoveOnAttack"))
        .onStartAction(hookAction(row, "OnStartAction"))
        .onRemoveAction(hookAction(row, "OnRemoveAction"))
        .spawnObject(sets(row, "SpawnObject") ? row.string("SpawnObject") : null)
        .spawnStartTimeMs(row.intValue("SpawnStartTime"))
        .spawnIntervalMs(row.intValue("SpawnInterval"))
        .spawnLimit(row.intValue("SpawnLimit"))
        .spawnNumber(row.intValue("SpawnNumber"))
        .spawnPauseTimeMs(row.intValue("SpawnPauseTime"))
        .spawnerAliveRequired(row.bool("SpawnerAliveRequired"))
        .unmodelledColumns(unmodelled)
        .build();
  }

  /**
   * True when a row sets a column: a value other than empty, 0 or false. A table counts as set
   * unless it is empty, and a fraction unless it is 0, so a column of either form is never taken
   * for one the row leaves out.
   */
  private static boolean sets(GameRow row, String column) {
    JsonNode value = row.value(column);
    if (value == null || value.isNull()) {
      return false;
    }
    if (value.isTextual()) {
      return !value.asText().isEmpty();
    }
    if (value.isBoolean()) {
      return value.asBoolean();
    }
    if (value.isNumber()) {
      return value.asDouble() != 0;
    }
    return !value.isEmpty();
  }

  /**
   * Whether a unit is a champion: its ability row says so, and an ability row that says nothing
   * makes it one. A unit without an ability is none.
   */
  private boolean champion(GameRow row) {
    String ability = row.string("Ability");
    if (ability.isEmpty()) {
      return false;
    }
    GameTable abilities = tables.table(CHARACTER_ABILITIES);
    checkArgument(
        abilities.has(ability),
        () -> row.name() + " names the ability " + ability + ", which the game tables lack");
    GameRow abilityRow = abilities.row(ability);
    return !abilityRow.has("IsChampion") || abilityRow.bool("IsChampion");
  }

  /**
   * The ability columns that make an ability do more than run its activation action, buff the unit
   * itself, dash, switch lanes, leave a character on its spot, create an area effect at the unit,
   * count the souls that area effect spends and hold the unit in its follow-up state - a buff over
   * a radius, morph and a deploy time of the character it leaves; the rest are the champion
   * controller's or the dash's, read into the ability, or presentation, which a request never
   * reads.
   */
  private static final List<String> UNMODELLED_ABILITY_COLUMNS =
      List.of("BuffRadius", "MorphTarget", "ActivationSpawnDeployTime");

  /**
   * A unit's ability row, or null for a unit without one. Its activation action, written inline, is
   * the actions table's row named after the ability and the column.
   */
  private AbilityData ability(GameRow row) {
    String name = row.string("Ability");
    if (name.isEmpty()) {
      return null;
    }
    GameTable abilities = tables.table(CHARACTER_ABILITIES);
    checkArgument(
        abilities.has(name),
        () -> row.name() + " names the ability " + name + ", which the game tables lack");
    GameRow ability = abilities.row(name);
    return AbilityData.builder()
        .name(name)
        .castTimeMs(ability.intValue("CastTime"))
        .triggerDelayMs(ability.intValue("TriggerDelay"))
        .keepCurrentTarget(ability.bool("KeepCurrentTarget"))
        .champion(!ability.has("IsChampion") || ability.bool("IsChampion"))
        .onActivationAction(inlineActionName(ability, "OnActivationAction"))
        .buff(set(ability, "Buff") ? ability.string("Buff") : null)
        .buffTimeMs(ability.intValue("BuffTime"))
        .manaCost(ability.intValue("ManaCost"))
        .cooldownMs(ability.intValue("Cooldown"))
        .maxCharges(ability.intValue("MaxCharges"))
        .dashRange(ability.intValue("DashRange"))
        .dashTargetFurthest(ability.bool("DashTargetFurthest"))
        .pendingBuff(set(ability, "PendingBuff") ? ability.string("PendingBuff") : null)
        .switchLanes(ability.bool("SwitchLanes"))
        .activationSpawnCharacter(
            set(ability, "ActivationSpawnCharacter")
                ? ability.string("ActivationSpawnCharacter")
                : null)
        .areaEffectObject(
            set(ability, "AreaEffectObject") ? ability.string("AreaEffectObject") : null)
        .abilityStateDurationMs(ability.intValue("AbilityStateDuration"))
        .gameTagsWhileAbilityActive(tagBits(ability.string("GameTagsWhileAbilityActive")))
        .resurrectBaseCount(ability.intValue("ResurrectBaseCount"))
        .resurrectEnemies(ability.bool("ResurrectEnemies"))
        .resurrectOwnTroops(ability.bool("ResurrectOwnTroops"))
        .spawnLimit(ability.intValue("SpawnLimit"))
        .unmodelledColumns(unmodelledAbilityColumns(ability))
        .build();
  }

  /**
   * The columns of an ability row the battle does not model; besides the list, its tags while it
   * holds the unit in its follow-up state, when it names one whose reader is not modelled.
   */
  private static List<String> unmodelledAbilityColumns(GameRow ability) {
    List<String> columns = new ArrayList<>();
    for (String column : UNMODELLED_ABILITY_COLUMNS) {
      if (set(ability, column)) {
        columns.add(column);
      }
    }
    for (String tag : ability.string("GameTagsWhileAbilityActive").split(",")) {
      if (!tag.isBlank() && !MODELLED_ROW_TAGS.contains(tag.trim())) {
        columns.add("GameTagsWhileAbilityActive");
        break;
      }
    }
    return columns;
  }

  /**
   * The action row a column names, or, for a row written inline, the actions table's row named
   * after the row and the column; null for none.
   */
  private String inlineActionName(GameRow row, String column) {
    JsonNode value = row.value(column);
    if (value == null || value.isNull()) {
      return null;
    }
    if (value.isObject() && !value.has("action")) {
      String inline = row.name() + "_" + column;
      checkArgument(
          tables.actionNames().contains(inline),
          () -> row.name() + " writes its " + column + " inline, with no row " + inline);
      return inline;
    }
    String name = value.isObject() ? value.path("action").asText("") : value.asText();
    return name.isEmpty() ? null : name;
  }

  /**
   * A projectile as the battle reads it, from the projectiles table.
   *
   * @param name the row's name
   */
  public ProjectileData projectile(String name) {
    GameTable table = tables.table(PROJECTILES);
    checkArgument(table.has(name), () -> "the game tables have no projectile " + name);
    GameRow row = table.row(name).tracking();
    ProjectileData data =
        ProjectileData.builder()
            .name(row.name())
            .rarity(rarity(row.string("Rarity")))
            .speed(row.intValue("Speed"))
            .gravity(row.intValue("Gravity"))
            .homing(row.bool("Homing"))
            .homingTimeMs(row.intValue("HomingTime"))
            .homingMinDistance(row.intValue("HomingMinDistance"))
            .damage(row.intValue("Damage"))
            .crownTowerDamagePercent(row.intValue("CrownTowerDamagePercent"))
            .damageMode(damageMode(row.string("DamageScalingMode")))
            .radius(row.intValue("Radius"))
            .aoeToAir(row.bool("AoeToAir"))
            .aoeToGround(row.bool("AoeToGround"))
            .onlyEnemies(row.bool("OnlyEnemies"))
            .projectileRadius(row.intValue("ProjectileRadius"))
            .projectileRange(row.intValue("ProjectileRange"))
            .checkCollisions(row.bool("CheckCollisions"))
            .considerZDistance(row.bool("ConsiderZDistance"))
            .alwaysApplyPushback(row.bool("AlwaysApplyPushback"))
            .minDistance(row.intValue("MinDistance"))
            .circleScatter("Circle".equals(row.string("Scatter")))
            .lineScatter("Line".equals(row.string("Scatter")))
            .pushback(row.intValue("Pushback"))
            .spawnCharacter(set(row, "SpawnCharacter") ? row.string("SpawnCharacter") : null)
            // The loader stores at least one child for a row that names a spawned character.
            .spawnCharacterCount(
                set(row, "SpawnCharacter") ? Math.max(row.intValue("SpawnCharacterCount"), 1) : 0)
            .spawnCharacterDeployTimeMs(row.intValue("SpawnCharacterDeployTime"))
            .deflectedCharacterSpawn(
                set(row, "DeflectedCharacterSpawn") ? row.string("DeflectedCharacterSpawn") : null)
            .spawnConstPriority(row.bool("SpawnConstPriority"))
            .radiusY(row.intValue("RadiusY"))
            .projectileRadiusY(row.intValue("ProjectileRadiusY"))
            .projectileStartExtraRadius(row.intValue("ProjectileStartExtraRadius"))
            .pushbackAll(row.bool("PushbackAll"))
            .spawnProjectile(set(row, "SpawnProjectile") ? row.string("SpawnProjectile") : null)
            .onStartingAction(actionName(row, "OnStartingAction"))
            // The loader stores at least one link for a row that names a spawned projectile.
            .spawnChain(set(row, "SpawnProjectile") ? Math.max(row.intValue("SpawnChain"), 1) : 0)
            .chainIsNewProjectile(row.bool("ChainIsNewProjectile"))
            .constantHeight(row.intValue("ConstantHeight"))
            .targetBuff(set(row, "TargetBuff") ? row.string("TargetBuff") : null)
            .applyBuffBeforeDamage(row.bool("ApplyBuffBeforeDamage"))
            .applyBuffEvenIfImmuneToDamage(row.bool("ApplyBuffEvenIfImmuneToDamage"))
            .buffTimeMs(row.intValue("BuffTime"))
            .buffTimeIncreasePerLevel(row.intValue("BuffTimeIncreasePerLevel"))
            // The loader stores 1000 for an empty target limit.
            .maximumTargets(
                set(row, "MaximumTargets")
                    ? row.intValue("MaximumTargets")
                    : DEFAULT_MAXIMUM_TARGETS)
            .onlyOwnTroops(row.bool("OnlyOwnTroops"))
            .spawnCount(row.intValue("SpawnCount"))
            .spawnRadius(row.intValue("SpawnRadius"))
            .chainedHitRadius(row.intValue("ChainedHitRadius"))
            .chainedHitCount(row.intValue("ChainedHitCount"))
            .pingpongVisualTimeMs(row.intValue("PingpongVisualTime"))
            .randomDelayMs(row.intValue("RandomDelay"))
            .onHitTargetAction(inlineActionName(row, "OnHitTargetAction"))
            .onTargetReachedAction(actionName(row, "OnTargetReachedAction"))
            .spawnAreaEffectObject(
                set(row, "SpawnAreaEffectObject") ? row.string("SpawnAreaEffectObject") : null)
            .ignoreReflectedAttack(row.bool("IgnoreReflectedAttack"))
            .dragBackSpeed(row.intValue("DragBackSpeed"))
            .dragSelfSpeed(row.intValue("DragSelfSpeed"))
            .dragMargin(row.intValue("DragMargin"))
            .dragBackAsAttractor(row.bool("DragBackAsAttractor"))
            // The loader stores true for an empty column.
            .allowResetTarget(!row.has("AllowResetTarget") || row.bool("AllowResetTarget"))
            .deflectBehaviour(deflectBehaviour(row.string("DeflectBehaviour")))
            .deflectRadius(row.intValue("DeflectRadius"))
            .actionOnDeflector(
                set(row, "ActionOnDeflector") ? row.string("ActionOnDeflector") : null)
            .customDeflectAction(actionName(row, "CustomDeflectAction"))
            .useCustomMovement(row.bool("UseCustomMovement"))
            .spawnAxisY(row.bool("SpawnAxisY"))
            .initialCollisionCheckFilter(
                set(row, "InitialCollisionCheckFilter")
                    ? filter(row.string("InitialCollisionCheckFilter"))
                    : null)
            .build();
    List<String> unmodelled =
        new ArrayList<>(
            UNMODELLED_PROJECTILE_COLUMNS.stream().filter(column -> set(row, column)).toList());
    // The spawned area effect is refused with its row, and so is one that follows the projectile,
    // which is made on its first flight visit, not at its impact.
    if (data.spawnAreaEffectObject() != null) {
      AreaEffectData spawned = areaEffect(data.spawnAreaEffectObject());
      if (!spawned.unmodelledColumns().isEmpty() || spawned.followsParent()) {
        unmodelled.add("SpawnAreaEffectObject");
      }
    }
    // A deflected spawn without a spawned character: how many of it the impact makes is not
    // established. No shipped row has one.
    if (data.deflectedCharacterSpawn() != null && data.spawnCharacter() == null) {
      unmodelled.add("DeflectedCharacterSpawn");
    }
    // A hook's impacts carry its hooked flag, whose effect on a damage is not established: no
    // hooking row deals any.
    if (data.dragBackSpeed() >= 1 && data.damage() != 0) {
      unmodelled.add("Damage");
    }
    // The target buff is modelled on the circle or the one target of the impact; a projectile that
    // flies to a point buffs through its hits on the way instead, which is not.
    if (data.targetBuff() != null && data.homingLike()) {
      unmodelled.add(0, "TargetBuff");
    }
    // The height a projectile under the z-distance column steps toward is its target's for one
    // that homes, which no shipped row does; and one that flies to a point has no single aim
    // height to fall onto.
    if (data.considerZDistance() && (data.homing() || data.homingLike())) {
      unmodelled.add("ConsiderZDistance");
    }
    return data.toBuilder()
        .unmodelledColumns(
            withUnread(
                unmodelled,
                row,
                PRESENTATION_PROJECTILE_COLUMNS,
                INERT_PROJECTILE_COLUMNS,
                PENDING_PROJECTILE_COLUMNS))
        .build();
  }

  /**
   * A troop or building card's placement as the battle reads it, from the spells characters table
   * or the spells buildings table: the units it summons, built from their own rows, how many, its
   * formation and where it may be placed. A building card is played as a troop card is; its unit is
   * a buildings-table row, whose footprint the placement snaps and searches over.
   *
   * <p>A card that names no unit - no summoned character, no second group and no list - but a
   * projectile or an area effect is a spell, whichever table it is in: the Electro Wizard and the
   * Ice Wizard are cast, and their unit is made by their area effect's starting action. Deploying
   * as a spell changes nothing else for a card: it only moves a projectile spell's start and, for a
   * card with a unit and a projectile or an area effect, the search's snap, which no card sets
   * together and which is refused.
   *
   * <p>A card with a unit may cast a projectile as well, which the Mega Knight's does: the cast
   * launches it before the units are made, from the placed point less five times the king tower's
   * collision radius along the length, whichever side plays, at three times that radius, onto the
   * placed point. A card with a unit and an area effect is refused: no card has one.
   *
   * <p>A card with no count summons one. The level index a card may carry is not read, as the game
   * never reads it: the summoned units take the level the card is played at. A card may summon a
   * list of characters, each at an offset of its own, in place of its groups or after its first
   * group, as the evolved Skeleton Army's general follows its soldiers; one that lists them besides
   * a second group but no first is refused, as no card does. The card's deploy time of its own is
   * read nowhere on the placement's path, and is not read here.
   *
   * @param name the card row's name
   */
  public DeployCard card(String name) {
    GameRow row = cardRow(name);
    // The card's unit is its summoned character, else its second group, else its list; a card with
    // none of them that casts is a spell, whichever table it is in. A card with a unit keeps the
    // troop path, and its refusals, and casts its projectile besides.
    boolean namesUnit =
        set(row, "SummonCharacter")
            || set(row, "SummonCharacterSecond")
            || set(row, "SummonCharactersList");
    boolean casts = set(row, "Projectile") || set(row, "AreaEffectObject");
    if (!namesUnit && casts) {
      return spell(row);
    }
    // A card with a unit and an area effect makes the area effect at the placed point after its
    // units, as the cast makes them in that order. Deployed as a spell, as the wizards of a newer
    // data version are, its search does not snap to its unit; without that, or with a projectile
    // as well, it is in no row and is refused.
    boolean areaEffect = set(row, "AreaEffectObject");
    if (areaEffect && (!row.bool("SpellAsDeploy") || set(row, "Projectile"))) {
      throw new UnsupportedOperationException(
          name
              + " makes an area effect as well as a unit, without deploying as a spell or with a"
              + " projectile besides, which the cast does not model");
    }
    if (casts && !areaEffect && row.bool("SpellAsDeploy")) {
      throw new UnsupportedOperationException(
          name
              + " deploys as a spell with a unit and a projectile, which moves the projectile's"
              + " start and is not modelled");
    }
    List<DeployCard.Listed> listed = listed(row);
    boolean namesCharacter = !row.string("SummonCharacter").isEmpty();
    if (!listed.isEmpty() && !namesCharacter && set(row, "SummonCharacterSecond")) {
      throw new UnsupportedOperationException(
          name
              + " lists its characters besides a second group but no first, which no card does, not"
              + " modelled");
    }
    checkArgument(!listed.isEmpty() || namesCharacter, () -> name + " summons no character");
    String second = row.string("SummonCharacterSecond");
    // The card's first unit, which the map check and the search read: its summoned character, else
    // its list's first. A card that names both places its groups first and its list after them.
    UnitData summoned = namesCharacter ? unit(row.string("SummonCharacter")) : listed.get(0).unit();
    return new DeployCard(
        row.name(),
        summoned,
        Math.max(row.intValue("SummonNumber"), 1),
        second.isEmpty() ? null : unit(second),
        row.intValue("SummonCharacterSecondCount"),
        row.intValue("SummonRadius"),
        row.intValue("SummonWidth"),
        row.intValue("SummonDeployDelay"),
        row.intValue("SummonDeployDelaySecond"),
        row.bool("CanDeployOnEnemySide"),
        row.bool("CanPlaceOnBuildings"),
        row.bool("CanPlaceOnWater"),
        row.bool("FullLaneDeploy"),
        row.bool("TouchdownLimitedDeploy"),
        row.intValue("DeployWTileMargin"),
        row.intValue("DeployStartY"),
        row.intValue("DeployEndY"),
        set(row, "Projectile") ? row.string("Projectile") : null,
        areaEffect ? row.string("AreaEffectObject") : null,
        // A unit that tunnels and morphs as it surfaces is searched for as its morph.
        tunnelMorph(summoned),
        areaEffect,
        row.intValue("Radius"),
        row.intValue("MultipleProjectiles"),
        row.intValue("ProjectileWaves"),
        row.intValue("ProjectileWaveInterval"),
        row.intValue("ProjectileInterval"),
        listed,
        row.bool("CharactersOffsetsXMirrored"),
        row.bool("IsAGroup"),
        null,
        namesCharacter);
  }

  /**
   * The champion a champion slot finds for a card: its linked champion character when that is a
   * champion, which only a hero form names - the Goblins' names the banner its last goblin leaves -
   * else the champion the card summons ({@link DeployCard#champion()}); null for none.
   *
   * @param name the card row's name
   */
  public UnitData cardChampion(String name) {
    GameRow row = cardRow(name);
    if (set(row, "LinkedChampionCharacter")) {
      UnitData linked = unit(row.string("LinkedChampionCharacter"));
      if (linked.champion()) {
        return linked;
      }
    }
    return card(name).champion();
  }

  /**
   * The characters a card lists, each with its offsets at its index in the two offset lists, which
   * the placement reads unchecked: a list longer than either is refused. Each waits before it
   * deploys the entry of the card's delay list at its index, or the list's last entry past its end;
   * a card without the list gives none.
   */
  private List<DeployCard.Listed> listed(GameRow row) {
    List<String> names = row.strings("SummonCharactersList");
    List<Integer> xs = row.ints("SummonCharactersOffsetsX");
    List<Integer> ys = row.ints("SummonCharactersOffsetsY");
    if (xs.size() < names.size() || ys.size() < names.size()) {
      throw new UnsupportedOperationException(
          row.name() + " lists more characters than offsets, which the placement reads past");
    }
    List<Integer> delays = row.ints("SummonCharactersDelayList");
    List<DeployCard.Listed> listed = new ArrayList<>();
    for (int j = 0; j < names.size(); j++) {
      int delay = delays.isEmpty() ? 0 : delays.get(Math.min(j, delays.size() - 1));
      listed.add(new DeployCard.Listed(unit(names.get(j)), xs.get(j), ys.get(j), delay));
    }
    return listed;
  }

  /** The row a unit that tunnels morphs into as it surfaces, or null for none. */
  private UnitData tunnelMorph(UnitData unit) {
    return unit.spawnPathfindMorph() == null ? null : unit(unit.spawnPathfindMorph());
  }

  /**
   * A spell card: no unit, the projectile it casts or the area effect it creates, and the unit its
   * placement is searched for - its projectile's spawned character, which the Goblin Barrel has.
   * The offsets a spell card may list for its characters are not read by the cast.
   */
  private DeployCard spell(GameRow row) {
    for (String column : UNMODELLED_SPELL_COLUMNS) {
      if (set(row, column)) {
        throw new UnsupportedOperationException(
            row.name() + " sets " + column + ", which the spell's cast does not model");
      }
    }
    String projectile = set(row, "Projectile") ? row.string("Projectile") : null;
    String areaEffect = set(row, "AreaEffectObject") ? row.string("AreaEffectObject") : null;
    UnitData searchUnit = null;
    if (projectile != null) {
      GameRow projectileRow = tables.table(PROJECTILES).row(projectile);
      if (set(projectileRow, "SpawnCharacter")) {
        searchUnit = unit(projectileRow.string("SpawnCharacter"));
        UnitData morph = tunnelMorph(searchUnit);
        if (morph != null) {
          searchUnit = morph;
        }
      }
    }
    return new DeployCard(
        row.name(),
        null,
        0,
        null,
        0,
        0,
        0,
        0,
        0,
        row.bool("CanDeployOnEnemySide"),
        row.bool("CanPlaceOnBuildings"),
        row.bool("CanPlaceOnWater"),
        row.bool("FullLaneDeploy"),
        row.bool("TouchdownLimitedDeploy"),
        row.intValue("DeployWTileMargin"),
        row.intValue("DeployStartY"),
        row.intValue("DeployEndY"),
        projectile,
        areaEffect,
        searchUnit,
        row.bool("SpellAsDeploy"),
        row.intValue("Radius"),
        row.intValue("MultipleProjectiles"),
        row.intValue("ProjectileWaves"),
        row.intValue("ProjectileWaveInterval"),
        row.intValue("ProjectileInterval"),
        List.of(),
        false,
        false,
        set(row, "OnExecuteAction") ? row.string("OnExecuteAction") : null,
        false);
  }

  /** True when a row sets a column: a value that is not empty, false, 0 or an empty list. */
  private static boolean set(GameRow row, String column) {
    JsonNode value = row.value(column);
    if (value == null || value.isNull()) {
      return false;
    }
    if (value.isTextual()) {
      return !value.asText().isEmpty();
    }
    if (value.isBoolean()) {
      return value.asBoolean();
    }
    if (value.isNumber()) {
      return value.asDouble() != 0;
    }
    return !value.isEmpty();
  }

  /**
   * The action row a unit's OnStartingAction names; null for none. One written inline as a bare
   * ActionBerserk, as the Berserker's is, as a charge counter (ActionBurstAttack), as the Dagger
   * Duchess's is, or as a spawn of an area effect and nothing more, as Goblinstein's doctor's is,
   * or as a group of named rows, as the Royal Chef's king tower's is, is the actions table's row
   * named after the unit and the column. Any other inline row is refused.
   */
  private String startingActionName(GameRow row) {
    JsonNode value = row.value("OnStartingAction");
    boolean berserk =
        value != null
            && value.isObject()
            && value.size() == 1
            && value.path("ClassType").asText().equals("ActionBerserk");
    boolean areaEffect =
        value != null
            && value.isObject()
            && value.size() == 3
            && value.path("ClassType").asText().equals("ActionSpawn")
            && value.path("SpawnType").asText().equals("AreaEffectType")
            && value.path("SpawnData").isTextual();
    boolean burstAttack =
        value != null
            && value.isObject()
            && value.path("ClassType").asText().equals("ActionBurstAttack");
    boolean namedGroup =
        value != null
            && value.isObject()
            && value.path("ClassType").asText().equals("ActionGroup")
            && namesOnly(value.path("SubActions"));
    return berserk || burstAttack || areaEffect || namedGroup
        ? inlineActionName(row, "OnStartingAction")
        : actionName(row, "OnStartingAction");
  }

  /**
   * Whether every element of a group's sub-actions names an action row, none written inline: such a
   * group is the actions table's row named after its unit and column, sub-actions and all.
   */
  private static boolean namesOnly(JsonNode subActions) {
    if (!subActions.isArray() || subActions.isEmpty()) {
      return false;
    }
    for (JsonNode sub : subActions) {
      if (!sub.isTextual()
          && !(sub.isObject() && sub.size() == 1 && sub.path("action").isTextual())) {
        return false;
      }
    }
    return true;
  }

  /**
   * The action row a hook column names, as a reference or by its name; null for none. A hook
   * written as an inline row of its own, with no name to build it by, is refused, so it is never
   * read as no hook at all.
   */
  private static String actionName(GameRow row, String column) {
    return actionName(row.name(), column, row.value(column));
  }

  /**
   * The action row a column's value names, as a reference or by its name; null for none. A value
   * written as an inline row of its own is refused.
   *
   * @param owner the row the value belongs to, for the refusal
   * @param column the column, for the refusal
   * @param value the column's value, or null
   */
  private static String actionName(String owner, String column, JsonNode value) {
    if (value == null || value.isNull() || value.isMissingNode()) {
      return null;
    }
    if (value.isObject() && !value.has("action")) {
      throw new UnsupportedOperationException(
          owner
              + " writes its "
              + column
              + " inline, as "
              + value.path("ClassType").asText("an unnamed row")
              + ", which is not modelled");
    }
    String name = value.isObject() ? value.path("action").asText("") : value.asText();
    return name.isEmpty() ? null : name;
  }

  /** The row of a unit: the characters table's, else the buildings table's. */
  /**
   * The global id of the character or building row of that name, as an expression names it: the
   * characters are asked first. Null for a name that is neither.
   *
   * @param name the row's name, matched exactly
   */
  public Integer unitGlobalId(String name) {
    for (String table : new String[] {CHARACTERS, BUILDINGS}) {
      GameTable rows = tables.table(table);
      if (rows.has(name)) {
        return rows.row(name).globalId();
      }
    }
    return null;
  }

  private GameRow unitRow(String name) {
    for (String table : new String[] {CHARACTERS, BUILDINGS}) {
      GameTable rows = tables.table(table);
      if (rows.has(name)) {
        return rows.row(name);
      }
    }
    throw new IllegalArgumentException("the game tables have no unit " + name);
  }

  /** The published rarity row of that name; a row without a rarity is scaled as Common. */
  private static RarityTable rarity(String name) {
    if (name.isEmpty()) {
      return RarityTable.COMMON;
    }
    for (RarityTable rarity : RarityTable.PUBLISHED) {
      if (rarity.name().equals(name)) {
        return rarity;
      }
    }
    throw new IllegalArgumentException("no published rarity " + name);
  }

  /** The scaling rule a damage scaling mode names: the two tower rules, else the card rule. */
  private static ScalingMode damageMode(String mode) {
    return switch (mode) {
      case "KingTower" -> ScalingMode.KING_DAMAGE;
      case "PrincessTower" -> ScalingMode.TOWER_DAMAGE;
      default -> ScalingMode.CARD_DAMAGE;
    };
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
    for (JsonNode element : arrayOf(row, "ElixirNotifyChange")) {
      notify.add(row.boolElement("ElixirNotifyChange", element));
    }
    return new BattleTimeline(
        row.name(),
        row.intValue("StartingElixir"),
        ints(row, "SectionLength"),
        types,
        ints(row, "SectionFlags"),
        ints(row, "ElixirRateLength"),
        ints(row, "ElixirFullBarMS"),
        ints(row, "ElixirRateVisible"),
        notify,
        ints(row, "NextSpellCooldownLength"),
        ints(row, "NextSpellCooldownMS"),
        ints(row, "EventTime"));
  }

  /**
   * What the match reads of a card: its cost, its two opening-hand columns, its production stop,
   * whether it is the Mirror, the options a variant card is played as, and the rows it is played as
   * in other forms: each its own match card, with its form and DarkElixirCost. A row's form is its
   * card form column, else the evolved form for a row of the evolved cards, the hero form for a row
   * of the hero forms, else the basic form. The card is looked up in the card tables in turn.
   *
   * @param name the card row's name
   */
  public MatchCard matchCard(String name) {
    String table = cardTable(name);
    GameRow row = tables.table(table).row(name);
    int form;
    if (set(row, "CardForm")) {
      Integer named = CARD_FORMS.get(row.string("CardForm"));
      checkArgument(named != null, () -> name + " has the card form " + row.string("CardForm"));
      form = named;
    } else if (table.equals(SPELLS_EVOLVED)) {
      form = MatchCard.EVO_FORM;
    } else if (table.equals(SPELLS_HERO_FORM)) {
      form = MatchCard.HERO_FORM;
    } else {
      form = MatchCard.BASIC_FORM;
    }
    // The list is a single name on a row with one other form.
    List<MatchCard> evolved = new ArrayList<>();
    JsonNode listed = row.value("EvolvedSpells");
    if (listed != null && listed.isArray()) {
      for (JsonNode element : listed) {
        evolved.add(matchCard(row.textElement("EvolvedSpells", element)));
      }
    } else if (!row.string("EvolvedSpells").isEmpty()) {
      evolved.add(matchCard(row.string("EvolvedSpells")));
    }
    return new MatchCard(
        row.name(),
        row.intValue("ManaCost"),
        row.bool("ForceToStartingHand"),
        row.bool("OmitFromStartingHand"),
        row.intValue("ElixirProductionStopTime"),
        row.bool("Mirror"),
        variant(row),
        row.intValue("DarkElixirCost"),
        form,
        evolved);
  }

  /**
   * Whether a card is a troop card: a row of the characters' cards.
   *
   * @param name the card row's name
   */
  public boolean troopCard(String name) {
    return tables.table(SPELLS_CHARACTERS).has(name);
  }

  /**
   * Whether a card is a spell card: a row of the other spells' cards.
   *
   * @param name the card row's name
   */
  public boolean spellCard(String name) {
    return tables.table(SPELLS_OTHER).has(name);
  }

  /**
   * Whether a card is a spell or building card: a row of the other spells' or the buildings' cards.
   *
   * @param name the card row's name
   */
  public boolean spellOrBuildingCard(String name) {
    return tables.table(SPELLS_OTHER).has(name) || tables.table(SPELLS_BUILDINGS).has(name);
  }

  /**
   * The mass a unit's or building's row is loaded at. A row that writes no mass - every building,
   * the crown towers among them - is given one worked out from its collision radius as the row is
   * loaded: the radius squared over 250, truncated, times the radius, over 62500, truncated. Every
   * mass, written or worked out, is then held to at most 20 and at least 1. A princess tower, of
   * radius 1000, is loaded at 20; a building of radius 500 at 8.
   *
   * @param mass the row's Mass column
   * @param collisionRadius the row's CollisionRadius column
   * @return the mass the battle reads
   */
  static int loadedMass(int mass, int collisionRadius) {
    int loaded = mass;
    if (loaded == 0) {
      int squareOver250 = Integer.divideUnsigned(collisionRadius * collisionRadius, 250);
      loaded = squareOver250 * collisionRadius / 62500;
    }
    return Math.max(Math.min(loaded, 20), 1);
  }

  /**
   * The speed a unit's row is loaded at. A row with a walk time - a unit that walks for
   * StopMovementAfterMS and then stands for WaitMS, over and over - has its Speed column raised as
   * the row is loaded, so that the time it stands costs it nothing: the walk and the wait together
   * over the walk, as a ratio in thousandths, truncated, times the column, over a thousand,
   * truncated again. A Giant's 45, walking 640 ms and waiting 100, is loaded as 52. A row without a
   * walk time keeps its column, and its wait is not read.
   *
   * @param speed the row's Speed column
   * @param stopMovementAfterMs the row's StopMovementAfterMS column
   * @param waitMs the row's WaitMS column
   * @return the speed the battle reads
   */
  static int loadedSpeed(int speed, int stopMovementAfterMs, int waitMs) {
    if (stopMovementAfterMs < 1) {
      return speed;
    }
    int ratio = (waitMs + stopMovementAfterMs) * 1000 / stopMovementAfterMs;
    return ratio * speed / 1000;
  }

  /** A card's row, looked up in the card tables in turn. */
  private GameRow cardRow(String name) {
    return tables.table(cardTable(name)).row(name);
  }

  /** The first card table that has a card. */
  private String cardTable(String name) {
    for (String table : CARD_TABLES) {
      if (tables.table(table).has(name)) {
        return table;
      }
    }
    throw new IllegalArgumentException("the game tables have no card " + name);
  }

  /**
   * The options a card is played as when its custom class makes it a variant, or null for any other
   * card. The options keep their order; each one's trigger is stored ten times over, in
   * ten-thousandths of an elixir, and its cost and production stop are its own row's. An option row
   * that is the Mirror or takes its cost from the king's elixir would cost otherwise, and is
   * refused, as is any other custom class.
   */
  private SpellVariant variant(GameRow row) {
    String type = row.string("CustomClassType");
    if (type.isEmpty()) {
      return null;
    }
    if (!type.equals(SPELL_VARIANT_CLASS)) {
      throw new UnsupportedOperationException(
          row.name() + " is of the class " + type + ", which a match does not model");
    }
    List<SpellVariant.Option> options = new ArrayList<>();
    for (JsonNode listed : arrayOf(row, "Options")) {
      JsonNode option = row.tableElement("Options", listed);
      checkArgument(
          option.hasNonNull("SpellData"), () -> row.name() + " has an option with no card");
      GameRow spell = cardRow(row.textField("Options", option, "SpellData"));
      if (spell.bool("Mirror") || spell.bool("ManaCostFromSummonerMana")) {
        throw new UnsupportedOperationException(
            row.name()
                + "'s option "
                + spell.name()
                + " costs other than its own cost, which no option does");
      }
      options.add(
          new SpellVariant.Option(
              spell.name(),
              row.intField("Options", option, "AvailableManaTrigger", 0) * TRIGGER_SCALE,
              row.intField("Options", option, "PrecastPendingTime", 0),
              spell.intValue("ManaCost"),
              spell.intValue("ElixirProductionStopTime")));
    }
    return new SpellVariant(row.bool("UseProjectedTimeSummon"), options);
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

  /** An array column of whole numbers; an empty list for a column left out. */
  private static List<Integer> ints(GameRow row, String column) {
    List<Integer> out = new ArrayList<>();
    for (JsonNode element : arrayOf(row, column)) {
      out.add(row.intElement(column, element));
    }
    return out;
  }

  /**
   * An array column's elements; none for a column left out or an empty cell. A column of one value
   * of another shape is refused.
   */
  private static List<JsonNode> arrayOf(GameRow row, String column) {
    List<JsonNode> out = new ArrayList<>();
    JsonNode value = row.value(column);
    if (value != null && value.isArray()) {
      value.forEach(out::add);
    } else if (value != null && !(value.isTextual() && value.asText().isEmpty())) {
      // A column of one value where a list is read: refused, naming its shape.
      row.strings(column);
    }
    return out;
  }
}
