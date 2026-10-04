package org.crforge.core.battle.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.battle.match.BattleTimeline;
import org.crforge.core.battle.match.SpellVariant;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.unit.AbilityData;
import org.crforge.core.battle.unit.AreaEffectData;
import org.crforge.core.battle.unit.AttackSequence;
import org.crforge.core.battle.unit.BuffData;
import org.crforge.core.battle.unit.UnitData;
import org.crforge.core.pathfinding.EntityFlags;
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
  @DisplayName(
      "a unit that stops walking for a while is loaded at a speed raised by its walk and wait"
          + " times, so its pauses cost it nothing")
  void aWalkAndWaitUnitsSpeedIsRaisedAsItLoads() {
    // (WaitMS + StopMovementAfterMS) * 1000 / StopMovementAfterMS, truncated, times Speed, over
    // 1000, truncated.
    // The Giant: 45 at 640 / 100, a ratio of 1156.
    assertThat(records.unit("Giant").speed()).isEqualTo(52);
    assertThat(records.unit("Giant").stopMovementAfterMs()).isEqualTo(640);
    assertThat(records.unit("Giant").waitMs()).isEqualTo(100);
    // The Golem: 45 at 1000 / 200, a ratio of 1200.
    assertThat(records.unit("Golem").speed()).isEqualTo(54);
    // The Ice Golem: 45 at 470 / 80, a ratio of 1170, and 52.65 truncated.
    assertThat(records.unit("IceGolemite").speed()).isEqualTo(52);
    // The Goblin Giant: 60 at 640 / 100, 69.36 truncated.
    assertThat(records.unit("GoblinGiant").speed()).isEqualTo(69);
    // A unit that never stops keeps its column.
    assertThat(records.unit("Knight").speed()).isEqualTo(60);
    assertThat(records.unit("Knight").stopMovementAfterMs()).isZero();
  }

  @Test
  @DisplayName("the speed a row is loaded at: its column, raised only by a walk time of at least 1")
  void theLoadedSpeed() {
    assertThat(BattleRecords.loadedSpeed(45, 640, 100)).isEqualTo(52);
    assertThat(BattleRecords.loadedSpeed(30, 470, 80)).isEqualTo(35);
    assertThat(BattleRecords.loadedSpeed(180, 640, 100)).isEqualTo(208);
    // No walk time: the wait is not read.
    assertThat(BattleRecords.loadedSpeed(45, 0, 100)).isEqualTo(45);
    assertThat(BattleRecords.loadedSpeed(45, -5, 100)).isEqualTo(45);
    // A walk time without a wait leaves the speed as it is.
    assertThat(BattleRecords.loadedSpeed(45, 640, 0)).isEqualTo(45);
    // Each division truncates on its own: 1999 * 1000 / 1000 = 1999, and 1999 * 1 / 1000 = 1.
    assertThat(BattleRecords.loadedSpeed(1, 1000, 999)).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "a row without a mass is loaded with one worked out from its collision radius, and every"
          + " mass is held between 1 and 20")
  void aRowsMassIsWorkedOutAndHeldAsItLoads() {
    // A building's row writes no mass: (radius * radius / 250) * radius / 62500, held to 20.
    assertThat(records.unit("PrincessTower").mass()).isEqualTo(20);
    assertThat(records.unit("KingTower").mass()).isEqualTo(20);
    // A written mass within the bounds is kept.
    assertThat(records.unit("Knight").mass()).isEqualTo(6);
    assertThat(records.unit("Skeleton").mass()).isEqualTo(1);
    assertThat(records.unit("Giant").mass()).isEqualTo(18);
  }

  @Test
  @DisplayName(
      "the mass a row is loaded at: its column held to 1..20, or its radius's when it is 0")
  void theLoadedMass() {
    // Radius 1000: 4000 * 1000 / 62500 = 64, held to 20.
    assertThat(BattleRecords.loadedMass(0, 1000)).isEqualTo(20);
    // Radius 500: 1000 * 500 / 62500 = 8.
    assertThat(BattleRecords.loadedMass(0, 500)).isEqualTo(8);
    // Radius 600: 1440 * 600 / 62500 = 13.8, truncated.
    assertThat(BattleRecords.loadedMass(0, 600)).isEqualTo(13);
    // Radius 300: 360 * 300 / 62500 = 1.7, truncated to 1.
    assertThat(BattleRecords.loadedMass(0, 300)).isEqualTo(1);
    // A radius too small for a whole unit of mass, and none at all, are held up to 1.
    assertThat(BattleRecords.loadedMass(0, 200)).isEqualTo(1);
    assertThat(BattleRecords.loadedMass(0, 0)).isEqualTo(1);
    // A written mass is not worked out, only held: above 20 comes down to 20.
    assertThat(BattleRecords.loadedMass(6, 500)).isEqualTo(6);
    assertThat(BattleRecords.loadedMass(28, 500)).isEqualTo(20);
    assertThat(BattleRecords.loadedMass(20, 1000)).isEqualTo(20);
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
  @DisplayName(
      "a projectile's spawn chain is carried, and whether its spawns share its group or are new"
          + " projectiles")
  void aProjectileSpawnChain() {
    ProjectileData bomb = records.projectile("BombSkeletonProjectile_EV1");
    assertThat(bomb.spawnProjectile()).isEqualTo("BombSkeletonProjectile_2_EV1");
    assertThat(bomb.spawnChain()).isEqualTo(2);
    assertThat(bomb.chainIsNewProjectile()).isFalse();
    assertThat(bomb.unmodelledColumns()).isEmpty();
    ProjectileData rocket = records.projectile("RocketSpell_crazy_1");
    assertThat(rocket.spawnChain()).isEqualTo(4);
    assertThat(rocket.chainIsNewProjectile()).isTrue();
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
  @DisplayName(
      "a card that lists its characters summons each at its offset after no group, its first as"
          + " the card's unit")
  void aListedCardSummonsItsList() {
    DeployCard card = records.card("ThreeMusketeers");
    assertThat(card.unit().name()).isEqualTo("ThreeMusketeer_Rework_Character_1");
    assertThat(card.primaryCount()).isZero();
    assertThat(card.secondaryTotal()).isZero();
    assertThat(card.total()).isEqualTo(3);
    assertThat(card.listed())
        .extracting(l -> l.unit().name() + " " + l.offsetX() + " " + l.offsetY())
        .containsExactly(
            "ThreeMusketeer_Rework_Character_1 0 -1000",
            "ThreeMusketeer_Rework_Character_2 -1000 1000",
            "ThreeMusketeer_Rework_Character_3 1000 1000");
    assertThat(card.listOffsetsXMirrored()).isTrue();
    assertThat(card.summonDeployDelayMs()).isEqualTo(100);
    // A card without a list summons its groups as before.
    DeployCard knight = records.card("Knight");
    assertThat(knight.listed()).isEmpty();
    assertThat(knight.primaryCount()).isEqualTo(1);
    assertThatThrownBy(() -> records.card("NoSuchCard"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("NoSuchCard");
  }

  @Test
  @DisplayName(
      "a card naming its character and listing another places its group first and its list after"
          + " it, the named character as the card's unit")
  void aListBesidesTheFirstGroupFollowsIt() {
    DeployCard card = records.card("SkeletonArmy_EV1");
    assertThat(card.unit().name()).isEqualTo("SkeletonArmy_EV1_Soldier");
    assertThat(card.namesCharacter()).isTrue();
    assertThat(card.primaryCount()).isEqualTo(15);
    assertThat(card.secondaryTotal()).isZero();
    assertThat(card.total()).isEqualTo(16);
    assertThat(card.unitAt(0).name()).isEqualTo("SkeletonArmy_EV1_Soldier");
    assertThat(card.unitAt(14).name()).isEqualTo("SkeletonArmy_EV1_Soldier");
    assertThat(card.unitAt(15).name()).isEqualTo("SkeletonArmy_EV1_General");
    assertThat(card.listed())
        .extracting(l -> l.unit().name() + " " + l.offsetX() + " " + l.offsetY())
        .containsExactly("SkeletonArmy_EV1_General 0 1000");
    assertThat(card.group()).isTrue();
  }

  @Test
  @DisplayName(
      "a card listing its characters besides a second group but no first, or more of them than"
          + " offsets, is refused")
  void aListBesidesASecondGroupAloneIsRefused(@TempDir Path folder) throws IOException {
    BattleRecords altered =
        new BattleRecords(
            GameData.altered(
                folder,
                "spells_characters",
                rows -> {
                  GameData.columns(rows, "ThreeMusketeers")
                      .put("SummonCharacterSecond", "Knight")
                      .put("SummonCharacterSecondCount", 1);
                  GameData.columns(rows, "Barbarians")
                      .set(
                          "SummonCharactersList",
                          GameData.columns(rows, "ThreeMusketeers").get("SummonCharactersList"));
                }));
    assertThatThrownBy(() -> altered.card("ThreeMusketeers"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(
            "ThreeMusketeers lists its characters besides a second group but no first, which no"
                + " card does, not modelled");
    assertThatThrownBy(() -> altered.card("Barbarians"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("more characters than offsets");
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
  @DisplayName("a unit carries its dash, and the Golden Knight its chain and its ability's dash")
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
    // The Golden Knight's dashes chain: ten at most, the next looked for within 5500 of the
    // landing, nearest first within 5500 behind it as ahead.
    UnitData goldenKnight = records.unit("GoldenKnight");
    assertThat(goldenKnight.unmodelledColumns()).isEmpty();
    assertThat(goldenKnight.dashCount()).isEqualTo(10);
    assertThat(goldenKnight.dashSecondaryRange()).isEqualTo(5500);
    assertThat(goldenKnight.backDashRadius()).isEqualTo(5500);
    // Its ability dashes at the nearest within 5500, and keeps its pending buff while it waits.
    AbilityData chain = goldenKnight.ability();
    assertThat(chain.unmodelledColumns()).isEmpty();
    assertThat(chain.dashRange()).isEqualTo(5500);
    assertThat(chain.dashTargetFurthest()).isFalse();
    assertThat(chain.pendingBuff()).isEqualTo("GoldenKnightCharge");
    // Its deploy push is read, and its spawner's limit changes nothing without a spawn.
    assertThat(megaKnight.spawnPushback()).isEqualTo(1000);
    assertThat(megaKnight.spawnPushbackRadius()).isEqualTo(1000);
    assertThat(megaKnight.pushesOnDeploy()).isTrue();
    assertThat(megaKnight.unmodelledColumns()).isEmpty();
    assertThat(bandit.pushesOnDeploy()).isFalse();
    // It takes both: a radius to search and a distance to push.
    assertThat(megaKnight.toBuilder().spawnPushback(0).build().pushesOnDeploy()).isFalse();
    assertThat(megaKnight.toBuilder().spawnPushbackRadius(0).build().pushesOnDeploy()).isFalse();
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
  }

  @Test
  @DisplayName(
      "an area effect carries its spawner: the character, its interval, initial delay, deploy time,"
          + " limit and least distance, the shuffled order and the clones, and whether it stays"
          + " after its parent; a spawner that does not shuffle is listed as not modelled")
  void anAreaEffectThatSpawns() {
    AreaEffectData graveyard = records.areaEffect("SkeletonKingGraveyard");
    assertThat(graveyard.spawnCharacter()).isEqualTo("SkeletonKingSkeleton");
    assertThat(graveyard.spawnIntervalMs()).isEqualTo(250);
    assertThat(graveyard.spawnInitialDelayMs()).isEqualTo(250);
    assertThat(graveyard.spawnTimeMs()).isEqualTo(400);
    assertThat(graveyard.spawnMaxCount()).isZero();
    assertThat(graveyard.spawnMinRadius()).isEqualTo(2500);
    assertThat(graveyard.spawnRandomizeSequence()).isTrue();
    assertThat(graveyard.spawnClones()).isTrue();
    assertThat(graveyard.stayAfterParentDies()).isTrue();
    assertThat(graveyard.followsParent()).isTrue();
    assertThat(graveyard.unmodelledColumns()).isEmpty();
    assertThat(records.areaEffect("Zap").spawnCharacter()).isNull();
    // Its directions turned by a fixed step from its side's, which no reference holds.
    assertThat(records.areaEffect("TriWizardSpawn").unmodelledColumns()).contains("SpawnCharacter");
  }

  @Test
  @DisplayName(
      "a unit's action run as it attacks is read when it is a spawn, an uppercut, a resetable"
          + " area effect, a variable's write or a group, and listed as not modelled otherwise")
  void anAttackActionOtherThanAModelledOneIsNotModelled(@TempDir Path folder) throws IOException {
    UnitData valkyrie = records.unit("Valkyrie_EV1");
    assertThat(valkyrie.onAttackAction()).isEqualTo("Valkyrie_EV1_Tornado");
    for (String name :
        List.of(
            "Valkyrie_EV1",
            "RoyalGiant_EV1",
            "MegaKnight_EV1",
            "BabyDragon_EV1",
            "InfernoDragon_EV1",
            "RoyalHog_EV1")) {
      assertThat(records.unit(name).unmodelledColumns()).as(name).isEmpty();
    }
    assertThat(records.unit("MegaKnight_EV1").onAttackAction())
        .isEqualTo("MegaKnight_EV1_uppercut");
    assertThat(records.unit("BabyDragon_EV1").onAttackAction())
        .isEqualTo("baby_dragon_evo_wind_action");
    // The evolved Inferno Dragon's counts its attacks in a variable; its list's entries leave the
    // row's two variable damage times unread.
    assertThat(records.unit("InfernoDragon_EV1").onAttackAction())
        .isEqualTo("InfernoDragon_EV1_IncrementAttackCount");
    // The evolved Royal Hog's fall is a group, whose parts are built from their own rows.
    assertThat(records.unit("RoyalHog_EV1").onAttackAction())
        .isEqualTo("RoyalHog_EV1_Fall_To_Ground_Group");
    // An action of a class whose run on a hit is not established, here an air-to-ground row
    // itself, is listed.
    GameTables tables =
        GameData.altered(
            folder,
            "characters",
            rows ->
                GameData.columns(rows, "RoyalHog_EV1")
                    .put("OnAttackAction", "RoyalHog_EV1_To_Ground"));
    assertThat(new BattleRecords(tables).unit("RoyalHog_EV1").unmodelledColumns())
        .contains("OnAttackAction");
  }

  @Test
  @DisplayName(
      "a shaped area effect reads its rectangle and its filter, and its damage type without"
          + " damage; a circle with a filter and damage reads its radius and damage type, with a"
          + " crown tower share too, a circle that pushes without damage its push, and one whose"
          + " hit action chooses a buff its hit action; a circle that neither damages, pushes nor"
          + " has such a hit action, or splits its damage, is not modelled")
  void aShapedAreaEffect(@TempDir Path folder) throws IOException {
    AreaEffectData wind = records.areaEffect("BabyDragon_EV1_wind_aeo");
    assertThat(wind.shaped()).isTrue();
    assertThat(List.of(wind.shapeWidth(), wind.shapeHeight())).containsExactly(8000, 9000);
    assertThat(wind.filter()).isEqualTo("all_characters_from_both_teams");
    assertThat(wind.onHitAction()).isEqualTo("BabyDragon_EV1_AEO_select_buff");
    assertThat(wind.unmodelledColumns()).isEmpty();
    assertThat(records.areaEffect("Zap").shaped()).isFalse();
    assertThat(records.areaEffect("Zap").filter()).isNull();
    // A circle that pushes without damage, as the Ice Golemite hero form's knockback does: its
    // damage type is read by no hit.
    AreaEffectData knockback = records.areaEffect("IceGolemiteHero_KnockBack_AEO");
    assertThat(knockback.unmodelledColumns()).isEmpty();
    assertThat(knockback.shapeRadius()).isEqualTo(1500);
    assertThat(knockback.pushback()).isEqualTo(1000);
    assertThat(knockback.damage()).isZero();
    assertThat(knockback.damageType()).isNull();
    assertThat(knockback.filter()).isEqualTo("CommonAreaDamageFilter");
    // A circle that neither damages nor pushes is not held.
    Files.createDirectories(folder.resolve("still"));
    GameTables still =
        GameData.altered(
            folder.resolve("still"),
            "area_effect_objects",
            rows -> GameData.columns(rows, "IceGolemiteHero_KnockBack_AEO").put("Pushback", 0));
    assertThat(
            new BattleRecords(still)
                .areaEffect("IceGolemiteHero_KnockBack_AEO")
                .unmodelledColumns())
        .contains("Shape");
    // A circle whose hit action chooses a buff to spawn on what it reaches, hitting on every
    // update, as the Ice Golemite hero form's slow circle does; its damage type is read by no hit.
    AreaEffectData slow = records.areaEffect("IceGolemiteHero_Slow_AEO");
    assertThat(slow.unmodelledColumns()).isEmpty();
    assertThat(slow.shapeRadius()).isEqualTo(4000);
    assertThat(slow.onHitAction()).isEqualTo("IceGolemiteHero_Select_Slow_Buff");
    assertThat(slow.damage()).isZero();
    assertThat(slow.damageType()).isNull();
    assertThat(slow.hitSpeedMs()).isEqualTo(50);
    // A circle whose damage a crown tower takes less of, hitting every 1500 ms, as the Ice
    // Golemite hero form's ability has; where its looping effect is shown is the view's.
    AreaEffectData storm = records.areaEffect("IceGolemiteHero_Damage_AEO");
    assertThat(storm.unmodelledColumns()).isEmpty();
    assertThat(storm.shapeRadius()).isEqualTo(4000);
    assertThat(storm.crownTowerDamagePercent()).isEqualTo(-95);
    assertThat(storm.damageType()).isEqualTo("IceGolemiteHero_AEO_Damage");
    assertThat(storm.filter()).isEqualTo("CommonAreaDamageFilter");
    assertThat(List.of(storm.hitSpeedMs(), storm.hitSpeedOffsetMs())).containsExactly(1500, 1450);
    // The same circle splitting its damage among what it reaches is not held.
    GameTables shared =
        GameData.altered(
            folder,
            "area_effect_objects",
            rows -> GameData.columns(rows, "IceGolemiteHero_Damage_AEO").put("SharedDamage", true));
    assertThat(
            new BattleRecords(shared).areaEffect("IceGolemiteHero_Damage_AEO").unmodelledColumns())
        .contains("Shape");
    // A circle with a filter and damage queued through its damage type, as the Giant hero form's
    // landing has.
    AreaEffectData landing = records.areaEffect("GiantHero_LandingAEO");
    assertThat(landing.unmodelledColumns()).isEmpty();
    assertThat(landing.shaped()).isTrue();
    assertThat(landing.shapeRadius()).isEqualTo(1000);
    assertThat(landing.shapeWidth()).isZero();
    assertThat(landing.damageType()).isEqualTo("GiantHero_LandingAEO_DamageType");
    assertThat(landing.filter()).isEqualTo("GroundCharacterTargets");
    assertThat(landing.damage()).isEqualTo(53);
  }

  @Test
  @DisplayName(
      "a buff's start and remove actions are read when they name an action row or are an inline"
          + " group of named rows, and listed as not modelled when written inline otherwise")
  void aBuffsHooksAreReadByName() {
    BuffData invisibility = records.buff("Ghost_EV1_Invisibility");
    assertThat(invisibility.onStartAction()).isEqualTo("Ghost_EV1_Invisible_Group");
    assertThat(invisibility.onRemoveAction()).isEqualTo("Ghost_EV1_Visible_Group");
    assertThat(invisibility.unmodelledColumns()).isEmpty();
    assertThat(records.buff("Rage").onStartAction()).isNull();

    // The Royal Chef's level-up buff writes its start action inline, as a group of named rows,
    // which is the actions table's row named after the buff and the column.
    BuffData chef = records.buff("ChefTower_increase_level_buff");
    assertThat(chef.onStartAction()).isEqualTo("ChefTower_increase_level_buff_OnStartAction");
    assertThat(chef.unmodelledColumns()).isEmpty();
  }

  @Test
  @DisplayName(
      "a buff's projectile in place of its carrier's and its removal on attack are read: the hero"
          + " Mega Minion's arrival buff sets both, its crown tower buff only the projectile")
  void aBuffsProjectileAndRemovalOnAttackAreRead() {
    BuffData arrival = records.buff("MegaMinion_hero_Damage_Buff");
    assertThat(arrival.overrideProjectile()).isEqualTo("MegaMinionSpit_DoubleDamage");
    assertThat(arrival.removeOnAttack()).isTrue();
    assertThat(arrival.onRemoveAction()).isEqualTo("MegaMinion_hero_CrownTowerBuff_Spawn");
    assertThat(arrival.unmodelledColumns()).isEmpty();
    BuffData crownTower = records.buff("MegaMinion_hero_CrownTower_Buff");
    assertThat(crownTower.overrideProjectile()).isEqualTo("MegaMinionSpit_CrownTowerDamage");
    assertThat(crownTower.removeOnAttack()).isFalse();
    assertThat(records.buff("Rage").overrideProjectile()).isNull();
  }

  @Test
  @DisplayName(
      "a buff's tags are read when the only one is the one that keeps enemies from pushing its"
          + " carrier, and listed as not modelled otherwise")
  void aBuffSetsOnlyTheTagThePushPassReads(@TempDir Path folder) throws IOException {
    BuffData notPushed = records.buff("Valkyrie_NotPushed_BUF");
    assertThat(notPushed.gameTagsToSet()).isEqualTo(EntityFlags.NO_PUSHED_BY_ENEMY);
    assertThat(notPushed.unmodelledColumns()).isEmpty();
    assertThat(records.buff("Valkyrie_MiniTornado_EV1").gameTagsToSet()).isZero();

    GameTables tables =
        GameData.altered(
            folder,
            "character_buffs",
            rows ->
                GameData.columns(rows, "Valkyrie_NotPushed_BUF")
                    .put("GameTagsToSet", "NO_PUSHED_BY_ENEMY,NO_ATTACK"));
    assertThat(new BattleRecords(tables).buff("Valkyrie_NotPushed_BUF").unmodelledColumns())
        .containsExactly("GameTagsToSet");
  }

  @Test
  @DisplayName(
      "an area effect's tags are read when they only hide its pushback, and listed as not"
          + " modelled otherwise")
  void anAreaEffectTagsOnlyHideItsPushback(@TempDir Path folder) throws IOException {
    assertThat(records.areaEffect("EvoRoyalGiantPush_EV1").unmodelledColumns()).isEmpty();

    GameTables tables =
        GameData.altered(
            folder,
            "area_effect_objects",
            rows ->
                GameData.columns(rows, "EvoRoyalGiantPush_EV1")
                    .put("Tags", "NO_AOE_DAMAGE_VFX,NO_AOE_PUSHBACK_VFX"));
    assertThat(new BattleRecords(tables).areaEffect("EvoRoyalGiantPush_EV1").unmodelledColumns())
        .containsExactly("Tags");
  }

  @Test
  @DisplayName(
      "a buff whose action on a reduced hit keeps an effect running is refused for that action")
  void aLastingDamageReductionActionIsRefused(@TempDir Path folder) throws IOException {
    GameTables tables =
        GameData.altered(
            folder,
            "actions",
            rows ->
                ((ObjectNode) rows.get("Knight_EV1_ProtectionVFX").get("fields"))
                    .put("EffectFlags", "FollowParent,Looping"));

    assertThat(new BattleRecords(tables).buff("Knight_Fortify_EV1").unmodelledColumns())
        .containsExactly("OnDamageReductionAction");
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
      "a projectile carries the area effect its impact makes, one that follows its target"
          + " included; refused when that row follows the projectile")
  void aProjectileThatSpawnsAnAreaEffect() {
    ProjectileData spirit = records.projectile("HealSpiritProjectile");
    assertThat(spirit.spawnAreaEffectObject()).isEqualTo("HealSpirit");
    assertThat(spirit.unmodelledColumns()).isEmpty();
    assertThat(records.projectile("FireballSpell").spawnAreaEffectObject()).isNull();
    // A row that follows the projectile is made on its first flight visit, not at its impact.
    ProjectileData parent = records.projectile("SuperArcherChargeArrow");
    assertThat(parent.spawnAreaEffectObject()).isEqualTo("SuperArcherChargePull");
    assertThat(parent.unmodelledColumns()).contains("SpawnAreaEffectObject");
    // The evolved Ice Spirit's area follows the projectile's target, which the impact hands it.
    assertThat(records.projectile("IceSpiritsProjectile_EV1").unmodelledColumns()).isEmpty();
  }

  @Test
  @DisplayName(
      "a Clone carries its hit action, and so does a group of buff spawns, a unit what a Clone makes"
          + " of it; any other hit action, and a Clone that also deals damage, is listed as not"
          + " modelled")
  void cloneColumns(@TempDir Path folder) throws IOException {
    AreaEffectData clone = records.areaEffect("Clone");
    assertThat(clone.cloning()).isTrue();
    assertThat(clone.onHitAction()).isEqualTo("CloneAction");
    assertThat(clone.unmodelledColumns()).isEmpty();
    assertThat(records.areaEffect("Zap").cloning()).isFalse();
    // The Goblin Curse's base spawns two buffs with each hit and the Blowdart Goblin's evolution
    // starts its poison damage; the Knight's hero taunts with a group of taunts.
    AreaEffectData curse = records.areaEffect("GoblinCurseBase");
    assertThat(curse.onHitAction()).isEqualTo("GoblinCurseCreateBuffs");
    assertThat(curse.unmodelledColumns()).isEmpty();
    assertThat(records.areaEffect("Knight_hero_TauntAEO").unmodelledColumns()).isEmpty();
    assertThat(records.areaEffect("BlowDartPoisonAeO_baseDamage").unmodelledColumns()).isEmpty();
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
    // The evolved Battle Ram's completed charge runs its push, which is modelled.
    assertThat(records.unit("BattleRam_EV1").unmodelledColumns()).isEmpty();
    assertThat(records.unit("BattleRam_EV1").onStartChargingAction())
        .isEqualTo("BattleRam_EV1_PushBack");
    assertThat(records.unit("BattleRam").onStartChargingAction()).isNull();
    assertThat(records.unit("DarkPrince").shieldHitpoints()).isEqualTo(94);
    // The evolved Wizard runs an action as its shield breaks; a push as it breaks is refused.
    assertThat(records.unit("Wizard_EV1").unmodelledColumns()).isEmpty();
    assertThat(records.unit("Wizard_EV1").shieldLostAction()).isEqualTo("Wizard_EV1_ShieldLost");
    assertThat(records.unit("Recruit_EV1").shieldLostAction()).isEqualTo("Recruit_EV1_StartCharge");
    assertThat(records.unit("Knight").shieldLostAction()).isNull();
    // The Tesla hides while it does not attack, 800 ms to go down and 800 to come up; its
    // evolution runs an action as it rises and another as it starts to hide.
    UnitData tesla = records.unit("Tesla");
    assertThat(tesla.unmodelledColumns()).isEmpty();
    assertThat(tesla.hidesWhenNotAttacking()).isTrue();
    assertThat(new int[] {tesla.hideTimeMs(), tesla.upTimeMs()}).containsExactly(800, 800);
    UnitData teslaEvo = records.unit("Tesla_EV1");
    assertThat(teslaEvo.unmodelledColumns()).isEmpty();
    assertThat(teslaEvo.onAppearAction()).isEqualTo("Tesla_EV1_AppearStun");
    assertThat(teslaEvo.onDisappearAction()).isEqualTo("Tesla_EV1_Charging");
    assertThat(tesla.onAppearAction()).isNull();
    // The evolved Cannon's two shadows are read by no battle logic.
    assertThat(records.unit("Cannon_EV1").unmodelledColumns()).isEmpty();
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
    assertThat(records.unit("SuperWitch").unmodelledColumns())
        .containsExactly("SpawnCharacter2", "SpawnCharacterLevelIndex2");
  }

  @Test
  @DisplayName(
      "the Skeleton Barrel flies direct paths and drains over its Kamikaze time; its container"
          + " gives its ring a fixed priority, which a spawner may not")
  void skeletonBarrelColumns() {
    UnitData barrel = records.unit("SkeletonBalloon");
    assertThat(barrel.unmodelledColumns()).isEmpty();
    assertThat(barrel.flyDirectPaths()).isTrue();
    assertThat(barrel.kamikaze()).isTrue();
    assertThat(barrel.kamikazeTimeMs()).isEqualTo(500);
    assertThat(barrel.deathSpawnCharacter()).isEqualTo("SkeletonContainerNew");
    UnitData container = records.unit("SkeletonContainerNew");
    assertThat(container.unmodelledColumns()).isEmpty();
    assertThat(container.unmodelledDeathColumns()).isEmpty();
    assertThat(container.spawnConstPriority()).isTrue();
    assertThat(records.unit("Knight").spawnConstPriority()).isFalse();
  }

  @Test
  @DisplayName(
      "the Phoenix launches its fireball as it dies; its egg fires once and leaves, immune as it is"
          + " made, with its three tags")
  void phoenixColumns() {
    UnitData phoenix = records.unit("Phoenix");
    assertThat(phoenix.unmodelledColumns()).isEmpty();
    assertThat(phoenix.unmodelledDeathColumns()).isEmpty();
    assertThat(phoenix.deathSpawnProjectile().name()).isEqualTo("PhoenixFireball");
    // The loader keeps a count of at least one under a death projectile, as under a death spawn.
    assertThat(phoenix.deathSpawnCount()).isEqualTo(1);
    assertThat(phoenix.deathSpawnCharacter()).isNull();
    assertThat(records.unit("Knight").deathSpawnCount()).isZero();
    UnitData egg = records.unit("PhoenixEgg");
    assertThat(egg.unmodelledColumns()).isEmpty();
    assertThat(egg.spawnCharacter()).isEqualTo("PhoenixNoRespawn");
    assertThat(egg.spawnLimit()).isEqualTo(1);
    assertThat(egg.destroyAtLimit()).isTrue();
    assertThat(egg.spawnCharacterWithDeploy()).isTrue();
    assertThat(egg.untargetableWhenSpawned()).isTrue();
    assertThat(egg.gameTagsToSet())
        .isEqualTo(
            EntityFlags.NO_GIANTBUFFER_CHEF_ENCHANTMENT
                | EntityFlags.AVOIDANCE_AS_OBSTACLE
                | EntityFlags.NO_MOVE_ALLOW_ATTRACT);
    // The same three written with spaces are the same tags.
    assertThat(records.unit("EliteArcherHero_Dummy").gameTagsToSet())
        .isEqualTo(egg.gameTagsToSet());
    // The Goblins hero's banner sets three more the battle reads: no damage, no contact, no
    // targeting; a row with one of them alone is taken too.
    assertThat(records.unit("GoblinHero_Flag_Building").gameTagsToSet())
        .isEqualTo(
            EntityFlags.NO_DAMAGE | EntityFlags.NO_CHECK_COLLISIONS | EntityFlags.UNTARGETABLE);
    assertThat(records.unit("GoblinHero_Flag_Building").unmodelledColumns()).isEmpty();
    assertThat(records.unit("RageBarbarianEvoGhost").gameTagsToSet())
        .isEqualTo(EntityFlags.NO_DAMAGE);
    assertThat(records.unit("RageBarbarianEvoGhost").unmodelledColumns()).isEmpty();
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
      "an ability carries its cast, its trigger, the target it keeps, its inline activation"
          + " action, its own buff, its lane switch, the character it leaves behind, the souls"
          + " its area effect spends and its controller's columns, and a unit whose death counts"
          + " no soul")
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
    assertThat(buffer.buff()).isNull();
    // A champion's ability buffs the champion itself, and its controller reads its cost, cooldown
    // and charges.
    AbilityData queen = records.unit("ArcherQueen").ability();
    assertThat(queen.champion()).isTrue();
    assertThat(queen.buff()).isEqualTo("ArcherQueenRapid");
    assertThat(queen.buffTimeMs()).isEqualTo(3500);
    assertThat(queen.manaCost()).isEqualTo(1);
    assertThat(queen.cooldownMs()).isEqualTo(17000);
    assertThat(queen.maxCharges()).isZero();
    assertThat(queen.unmodelledColumns()).isEmpty();
    assertThat(records.unit("BossBandit").ability().maxCharges()).isEqualTo(2);
    // A lane switch, and the character the ability leaves behind, are read.
    AbilityData miner = records.unit("MightyMiner").ability();
    assertThat(miner.switchLanes()).isTrue();
    assertThat(miner.activationSpawnCharacter()).isEqualTo("MightyMinerBomb");
    assertThat(miner.unmodelledColumns()).isEmpty();
    assertThat(queen.switchLanes()).isFalse();
    assertThat(queen.activationSpawnCharacter()).isNull();
    // An area object, the follow-up state and the tags the unit carries in it are read.
    AbilityData monk = records.unit("Monk").ability();
    assertThat(monk.areaEffectObject()).isEqualTo("Deflect");
    assertThat(monk.abilityStateDurationMs()).isEqualTo(4000);
    assertThat(monk.gameTagsWhileAbilityActive())
        .isEqualTo(EntityFlags.AVOIDANCE_AS_OBSTACLE | EntityFlags.NO_MOVE_ALLOW_ATTRACT);
    assertThat(monk.unmodelledColumns()).isEmpty();
    assertThat(queen.areaEffectObject()).isNull();
    assertThat(queen.abilityStateDurationMs()).isZero();
    assertThat(queen.gameTagsWhileAbilityActive()).isZero();
    // The souls an area object counts to resurrect are refused.
    AbilityData souls = records.unit("SkeletonKing").ability();
    assertThat(souls.areaEffectObject()).isEqualTo("SkeletonKingGraveyard");
    assertThat(souls.resurrectBaseCount()).isEqualTo(6);
    assertThat(souls.resurrectEnemies()).isTrue();
    assertThat(souls.resurrectOwnTroops()).isTrue();
    assertThat(souls.spawnLimit()).isEqualTo(16);
    assertThat(souls.unmodelledColumns()).isEmpty();
    assertThat(records.unit("Golem").ignoreResurrect()).isTrue();
    assertThat(records.unit("Knight").ignoreResurrect()).isFalse();
    assertThat(records.unit("Knight").ability()).isNull();
  }

  @Test
  @DisplayName(
      "a buff carries its damage reduction and whether it ignores pushback, an area effect whether"
          + " it deflects projectiles, a projectile how a deflection treats it, and a unit whether"
          + " it groups its volley")
  void deflectionColumns() {
    BuffData shield = records.buff("ShieldBoostMonk");
    assertThat(shield.damageReduction()).isEqualTo(65);
    assertThat(shield.ignorePushBack()).isTrue();
    assertThat(shield.unmodelledColumns()).isEmpty();
    assertThat(records.buff("DarkElixirBuff").damageReduction()).isEqualTo(-100);
    assertThat(records.buff("Rage").damageReduction()).isZero();
    assertThat(records.buff("Rage").ignorePushBack()).isFalse();
    // The action the evolved Knight's buff runs as it reduces damage only plays an effect.
    assertThat(records.buff("Knight_Fortify_EV1").unmodelledColumns()).isEmpty();

    AreaEffectData deflect = records.areaEffect("Deflect");
    assertThat(deflect.deflectsProjectiles()).isTrue();
    assertThat(deflect.followsParent()).isTrue();
    assertThat(deflect.unmodelledColumns()).isEmpty();
    assertThat(records.areaEffect("Zap").deflectsProjectiles()).isFalse();

    assertThat(records.projectile("TowerPrincessProjectile").deflectBehaviour()).isZero();
    assertThat(records.projectile("FireSpiritsProjectile").deflectBehaviour())
        .isEqualTo(ProjectileData.NO_DEFLECT);
    assertThat(records.projectile("FireballSpell").deflectRadius()).isPositive();
    assertThat(records.projectile("FirecrackerProjectile").actionOnDeflector()).isNotNull();

    assertThat(records.unit("Princess").groupProjectiles()).isTrue();
    assertThat(records.unit("Musketeer").groupProjectiles()).isFalse();
  }

  @Test
  @DisplayName("a projectile carries the action its impact schedules on its target")
  void onHitTargetAction() {
    assertThat(records.projectile("GiantBuffProjectile").onHitTargetAction())
        .isEqualTo("GiantBuffProjectile_OnHitTargetAction");
    assertThat(records.projectile("MusketeerProjectile").onHitTargetAction()).isNull();
  }

  @Test
  @DisplayName(
      "a unit carries its speed and its visibility as its ability sends it across the arena, and"
          + " whether it deploys again as it arrives")
  void ingamePathfindColumns() {
    UnitData miner = records.unit("MightyMiner");
    assertThat(miner.ingamePathfindSpeed()).isEqualTo(650);
    assertThat(miner.ingamePathfindVisible()).isFalse();
    assertThat(miner.ingamePathfindStopDeploys()).isTrue();
    assertThat(miner.unmodelledColumns()).isEmpty();
    UnitData knight = records.unit("Knight");
    assertThat(knight.ingamePathfindSpeed()).isZero();
    assertThat(knight.ingamePathfindStopDeploys()).isFalse();
  }

  @Test
  @DisplayName(
      "a projectile loses its target as the target goes underground or across the arena, unless"
          + " its row says not")
  void allowResetTarget() {
    assertThat(records.projectile("TowerPrincessProjectile").allowResetTarget())
        .as("the row leaves it empty")
        .isTrue();
    assertThat(records.projectile("GiantBuffProjectile").allowResetTarget()).isFalse();
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

  @Test
  @DisplayName(
      "an attack sequence entry that sets a field its entry does not read is listed as not"
          + " modelled, and one of another shape is refused")
  void anAttackSequenceEntryFieldNotReadIsListed(@TempDir Path folder) throws IOException {
    String unit = attackSequenceListUnit();
    assertThat(records.unit(unit).unmodelledColumns()).doesNotContain("AttackSequenceList");
    BattleRecords altered =
        new BattleRecords(
            GameData.altered(
                folder,
                unitTable(unit),
                rows -> {
                  ObjectNode entry =
                      (ObjectNode) GameData.columns(rows, unit).get("AttackSequenceList").get(0);
                  entry.put("CustomOnAttackAction", "SomeAction");
                }));
    assertThat(altered.unit(unit).unmodelledColumns()).contains("AttackSequenceList");

    BattleRecords mistyped =
        new BattleRecords(
            GameData.altered(
                Files.createDirectories(folder.resolve("mistyped")),
                unitTable(unit),
                rows -> {
                  ObjectNode entry =
                      (ObjectNode) GameData.columns(rows, unit).get("AttackSequenceList").get(0);
                  entry.put("Damage", "RageDamage");
                }));
    assertThatThrownBy(() -> mistyped.unit(unit))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageEndingWith(
            " row "
                + unit
                + " sets AttackSequenceList to a table whose Damage is the text \"RageDamage\""
                + " where a number is read, which is not modelled");
  }

  /** The first unit whose row lists its attack sequence entries, each with its damage. */
  private static String attackSequenceListUnit() {
    for (String table : List.of("characters", "buildings")) {
      for (GameRow row : GameData.tables().table(table).rows()) {
        JsonNode list = row.value("AttackSequenceList");
        if (list != null && list.isArray() && !list.isEmpty() && list.get(0).has("Damage")) {
          return row.name();
        }
      }
    }
    throw new IllegalStateException("no unit lists its attack sequence");
  }

  private static String unitTable(String unit) {
    return GameData.tables().table("characters").has(unit) ? "characters" : "buildings";
  }

  @Test
  @DisplayName(
      "a game object filter that sets a column the filter does not read, as a base filter or a"
          + " buff checker, is refused rather than read without it")
  void aFilterColumnNotReadIsRefused(@TempDir Path folder) throws IOException {
    BattleRecords altered =
        new BattleRecords(
            GameData.altered(
                folder,
                "game_object_filters",
                rows -> {
                  ObjectNode troop = GameData.columns(rows, "friendly_troop");
                  troop.put("Base", "friendly_troop_no_buildings");
                  troop.put("FilterIfBuffedByChecker", "Rage");
                }));
    assertThatThrownBy(() -> altered.filter("friendly_troop"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(
            "the game object filter friendly_troop sets columns not modelled: [Base,"
                + " FilterIfBuffedByChecker]");
    // A filter that sets only what the filter reads, and the text the game shows for it, is built.
    assertThat(altered.filter("friendly_troop_no_buildings").isMatchTeamOwn()).isTrue();
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
  void anInlineHookIsRefused(@TempDir Path folder) throws IOException {
    // Every shipped row's inline starting action is now built, so the Knight is given one the
    // battle does not read: a group whose sub-action is itself written inline.
    GameTables tables =
        GameData.altered(
            folder,
            "characters",
            rows ->
                GameData.columns(rows, "Knight")
                    .putObject("OnStartingAction")
                    .put("ClassType", "ActionGroup")
                    .putArray("SubActions")
                    .addObject()
                    .put("ClassType", "ActionBerserk"));

    assertThatThrownBy(() -> new BattleRecords(tables).unit("Knight"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("Knight")
        .hasMessageContaining("OnStartingAction")
        .hasMessageContaining("ActionGroup");
  }

  @Test
  @DisplayName(
      "the Dagger Duchess's inline charge counter is its row's action, and its entries pace")
  void theDaggerDuchessStartsItsChargeCounterAndPacesItsEntries() {
    UnitData duchess = records.unit("DaggerDuchess");

    assertThat(duchess.onStartingAction()).isEqualTo("DaggerDuchess_OnStartingAction");
    assertThat(duchess.attackSequence().mode()).isEqualTo(AttackSequence.MODE_NONE);
    assertThat(duchess.attackSequence().order()).containsExactly(0, 1, 2, 3);
    assertThat(duchess.attackSequence().entries())
        .extracting(AttackSequence.Entry::hitSpeedMultiplier)
        .containsExactly(100, 100, 70, 90);
    assertThat(duchess.attackSequence().entries())
        .extracting(entry -> entry.projectile().name())
        .containsOnly("TowerKnifeThrowerProjectile");
  }

  @Test
  @DisplayName(
      "a card that is a group says so, and summons its champion in its second group, as"
          + " Goblinstein does")
  void aGroupCard() {
    assertThat(records.card("Goblinstein").group()).isTrue();
    assertThat(records.card("Goblinstein").summonsChampion()).isTrue();
    assertThat(records.card("Goblinstein").unit().champion()).isFalse();
    assertThat(records.card("GoblinGang").group()).isFalse();
    assertThat(records.card("Knight").summonsChampion()).isFalse();
  }

  @Test
  @DisplayName(
      "Goblinstein's doctor's starting action, written inline as a spawn of an area effect, is the"
          + " actions table's row named after the unit and the column")
  void theDoctorsInlineStartingAction() {
    assertThat(records.unit("goblinstein_doctor").onStartingAction())
        .isEqualTo("goblinstein_doctor_OnStartingAction");
  }

  @Test
  @DisplayName(
      "the Berserker's starting action, written inline as a bare ActionBerserk, is the actions"
          + " table's row named after the unit and the column")
  void theBerserkersInlineStartingAction() {
    assertThat(records.unit("Berserker").onStartingAction())
        .isEqualTo("Berserker_OnStartingAction");
  }

  @Test
  @DisplayName(
      "Dark Magic's starting and life-end actions, written inline, are the actions table's rows"
          + " named after the area effect and the column")
  void darkMagicsInlineActions() {
    AreaEffectData darkMagic = records.areaEffect("DarkMagicAOE");
    assertThat(darkMagic.onStartingAction()).isEqualTo("DarkMagicAOE_OnStartingAction");
    assertThat(darkMagic.onLifeTimeEndAction()).isEqualTo("DarkMagicAOE_OnLifeTimeEndAction");
    assertThat(darkMagic.unmodelledColumns()).isEmpty();
  }

  @Test
  @DisplayName(
      "a buff a spawn row writes inline is read as a buff row of its Name, adding itself as an"
          + " individual buff")
  void anInlineBuff() {
    BuffData strongest = records.buff("DarkMagicAOE_Damage_lv3");
    assertThat(strongest.damagePerSecond()).isEqualTo(1330);
    assertThat(strongest.crownTowerDamagePerHit()).isEqualTo(19);
    assertThat(strongest.hitFrequency()).isEqualTo(100);
    assertThat(strongest.addAsIndividualBuff()).isTrue();
    assertThat(strongest.unmodelledColumns()).isEmpty();
    assertThat(records.buff("DarkMagicAOE_Damage_lv1").damagePerSecond()).isEqualTo(297);
    assertThat(records.buff("Rage").addAsIndividualBuff()).isFalse();
    assertThatThrownBy(() -> records.buff("DarkMagicAOE_Damage_lv4"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no buff DarkMagicAOE_Damage_lv4");
  }

  @Test
  @DisplayName(
      "a circle shape reads its radius, and any other shape is refused; Vines' snares, which name"
          + " a base and a buff for riders, are modelled")
  void vinesShapeAndSnares() {
    assertThat(records.circleRadius("Vines_AOE_Shape")).isEqualTo(2500);
    assertThatThrownBy(() -> records.circleRadius("MegaMinion_hero_shape"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("is a Global");
    BuffData snare = records.buff("Vines_Trap_Snare_Large");
    assertThat(snare.unmodelledColumns()).isEmpty();
    assertThat(snare.speedMultiplier()).isEqualTo(-100);
    assertThat(snare.hitSpeedMultiplier()).isEqualTo(-100);
    assertThat(snare.spawnSpeedMultiplier()).isEqualTo(-100);
    assertThat(snare.damagePerSecond()).isEqualTo(60);
    assertThat(snare.crownTowerDamagePerHit()).isEqualTo(15);
    assertThat(snare.enableStacking()).isTrue();
  }

  @Test
  @DisplayName(
      "a game mode's battle timeline, a card's cost, hand columns and options, and a global")
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
    assertThat(records.matchCard("Knight").variant()).isNull();
    // The Merge Maiden is played as one of its options: the triggers in ten-thousandths, each
    // option's cost and production stop its own row's.
    SpellVariant maiden = records.matchCard("MergeMaiden").variant();
    assertThat(maiden.useProjectedTimeSummon()).isTrue();
    assertThat(maiden.options())
        .containsExactly(
            new SpellVariant.Option("MergeMaiden_Mounted", 60000, 1200, 6, 0),
            new SpellVariant.Option("MergeMaiden_Normal", 30000, 1200, 3, 0));
    assertThat(records.globalNumber("MAX_MANA")).isEqualTo(10);
  }

  @Test
  @DisplayName(
      "a column a row sets that nothing reads is not modelled; presentation, inert and pending"
          + " columns are carried")
  void everyUnreadColumnIsListed() {
    BattleRecords records = GameData.records();
    // Read, and modelled in one shape only: a troop's special in its ring firing its special
    // projectile. A special projectile without a ring is never fired and carried; a building's
    // special is refused.
    assertThat(records.unit("Fisherman").unmodelledColumns()).isEmpty();
    assertThat(records.projectile("FishermanProjectile").unmodelledColumns()).isEmpty();
    assertThat(records.unit("Firecracker_EV1").unmodelledColumns()).isEmpty();
    assertThat(records.unit("Fisherbarrel").unmodelledColumns()).contains("SpecialRange");
    // The evolved Dart Goblin's poison area starts its poison damage, which is modelled.
    assertThat(records.areaEffect("BlowDartPoisonAeO_baseDamage").unmodelledColumns()).isEmpty();
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
  @DisplayName(
      "an area effect carries a taunt as its hit action, one hit per target with it, following its"
          + " parent or a target and a Filter off the Shape path; one hit per target without a hit"
          + " action is listed as not modelled")
  void aTauntingAreaEffect(@TempDir Path folder) throws IOException {
    BattleRecords records = GameData.records();
    AreaEffectData cancel = records.areaEffect("CancelTauntAEO");
    assertThat(cancel.onHitAction()).isEqualTo("ResetTauntEffect");
    assertThat(cancel.oneHitPerTarget()).isTrue();
    assertThat(cancel.followsParent()).isTrue();
    assertThat(cancel.unmodelledColumns()).as("its Filter among them").isEmpty();
    assertThat(records.areaEffect("GoblinCurseBase").followsParent()).isFalse();
    AreaEffectData ice = records.areaEffect("IceSpiritsAOE_EV1");
    assertThat(ice.followsTarget()).isTrue();
    assertThat(ice.followsParent()).isFalse();
    assertThat(ice.unmodelledColumns()).isEmpty();

    GameTables tables =
        GameData.altered(
            folder,
            "area_effect_objects",
            rows -> GameData.columns(rows, "Zap").put("OneHitPerTarget", true));
    assertThat(new BattleRecords(tables).areaEffect("Zap").unmodelledColumns())
        .containsExactly("OneHitPerTarget");
  }

  @Test
  @DisplayName("a buff carries whether it locks its carrier's reference")
  void aBuffCarriesItsTargetLock() {
    BattleRecords records = GameData.records();
    BuffData lock = records.buff("GoblinDemolisher_ResetTargetBuff");
    assertThat(lock.lockTarget()).isTrue();
    assertThat(lock.unmodelledColumns()).isEmpty();
    assertThat(records.buff("Rage").lockTarget()).isFalse();
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
