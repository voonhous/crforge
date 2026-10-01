package org.crforge.core.battle.data;

import static org.crforge.core.util.ValidationUtils.checkArgument;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.battle.match.BattleTimeline;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.unit.AbilityData;
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
  private static final String GAME_MODES = "game_modes";
  private static final String BATTLE_TIMELINES = "battle_timelines";
  private static final String GLOBALS = "globals";
  private static final String LOCATIONS = "locations";

  /** The section types of a battle timeline by name; the order is their number. */
  private static final List<String> SECTION_TYPES = List.of("Normal", "Overtime", "BonusTime");

  /**
   * The columns of a spell card the cast does not model yet: a Mirror, a first projectile of its
   * own, a spell deployed as a thrown projectile, and the play variants no reference holds. A spell
   * that sets one is refused.
   */
  private static final List<String> UNMODELLED_SPELL_COLUMNS =
      List.of("Mirror", "CustomFirstProjectile", "CustomClassType", "UseProjectedTimeSummon");

  /**
   * The columns of a projectile the impact does not model: the action on reaching its target,
   * spawned projectiles laid along an axis, and the push's floor and a push along the flight. A
   * spell whose projectile, or the projectile that one spawns, sets one is refused as it is cast or
   * spawned, and a unit's shot as it is fired. The area effect it spawns is modelled unless its row
   * is refused.
   */
  private static final List<String> UNMODELLED_PROJECTILE_COLUMNS =
      List.of(
          "OnTargetReachedAction",
          "SpawnAxisX",
          "SpawnAxisY",
          "MinPushback",
          "DoDirectionalPushback");

  /** The target limit the loader stores for a projectile row that leaves it empty. */
  private static final int DEFAULT_MAXIMUM_TARGETS = 1000;

  private static final String CHARACTER_ABILITIES = "character_abilities";
  private static final String GAME_OBJECT_FILTERS = "game_object_filters";
  private static final String AREA_EFFECT_OBJECTS = "area_effect_objects";
  private static final String CHARACTER_BUFFS = "character_buffs";

  /** The columns of a buff the battle reads. */
  private static final Set<String> MODELLED_BUFF_COLUMNS =
      Set.of(
          "Name",
          "Rarity",
          "Invisible",
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
          // Read only by the apply, to keep the buff off a unit's riders; a buff on a rider or on
          // a unit that carries riders is refused as it is applied.
          "Clone");

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
   * area effect is created. A buff that boosts one target or lasts longer by level, the hit action
   * on itself, the shape, the filter, the spawns, the life condition, the following, the tags, the
   * deflection, the per-level lifetime and the push's floor and gate lift. Its projectile is
   * modelled, but not a launch from its source or a spread one; its hit action only for a Clone.
   */
  private static final List<String> UNMODELLED_AREA_EFFECT_COLUMNS =
      List.of(
          "Boost",
          "BuffTimeIncreasePerLevel",
          "BuffTimeIncreaseAfterTournamentCap",
          "OnHitSelfAction",
          "Shape",
          "Filter",
          "SpawnCharacter",
          "AliveIfTrue",
          "FollowBehaviour",
          "Tags",
          "DeflectProjectilesEnabled",
          "LifeDurationIncreasePerLevel",
          "LifeDurationIncreaseAfterTournamentCap",
          "MinPushback",
          "PushbackAll",
          "OneHitPerTarget");

  private static final String GAME_TAGS = "game_tags";

  /**
   * The columns of what a unit does as it dies that the battle does not model: a unit whose row
   * sets one is refused when it dies. The elixir a death gives is not among them: the death handler
   * pays a player's unit's ManaOnDeathForOpponent to the side that killed it in a match, and pays
   * ManaOnDeath only for a neutral object, which the battle has none of.
   */
  private static final List<String> UNMODELLED_DEATH_COLUMNS =
      List.of(
          "DeathSpawnCharacter2", "DeathSpawnCharacter3", "StartingBuff", "DeathSpawnIsSameUnit");

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
   * refused as it is created. A shield, hiding before the first hit, a buff at a share of its hit
   * points, the action a completed charge runs, a chained dash, a dash's contact damage, fixed
   * distance, area effect and closing action, a limit on the elixir a collector makes, a spawner's
   * launches, and its second and third characters.
   */
  private static final List<String> UNMODELLED_UNIT_COLUMNS =
      List.of(
          "ShieldDiePushback",
          "ShieldLostAction",
          "HideBeforeFirstHit",
          "BuffOnXHP",
          "OnStartChargingAction",
          "DashCount",
          "DashingDamage",
          "DashDistance",
          "AreaEffectOnDash",
          "OnAfterDashAction",
          "ManaGenerateLimit",
          "SpawnProjectile",
          "SpawnCharacter2",
          "SpawnCharacter3");

  /**
   * The columns of a spawner the battle does not model, refused only for a unit whose spawner makes
   * characters: its push on its children, and a fixed priority for them, which is held only for a
   * death spawn.
   */
  private static final List<String> UNMODELLED_SPAWNER_COLUMNS =
      List.of("SpawnPushback", "SpawnConstPriority");

  /**
   * The tags a unit's own row may set: those of the Phoenix's egg, each read where the battle reads
   * the tag word. A row that sets any other is refused as the unit is created.
   */
  private static final Set<String> MODELLED_ROW_TAGS =
      Set.of("NO_GIANTBUFFER_CHEF_ENCHANTMENT", "AVOIDANCE_AS_OBSTACLE", "NO_MOVE_ALLOW_ATTRACT");

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
          "IngamePathfindStopDeployBaseAnim",
          "IngamePathfindStopEffect",
          "KamikazeEffect",
          "LandingEffect",
          "LoopMoveEffect",
          "MoveEffect",
          "NewHealthBarOffsetXBlue",
          "NewHealthBarOffsetXRed",
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
          // Read only by the deflection, which finds nothing: no object the battle builds deflects.
          "GroupProjectiles",
          // A later entry's columns load into no entry without an order or VariableDamageTime1; a
          // row that builds its entries reads them.
          "VariableDamage2",
          "VariableDamage3",
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
          "DontStopMoveAnim",
          "AttackShakeTime",
          "LoopingFilter",
          "CustomSpawnFilter",
          "CustomCloneFilter",
          // No battle logic reads it: an object of the view only, of which no entity is made, and
          // a building fires its own shots.
          "AttachedCharacter",
          "AttachedCharacterHeight",
          // Stored and never read.
          "TurretMovement",
          // Times only the attack's turn toward its target and a call of the view, 150 ms before
          // the period's boundary for the Bats; the turn is modelled for no unit, and no hit, timer
          // or readiness reads it.
          "AttackDashTime",
          // Read only by the soul count of a unit whose ability resurrects, which only that
          // ability spends, and a card play never requests it.
          "IgnoreResurrect",
          // Read only in the in-game pathfinding state, which only an ability's lane switch
          // enters, and that switch is refused.
          "IngamePathfindSpeed");

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
          "AlwaysResetAnimation",
          "DeathEffect",
          "DragEffect",
          "ExportName",
          "FileName",
          "HitEffect",
          "HitSoundWhenParentAlive",
          "PingpongDeathEffect",
          "PrestigeExportName",
          "PrestigeExportName2",
          "PrestigeExportName3",
          "PrestigeRedExportName2",
          "PrestigeRedExportName3",
          "PrestigeSWF",
          "RedExportName",
          "Scale",
          "ShadowDisableRotate",
          "ShadowExportName",
          "ShakesShooter",
          "ShakesTargets",
          "SpawnDeployBaseAnim",
          "TargettedEffect",
          "TrailEffect");

  /** The columns of a projectile's row the record shows no battle logic reads to any effect. */
  private static final Set<String> INERT_PROJECTILE_COLUMNS =
      Set.of(
          "Name",
          "Base",
          // Nothing in the battle logic reads it; carried as presentation.
          "PingpongMovingShooter",
          // Read only by the deflection, which finds nothing: no object the battle builds deflects.
          "DeflectBehaviour",
          "DeflectRadius",
          "ActionOnDeflector",
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
          "DeflectedProjectileEffect",
          "DeflectionFBEffect",
          "LoopingEffect",
          "OneShotEffect",
          "ScaledEffect",
          "ScaledEffectFollowAeO",
          "SpawnDeployBaseAnim",
          "SpawnEffect");

  /**
   * The columns of an area effect's row the record shows no battle logic reads. BuffNumber is
   * declared by the table and never looked up.
   */
  private static final Set<String> INERT_AREA_EFFECT_COLUMNS = Set.of("Name", "Base", "BuffNumber");

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
            .speed(row.intValue("Speed"))
            .range(row.intValue("Range"))
            .sightRange(row.intValue("SightRange"))
            .collisionRadius(row.intValue("CollisionRadius"))
            .mass(row.intValue("Mass"))
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
            .spawnRadius(row.intValue("SpawnRadius"))
            .spawnAngleShift(row.intValue("SpawnAngleShift"))
            .flyingHeight(row.intValue("FlyingHeight"))
            .flyDirectPaths(row.bool("FlyDirectPaths"))
            .spawnPathfindSpeed(row.intValue("SpawnPathfindSpeed"))
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
            .onStartingAction(actionName(row, "OnStartingAction"))
            .onDeathAction(actionName(row, "OnDeathAction"))
            .onKilledAction(actionName(row, "OnKilledAction"))
            .deathDamage(row.intValue("DeathDamage"))
            .deathDamageRadius(row.intValue("DeathDamageRadius"))
            .deathPushBack(row.intValue("DeathPushBack"))
            .deathSpawnCharacter(deathSpawn.isEmpty() ? null : deathSpawn)
            // The loader keeps at least one for a row that spawns or launches on its death.
            .deathSpawnCount(
                deathSpawn.isEmpty() && deathProjectile.isEmpty()
                    ? 0
                    : Math.max(row.intValue("DeathSpawnCount"), 1))
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
            .minimumRange(row.intValue("MinimumRange"))
            .sightClip(sightClip(row))
            .sightClipSide(row.intValue("SightClipSide"))
            .loadFirstHit(row.bool("LoadFirstHit"))
            .spawnCharacter(
                row.string("SpawnCharacter").isEmpty() ? null : row.string("SpawnCharacter"))
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
            .allowAreaDamageWhenInvisible(row.bool("AllowAreaDmgWhenInvisible"))
            .areaEffectOnHit(set(row, "AreaEffectOnHit") ? row.string("AreaEffectOnHit") : null)
            // The loader's default is true: only a row that sets it false lets its target go.
            .keepTargetWithPendingDamage(
                !row.has("KeepTargetWithPendingDamage") || row.bool("KeepTargetWithPendingDamage"))
            .hidesWhenNotAttacking(row.bool("HidesWhenNotAttacking"))
            .hideTimeMs(row.intValue("HideTimeMs"))
            .upTimeMs(row.intValue("UpTimeMs"))
            .ignoreClone(row.bool("IgnoreClone"))
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
            .unmodelledColumns(unmodelledColumns(row))
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
      return List.of(value.asInt());
    }
    List<Integer> out = new ArrayList<>();
    for (JsonNode element : value) {
      out.add(element.asInt());
    }
    return List.copyOf(out);
  }

  private static List<String> unmodelledColumns(GameRow row) {
    List<String> columns = new ArrayList<>();
    for (String column : UNMODELLED_UNIT_COLUMNS) {
      if (sets(row, column)) {
        columns.add(column);
      }
    }
    // The special attack is modelled in one shape: a troop that loads it in its ring and fires its
    // special projectile. A special projectile without a ring is another kind of special, a ring
    // without a projectile a special direct hit, and a building's special one whose owner has no
    // movement component. A special that lists its targets, which the ring would not re-arm on, is
    // refused as a column nothing reads.
    boolean ring = sets(row, "SpecialRange");
    boolean special = sets(row, "ProjectileSpecial");
    if (special && !ring) {
      columns.add("ProjectileSpecial");
    }
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
    }
    for (String tag : row.string("GameTagsToSet").split(",")) {
      if (!tag.isBlank() && !MODELLED_ROW_TAGS.contains(tag.trim())) {
        columns.add("GameTagsToSet");
        break;
      }
    }
    return columns;
  }

  /**
   * A game object filter as the battle reads it, from the game object filters table. Every column
   * is its field of the same name; the dead are filtered unless the row says not; the tags it
   * excludes, written as names separated by commas, are the bits the game tags table gives them;
   * the text the game shows for it is not read.
   *
   * @param name the row's name
   */
  public GameObjectFilter filter(String name) {
    GameTable table = tables.table(GAME_OBJECT_FILTERS);
    checkArgument(table.has(name), () -> "the game tables have no game object filter " + name);
    GameRow row = table.row(name);
    return GameObjectFilter.builder()
        .matchTeamOwn(row.bool("MatchTeamOwn"))
        .matchTeamEnemy(row.bool("MatchTeamEnemy"))
        .matchTypeCharacters(row.bool("MatchTypeCharacters"))
        .matchTypeBuildings(row.bool("MatchTypeBuildings"))
        .matchTypeProjectiles(row.bool("MatchTypeProjectiles"))
        .matchTypeAoe(row.bool("MatchTypeAoe"))
        .matchTypeGoblinRef(row.bool("MatchTypeGoblinRef"))
        .matchTowers(row.bool("MatchTowers"))
        .filterHidden(row.bool("FilterHidden"))
        .filterInvisible(row.bool("FilterInvisible"))
        .filterUnderground(row.bool("FilterUnderground"))
        .filterBuildings(row.bool("FilterBuildings"))
        .filterTowers(row.bool("FilterTowers"))
        .filterSummoner(row.bool("FilterSummoner"))
        .filterFlying(row.bool("FilterFlying"))
        .filterJumping(row.bool("FilterJumping"))
        .filterDashImmune(row.bool("FilterDashImmune"))
        .filterDragging(row.bool("FilterDragging"))
        .filterCloning(row.bool("FilterCloning"))
        .filterIfNoHitpointComponent(row.bool("FilterIfNoHitpointComponent"))
        .filterPushbackIgnore(row.bool("FilterPushbackIgnore"))
        .matchAttachedChildren(row.bool("MatchAttachedChildren"))
        .filterSameObjects(row.bool("FilterSameObjects"))
        .filterTags(tagBits(row.string("FilterTags")))
        .filterPrincessTowers(row.bool("FilterPrincessTowers"))
        .filterDead(!row.has("FilterDead") || row.bool("FilterDead"))
        .filterClones(row.bool("FilterClones"))
        .includeCharactersWithData(Set.copyOf(row.strings("IncludeCharactersWithData")))
        .excludeCharactersWithData(Set.copyOf(row.strings("ExcludeCharactersWithData")))
        .build();
  }

  /** The bits of game tags written as names separated by commas; none for an empty text. */
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
    List<Integer> order = new ArrayList<>();
    JsonNode orderColumn = row.value("AttackSequence");
    if (orderColumn != null && orderColumn.isArray()) {
      orderColumn.forEach(element -> order.add(element.asInt()));
    }
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
            row.intValue("MeleePushback")));
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
        row.intValue("MeleePushback" + n));
  }

  /** An entry of the four columns the row's own and numbered entries carry, the rest defaults. */
  private AttackSequence.Entry entry(
      int damage, String projectile, int variableDamageTime, int meleePushback) {
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
        null);
  }

  /** An entry of an AttackSequenceList element, with the entry columns' defaults. */
  private AttackSequence.Entry listEntry(GameRow row, JsonNode element) {
    String projectile = element.path("Projectile").asText("");
    return new AttackSequence.Entry(
        element.path("Damage").asInt(0),
        projectile.isEmpty() ? null : projectile(projectile),
        element.path("VariableDamageTime").asInt(0),
        element.path("HitSpeedMultiplier").asInt(100),
        element.path("CustomRange").asInt(-1),
        element.path("CustomSightRange").asInt(-1),
        element.path("CustomMinimunRange").asInt(-1),
        element.path("CustomProjectileStartZ").asInt(-1),
        element.path("CustomProjectileStartRadius").asInt(-1),
        element.path("MeleePushback").asInt(0),
        actionName(row.name(), "DoAttackAction", element.path("DoAttackAction")));
  }

  /** The columns of a row's death that are not modelled, those of its death spawn only with one. */
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
    return columns;
  }

  /**
   * An area effect as the battle reads it, from the area effect objects table.
   *
   * @param name the row's name
   */
  public AreaEffectData areaEffect(String name) {
    GameTable table = tables.table(AREA_EFFECT_OBJECTS);
    checkArgument(table.has(name), () -> "the game tables have no area effect " + name);
    GameRow row = table.row(name).tracking();
    List<String> unmodelled = new ArrayList<>();
    for (String column : UNMODELLED_AREA_EFFECT_COLUMNS) {
      if (sets(row, column)) {
        unmodelled.add(column);
      }
    }
    AreaEffectData data =
        AreaEffectData.builder()
            .name(row.name())
            .rarity(rarity(row.string("Rarity")))
            .lifeDurationMs(row.intValue("LifeDuration"))
            .radius(row.intValue("Radius"))
            .maxRadius(row.intValue("MaxRadius"))
            .hitSpeedMs(row.intValue("HitSpeed"))
            .hitSpeedOffsetMs(row.intValue("HitSpeedOffset"))
            .damage(row.intValue("Damage"))
            .crownTowerDamagePercent(row.intValue("CrownTowerDamagePercent"))
            .hitsAir(row.bool("HitsAir"))
            .hitsGround(row.bool("HitsGround"))
            .onlyEnemies(row.bool("OnlyEnemies"))
            .ignoreBuildings(row.bool("IgnoreBuildings"))
            .affectsHidden(row.bool("AffectsHidden"))
            .controlsBuff(row.bool("ControlsBuff"))
            .pushback(row.intValue("Pushback"))
            .maximumTargets(row.intValue("MaximumTargets"))
            .sharedDamage(row.bool("SharedDamage"))
            .onStartingAction(actionName(row, "OnStartingAction"))
            .onLifeTimeEndAction(actionName(row, "OnLifeTimeEndAction"))
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
            .cloning(row.bool("Clone"))
            .onHitAction(actionName(row, "OnHitAction"))
            .unmodelledColumns(unmodelled)
            .build();
    // The hit action is modelled for a Clone alone: a Clone row whose hit action clones, and which
    // neither deals damage nor applies a buff, as the shipped Clone does.
    boolean cloning =
        data.onHitAction() != null
            && tables.action(data.onHitAction()).classType().equals("ActionClone");
    if (data.onHitAction() != null && !(data.cloning() && cloning)) {
      unmodelled.add("OnHitAction");
    }
    if (data.cloning() && (!cloning || data.damage() != 0 || data.buff() != null)) {
      unmodelled.add("Clone");
    }
    if (data.projectile() != null) {
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
    // SpawnInitialDelay and SpawnTime are read only by the character spawner, which a row without a
    // SpawnCharacter never enters, and by the encoding of the spawner's order list: on such a row,
    // the Royal Delivery's, they change nothing.
    if (!sets(row, "SpawnCharacter")) {
      row.has("SpawnInitialDelay");
      row.has("SpawnTime");
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
   * A character buff as the battle reads it, from the character buffs table. Every column it sets
   * that is neither read nor only shows something is listed as not modelled.
   *
   * @param name the row's name
   */
  public BuffData buff(String name) {
    GameTable table = tables.table(CHARACTER_BUFFS);
    checkArgument(table.has(name), () -> "the game tables have no buff " + name);
    GameRow row = table.row(name);
    List<String> unmodelled = new ArrayList<>();
    for (String column : row.columns().keySet()) {
      if (!MODELLED_BUFF_COLUMNS.contains(column)
          && !PRESENTATION_BUFF_COLUMNS.contains(column)
          && sets(row, column)) {
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
        .healPerSecond(row.intValue("HealPerSecond"))
        .allowedOverHealPercent(row.intValue("AllowedOverHealPerc"))
        .unmodelledColumns(unmodelled)
        .build();
  }

  /** True when a row sets a column: a value other than empty, 0 or false. */
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
    if (value.isArray()) {
      return !value.isEmpty();
    }
    return value.asInt() != 0;
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
   * The ability columns that make an ability do more than run its activation action - its dash,
   * buff, area object, lane switch, morph, spawn and follow-up state - or keep a buff on a unit
   * waiting to cast; the rest are the champion controller's or presentation, which a request never
   * reads.
   */
  private static final List<String> UNMODELLED_ABILITY_COLUMNS =
      List.of(
          "DashRange",
          "Buff",
          "AreaEffectObject",
          "SwitchLanes",
          "MorphTarget",
          "ActivationSpawnCharacter",
          "AbilityStateDuration",
          "PendingBuff");

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
        .unmodelledColumns(
            UNMODELLED_ABILITY_COLUMNS.stream().filter(column -> set(ability, column)).toList())
        .build();
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
            .minDistance(row.intValue("MinDistance"))
            .circleScatter("Circle".equals(row.string("Scatter")))
            .lineScatter("Line".equals(row.string("Scatter")))
            .pushback(row.intValue("Pushback"))
            .spawnCharacter(set(row, "SpawnCharacter") ? row.string("SpawnCharacter") : null)
            // The loader stores at least one child for a row that names a spawned character.
            .spawnCharacterCount(
                set(row, "SpawnCharacter") ? Math.max(row.intValue("SpawnCharacterCount"), 1) : 0)
            .spawnCharacterDeployTimeMs(row.intValue("SpawnCharacterDeployTime"))
            .spawnConstPriority(row.bool("SpawnConstPriority"))
            .radiusY(row.intValue("RadiusY"))
            .projectileRadiusY(row.intValue("ProjectileRadiusY"))
            .projectileStartExtraRadius(row.intValue("ProjectileStartExtraRadius"))
            .pushbackAll(row.bool("PushbackAll"))
            .spawnProjectile(set(row, "SpawnProjectile") ? row.string("SpawnProjectile") : null)
            // The loader stores at least one link for a row that names a spawned projectile.
            .spawnChain(set(row, "SpawnProjectile") ? Math.max(row.intValue("SpawnChain"), 1) : 0)
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
            .spawnAreaEffectObject(
                set(row, "SpawnAreaEffectObject") ? row.string("SpawnAreaEffectObject") : null)
            .ignoreReflectedAttack(row.bool("IgnoreReflectedAttack"))
            .dragBackSpeed(row.intValue("DragBackSpeed"))
            .dragSelfSpeed(row.intValue("DragSelfSpeed"))
            .dragMargin(row.intValue("DragMargin"))
            .dragBackAsAttractor(row.bool("DragBackAsAttractor"))
            .build();
    List<String> unmodelled =
        new ArrayList<>(
            UNMODELLED_PROJECTILE_COLUMNS.stream().filter(column -> set(row, column)).toList());
    // The spawned area effect is refused with its row: one that follows is among them, and one that
    // follows the projectile is made on its first flight visit, not at its impact.
    if (data.spawnAreaEffectObject() != null
        && !areaEffect(data.spawnAreaEffectObject()).unmodelledColumns().isEmpty()) {
      unmodelled.add("SpawnAreaEffectObject");
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
   * list of characters, each at an offset of its own, in place of its groups; one that lists them
   * besides a group is refused, as no card does. The card's deploy time of its own is read nowhere
   * on the placement's path, and is not read here.
   *
   * @param name the card row's name
   */
  public DeployCard card(String name) {
    GameTable table = tables.table(SPELLS_CHARACTERS);
    if (!table.has(name) && tables.table(SPELLS_BUILDINGS).has(name)) {
      table = tables.table(SPELLS_BUILDINGS);
    } else if (!table.has(name) && tables.table(SPELLS_OTHER).has(name)) {
      table = tables.table(SPELLS_OTHER);
    }
    checkArgument(table.has(name), () -> "the game tables have no card " + name);
    GameRow row = table.row(name);
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
    if (casts && row.bool("SpellAsDeploy")) {
      throw new UnsupportedOperationException(
          name
              + " deploys as a spell with a unit and a cast, which clears the search's snap and is"
              + " not modelled");
    }
    if (set(row, "AreaEffectObject")) {
      throw new UnsupportedOperationException(
          name + " makes an area effect as well as a unit, which the cast does not model");
    }
    List<DeployCard.Listed> listed = listed(row);
    if (!listed.isEmpty() && (set(row, "SummonCharacter") || set(row, "SummonCharacterSecond"))) {
      throw new UnsupportedOperationException(
          name + " summons a list of characters besides a group, which no card does");
    }
    checkArgument(
        !listed.isEmpty() || !row.string("SummonCharacter").isEmpty(),
        () -> name + " summons no character");
    String second = row.string("SummonCharacterSecond");
    // The card's first unit, which the map check and the search read: its summoned character, else
    // its list's first.
    UnitData summoned =
        listed.isEmpty() ? unit(row.string("SummonCharacter")) : listed.get(0).unit();
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
        null,
        // A unit that tunnels and morphs as it surfaces is searched for as its morph.
        tunnelMorph(summoned),
        false,
        row.intValue("Radius"),
        row.intValue("MultipleProjectiles"),
        row.intValue("ProjectileWaves"),
        row.intValue("ProjectileWaveInterval"),
        row.intValue("ProjectileInterval"),
        listed,
        row.bool("CharactersOffsetsXMirrored"));
  }

  /**
   * The characters a card lists, each with its offsets at its index in the two offset lists, which
   * the placement reads unchecked: a list longer than either is refused.
   */
  private List<DeployCard.Listed> listed(GameRow row) {
    List<String> names = row.strings("SummonCharactersList");
    List<Integer> xs = row.ints("SummonCharactersOffsetsX");
    List<Integer> ys = row.ints("SummonCharactersOffsetsY");
    if (xs.size() < names.size() || ys.size() < names.size()) {
      throw new UnsupportedOperationException(
          row.name() + " lists more characters than offsets, which the placement reads past");
    }
    List<DeployCard.Listed> listed = new ArrayList<>();
    for (int j = 0; j < names.size(); j++) {
      listed.add(new DeployCard.Listed(unit(names.get(j)), xs.get(j), ys.get(j)));
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
      return value.asInt() != 0;
    }
    return !value.isEmpty();
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
      notify.add(element.asBoolean());
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
   * What the match reads of a card: its cost, its two opening-hand columns, its production stop and
   * whether it is the Mirror. The card is looked up in the three card tables in turn.
   *
   * @param name the card row's name
   */
  public MatchCard matchCard(String name) {
    GameRow row = null;
    for (String table : List.of(SPELLS_CHARACTERS, SPELLS_BUILDINGS, SPELLS_OTHER)) {
      if (tables.table(table).has(name)) {
        row = tables.table(table).row(name);
        break;
      }
    }
    checkArgument(row != null, () -> "the game tables have no card " + name);
    return new MatchCard(
        row.name(),
        row.intValue("ManaCost"),
        row.bool("ForceToStartingHand"),
        row.bool("OmitFromStartingHand"),
        row.intValue("ElixirProductionStopTime"),
        row.bool("Mirror"));
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

  /** An array column of whole numbers; an empty list for a column left out. */
  private static List<Integer> ints(GameRow row, String column) {
    List<Integer> out = new ArrayList<>();
    for (JsonNode element : arrayOf(row, column)) {
      out.add(element.asInt());
    }
    return out;
  }

  /** An array column's elements; none for a column left out or not an array. */
  private static List<JsonNode> arrayOf(GameRow row, String column) {
    List<JsonNode> out = new ArrayList<>();
    JsonNode value = row.value(column);
    if (value != null && value.isArray()) {
      value.forEach(out::add);
    }
    return out;
  }
}
