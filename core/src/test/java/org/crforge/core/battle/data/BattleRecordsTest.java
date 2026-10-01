package org.crforge.core.battle.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.battle.match.BattleTimeline;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.unit.AbilityData;
import org.crforge.core.battle.unit.AreaEffectData;
import org.crforge.core.battle.unit.BuffData;
import org.crforge.core.battle.unit.UnitData;
import org.crforge.core.pathfinding.combat.RarityTable;
import org.crforge.core.pathfinding.combat.ScalingMode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The battle's records built from the game's own rows: every field is its column, in the column's
 * own units, and nothing is converted. The configured game tables are required.
 */
class BattleRecordsTest {

  private static BattleRecords records;

  @BeforeAll
  static void load() {
    records = new BattleRecords(GameTables.loadConfigured());
  }

  @Test
  @DisplayName("a unit's fields are its row's columns, in milliseconds and game units")
  void aUnitIsItsColumns() {
    UnitData knight = records.unit("Knight");
    assertThat(knight.name()).isEqualTo("Knight");
    assertThat(knight.speed()).isEqualTo(60);
    assertThat(knight.range()).isEqualTo(1200);
    assertThat(knight.sightRange()).isEqualTo(5500);
    assertThat(knight.collisionRadius()).isEqualTo(500);
    assertThat(knight.mass()).isEqualTo(6);
    assertThat(knight.hitSpeedMs()).isEqualTo(1200);
    assertThat(knight.loadTimeMs()).isEqualTo(700);
    assertThat(knight.deployTimeMs()).isEqualTo(1000);
    assertThat(knight.attacksGround()).isTrue();
    assertThat(knight.attacksAir()).isFalse();
    assertThat(knight.air()).isFalse();
    assertThat(knight.building()).isFalse();
    assertThat(knight.hitpoints()).isEqualTo(690);
    assertThat(knight.damage()).isEqualTo(79);
    assertThat(knight.rarity()).isEqualTo(RarityTable.COMMON);
    assertThat(knight.projectile()).isNull();
    assertThat(knight.projectileStartRadius()).isEqualTo(450);
    assertThat(knight.projectileStartZ()).isEqualTo(450);

    UnitData valkyrie = records.unit("Valkyrie");
    assertThat(valkyrie.areaDamageRadius()).isEqualTo(2000);
    assertThat(valkyrie.selfAsAoeCenter()).isTrue();
    assertThat(valkyrie.overrideAttackFinishTime()).isTrue();
    assertThat(valkyrie.attackFinishTimeMs()).isEqualTo(100);
  }

  @Test
  @DisplayName("a unit with a flying height flies")
  void aFlyingUnit() {
    UnitData minion = records.unit("Minion");
    assertThat(minion.air()).isTrue();
    assertThat(minion.flyingHeight()).isEqualTo(1500);
    assertThat(minion.attacksAir()).isTrue();
  }

  @Test
  @DisplayName("a unit that fires carries its projectile's row, and its own empty damage column")
  void aUnitThatFires() {
    UnitData musketeer = records.unit("Musketeer");
    assertThat(musketeer.damage()).as("the unit's own column is empty").isZero();
    ProjectileData projectile = musketeer.projectile();
    assertThat(projectile.name()).isEqualTo("MusketeerProjectile");
    assertThat(projectile.damage()).isEqualTo(85);
    assertThat(projectile.speed()).isEqualTo(1000);
    assertThat(projectile.homing()).isTrue();
    assertThat(projectile.onlyEnemies()).isTrue();
    assertThat(projectile.rarity()).isEqualTo(RarityTable.COMMON);
    assertThat(projectile.damageMode()).isEqualTo(ScalingMode.CARD_DAMAGE);
  }

  @Test
  @DisplayName("a projectile's damage scaling mode names its rule")
  void aProjectileScalingMode() {
    ProjectileData arrow = records.projectile("TowerPrincessProjectile");
    assertThat(arrow.damageMode()).isEqualTo(ScalingMode.TOWER_DAMAGE);
    assertThat(arrow.gravity()).isEqualTo(60);
    assertThat(arrow.speed()).isEqualTo(600);
    assertThat(records.projectile("KingProjectile").damageMode())
        .isEqualTo(ScalingMode.KING_DAMAGE);
  }

  @Test
  @DisplayName(
      "a projectile's target buff is carried for its circle and its one target, before or after"
          + " the damage, and refused on a projectile that flies to a point; a hop and a fan are"
          + " carried")
  void aProjectileTargetBuff() {
    ProjectileData snowball = records.projectile("SnowballSpell");
    assertThat(snowball.targetBuff()).isEqualTo("IceWizardSlowDown");
    assertThat(snowball.buffTimeMs()).isEqualTo(3000);
    assertThat(snowball.applyBuffBeforeDamage()).isFalse();
    // An empty target limit is the loader's 1000.
    assertThat(snowball.maximumTargets()).isEqualTo(1000);
    assertThat(snowball.unmodelledColumns()).isEmpty();
    ProjectileData voodoo = records.projectile("VoodooProjectile");
    assertThat(voodoo.targetBuff()).isEqualTo("VoodooCurse");
    assertThat(voodoo.applyBuffBeforeDamage()).isTrue();
    assertThat(voodoo.unmodelledColumns()).isEmpty();
    ProjectileData chain = records.projectile("ElectroDragonProjectile");
    assertThat(chain.chainedHitRadius()).isEqualTo(4000);
    assertThat(chain.chainedHitCount()).isEqualTo(3);
    assertThat(chain.unmodelledColumns()).isEmpty();
    // A spawned row's count and radius make its fan.
    ProjectileData explosion = records.projectile("FirecrackerExplosion");
    assertThat(explosion.spawnCount()).isEqualTo(5);
    assertThat(explosion.spawnRadius()).isEqualTo(80);
    assertThat(explosion.unmodelledColumns()).isEmpty();
    // A pingpong row's sweep time.
    ProjectileData axe = records.projectile("AxeManProjectile");
    assertThat(axe.pingpongVisualTimeMs()).isEqualTo(1500);
    assertThat(axe.unmodelledColumns()).isEmpty();
    // The Hunter's pellet: a line scatter, a random delay and a stop at the first landed hit.
    ProjectileData pellet = records.projectile("HunterProjectile");
    assertThat(pellet.lineScatter()).isTrue();
    assertThat(pellet.randomDelayMs()).isEqualTo(200);
    assertThat(pellet.checkCollisions()).isTrue();
    assertThat(pellet.unmodelledColumns()).isEmpty();
    assertThat(records.unit("Hunter").customFirstProjectile().name()).isEqualTo("HunterProjectile");
    // A projectile that flies to a point buffs through its hits on the way, which is not modelled.
    assertThat(records.projectile("SuperEliteArcherArrow").unmodelledColumns())
        .contains("TargetBuff");
  }

  @Test
  @DisplayName("a buff's death spawn is carried")
  void aBuffDeathSpawn() {
    BuffData curse = records.buff("VoodooCurse");
    assertThat(curse.deathSpawn()).isEqualTo("VoodooHog");
    assertThat(curse.deathSpawnCount()).isEqualTo(1);
    assertThat(curse.deathSpawnIsEnemy()).isTrue();
    assertThat(curse.deathSpawnDeployDelay()).isTrue();
    assertThat(curse.otherBuffDeathSpawnAllowed()).isTrue();
    assertThat(curse.unmodelledColumns()).isEmpty();
  }

  @Test
  @DisplayName("a row the tables do not have is refused, naming it")
  void anUnknownRow() {
    assertThatThrownBy(() -> records.unit("NoSuchUnit"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("NoSuchUnit");
    assertThatThrownBy(() -> records.projectile("NoSuchProjectile"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("NoSuchProjectile");
  }

  @Test
  @DisplayName("a card's placement is its row's columns, its units built from their own rows")
  void aCardIsItsColumns() {
    DeployCard barbarians = records.card("Barbarians");
    assertThat(barbarians.name()).isEqualTo("Barbarians");
    assertThat(barbarians.unit().name()).isEqualTo("Barbarian");
    assertThat(barbarians.count()).isEqualTo(5);
    assertThat(barbarians.summonRadius()).isEqualTo(700);
    assertThat(barbarians.summonDeployDelayMs()).isEqualTo(100);
    assertThat(barbarians.secondary()).isNull();
    assertThat(barbarians.secondaryCount()).isZero();
    assertThat(barbarians.canDeployOnEnemySide()).isFalse();

    DeployCard knight = records.card("Knight");
    assertThat(knight.count()).as("a card without a count summons one").isEqualTo(1);
    assertThat(knight.summonRadius()).isZero();

    assertThat(records.card("Miner").canDeployOnEnemySide()).isTrue();
    assertThat(records.card("RoyalRecruits").fullLaneDeploy()).isTrue();

    DeployCard rascals = records.card("Rascals");
    assertThat(rascals.secondary()).as("a second group from its own row").isNotNull();
    assertThat(rascals.secondaryCount()).isPositive();
  }

  @Test
  @DisplayName(
      "a building card is read from the buildings card table, as a troop card: its unit a building")
  void aBuildingCardIsATroopCard() {
    DeployCard cannon = records.card("Cannon");
    assertThat(cannon.spell()).isFalse();
    assertThat(cannon.unit().name()).isEqualTo("Cannon");
    assertThat(cannon.unit().building()).isTrue();
    assertThat(cannon.count()).isEqualTo(1);
    assertThat(cannon.summonDeployDelayMs()).isZero();

    // A card's name is its own: the unit's row may be named otherwise.
    assertThat(records.card("Elixir Collector").unit().name()).isEqualTo("ElixirCollector");
    assertThat(records.card("GoblinHut").unit().name()).isEqualTo("GoblinHut_Rework");
    // Deploying as a spell changes nothing for the Goblin Drill: its dig is its unit, which tunnels
    // in and is refused where it is played.
    assertThat(records.card("GoblinDrill").unit().name()).isEqualTo("GoblinDrillDig");
  }

  @Test
  @DisplayName("a level index on a card is not read: the summoned unit keeps the card's level")
  void theLevelIndexIsNotRead() {
    DeployCard army = records.card("SkeletonArmy");
    assertThat(army.unit().name()).isEqualTo("Skeleton");
    assertThat(army.count()).isEqualTo(15);
  }

  @Test
  @DisplayName("a card whose placement the battle does not model is refused, naming the column")
  void unmodelledCardsAreRefused() {
    assertThatThrownBy(() -> records.card("ThreeMusketeers"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("SummonCharactersList");
    assertThatThrownBy(() -> records.card("NoSuchCard"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("NoSuchCard");
  }

  @Test
  @DisplayName("a unit carries the names of its row's three hook actions, null for none")
  void hookNames() {
    UnitData king = records.unit("KingTower");
    assertThat(king.onStartingAction()).isEqualTo("KingTower_StartingGroup");
    assertThat(king.onDeathAction()).isNull();
    assertThat(records.unit("Witch_crazy_1").onStartingAction())
        .isEqualTo("Witch_crazy_1_start_action_group");
    assertThat(records.unit("Tombstone_crazy_1").onDeathAction())
        .isEqualTo("Tombstone_crazy_1_OnDeathAction");
    UnitData knight = records.unit("Knight");
    assertThat(knight.onStartingAction()).isNull();
    assertThat(knight.onDeathAction()).isNull();
    assertThat(knight.onKilledAction()).isNull();
  }

  @Test
  @DisplayName(
      "a unit carries its death damage, pushback and death spawn, and lists the columns of its"
          + " death the battle does not model")
  void deathColumns() {
    UnitData tombstone = records.unit("Tombstone_crazy_1");
    assertThat(tombstone.deathDamage()).isEqualTo(500);
    assertThat(tombstone.deathDamageRadius()).isEqualTo(3000);
    assertThat(tombstone.unmodelledDeathColumns()).isEmpty();
    // The Golem's children fly back to their ring points; the Battle Ram's turn by its facing.
    UnitData golem = records.unit("Golem");
    assertThat(golem.unmodelledDeathColumns()).isEmpty();
    assertThat(golem.deathSpawnPushback()).isTrue();
    assertThat(records.unit("BattleRam").unmodelledDeathColumns()).isEmpty();
    assertThat(records.unit("BattleRam").spawnAngleShift()).isEqualTo(180);
    assertThat(records.unit("ElixirGolem1").unmodelledDeathColumns()).isEmpty();
    UnitData golemite = records.unit("Golemite");
    assertThat(golemite.deathPushBack()).isEqualTo(900);
    assertThat(golemite.targetOnlyBuildings()).isTrue();
    UnitData elixirGolem = records.unit("ElixirGolem2");
    assertThat(elixirGolem.deathSpawnCharacter()).isEqualTo("ElixirGolem4");
    assertThat(elixirGolem.deathSpawnCount()).isEqualTo(2);
    assertThat(elixirGolem.deathSpawnRadius()).isEqualTo(750);
    assertThat(records.unit("Knight").deathSpawnCount()).isZero();
    UnitData knight = records.unit("Knight");
    assertThat(knight.deathDamage()).isZero();
    assertThat(knight.unmodelledDeathColumns()).isEmpty();
  }

  @Test
  @DisplayName("a unit carries its charge, its river jump and whether its hit destroys it")
  void chargeAndJumpColumns() {
    UnitData prince = records.unit("Prince");
    assertThat(prince.chargeRange()).isEqualTo(250);
    assertThat(prince.chargeSpeedMultiplier()).isEqualTo(200);
    assertThat(prince.damageSpecial()).isEqualTo(306);
    assertThat(prince.keepChargingAfterAttack()).isFalse();
    assertThat(prince.jumpEnabled()).isTrue();
    assertThat(prince.jumpHeight()).isEqualTo(4000);
    assertThat(prince.jumpSpeed()).isEqualTo(160);
    assertThat(prince.kamikaze()).isFalse();
    assertThat(records.unit("Ram_crazy_1").keepChargingAfterAttack()).isTrue();
    UnitData ram = records.unit("BattleRam");
    assertThat(ram.chargeRange()).isEqualTo(300);
    assertThat(ram.jumpEnabled()).isFalse();
    assertThat(ram.kamikaze()).isTrue();
    UnitData knight = records.unit("Knight");
    assertThat(knight.chargeRange()).isZero();
    assertThat(knight.jumpEnabled()).isFalse();
  }

  @Test
  @DisplayName("a unit carries its dash, and a chained dash is among the columns not modelled")
  void dashColumns() {
    UnitData bandit = records.unit("Assassin");
    assertThat(bandit.dashCooldown()).isEqualTo(800);
    assertThat(bandit.dashMinRange()).isEqualTo(3500);
    assertThat(bandit.dashMaxRange()).isEqualTo(6000);
    assertThat(bandit.dashDamage()).isEqualTo(152);
    assertThat(bandit.dashRadius()).isZero();
    assertThat(bandit.dashLandingTimeMs()).isZero();
    assertThat(bandit.dashImmuneToDamageTimeMs()).isEqualTo(100);
    assertThat(bandit.jumpSpeed()).isEqualTo(500);
    assertThat(bandit.unmodelledColumns()).isEmpty();
    UnitData megaKnight = records.unit("MegaKnight");
    assertThat(megaKnight.dashRadius()).isEqualTo(2200);
    assertThat(megaKnight.dashPushBack()).isEqualTo(1000);
    assertThat(megaKnight.dashLandingTimeMs()).isEqualTo(300);
    assertThat(megaKnight.dashConstantTimeMs()).isEqualTo(800);
    assertThat(megaKnight.jumpHeight()).isEqualTo(3000);
    assertThat(megaKnight.dashToTargetRadius()).isFalse();
    // The chained dash is listed, and so are its further columns, which nothing reads.
    assertThat(records.unit("GoldenKnight").unmodelledColumns())
        .containsExactly("DashCount", "BackDashRadius", "DashSecondaryRange");
    // Its deploy push is read, and its spawner's limit changes nothing without a spawn.
    assertThat(megaKnight.spawnPushback()).isEqualTo(1000);
    assertThat(megaKnight.spawnPushbackRadius()).isEqualTo(1000);
    assertThat(megaKnight.pushesOnDeploy()).isTrue();
    assertThat(megaKnight.unmodelledColumns()).isEmpty();
    assertThat(bandit.pushesOnDeploy()).isFalse();
    // It takes both: a radius to search and a distance to push.
    assertThat(megaKnight.toBuilder().spawnPushback(0).build().pushesOnDeploy()).isFalse();
    assertThat(megaKnight.toBuilder().spawnPushbackRadius(0).build().pushesOnDeploy()).isFalse();
    // A spawner's limit is still refused where it counts.
    assertThat(records.unit("PhoenixEgg").unmodelledColumns()).contains("SpawnLimit");
  }

  @Test
  @DisplayName("the Mega Knight's card keeps its unit and the projectile it casts as it plays")
  void aTroopCardThatCasts() {
    DeployCard megaKnight = records.card("MegaKnight");
    assertThat(megaKnight.spell()).isFalse();
    assertThat(megaKnight.unit().name()).isEqualTo("MegaKnight");
    assertThat(megaKnight.projectile()).isEqualTo("MegaKnightAppear");
    assertThat(megaKnight.casts()).isTrue();
    assertThat(megaKnight.multipleProjectiles()).isZero();
    assertThat(megaKnight.projectileWaves()).isZero();
    DeployCard knight = records.card("Knight");
    assertThat(knight.projectile()).isNull();
    assertThat(knight.casts()).isFalse();
    assertThat(records.card("Fireball").casts()).isTrue();
  }

  @Test
  @DisplayName(
      "an area effect carries its projectile, how it picks a target and its start height; the"
          + " spawner's delays change nothing without a character to spawn")
  void anAreaEffectThatLaunches() {
    AreaEffectData lightning = records.areaEffect("Lightning");
    assertThat(lightning.projectile()).isEqualTo("LighningSpell");
    assertThat(lightning.hitBiggestTargets()).isTrue();
    assertThat(lightning.projectileStartHeight()).isEqualTo(10);
    assertThat(lightning.unmodelledColumns()).isEmpty();
    AreaEffectData delivery = records.areaEffect("RoyalDeliveryArea");
    assertThat(delivery.projectile()).isEqualTo("RoyalDeliveryProjectile");
    assertThat(delivery.hitBiggestTargets()).isFalse();
    assertThat(delivery.projectileStartHeight()).isZero();
    assertThat(delivery.unmodelledColumns()).isEmpty();
    assertThat(records.areaEffect("Zap").projectile()).isNull();
    // A row that spawns characters reads them, which is not modelled.
    assertThat(records.areaEffect("Graveyard").unmodelledColumns())
        .contains("SpawnCharacter", "SpawnInitialDelay", "SpawnTime");
  }

  @Test
  @DisplayName(
      "an area effect launching from its source, or spreading its projectiles over several hits,"
          + " is listed as not modelled")
  void anAreaEffectLaunchNotModelled(@TempDir Path folder) throws IOException {
    GameTables tables =
        GameData.altered(
            folder,
            "area_effect_objects",
            rows -> {
              GameData.columns(rows, "Lightning").put("ProjectileStartHeight", -1);
              // Two hits over its life, without HitBiggestTargets.
              GameData.columns(rows, "RoyalDeliveryArea").put("HitSpeed", 1000);
              // One hit only, the projectile on its own point.
              GameData.columns(rows, "Zap").put("Projectile", "RoyalDeliveryProjectile");
            });
    BattleRecords altered = new BattleRecords(tables);

    assertThat(altered.areaEffect("Lightning").unmodelledColumns())
        .containsExactly("ProjectileStartHeight");
    assertThat(altered.areaEffect("RoyalDeliveryArea").unmodelledColumns())
        .containsExactly("Projectile");
    assertThat(altered.areaEffect("Zap").unmodelledColumns()).isEmpty();
  }

  @Test
  @DisplayName(
      "a projectile carries the area effect its impact makes, refused when that row follows the"
          + " projectile or its target")
  void aProjectileThatSpawnsAnAreaEffect() {
    ProjectileData spirit = records.projectile("HealSpiritProjectile");
    assertThat(spirit.spawnAreaEffectObject()).isEqualTo("HealSpirit");
    assertThat(spirit.unmodelledColumns()).isEmpty();
    assertThat(records.projectile("FireballSpell").spawnAreaEffectObject()).isNull();
    // A row that follows the projectile is made on its first flight visit, not at its impact.
    ProjectileData parent = records.projectile("SuperArcherChargeArrow");
    assertThat(parent.spawnAreaEffectObject()).isEqualTo("SuperArcherChargePull");
    assertThat(parent.unmodelledColumns()).contains("SpawnAreaEffectObject");
    assertThat(records.projectile("IceSpiritsProjectile_EV1").unmodelledColumns())
        .contains("SpawnAreaEffectObject");
  }

  @Test
  @DisplayName(
      "a Clone carries its hit action, a unit what a Clone makes of it; any other hit action, and a"
          + " Clone that also deals damage, is listed as not modelled")
  void cloneColumns(@TempDir Path folder) throws IOException {
    AreaEffectData clone = records.areaEffect("Clone");
    assertThat(clone.cloning()).isTrue();
    assertThat(clone.onHitAction()).isEqualTo("CloneAction");
    assertThat(clone.unmodelledColumns()).isEmpty();
    assertThat(records.areaEffect("Zap").cloning()).isFalse();
    assertThat(records.areaEffect("GoblinCurseBase").unmodelledColumns()).contains("OnHitAction");
    assertThat(records.unit("Recruit_Chess").ignoreClone()).isTrue();
    assertThat(records.unit("Knight").ignoreClone()).isFalse();
    assertThat(records.unit("Knight_EV1").clonedVersion()).isEqualTo("Knight");
    assertThat(records.unit("Knight").clonedVersion()).isNull();
    assertThat(records.buff("Clone").unmodelledColumns()).isEmpty();

    GameTables tables =
        GameData.altered(
            folder,
            "area_effect_objects",
            rows -> GameData.columns(rows, "Clone").put("Damage", 100));
    assertThat(new BattleRecords(tables).areaEffect("Clone").unmodelledColumns())
        .containsExactly("Clone");
  }

  @Test
  @DisplayName(
      "the Electro Giant carries its reflect, and a projectile whether its hits are reflected")
  void reflectColumns() {
    UnitData giant = records.unit("ElectroGiant");
    assertThat(giant.unmodelledColumns()).isEmpty();
    assertThat(giant.reflectedAttackBuff()).isEqualTo("ZapFreeze");
    assertThat(giant.reflectedAttackBuffDurationMs()).isEqualTo(500);
    assertThat(giant.reflectedAttackRadius()).isEqualTo(2000);
    assertThat(giant.reflectedAttackDamage()).isEqualTo(75);
    assertThat(giant.reflectAttackCrownTowerDamage()).isEqualTo(50);
    assertThat(records.unit("Knight").reflectedAttackBuff()).isNull();
    assertThat(records.projectile("BarbLogHeroProjectileReRolling").ignoreReflectedAttack())
        .isTrue();
    assertThat(records.projectile("MusketeerProjectile").ignoreReflectedAttack()).isFalse();
  }

  @Test
  @DisplayName(
      "a rider carries what it may target, and its bola's flight back to a moving shooter is"
          + " presentation")
  void riderTargetingColumns() {
    UnitData rider = records.unit("RamRider");
    assertThat(rider.targetOnlyTroops()).isTrue();
    assertThat(rider.ignoreTargetsWithBuff()).isEqualTo("BolaSnare");
    assertThat(rider.deprioritizeTargetsWithBuff()).isTrue();
    assertThat(rider.projectile().unmodelledColumns()).isEmpty();
    UnitData knight = records.unit("Knight");
    assertThat(knight.targetOnlyTroops()).isFalse();
    assertThat(knight.ignoreTargetsWithBuff()).isNull();
  }

  @Test
  @DisplayName(
      "a building carries its lifetime, minimum range and spawner, and a unit lists the columns"
          + " the battle does not model")
  void buildingColumns() {
    UnitData tombstone = records.unit("Tombstone");
    assertThat(tombstone.lifeTimeMs()).isEqualTo(30000);
    assertThat(tombstone.spawnCharacter()).isEqualTo("Skeleton");
    assertThat(tombstone.spawnNumber()).isEqualTo(2);
    assertThat(tombstone.spawnIntervalMs()).isEqualTo(500);
    assertThat(tombstone.spawnPauseTimeMs()).isEqualTo(3500);
    assertThat(tombstone.spawnStartTimeMs()).isZero();
    assertThat(tombstone.unmodelledColumns()).isEmpty();
    assertThat(records.unit("GoblinDrill").spawnStartTimeMs()).isEqualTo(1000);
    assertThat(records.unit("Mortar").minimumRange()).isEqualTo(2900);
    assertThat(records.unit("Cannon").spawnCharacter()).isNull();
    assertThat(records.unit("DarkPrince").unmodelledColumns()).isEmpty();
    assertThat(records.unit("Ram_crazy_1").unmodelledColumns())
        .containsExactly("OnStartChargingAction");
    assertThat(records.unit("DarkPrince").shieldHitpoints()).isEqualTo(94);
    assertThat(records.unit("Wizard_EV1").unmodelledColumns()).contains("ShieldLostAction");
    // The Tesla hides while it does not attack, 800 ms to go down and 800 to come up; its
    // evolution's actions as it rises and hides are not modelled.
    UnitData tesla = records.unit("Tesla");
    assertThat(tesla.unmodelledColumns()).isEmpty();
    assertThat(tesla.hidesWhenNotAttacking()).isTrue();
    assertThat(new int[] {tesla.hideTimeMs(), tesla.upTimeMs()}).containsExactly(800, 800);
    assertThat(records.unit("Tesla_EV1").unmodelledColumns())
        .containsExactly("OnAppearAction", "OnDisappearAction");
    // An elixir collector is modelled: one elixir every 13000 ms; an Elixir Golem's death pays
    // 1000.
    UnitData collector = records.unit("ElixirCollector");
    assertThat(collector.unmodelledColumns()).isEmpty();
    assertThat(new int[] {collector.manaCollectAmount(), collector.manaGenerateTimeMs()})
        .containsExactly(1, 13000);
    assertThat(records.unit("ElixirGolem1").manaOnDeathForOpponent()).isEqualTo(1000);
    // The Goblin Giant's two Spear Goblins ride on it, at 900, turned by their own -22.
    UnitData goblinGiant = records.unit("GoblinGiant");
    assertThat(goblinGiant.unmodelledColumns()).isEmpty();
    assertThat(goblinGiant.spawnAttach()).isTrue();
    assertThat(goblinGiant.spawnNumber()).isEqualTo(2);
    assertThat(goblinGiant.spawnRadius()).isEqualTo(900);
    UnitData rider = records.unit("SpearGoblinGiant");
    assertThat(rider.spawnAngleShift()).isEqualTo(-22);
    assertThat(rider.spawnMaxAngle()).isEqualTo(90);
    assertThat(rider.spawnAttachMaxRotation()).isZero();
    assertThat(rider.flyingHeight()).isEqualTo(4000);
    assertThat(rider.deathInheritIgnoreList()).isTrue();
    // The listed columns first, then those the row sets that nothing reads, in name order.
    assertThat(records.unit("PhoenixEgg").unmodelledColumns())
        .containsExactly(
            "DestroyAtLimit",
            "SpawnCharacterWithDeploy",
            "SpawnLimit",
            "GameTagsToSet",
            "UntargetableWhenSpawned");
  }

  @Test
  @DisplayName(
      "a unit whose ability row says nothing of it is a champion, unless the row says it is not;"
          + " one without an ability is none")
  void champion() {
    assertThat(records.unit("SkeletonKing").champion()).isTrue();
    assertThat(records.unit("GiantBuffer").champion()).isFalse();
    assertThat(records.unit("Knight").champion()).isFalse();
  }

  @Test
  @DisplayName(
      "an ability carries its cast, its trigger, the target it keeps and its inline activation"
          + " action, and lists the effects the battle does not model")
  void ability() {
    AbilityData buffer = records.unit("GiantBuffer").ability();
    assertThat(buffer.name()).isEqualTo("giantbuffer_ability");
    assertThat(buffer.castTimeMs()).isEqualTo(933);
    assertThat(buffer.triggerDelayMs()).isEqualTo(50);
    assertThat(buffer.keepCurrentTarget()).isTrue();
    assertThat(buffer.champion()).isFalse();
    // Written inline, it is the actions table's row named after the ability and the column.
    assertThat(buffer.onActivationAction()).isEqualTo("giantbuffer_ability_OnActivationAction");
    assertThat(buffer.unmodelledColumns()).isEmpty();
    assertThat(records.unit("ArcherQueen").ability().unmodelledColumns()).isNotEmpty();
    assertThat(records.unit("Knight").ability()).isNull();
  }

  @Test
  @DisplayName("a projectile carries the action its impact schedules on its target")
  void onHitTargetAction() {
    assertThat(records.projectile("GiantBuffProjectile").onHitTargetAction())
        .isEqualTo("GiantBuffProjectile_OnHitTargetAction");
    assertThat(records.projectile("MusketeerProjectile").onHitTargetAction()).isNull();
  }

  @Test
  @DisplayName("a unit carries its row's global id, a building's as much as a character's")
  void globalId() {
    assertThat(records.unit("MiniPekka").globalId()).isEqualTo(34000016);
    assertThat(records.unit("KingTower").globalId()).isEqualTo(35000000);
    // A row named in an expression is looked up by name, characters first; this one hashes below 0.
    assertThat(records.unitGlobalId("DaggerDuchess")).isEqualTo(-1749071821);
    assertThat(records.unitGlobalId("MiniPekka")).isEqualTo(34000016);
    assertThat(records.unitGlobalId("NoSuchRow")).isNull();
  }

  @Test
  @DisplayName(
      "a game object filter is its columns, the dead filtered unless it says not, its tags as"
          + " their bits")
  void aFilterIsItsColumns() {
    GameObjectFilter troops = records.filter("friendly_troop_no_buildings");
    assertThat(troops.isMatchTeamOwn()).isTrue();
    assertThat(troops.isMatchTeamEnemy()).isFalse();
    assertThat(troops.isMatchTypeCharacters()).isTrue();
    assertThat(troops.isFilterBuildings()).isTrue();
    assertThat(troops.isFilterSummoner()).isTrue();
    assertThat(troops.isFilterPrincessTowers()).isTrue();
    assertThat(troops.isFilterDead()).as("the default").isTrue();
    assertThat(troops.getFilterTags()).isZero();

    assertThat(records.filter("EnemyTowersOnly").isFilterDead()).isFalse();
    GameObjectFilter skeletons = records.filter("friendly_skeletons_can_be_dead");
    assertThat(skeletons.getIncludeCharactersWithData())
        .containsExactlyInAnyOrder("Skeleton", "Skeleton_EV1", "SkeletonWarrior");
    // Its three tags, by the bits the game tags table gives them.
    GameObjectFilter noDash = records.filter("enemy_troops_no_dash");
    assertThat(noDash.getFilterTags())
        .isEqualTo(tagBits("NO_CHECKAVOIDANCE", "NO_CHECKCOLLISIONS", "DASHING"));
    assertThat(noDash.isFilterSameObjects()).isFalse();
    assertThat(records.filter("passive_hit_ground_characters_not_same").isFilterSameObjects())
        .isTrue();
  }

  private static long tagBits(String... names) {
    long bits = 0;
    for (String name : names) {
      bits |= 1L << GameData.tables().table("game_tags").row(name).index();
    }
    return bits;
  }

  @Test
  @DisplayName("a hook written inline, with no name to build it by, is refused rather than dropped")
  void anInlineHookIsRefused() {
    assertThatThrownBy(() -> records.unit("Berserker"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("Berserker")
        .hasMessageContaining("OnStartingAction")
        .hasMessageContaining("ActionBerserk");
  }

  @Test
  @DisplayName("a game mode's battle timeline, a card's cost and hand columns, and a global")
  void matchRows() {
    BattleTimeline ladder = records.gameModeTimeline("Ladder");
    assertThat(ladder.name()).isEqualTo("Default");
    assertThat(ladder.startingElixir()).isEqualTo(6);
    assertThat(ladder.sectionLengths()).containsExactly(180, 120);
    assertThat(ladder.sectionTypes())
        .containsExactly(BattleTimeline.NORMAL, BattleTimeline.OVERTIME);
    assertThat(ladder.fullBarMs()).containsExactly(28000, 14000, 9300);
    assertThat(ladder.cooldownMs()).containsExactly(1000, 500, 350);
    assertThat(records.matchCard("Knight").cost()).isEqualTo(3);
    assertThat(records.matchCard("Mirror").mirror()).isTrue();
    assertThat(records.matchCard("Mirror").omitFromStartingHand()).isTrue();
    assertThat(records.matchCard("Elixir Collector").omitFromStartingHand()).isTrue();
    assertThat(records.globalNumber("MAX_MANA")).isEqualTo(10);
  }

  @Test
  @DisplayName(
      "a column a row sets that nothing reads is not modelled; presentation, inert and pending"
          + " columns are carried")
  void everyUnreadColumnIsListed() {
    BattleRecords records = GameData.records();
    // Read by nothing: the Fisherman's special and the Fisherman's hook's drag.
    assertThat(records.unit("Fisherman").unmodelledColumns())
        .containsExactly("ProjectileSpecial", "SpecialLoadTime", "SpecialMinRange", "SpecialRange");
    assertThat(records.projectile("FishermanProjectile").unmodelledColumns())
        .containsExactly("DragBackAsAttractor", "DragBackSpeed", "DragMargin", "DragSelfSpeed");
    assertThat(records.areaEffect("GoblinCurseBase").unmodelledColumns())
        .containsExactly("OnHitAction");
    // Carried: art and effects, and inert columns (a Monk's later entries, a collector's
    // ManaOnDeath, a Bat's filter and attack dash time, a tower's turret and attached character).
    for (String unit :
        List.of(
            "Knight",
            "HogRider",
            "ZapMachine",
            "Monk",
            "ElixirCollector",
            "Bat",
            "KingTower",
            "PrincessTower")) {
      assertThat(records.unit(unit).unmodelledColumns()).as(unit).isEmpty();
    }
    assertThat(records.projectile("ArrowsSpell").unmodelledColumns()).isEmpty();
    assertThat(records.areaEffect("Freeze").unmodelledColumns()).isEmpty();
  }

  @Test
  @DisplayName(
      "a unit carries its sight clips as the loader leaves them - 1000 behind for a row without"
          + " one, none for a building - and its LoadFirstHit")
  void aUnitCarriesItsSightClips() {
    BattleRecords records = GameData.records();
    UnitData hog = records.unit("HogRider");
    assertThat(hog.sightClip()).isEqualTo(4000);
    assertThat(hog.sightClipSide()).isEqualTo(4000);
    UnitData golem = records.unit("Golem");
    assertThat(golem.sightClip()).isEqualTo(2000);
    assertThat(golem.sightClipSide()).isEqualTo(1900);
    UnitData balloon = records.unit("Balloon");
    assertThat(balloon.sightClip()).as("the row leaves it 0").isEqualTo(1000);
    assertThat(balloon.sightClipSide()).isEqualTo(2000);
    UnitData knight = records.unit("Knight");
    assertThat(knight.sightClip()).isEqualTo(1000);
    assertThat(knight.sightClipSide()).isZero();
    assertThat(records.unit("Cannon").sightClip()).as("a building").isZero();

    assertThat(records.unit("ZapMachine").loadFirstHit()).isTrue();
    assertThat(knight.loadFirstHit()).isFalse();
  }

  @Test
  @DisplayName(
      "a hovering row carries its buff while not attacking, its countdown and its gate, whether it"
          + " starts with the buff - true unless the row says not - its area flag and its area"
          + " effect on hit")
  void aHoveringRowCarriesItsColumns() {
    BattleRecords records = GameData.records();
    UnitData ghost = records.unit("Ghost");
    assertThat(ghost.hovering()).isTrue();
    assertThat(ghost.buffWhenNotAttacking()).isEqualTo("Invisibility");
    assertThat(ghost.buffWhenNotAttackingTimeMs()).isEqualTo(2000);
    assertThat(ghost.buffWhenNotAttackingUseAttackRange()).isTrue();
    assertThat(ghost.startWithBuffWhenNotAttacking()).as("the row leaves it empty").isTrue();
    assertThat(ghost.allowAreaDamageWhenInvisible()).isTrue();
    assertThat(ghost.unmodelledColumns()).as("its overlay is the view's").isEmpty();
    assertThat(records.unit("Ghost_EV1_Summon_Base").startWithBuffWhenNotAttacking()).isFalse();

    UnitData healer = records.unit("BattleHealer");
    assertThat(healer.hovering()).isTrue();
    assertThat(healer.areaEffectOnHit()).isEqualTo("BattleHealerHeal");
    assertThat(healer.spawnAreaObject()).isEqualTo("BattleHealerSpawnHeal");
    assertThat(healer.buffWhenNotAttacking()).isNull();
    assertThat(healer.unmodelledColumns()).isEmpty();
    assertThat(records.unit("Knight").areaEffectOnHit()).isNull();

    // A buff while not attacking without its range gate is read: a touch test holds its countdown.
    UnitData bush = records.unit("SuspiciousBush");
    assertThat(bush.buffWhenNotAttacking()).isEqualTo("BushInvisibility");
    assertThat(bush.buffWhenNotAttackingUseAttackRange()).isFalse();
    assertThat(bush.startWithBuffWhenNotAttacking()).isTrue();
    assertThat(bush.unmodelledColumns()).isEmpty();
    for (String unit :
        List.of("SuspiciousBush", "SuperKnight", "Hunter_crazy_2", "RageBarbarianEvoGhost")) {
      assertThat(records.unit(unit).unmodelledColumns())
          .as(unit)
          .doesNotContain("BuffWhenNotAttacking");
    }
  }

  @Test
  @DisplayName("a buff carries whether it makes its carrier invisible, and its heal over time")
  void aBuffCarriesItsInvisibilityAndHeal() {
    BattleRecords records = GameData.records();
    BuffData invisibility = records.buff("Invisibility");
    assertThat(invisibility.invisible()).isTrue();
    assertThat(invisibility.unmodelledColumns()).isEmpty();
    BuffData heal = records.buff("BattleHealerAll");
    assertThat(heal.invisible()).isFalse();
    assertThat(heal.healPerSecond()).isEqualTo(40);
    assertThat(heal.hitFrequency()).isEqualTo(250);
    assertThat(heal.allowedOverHealPercent()).isZero();
    assertThat(heal.unmodelledColumns()).isEmpty();
    assertThat(records.buff("BatsEV1_Heal").allowedOverHealPercent()).isEqualTo(200);
  }

  @Test
  @DisplayName("a projectile carries whether its impact fixes its children's priority")
  void aProjectileCarriesItsSpawnPriority() {
    BattleRecords records = GameData.records();
    assertThat(records.projectile("GoblinBarrelSpell").spawnConstPriority()).isTrue();
    assertThat(records.projectile("ArrowsSpell").spawnConstPriority()).isFalse();
  }
}
