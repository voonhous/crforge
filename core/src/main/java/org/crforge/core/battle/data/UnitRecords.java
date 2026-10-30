/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.data;

import static org.crforge.core.util.ValidationUtils.checkArgument;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.crforge.core.battle.unit.AbilityData;
import org.crforge.core.battle.unit.AttackSequence;
import org.crforge.core.battle.unit.UnitData;
import org.crforge.core.pathfinding.target.TargetingConfig;

/**
 * The units and buildings of the battle, built from the characters and buildings tables, and their
 * abilities.
 */
final class UnitRecords extends RowReader {

  private static final String CHARACTERS = "characters";

  private static final String BUILDINGS = "buildings";

  private static final String CHARACTER_ABILITIES = "character_abilities";

  /**
   * The columns of what a unit does as it dies that the battle does not model: a unit whose row
   * sets one is refused when it dies. The elixir a death gives is not among them: the death handler
   * pays a player's unit's ManaOnDeathForOpponent to the side that killed it in a match, and the
   * death slot pays its ManaOnDeath to its own side's king. Nor is StartingBuff, whose instances
   * the death slot takes off where the dying unit is their parent.
   */
  private static final List<String> UNMODELLED_DEATH_COLUMNS =
      List.of("DeathSpawnCharacter3", "DeathSpawnIsSameUnit");

  /**
   * The columns that change where a unit's death spawn stands or what its children take, which the
   * battle does not model: refused only for a unit that spawns on its death. The inherited ignore
   * list is not among them: a death spawn under it joins every projectile's id list that holds the
   * dying unit (what its flying body hit, a carrier's riders with it); an area effect's list that
   * holds it is refused as it happens.
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
   * step; and the Tombstone hero's dummies' and passive monster's no physical interaction, read by
   * the push pass and the avoidance as a warp's and a knockback's are, and no buffs, which refuses
   * a buff's apply as a guard's tags do. A row that sets any other is refused as the unit is
   * created.
   */
  private static final Set<String> MODELLED_ROW_TAGS =
      Set.of(
          "NO_GIANTBUFFER_CHEF_ENCHANTMENT",
          "AVOIDANCE_AS_OBSTACLE",
          "NO_MOVE_ALLOW_ATTRACT",
          "NO_DAMAGE",
          "NO_CHECKCOLLISIONS",
          "UNTARGETABLE",
          "NO_ATTACK",
          "DISABLE_PHYSICAL_INTERACTIONS_WITH_OBJECTS",
          "NO_BUFFS");

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
          "UseAnimator",
          // Read only by the character's view: whether it is shown as a good target, set on its
          // own view unless the row disables it, and on the views of its group's members that do
          // not disable it either when the row sets it group wide.
          "ShowAsGoodTargetGroupWide",
          "DisableIndependentShowAsGoodTarget",
          // Read only where the view builds a buff's visual filter: it is built for the carrier
          // and,
          // when the carrier's row passes it on, for each member of its group whose row receives
          // it.
          "PassBuffFiltersInGroup",
          "ReceiveBuffFiltersFromGroup");

  /**
   * The columns of a unit's row the record shows no battle logic reads, or reads to no effect for
   * every row the battle builds while another mechanic stays refused.
   */
  private static final Set<String> INERT_UNIT_COLUMNS =
      Set.of(
          // The row's own name, read as the row's key.
          "Name",
          // Stored by the character loader and read by no battle logic. Only the hero Dark
          // Prince's mount sets it, which targets only buildings, which no push moves; a row that
          // targets anything else is refused below, in case a reader was missed.
          "Pushback",
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
          // Read by client code only.
          "DashFilter",
          // Resolved when the tables are derived: the row already carries what it inherits.
          "Base",
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
          "DisableMeleeAeoDamageEffect",
          // Carried by the death spawn's request of the first row into the spawn, where it only
          // skips playing each child's SpawnEffect as the child is made.
          "DeathSpawnCharacterIgnoreEffect");

  /**
   * The columns of a unit's row whose role in the battle is not yet established, carried unread
   * until a trace settles them: none now.
   */
  private static final Set<String> PENDING_UNIT_COLUMNS = Set.of();

  /**
   * The columns of an ability's row that only show something: its button, icons, texts, stats and
   * effects, classified by what they name.
   */
  private static final Set<String> PRESENTATION_ABILITY_COLUMNS =
      Set.of(
          // The texts, icons and stats the card's info and the ability button show.
          "TID",
          "TID_INFO",
          "IconExportName",
          "IconSWF",
          "PopoverIconExportName",
          "PopoverIconFileName",
          "OutOfChargesTID",
          "Stats",
          "StatsTags",
          // How the ability button shows its charges.
          "HideChargesTextField",
          "KeepIconEvenWhenOutOfCharges",
          // Read only by the client's ability button: with the button's own flag set, it draws the
          // casting state instead of the state it worked out. No battle logic reads it.
          "CanBePreCasted",
          // The effects shown as the ability is cast, and where the effect is drawn.
          "DeployedClip",
          "DeployedEffect",
          "DeployedEffectAbsolutePositionToParent",
          // The effect shown as a soul is gained, and the health bar the souls are shown in.
          "ResurrectGainChargeEffect",
          "ResurrectHealthBar",
          // The look of a soul on that bar: the name the resurrect bar's client action gives as its
          // charge filter, which names no row of the game's tables. The deaths that give a soul
          // are chosen by the game object filter of the action that listens for them, read with
          // that action.
          "ResurrectChargeFilter");

  /**
   * The columns of an ability's row the record shows no battle logic reads: its name, and a death
   * action, which the game's ability loader has no column for, so it stores nothing.
   */
  private static final Set<String> INERT_ABILITY_COLUMNS = Set.of("Name", "OnDeathAction");

  /** The columns of an ability's row whose role is not yet established: none now. */
  private static final Set<String> PENDING_ABILITY_COLUMNS = Set.of();

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
   * The fields of an attack sequence entry its loader reads: its damage, projectile, timing, range
   * overrides, push and attack action, its first projectile, its number of targets and whether it
   * remembers them, the action its hit runs in place of the row's attack action, its start delay,
   * and the buff its direct hit applies at the damage drain and for how long.
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
          "AttackStartDelay",
          "CustomBuffOnDamage",
          "CustomBuffOnDamageTime");

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

  /** The attack sequence modes by name; any other name is 0. */
  private static final Map<String, Integer> SEQUENCE_MODES =
      Map.of("None", 0, "StaticLoop", 1, "HittimeLoop", 2, "Hittime", 3, "Manual", 4);

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
   * Each unit row read so far, by name. A unit is built from the tables alone and is immutable, so
   * the first build stands for every later ask, a speed choice only. A battle's records are read by
   * the one thread that runs the battle.
   */
  private final Map<String, UnitData> units = new HashMap<>();

  UnitRecords(GameTables tables, BattleRecords records) {
    super(tables, records);
  }

  /**
   * A unit or a building as the battle reads it, from the characters table or, for a building, the
   * buildings table.
   *
   * @param name the row's name
   */
  UnitData unit(String name) {
    UnitData known = units.get(name);
    if (known != null) {
      return known;
    }
    UnitData built = buildUnit(name);
    units.put(name, built);
    return built;
  }

  /** Builds a unit from its row, as {@link #unit(String)} first reads it. */
  private UnitData buildUnit(String name) {
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
            // A building of the characters table answers contact by the buildings beside it.
            .characterBuilding(row.bool("IsBuilding") && tables.table(CHARACTERS).has(name))
            .king(row.bool("IsSummoner"))
            .summonerTower(row.bool("IsSummonerTower"))
            .hitpoints(row.intValue("Hitpoints"))
            .damage(row.intValue("Damage"))
            .crownTowerDamagePercent(row.intValue("CrownTowerDamagePercent"))
            .rarity(rarity(row.string("Rarity")))
            .projectile(
                row.string("Projectile").isEmpty()
                    ? null
                    : records.projectile(row.string("Projectile")))
            .customFirstProjectile(
                row.string("CustomFirstProjectile").isEmpty()
                    ? null
                    : records.projectile(row.string("CustomFirstProjectile")))
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
            .deathSpawnProjectile(
                deathProjectile.isEmpty() ? null : records.projectile(deathProjectile))
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
            .onDamageTakenAction(actionName(row, "OnDamageTakenAction"))
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
            .manaOnDeath(row.intValue("ManaOnDeath"))
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
                set(row, "ProjectileSpecial")
                    ? records.projectile(row.string("ProjectileSpecial"))
                    : null)
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
    // The character Pushback column is held inert only where a reader would move nothing.
    if (sets(row, "Pushback") && !row.bool("TargetOnlyBuildings")) {
      columns.add("Pushback");
    }
    // The action a hit on the unit runs is scheduled with the unit as its instigator when the row
    // says so, else with the hit's source, and not at all for a hit without one: only the first is
    // held, so a row that names an action without it is refused.
    boolean instigatorAsSelf = row.bool("OnDamageTakenActionInstigatorAsSelf");
    if (sets(row, "OnDamageTakenAction") && !instigatorAsSelf) {
      columns.add("OnDamageTakenActionInstigatorAsSelf");
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
        projectile.isEmpty() ? null : records.projectile(projectile),
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
        0,
        null,
        AttackSequence.Entry.ROW_BUFF_ON_DAMAGE_TIME);
  }

  /** An entry of an AttackSequenceList element, with the entry columns' defaults. */
  private AttackSequence.Entry listEntry(GameRow row, JsonNode element) {
    String column = "AttackSequenceList";
    row.tableElement(column, element);
    String projectile = row.textField(column, element, "Projectile");
    String first = row.textField(column, element, "CustomFirstProjectile");
    // The entry's own buff on damage: no buff and a time of -1 when it leaves them out, the
    // loader's defaults, which keep the row's BuffOnDamage and BuffOnDamageTime.
    // The buff is resolved as the drain applies it, as the row's BuffOnDamage is.
    String buff = row.textField(column, element, "CustomBuffOnDamage");
    return new AttackSequence.Entry(
        row.intField(column, element, "Damage", 0),
        projectile.isEmpty() ? null : records.projectile(projectile),
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
        first.isEmpty() ? null : records.projectile(first),
        row.intField(column, element, "CustomMultipleTargets", -1),
        row.intField(column, element, "CustomRememberMultipleTargets", -1),
        actionName(row.name(), "CustomOnAttackAction", element.path("CustomOnAttackAction")),
        row.intField(column, element, "AttackStartDelay", 0),
        buff.isEmpty() ? null : buff,
        row.intField(
            column,
            element,
            "CustomBuffOnDamageTime",
            AttackSequence.Entry.ROW_BUFF_ON_DAMAGE_TIME));
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
    GameRow ability = abilities.row(name).tracking();
    AbilityData data =
        AbilityData.builder()
            .name(name)
            .castTimeMs(ability.intValue("CastTime"))
            .triggerDelayMs(ability.intValue("TriggerDelay"))
            .keepCurrentTarget(ability.bool("KeepCurrentTarget"))
            .champion(!ability.has("IsChampion") || ability.bool("IsChampion"))
            .onActivationAction(inlineActionName(ability, "OnActivationAction"))
            .buff(set(ability, "Buff") ? ability.string("Buff") : null)
            .buffTimeMs(ability.intValue("BuffTime"))
            .manaCost(ability.intValue("ManaCost"))
            // A row whose RefundWindow is not positive takes its trigger delay instead, as the
            // game's
            // ability loader sets it once the row is read.
            .refundWindowMs(
                ability.intValue("RefundWindow") > 0
                    ? ability.intValue("RefundWindow")
                    : ability.intValue("TriggerDelay"))
            // A newer row's RefundUntilDelivered (false when the row leaves it out, as every row of
            // the older tables does): the cost rides on the unit until the ability is delivered.
            .refundUntilDelivered(ability.bool("RefundUntilDelivered"))
            .cooldownMs(ability.intValue("Cooldown"))
            .maxCharges(ability.intValue("MaxCharges"))
            .dashRange(ability.intValue("DashRange"))
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
            // A row with ResurrectChargesExpression sizes the area effect by it, its souls drained
            // by
            // actions into a variable, and counts no death on the unit; one without counts the
            // deaths.
            .soulsFromDeaths(!ability.has("ResurrectChargesExpression"))
            .resurrectChargesExpression(
                set(ability, "ResurrectChargesExpression")
                    ? ability.string("ResurrectChargesExpression")
                    : null)
            .spawnCountResetAction(inlineActionName(ability, "SpawnCountResetAction"))
            .unmodelledColumns(unmodelledAbilityColumns(ability))
            .build();
    // Any other column the row sets is refused too, as a unit's, a buff's and a filter's are.
    return data.toBuilder()
        .unmodelledColumns(
            withUnread(
                data.unmodelledColumns(),
                ability,
                PRESENTATION_ABILITY_COLUMNS,
                INERT_ABILITY_COLUMNS,
                PENDING_ABILITY_COLUMNS))
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

  private GameRow unitRow(String name) {
    for (String table : new String[] {CHARACTERS, BUILDINGS}) {
      GameTable rows = tables.table(table);
      if (rows.has(name)) {
        return rows.row(name);
      }
    }
    throw new IllegalArgumentException("the game tables have no unit " + name);
  }

  /** The row of a unit: the characters table's, else the buildings table's. */
  /**
   * The global id of the character or building row of that name, as an expression names it: the
   * characters are asked first. Null for a name that is neither.
   *
   * @param name the row's name, matched exactly
   */
  Integer unitGlobalId(String name) {
    for (String table : new String[] {CHARACTERS, BUILDINGS}) {
      GameTable rows = tables.table(table);
      if (rows.has(name)) {
        return rows.row(name).globalId();
      }
    }
    return null;
  }
}
