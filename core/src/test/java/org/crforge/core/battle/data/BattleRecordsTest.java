package org.crforge.core.battle.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.crforge.core.battle.GameData;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.battle.match.BattleTimeline;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.unit.BuffData;
import org.crforge.core.battle.unit.UnitData;
import org.crforge.core.pathfinding.combat.RarityTable;
import org.crforge.core.pathfinding.combat.ScalingMode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
    assertThat(records.unit("GoldenKnight").unmodelledColumns()).containsExactly("DashCount");
  }

  @Test
  @DisplayName(
      "a rider carries what it may target, and its bola's slow is among the columns refused")
  void riderTargetingColumns() {
    UnitData rider = records.unit("RamRider");
    assertThat(rider.targetOnlyTroops()).isTrue();
    assertThat(rider.ignoreTargetsWithBuff()).isEqualTo("BolaSnare");
    assertThat(rider.deprioritizeTargetsWithBuff()).isTrue();
    assertThat(rider.projectile().unmodelledColumns()).containsExactly("PingpongMovingShooter");
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
    assertThat(records.unit("Tesla").unmodelledColumns()).containsExactly("HidesWhenNotAttacking");
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
    assertThat(records.unit("PhoenixEgg").unmodelledColumns())
        .containsExactly("DestroyAtLimit", "SpawnCharacterWithDeploy", "SpawnLimit");
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
}
