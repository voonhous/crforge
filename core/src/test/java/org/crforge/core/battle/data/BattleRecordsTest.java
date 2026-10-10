/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.crforge.core.battle.Shipped.column;
import static org.crforge.core.battle.Shipped.flag;
import static org.crforge.core.battle.Shipped.number;
import static org.crforge.core.battle.Shipped.numbers;
import static org.crforge.core.battle.Shipped.row;
import static org.crforge.core.battle.Shipped.text;
import static org.crforge.core.battle.Shipped.texts;
import static org.crforge.core.battle.Shipped.unitRow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

  /** Which bit of a tag word each flag is, as the configured tables number the game tags. */
  private static final EntityFlags BITS = EntityFlags.of(GameData.tables());

  private static BattleRecords records;

  @BeforeAll
  static void load() {
    records = new BattleRecords(GameTables.loadConfigured());
  }

  @Test
  @DisplayName("a unit's fields are its row's columns, in milliseconds and game units")
  void aUnitIsItsColumns() {
    UnitData knight = records.unit("Knight");
    GameRow row = unitRow("Knight");
    assertThat(knight.name()).isEqualTo("Knight");
    // The Knight never stops walking, so it is loaded at its column's speed.
    assertThat(number(row, "StopMovementAfterMS")).isZero();
    assertThat(knight.speed()).isEqualTo(number(row, "Speed"));
    assertThat(knight.range()).isEqualTo(number(row, "Range"));
    assertThat(knight.sightRange()).isEqualTo(number(row, "SightRange"));
    assertThat(knight.collisionRadius()).isEqualTo(number(row, "CollisionRadius"));
    assertThat(knight.mass()).isEqualTo(number(row, "Mass"));
    assertThat(knight.hitSpeedMs()).isEqualTo(number(row, "HitSpeed"));
    assertThat(knight.loadTimeMs()).isEqualTo(number(row, "LoadTime"));
    assertThat(knight.deployTimeMs()).isEqualTo(number(row, "DeployTime"));
    assertThat(knight.attacksGround()).isEqualTo(flag(row, "AttacksGround"));
    assertThat(knight.attacksAir()).isEqualTo(flag(row, "AttacksAir"));
    assertThat(knight.air()).isFalse();
    assertThat(knight.building()).isFalse();
    assertThat(knight.hitpoints()).isEqualTo(number(row, "Hitpoints"));
    assertThat(knight.damage()).isEqualTo(number(row, "Damage"));
    assertThat(knight.rarity()).isEqualTo(RarityTable.COMMON);
    assertThat(knight.projectile()).isNull();
    assertThat(knight.projectileStartRadius()).isEqualTo(number(row, "ProjectileStartRadius"));
    assertThat(knight.projectileStartZ()).isEqualTo(number(row, "ProjectileStartZ"));

    UnitData valkyrie = records.unit("Valkyrie");
    GameRow valkyrieRow = unitRow("Valkyrie");
    assertThat(valkyrie.areaDamageRadius()).isEqualTo(number(valkyrieRow, "AreaDamageRadius"));
    assertThat(valkyrie.selfAsAoeCenter()).isEqualTo(flag(valkyrieRow, "SelfAsAoeCenter"));
    assertThat(valkyrie.overrideAttackFinishTime())
        .isEqualTo(flag(valkyrieRow, "OverrideAttackFinishTime"));
    assertThat(valkyrie.attackFinishTimeMs()).isEqualTo(number(valkyrieRow, "AttackFinishTime"));
  }

  @Test
  @DisplayName(
      "a unit that stops walking for a while is loaded at a speed raised by its walk and wait"
          + " times, so its pauses cost it nothing")
  void aWalkAndWaitUnitsSpeedIsRaisedAsItLoads() {
    // (WaitMS + StopMovementAfterMS) * 1000 / StopMovementAfterMS, truncated, times Speed, over
    // 1000, truncated.
    for (String name : List.of("Giant", "Golem", "IceGolemite", "GoblinGiant")) {
      GameRow row = unitRow(name);
      int walk = number(row, "StopMovementAfterMS");
      int wait = number(row, "WaitMS");
      assertThat(walk).as(name + " stops walking").isPositive();
      assertThat(wait).as(name + " waits").isPositive();
      int ratio = (wait + walk) * 1000 / walk;
      assertThat(records.unit(name).speed())
          .as(name)
          .isEqualTo(number(row, "Speed") * ratio / 1000);
      assertThat(records.unit(name).stopMovementAfterMs()).as(name).isEqualTo(walk);
      assertThat(records.unit(name).waitMs()).as(name).isEqualTo(wait);
    }
    // A unit that never stops keeps its column.
    assertThat(records.unit("Knight").speed()).isEqualTo(number(unitRow("Knight"), "Speed"));
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
    // A building's row writes no mass: (radius * radius / 250) * radius / 62500, held to 1..20.
    for (String name : List.of("PrincessTower", "KingTower")) {
      GameRow row = unitRow(name);
      assertThat(number(row, "Mass")).as(name + " writes no mass").isZero();
      int radius = number(row, "CollisionRadius");
      int worked = Math.min(20, Math.max(1, (radius * radius / 250) * radius / 62500));
      assertThat(records.unit(name).mass()).as(name).isEqualTo(worked);
    }
    // A written mass within the bounds is kept.
    for (String name : List.of("Knight", "Skeleton", "Giant")) {
      int mass = number(unitRow(name), "Mass");
      assertThat(mass).as(name + " writes a mass within the bounds").isBetween(1, 20);
      assertThat(records.unit(name).mass()).as(name).isEqualTo(mass);
    }
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
    assertThat(number(unitRow("Minion"), "FlyingHeight")).isPositive();
    assertThat(minion.air()).isTrue();
    assertThat(minion.flyingHeight()).isEqualTo(number(unitRow("Minion"), "FlyingHeight"));
    assertThat(minion.attacksAir()).isEqualTo(flag(unitRow("Minion"), "AttacksAir"));
  }

  @Test
  @DisplayName("a unit that fires carries its projectile's row, and its own empty damage column")
  void aUnitThatFires() {
    UnitData musketeer = records.unit("Musketeer");
    assertThat(musketeer.damage()).as("the unit's own column is empty").isZero();
    ProjectileData projectile = musketeer.projectile();
    GameRow row = row("projectiles", text(unitRow("Musketeer"), "Projectile"));
    assertThat(projectile.name()).isEqualTo(row.name());
    assertThat(projectile.damage()).isEqualTo(number(row, "Damage"));
    assertThat(projectile.speed()).isEqualTo(number(row, "Speed"));
    assertThat(projectile.homing()).isEqualTo(flag(row, "Homing"));
    assertThat(projectile.onlyEnemies()).isEqualTo(flag(row, "OnlyEnemies"));
    assertThat(projectile.rarity()).isEqualTo(RarityTable.COMMON);
    assertThat(projectile.damageMode()).isEqualTo(ScalingMode.CARD_DAMAGE);
  }

  @Test
  @DisplayName("a projectile's damage scaling mode names its rule")
  void aProjectileScalingMode() {
    ProjectileData arrow = records.projectile("TowerPrincessProjectile");
    GameRow row = row("projectiles", "TowerPrincessProjectile");
    assertThat(arrow.damageMode()).isEqualTo(ScalingMode.TOWER_DAMAGE);
    assertThat(arrow.gravity()).isEqualTo(number(row, "Gravity"));
    assertThat(arrow.speed()).isEqualTo(number(row, "Speed"));
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
    GameRow snowballRow = row("projectiles", "SnowballSpell");
    assertThat(snowball.targetBuff()).isEqualTo(text(snowballRow, "TargetBuff"));
    assertThat(snowball.buffTimeMs()).isEqualTo(number(snowballRow, "BuffTime"));
    assertThat(snowball.applyBuffBeforeDamage())
        .isEqualTo(flag(snowballRow, "ApplyBuffBeforeDamage"));
    // An empty target limit is the loader's 1000.
    assertThat(snowball.maximumTargets()).isEqualTo(number(snowballRow, "MaximumTargets", 1000));
    assertThat(snowball.unmodelledColumns()).isEmpty();
    ProjectileData voodoo = records.projectile("VoodooProjectile");
    GameRow voodooRow = row("projectiles", "VoodooProjectile");
    assertThat(voodoo.targetBuff()).isEqualTo(text(voodooRow, "TargetBuff"));
    assertThat(voodoo.applyBuffBeforeDamage()).isEqualTo(flag(voodooRow, "ApplyBuffBeforeDamage"));
    assertThat(voodoo.unmodelledColumns()).isEmpty();
    ProjectileData chain = records.projectile("ElectroDragonProjectile");
    GameRow chainRow = row("projectiles", "ElectroDragonProjectile");
    assertThat(chain.chainedHitRadius()).isEqualTo(number(chainRow, "ChainedHitRadius"));
    assertThat(chain.chainedHitCount()).isEqualTo(number(chainRow, "ChainedHitCount"));
    assertThat(chain.unmodelledColumns()).isEmpty();
    // A spawned row's count and radius make its fan.
    ProjectileData explosion = records.projectile("FirecrackerExplosion");
    GameRow explosionRow = row("projectiles", "FirecrackerExplosion");
    assertThat(explosion.spawnCount()).isEqualTo(number(explosionRow, "SpawnCount"));
    assertThat(explosion.spawnRadius()).isEqualTo(number(explosionRow, "SpawnRadius"));
    assertThat(explosion.unmodelledColumns()).isEmpty();
    // A pingpong row's sweep time.
    ProjectileData axe = records.projectile("AxeManProjectile");
    assertThat(axe.pingpongVisualTimeMs())
        .isEqualTo(number(row("projectiles", "AxeManProjectile"), "PingpongVisualTime"));
    assertThat(axe.unmodelledColumns()).isEmpty();
    // The Hunter's pellet: a line scatter, a random delay and a stop at the first landed hit.
    ProjectileData pellet = records.projectile("HunterProjectile");
    GameRow pelletRow = row("projectiles", "HunterProjectile");
    assertThat(pellet.lineScatter()).isTrue();
    assertThat(pellet.randomDelayMs()).isEqualTo(number(pelletRow, "RandomDelay"));
    assertThat(pellet.checkCollisions()).isEqualTo(flag(pelletRow, "CheckCollisions"));
    assertThat(pellet.unmodelledColumns()).isEmpty();
    assertThat(records.unit("Hunter").customFirstProjectile().name())
        .isEqualTo(text(unitRow("Hunter"), "CustomFirstProjectile"));
    // A projectile that flies to a point buffs through its hits on the way, which is not modelled.
    assertThat(records.projectile("SuperEliteArcherArrow").unmodelledColumns())
        .contains("TargetBuff");
  }

  @Test
  @DisplayName(
      "a projectile's spawn chain is carried, and whether its spawns share its group or are new"
          + " projectiles")
  void aProjectileSpawnChain(@TempDir Path folder) throws IOException {
    ProjectileData bomb = records.projectile("BombSkeletonProjectile_EV1");
    GameRow bombRow = row("projectiles", "BombSkeletonProjectile_EV1");
    assertThat(bomb.spawnProjectile()).isEqualTo(text(bombRow, "SpawnProjectile"));
    assertThat(bomb.spawnChain()).isEqualTo(number(bombRow, "SpawnChain"));
    assertThat(bomb.chainIsNewProjectile()).isFalse();
    assertThat(bomb.unmodelledColumns()).isEmpty();
    ProjectileData fireWall = records.projectile("FireWallProjectile");
    GameRow fireWallRow = row("projectiles", "FireWallProjectile");
    assertThat(fireWall.spawnProjectile()).isEqualTo(text(fireWallRow, "SpawnProjectile"));
    assertThat(fireWall.spawnChain()).isEqualTo(number(fireWallRow, "SpawnChain"));
    // No configured row makes its chain's spawns new projectiles; a row that does carries it.
    GameTables tables =
        GameData.altered(
            folder,
            "projectiles",
            rows -> {
              ObjectNode columns = GameData.columns(rows, "FireWallProjectile");
              columns.put("SpawnChain", 4);
              columns.put("ChainIsNewProjectile", true);
            });
    ProjectileData chained = new BattleRecords(tables).projectile("FireWallProjectile");
    assertThat(chained.spawnChain()).isEqualTo(4);
    assertThat(chained.chainIsNewProjectile()).isTrue();
  }

  @Test
  @DisplayName("a buff's death spawn is carried")
  void aBuffDeathSpawn() {
    BuffData curse = records.buff("VoodooCurse");
    GameRow row = row("character_buffs", "VoodooCurse");
    assertThat(curse.deathSpawn()).isEqualTo(text(row, "DeathSpawn"));
    assertThat(curse.deathSpawnCount()).isEqualTo(number(row, "DeathSpawnCount"));
    assertThat(curse.deathSpawnIsEnemy()).isEqualTo(flag(row, "DeathSpawnIsEnemy"));
    assertThat(curse.deathSpawnDeployDelay()).isEqualTo(flag(row, "DeathSpawnDeployDelay"));
    assertThat(curse.otherBuffDeathSpawnAllowed())
        .isEqualTo(flag(row, "OtherBuffDeathSpawnAllowed"));
    assertThat(curse.unmodelledColumns()).isEmpty();
    assertThat(records.buff("PancakesCurse").deathSpawnDeployDelay())
        .isEqualTo(flag(row("character_buffs", "PancakesCurse"), "DeathSpawnDeployDelay"));
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
    GameRow row = row("spells_characters", "Barbarians");
    assertThat(barbarians.name()).isEqualTo("Barbarians");
    assertThat(barbarians.unit().name()).isEqualTo(text(row, "SummonCharacter"));
    assertThat(barbarians.count()).isEqualTo(number(row, "SummonNumber"));
    assertThat(barbarians.summonRadius()).isEqualTo(number(row, "SummonRadius"));
    assertThat(barbarians.summonDeployDelayMs()).isEqualTo(number(row, "SummonDeployDelay"));
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
    assertThat(cannon.unit().name())
        .isEqualTo(text(row("spells_buildings", "Cannon"), "SummonCharacter"));
    assertThat(cannon.unit().building()).isTrue();
    assertThat(cannon.count()).isEqualTo(1);
    assertThat(cannon.summonDeployDelayMs()).isZero();

    // A card's name is its own: the unit's row may be named otherwise.
    for (String card : List.of("Elixir Collector", "GoblinHut")) {
      assertThat(records.card(card).unit().name())
          .as(card)
          .isEqualTo(text(row("spells_buildings", card), "SummonCharacter"));
    }
    // Deploying as a spell changes nothing for the Goblin Drill: its dig is its unit, which tunnels
    // in and is refused where it is played.
    assertThat(records.card("GoblinDrill").unit().name())
        .isEqualTo(text(row("spells_buildings", "GoblinDrill"), "SummonCharacter"));
  }

  @Test
  @DisplayName("a level index on a card is not read: the summoned unit keeps the card's level")
  void theLevelIndexIsNotRead() {
    DeployCard army = records.card("SkeletonArmy");
    GameRow row = row("spells_characters", "SkeletonArmy");
    assertThat(number(row, "SummonCharacterLevelIndex"))
        .as("the row sets a level index")
        .isPositive();
    assertThat(army.unit().name()).isEqualTo(text(row, "SummonCharacter"));
    assertThat(army.count()).isEqualTo(number(row, "SummonNumber"));
  }

  @Test
  @DisplayName(
      "a card that lists its characters summons each at its offset after no group, its first as"
          + " the card's unit")
  void aListedCardSummonsItsList() {
    DeployCard card = records.card("ThreeMusketeers");
    GameRow row = row("spells_characters", "ThreeMusketeers");
    List<String> list = texts(row, "SummonCharactersList");
    assertThat(card.unit().name()).isEqualTo(list.get(0));
    assertThat(card.primaryCount()).isZero();
    assertThat(card.secondaryTotal()).isZero();
    assertThat(card.total()).isEqualTo(list.size());
    assertThat(card.listed())
        .extracting(l -> l.unit().name() + " " + l.offsetX() + " " + l.offsetY())
        .containsExactlyElementsOf(listed(row));
    assertThat(card.listOffsetsXMirrored()).isEqualTo(flag(row, "CharactersOffsetsXMirrored"));
    assertThat(card.summonDeployDelayMs()).isEqualTo(number(row, "SummonDeployDelay"));
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
    GameRow row = row("spells_evolved", "SkeletonArmy_EV1");
    String soldier = text(row, "SummonCharacter");
    int soldiers = number(row, "SummonNumber");
    List<String> list = texts(row, "SummonCharactersList");
    assertThat(card.unit().name()).isEqualTo(soldier);
    assertThat(card.namesCharacter()).isTrue();
    assertThat(card.primaryCount()).isEqualTo(soldiers);
    assertThat(card.secondaryTotal()).isZero();
    assertThat(card.total()).isEqualTo(soldiers + list.size());
    assertThat(card.unitAt(0).name()).isEqualTo(soldier);
    assertThat(card.unitAt(soldiers - 1).name()).isEqualTo(soldier);
    assertThat(card.unitAt(soldiers).name()).isEqualTo(list.get(0));
    assertThat(card.listed())
        .extracting(l -> l.unit().name() + " " + l.offsetX() + " " + l.offsetY())
        .containsExactlyElementsOf(listed(row));
    assertThat(card.group()).isEqualTo(flag(row, "IsAGroup"));
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

  /** A card row's listed characters, each with its offsets, as "name x y" in the list's order. */
  private static List<String> listed(GameRow card) {
    List<String> names = texts(card, "SummonCharactersList");
    List<Integer> x = numbers(card, "SummonCharactersOffsetsX");
    List<Integer> y = numbers(card, "SummonCharactersOffsetsY");
    List<String> out = new ArrayList<>();
    for (int i = 0; i < names.size(); i++) {
      out.add(names.get(i) + " " + x.get(i) + " " + y.get(i));
    }
    return out;
  }

  @Test
  @DisplayName("a unit carries the names of its row's three hook actions, null for none")
  void hookNames() {
    UnitData king = records.unit("KingTower");
    assertThat(king.onStartingAction()).isEqualTo(text(unitRow("KingTower"), "OnStartingAction"));
    assertThat(king.onDeathAction()).isNull();
    assertThat(records.unit("Witch_EV1").onStartingAction())
        .isEqualTo(text(unitRow("Witch_EV1"), "OnStartingAction"));
    assertThat(records.unit("IceGolemite").onDeathAction())
        .isEqualTo(text(unitRow("IceGolemite"), "OnDeathAction"));
    assertThat(records.unit("BossBandit").onKilledAction())
        .isEqualTo(text(unitRow("BossBandit"), "OnKilledAction"));
    UnitData knight = records.unit("Knight");
    assertThat(knight.onStartingAction()).isNull();
    assertThat(knight.onDeathAction()).isNull();
    assertThat(knight.onKilledAction()).isNull();
  }

  @Test
  @DisplayName(
      "a unit carries its death damage, pushback and death spawn, and lists the columns of its"
          + " death the battle does not model")
  void deathColumns(@TempDir Path folder) throws IOException {
    // No configured row deals damage or pushes as it dies, its death area effect doing both; a row
    // that does carries them.
    GameData.altered(
        folder,
        "buildings",
        rows -> {
          ObjectNode columns = GameData.columns(rows, "Tombstone");
          columns.put("DeathDamage", 500);
          columns.put("DeathDamageRadius", 3000);
        });
    GameData.alterLoaded(
        folder, "characters", rows -> GameData.columns(rows, "Golemite").put("DeathPushBack", 900));
    BattleRecords altered = new BattleRecords(GameTables.load(folder));
    UnitData tombstone = altered.unit("Tombstone");
    assertThat(tombstone.deathDamage()).isEqualTo(500);
    assertThat(tombstone.deathDamageRadius()).isEqualTo(3000);
    assertThat(tombstone.unmodelledDeathColumns()).isEmpty();
    // The Golem's children fly back to their ring points; the Battle Ram's turn by its facing.
    UnitData golem = records.unit("Golem");
    assertThat(golem.unmodelledDeathColumns()).isEmpty();
    assertThat(golem.deathSpawnPushback()).isTrue();
    assertThat(records.unit("BattleRam").unmodelledDeathColumns()).isEmpty();
    assertThat(records.unit("BattleRam").spawnAngleShift())
        .isEqualTo(number(unitRow("BattleRam"), "SpawnAngleShift"));
    assertThat(records.unit("ElixirGolem1").unmodelledDeathColumns()).isEmpty();
    UnitData golemite = altered.unit("Golemite");
    assertThat(golemite.deathPushBack()).isEqualTo(900);
    assertThat(golemite.targetOnlyBuildings()).isTrue();
    UnitData elixirGolem = records.unit("ElixirGolem2");
    GameRow elixirGolemRow = unitRow("ElixirGolem2");
    assertThat(elixirGolem.deathSpawnCharacter())
        .isEqualTo(text(elixirGolemRow, "DeathSpawnCharacter"));
    assertThat(elixirGolem.deathSpawnCount()).isEqualTo(number(elixirGolemRow, "DeathSpawnCount"));
    assertThat(elixirGolem.deathSpawnRadius())
        .isEqualTo(number(elixirGolemRow, "DeathSpawnRadius"));
    assertThat(records.unit("Knight").deathSpawnCount()).isZero();
    UnitData knight = records.unit("Knight");
    assertThat(knight.deathDamage()).isZero();
    assertThat(knight.unmodelledDeathColumns()).isEmpty();
  }

  @Test
  @DisplayName("a unit carries its charge, its river jump and whether its hit destroys it")
  void chargeAndJumpColumns() {
    UnitData prince = records.unit("Prince");
    GameRow princeRow = unitRow("Prince");
    assertThat(prince.chargeRange()).isEqualTo(number(princeRow, "ChargeRange"));
    assertThat(prince.chargeSpeedMultiplier())
        .isEqualTo(number(princeRow, "ChargeSpeedMultiplier"));
    assertThat(prince.damageSpecial()).isEqualTo(number(princeRow, "DamageSpecial"));
    assertThat(prince.keepChargingAfterAttack())
        .isEqualTo(flag(princeRow, "KeepChargingAfterAttack"));
    assertThat(prince.jumpEnabled()).isEqualTo(flag(princeRow, "JumpEnabled"));
    assertThat(prince.jumpHeight()).isEqualTo(number(princeRow, "JumpHeight"));
    assertThat(prince.jumpSpeed()).isEqualTo(number(princeRow, "JumpSpeed"));
    assertThat(prince.kamikaze()).isEqualTo(flag(princeRow, "Kamikaze"));
    assertThat(records.unit("BattleRam_EV1").keepChargingAfterAttack())
        .isEqualTo(flag(unitRow("BattleRam_EV1"), "KeepChargingAfterAttack"));
    UnitData ram = records.unit("BattleRam");
    GameRow ramRow = unitRow("BattleRam");
    assertThat(ram.chargeRange()).isEqualTo(number(ramRow, "ChargeRange"));
    assertThat(ram.jumpEnabled()).isEqualTo(flag(ramRow, "JumpEnabled"));
    assertThat(ram.kamikaze()).isEqualTo(flag(ramRow, "Kamikaze"));
    UnitData knight = records.unit("Knight");
    assertThat(knight.chargeRange()).isZero();
    assertThat(knight.jumpEnabled()).isFalse();
  }

  @Test
  @DisplayName("a unit carries its dash, and the Golden Knight its chain and its ability's dash")
  void dashColumns() {
    UnitData bandit = records.unit("Assassin");
    GameRow banditRow = unitRow("Assassin");
    assertThat(bandit.dashCooldown()).isEqualTo(number(banditRow, "DashCooldown"));
    assertThat(bandit.dashMinRange()).isEqualTo(number(banditRow, "DashMinRange"));
    assertThat(bandit.dashMaxRange()).isEqualTo(number(banditRow, "DashMaxRange"));
    assertThat(bandit.dashDamage()).isEqualTo(number(banditRow, "DashDamage"));
    assertThat(bandit.dashRadius()).isZero();
    assertThat(bandit.dashLandingTimeMs()).isZero();
    assertThat(bandit.dashImmuneToDamageTimeMs())
        .isEqualTo(number(banditRow, "DashImmuneToDamageTime"));
    assertThat(bandit.jumpSpeed()).isEqualTo(number(banditRow, "JumpSpeed"));
    assertThat(bandit.unmodelledColumns()).isEmpty();
    UnitData megaKnight = records.unit("MegaKnight");
    GameRow megaKnightRow = unitRow("MegaKnight");
    assertThat(megaKnight.dashRadius()).isEqualTo(number(megaKnightRow, "DashRadius"));
    assertThat(megaKnight.dashPushBack()).isEqualTo(number(megaKnightRow, "DashPushBack"));
    assertThat(megaKnight.dashLandingTimeMs()).isEqualTo(number(megaKnightRow, "DashLandingTime"));
    assertThat(megaKnight.dashConstantTimeMs())
        .isEqualTo(number(megaKnightRow, "DashConstantTime"));
    assertThat(megaKnight.jumpHeight()).isEqualTo(number(megaKnightRow, "JumpHeight"));
    assertThat(megaKnight.dashToTargetRadius())
        .isEqualTo(flag(megaKnightRow, "DashToTargetRadius"));
    // The Golden Knight's dashes chain: DashCount at most, the next looked for within its
    // secondary range of the landing, nearest first within its back dash radius behind it as
    // ahead.
    UnitData goldenKnight = records.unit("GoldenKnight");
    GameRow goldenKnightRow = unitRow("GoldenKnight");
    assertThat(goldenKnight.unmodelledColumns()).isEmpty();
    assertThat(goldenKnight.dashCount()).isEqualTo(number(goldenKnightRow, "DashCount"));
    assertThat(goldenKnight.dashSecondaryRange())
        .isEqualTo(number(goldenKnightRow, "DashSecondaryRange"));
    assertThat(goldenKnight.backDashRadius()).isEqualTo(number(goldenKnightRow, "BackDashRadius"));
    // Its ability waits for a reference within its dash range and runs its activation group as
    // it fires; it names no pending buff.
    AbilityData chain = goldenKnight.ability();
    GameRow chainRow = row("character_abilities", text(goldenKnightRow, "Ability"));
    assertThat(chain.unmodelledColumns()).isEmpty();
    assertThat(chain.dashRange()).isEqualTo(number(chainRow, "DashRange"));
    assertThat(chain.onActivationAction()).isEqualTo(text(chainRow, "OnActivationAction"));
    assertThat(chain.pendingBuff()).isNull();
    // Its deploy push is read, and its spawner's limit changes nothing without a spawn.
    assertThat(megaKnight.spawnPushback()).isEqualTo(number(megaKnightRow, "SpawnPushback"));
    assertThat(megaKnight.spawnPushbackRadius())
        .isEqualTo(number(megaKnightRow, "SpawnPushbackRadius"));
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
    assertThat(megaKnight.projectile())
        .isEqualTo(text(row("spells_characters", "MegaKnight"), "Projectile"));
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
    GameRow lightningRow = row("area_effect_objects", "Lightning");
    assertThat(lightning.projectile()).isEqualTo(text(lightningRow, "Projectile"));
    assertThat(lightning.hitBiggestTargets()).isEqualTo(flag(lightningRow, "HitBiggestTargets"));
    assertThat(lightning.projectileStartHeight())
        .isEqualTo(number(lightningRow, "ProjectileStartHeight"));
    assertThat(lightning.unmodelledColumns()).isEmpty();
    AreaEffectData delivery = records.areaEffect("RoyalDeliveryArea");
    GameRow deliveryRow = row("area_effect_objects", "RoyalDeliveryArea");
    assertThat(delivery.projectile()).isEqualTo(text(deliveryRow, "Projectile"));
    assertThat(delivery.hitBiggestTargets()).isEqualTo(flag(deliveryRow, "HitBiggestTargets"));
    assertThat(delivery.projectileStartHeight())
        .isEqualTo(number(deliveryRow, "ProjectileStartHeight"));
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
    GameRow row = row("area_effect_objects", "SkeletonKingGraveyard");
    assertThat(graveyard.spawnCharacter()).isEqualTo(text(row, "SpawnCharacter"));
    assertThat(graveyard.spawnIntervalMs()).isEqualTo(number(row, "SpawnInterval"));
    assertThat(graveyard.spawnInitialDelayMs()).isEqualTo(number(row, "SpawnInitialDelay"));
    assertThat(graveyard.spawnTimeMs()).isEqualTo(number(row, "SpawnTime"));
    assertThat(graveyard.spawnMaxCount()).isEqualTo(number(row, "SpawnMaxCount"));
    assertThat(graveyard.spawnMinRadius()).isEqualTo(number(row, "SpawnMinRadius"));
    assertThat(graveyard.spawnRandomizeSequence()).isEqualTo(flag(row, "SpawnRandomizeSequence"));
    assertThat(graveyard.spawnClones()).isEqualTo(flag(row, "SpawnClones"));
    assertThat(graveyard.stayAfterParentDies()).isEqualTo(flag(row, "StayAfterParentDies"));
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
    assertThat(valkyrie.onAttackAction())
        .isEqualTo(text(unitRow("Valkyrie_EV1"), "OnAttackAction"));
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
    for (String name : List.of("MegaKnight_EV1", "BabyDragon_EV1")) {
      assertThat(records.unit(name).onAttackAction())
          .as(name)
          .isEqualTo(text(unitRow(name), "OnAttackAction"));
    }
    // The evolved Inferno Dragon's counts its attacks in a variable; its list's entries leave the
    // row's two variable damage times unread.
    assertThat(records.unit("InfernoDragon_EV1").onAttackAction())
        .isEqualTo(text(unitRow("InfernoDragon_EV1"), "OnAttackAction"));
    // The evolved Royal Hog's fall is a group, whose parts are built from their own rows.
    assertThat(records.unit("RoyalHog_EV1").onAttackAction())
        .isEqualTo(text(unitRow("RoyalHog_EV1"), "OnAttackAction"));
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
      "a shaped area effect of the filter form reads its rectangle and its filter; a circle with a"
          + " filter and damage reads its radius and its damage, by name or inline, and one whose"
          + " hit action chooses a buff its hit action")
  void aShapedAreaEffect() {
    AreaEffectData wind = records.areaEffect("BabyDragon_EV1_wind_aeo");
    GameRow windRow = row("area_effect_objects", "BabyDragon_EV1_wind_aeo");
    GameRow windShape = row("shapes", text(windRow, "Shape"));
    assertThat(wind.shaped()).isTrue();
    assertThat(List.of(wind.shapeWidth(), wind.shapeHeight()))
        .containsExactly(number(windShape, "Width"), number(windShape, "Height"));
    assertThat(wind.filter()).isEqualTo(text(windRow, "Filter"));
    assertThat(wind.onHitAction()).isEqualTo(text(windRow, "OnHitAction"));
    assertThat(wind.unmodelledColumns()).isEmpty();
    assertThat(records.areaEffect("Zap").shaped()).isFalse();
    assertThat(records.areaEffect("Zap").filter())
        .isEqualTo(text(row("area_effect_objects", "Zap"), "Filter"));
    // A circle whose hit action chooses a buff to spawn on what it reaches, hitting on every
    // update, as the Ice Golemite hero form's slow circle does; its damage type is read by no hit.
    AreaEffectData slow = records.areaEffect("IceGolemiteHero_Slow_AEO");
    GameRow slowRow = row("area_effect_objects", "IceGolemiteHero_Slow_AEO");
    assertThat(slow.unmodelledColumns()).isEmpty();
    assertThat(slow.shapeRadius())
        .isEqualTo(number(row("shapes", text(slowRow, "Shape")), "Radius"));
    assertThat(slow.onHitAction()).isEqualTo(text(slowRow, "OnHitAction"));
    assertThat(slow.damage()).isZero();
    assertThat(slow.damageType()).isNull();
    assertThat(slow.hitSpeedMs()).isEqualTo(number(slowRow, "HitSpeed"));
    // A circle whose damage names a damage type row, hitting at its row's hit speed, as the Ice
    // Golemite hero form's ability has; where its looping effect is shown is the view's.
    AreaEffectData storm = records.areaEffect("IceGolemiteHero_Damage_AEO");
    GameRow stormRow = row("area_effect_objects", "IceGolemiteHero_Damage_AEO");
    assertThat(storm.unmodelledColumns()).isEmpty();
    assertThat(storm.shapeRadius())
        .isEqualTo(number(row("shapes", text(stormRow, "Shape")), "Radius"));
    assertThat(storm.typedDamage().name()).isEqualTo(text(stormRow, "Damage"));
    assertThat(storm.filter()).isEqualTo(text(stormRow, "Filter"));
    assertThat(List.of(storm.hitSpeedMs(), storm.hitSpeedOffsetMs()))
        .containsExactly(number(stormRow, "HitSpeed"), number(stormRow, "HitSpeedOffset"));
    // A circle with a filter and its damage written inline, as the Giant hero form's landing has.
    AreaEffectData landing = records.areaEffect("GiantHero_LandingAEO");
    GameRow landingRow = row("area_effect_objects", "GiantHero_LandingAEO");
    assertThat(landing.unmodelledColumns()).isEmpty();
    assertThat(landing.shaped()).isTrue();
    assertThat(landing.shapeRadius())
        .isEqualTo(number(row("shapes", text(landingRow, "Shape")), "Radius"));
    assertThat(landing.shapeWidth()).isZero();
    assertThat(landing.filter()).isEqualTo(text(landingRow, "Filter"));
    assertThat(landing.typedDamage().baseDamage())
        .isEqualTo(column(landingRow, "Damage").path("BaseDamage").asInt());
  }

  @Test
  @DisplayName(
      "a buff's start and remove actions are read when they name an action row or are an inline"
          + " group of named rows, and listed as not modelled when written inline otherwise")
  void aBuffsHooksAreReadByName() {
    BuffData invisibility = records.buff("Ghost_EV1_Invisibility");
    GameRow invisibilityRow = row("character_buffs", "Ghost_EV1_Invisibility");
    assertThat(invisibility.onStartAction()).isEqualTo(text(invisibilityRow, "OnStartAction"));
    assertThat(invisibility.onRemoveAction()).isEqualTo(text(invisibilityRow, "OnRemoveAction"));
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
          + " Mega Minion's arrival buff sets both")
  void aBuffsProjectileAndRemovalOnAttackAreRead() {
    BuffData arrival = records.buff("MegaMinion_hero_Damage_Buff");
    GameRow arrivalRow = row("character_buffs", "MegaMinion_hero_Damage_Buff");
    assertThat(arrival.overrideProjectile()).isEqualTo(text(arrivalRow, "OverrideProjectile"));
    assertThat(arrival.removeOnAttack()).isEqualTo(flag(arrivalRow, "RemoveOnAttack"));
    assertThat(arrival.onRemoveAction()).isNull();
    assertThat(arrival.unmodelledColumns()).isEmpty();
    assertThat(records.buff("Rage").overrideProjectile()).isNull();
    assertThat(records.buff("Rage").removeOnAttack()).isFalse();
  }

  @Test
  @DisplayName(
      "a buff's tags are read when the only one is the one that keeps enemies from pushing its"
          + " carrier, and listed as not modelled otherwise")
  void aBuffSetsOnlyTheTagThePushPassReads(@TempDir Path folder) throws IOException {
    BuffData notPushed = records.buff("Valkyrie_NotPushed_BUF");
    assertThat(notPushed.gameTagsToSet()).isEqualTo(BITS.noPushedByEnemy());
    assertThat(notPushed.unmodelledColumns()).isEmpty();
    assertThat(records.buff("Valkyrie_MiniTornado_EV1_BUFF").gameTagsToSet()).isZero();

    GameTables tables =
        GameData.altered(
            folder,
            "character_buffs",
            rows ->
                GameData.columns(rows, "Valkyrie_NotPushed_BUF")
                    .put("GameTagsToSet", "NO_PUSHED_BY_ENEMY,NO_DASH"));
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
              // Two hits over its life, without HitBiggestTargets, written in the hit-switch form
              // whose hit pass spreads them: the configured row is in the filter form.
              ObjectNode delivery = GameData.columns(rows, "RoyalDeliveryArea");
              delivery.remove("Filter");
              delivery.put("HitsAir", true);
              delivery.put("HitsGround", true);
              delivery.put("OnlyEnemies", true);
              delivery.put("IgnoreBuildings", true);
              delivery.put("HitSpeed", 1000);
              // A hit-switch row puts a projectile onto each object it hits unless it says not.
              delivery.remove("TargetProjectiles");
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
    assertThat(spirit.spawnAreaEffectObject())
        .isEqualTo(text(row("projectiles", "HealSpiritProjectile"), "SpawnAreaEffectObject"));
    assertThat(spirit.unmodelledColumns()).isEmpty();
    assertThat(records.projectile("FireballSpell").spawnAreaEffectObject()).isNull();
    // A row that follows the projectile is made on its first flight visit, not at its impact.
    ProjectileData parent = records.projectile("SuperArcherChargeArrow");
    assertThat(parent.spawnAreaEffectObject())
        .isEqualTo(text(row("projectiles", "SuperArcherChargeArrow"), "SpawnAreaEffectObject"));
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
    assertThat(clone.onHitAction())
        .isEqualTo(text(row("area_effect_objects", "Clone"), "OnHitAction"));
    assertThat(clone.unmodelledColumns()).isEmpty();
    assertThat(records.areaEffect("Zap").cloning()).isFalse();
    // The Goblin Curse's base spawns two buffs with each hit and the Blowdart Goblin's evolution
    // starts its poison damage; the Knight's hero taunts with a group of taunts.
    AreaEffectData curse = records.areaEffect("GoblinCurseBase");
    assertThat(curse.onHitAction())
        .isEqualTo(text(row("area_effect_objects", "GoblinCurseBase"), "OnHitAction"));
    assertThat(curse.unmodelledColumns()).isEmpty();
    assertThat(records.areaEffect("Knight_hero_TauntAEO").unmodelledColumns()).isEmpty();
    assertThat(records.areaEffect("BlowDartPoisonAeO_baseDamage").unmodelledColumns()).isEmpty();
    assertThat(records.unit("Recruit_Chess").ignoreClone())
        .isEqualTo(flag(unitRow("Recruit_Chess"), "IgnoreClone"));
    assertThat(records.unit("Knight").ignoreClone()).isFalse();
    assertThat(records.unit("Knight_EV1").clonedVersion())
        .isEqualTo(text(unitRow("Knight_EV1"), "ClonedVersion"));
    assertThat(records.unit("Knight").clonedVersion()).isNull();
    assertThat(records.buff("Clone").unmodelledColumns()).isEmpty();

    // The configured Clone is in the filter form, which refuses a number damage by its Damage
    // column; written in the hit-switch form, whose hit pass reads the Clone switch, it is refused
    // by its Clone column.
    GameTables tables =
        GameData.altered(
            folder,
            "area_effect_objects",
            rows -> GameData.columns(rows, "Clone").put("Damage", 100));
    assertThat(new BattleRecords(tables).areaEffect("Clone").unmodelledColumns())
        .containsExactly("Damage");
    GameTables switches =
        GameData.altered(
            Files.createDirectories(folder.resolve("switches")),
            "area_effect_objects",
            rows -> {
              ObjectNode columns = GameData.columns(rows, "Clone");
              columns.remove("Filter");
              columns.put("HitsAir", true);
              columns.put("HitsGround", true);
              columns.put("OnlyOwnTroops", true);
              columns.put("IgnoreBuildings", true);
              columns.put("Damage", 100);
            });
    assertThat(new BattleRecords(switches).areaEffect("Clone").unmodelledColumns())
        .containsExactly("Clone");
  }

  @Test
  @DisplayName(
      "the Electro Giant carries its reflect, and a projectile whether its hits are reflected")
  void reflectColumns() {
    UnitData giant = records.unit("ElectroGiant");
    GameRow row = unitRow("ElectroGiant");
    assertThat(giant.unmodelledColumns()).isEmpty();
    assertThat(giant.reflectedAttackBuff()).isEqualTo(text(row, "ReflectedAttackBuff"));
    assertThat(giant.reflectedAttackBuffDurationMs())
        .isEqualTo(number(row, "ReflectedAttackBuffDuration"));
    assertThat(giant.reflectedAttackRadius()).isEqualTo(number(row, "ReflectedAttackRadius"));
    assertThat(giant.reflectedAttackDamage()).isEqualTo(number(row, "ReflectedAttackDamage"));
    assertThat(giant.reflectAttackCrownTowerDamage())
        .isEqualTo(number(row, "ReflectAttackCrownTowerDamage"));
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
    GameRow row = unitRow("RamRider");
    assertThat(rider.targetOnlyTroops()).isEqualTo(flag(row, "TargetOnlyTroops"));
    assertThat(rider.ignoreTargetsWithBuff()).isEqualTo(text(row, "IgnoreTargetsWithBuff"));
    assertThat(rider.deprioritizeTargetsWithBuff())
        .isEqualTo(flag(row, "DeprioritizeTargetsWithBuff"));
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
    GameRow tombstoneRow = unitRow("Tombstone");
    assertThat(tombstone.lifeTimeMs()).isEqualTo(number(tombstoneRow, "LifeTime"));
    assertThat(tombstone.spawnCharacter()).isEqualTo(text(tombstoneRow, "SpawnCharacter"));
    assertThat(tombstone.spawnNumber()).isEqualTo(number(tombstoneRow, "SpawnNumber"));
    assertThat(tombstone.spawnIntervalMs()).isEqualTo(number(tombstoneRow, "SpawnInterval"));
    assertThat(tombstone.spawnPauseTimeMs()).isEqualTo(number(tombstoneRow, "SpawnPauseTime"));
    assertThat(tombstone.spawnStartTimeMs()).isEqualTo(number(tombstoneRow, "SpawnStartTime"));
    assertThat(tombstone.unmodelledColumns()).isEmpty();
    assertThat(records.unit("GoblinDrill").spawnStartTimeMs())
        .isEqualTo(number(unitRow("GoblinDrill"), "SpawnStartTime"));
    assertThat(records.unit("Mortar").minimumRange())
        .isEqualTo(number(unitRow("Mortar"), "MinimumRange"));
    assertThat(records.unit("Cannon").spawnCharacter()).isNull();
    assertThat(records.unit("DarkPrince").unmodelledColumns()).isEmpty();
    // The evolved Battle Ram's completed charge runs its push, which is modelled.
    assertThat(records.unit("BattleRam_EV1").unmodelledColumns()).isEmpty();
    assertThat(records.unit("BattleRam_EV1").onStartChargingAction())
        .isEqualTo(text(unitRow("BattleRam_EV1"), "OnStartChargingAction"));
    assertThat(records.unit("BattleRam").onStartChargingAction()).isNull();
    assertThat(records.unit("DarkPrince").shieldHitpoints())
        .isEqualTo(number(unitRow("DarkPrince"), "ShieldHitpoints"));
    // The evolved Wizard runs an action as its shield breaks; a push as it breaks is refused.
    assertThat(records.unit("Wizard_EV1").unmodelledColumns()).isEmpty();
    for (String name : List.of("Wizard_EV1", "Recruit_EV1")) {
      assertThat(records.unit(name).shieldLostAction())
          .as(name)
          .isEqualTo(text(unitRow(name), "ShieldLostAction"));
    }
    assertThat(records.unit("Knight").shieldLostAction()).isNull();
    // The Tesla hides while it does not attack, a while to go down and a while to come up; its
    // evolution runs an action as it rises and another as it starts to hide.
    UnitData tesla = records.unit("Tesla");
    GameRow teslaRow = unitRow("Tesla");
    assertThat(tesla.unmodelledColumns()).isEmpty();
    assertThat(tesla.hidesWhenNotAttacking()).isEqualTo(flag(teslaRow, "HidesWhenNotAttacking"));
    assertThat(new int[] {tesla.hideTimeMs(), tesla.upTimeMs()})
        .containsExactly(number(teslaRow, "HideTimeMs"), number(teslaRow, "UpTimeMs"));
    UnitData teslaEvo = records.unit("Tesla_EV1");
    GameRow teslaEvoRow = unitRow("Tesla_EV1");
    assertThat(teslaEvo.unmodelledColumns()).isEmpty();
    assertThat(teslaEvo.onAppearAction()).isEqualTo(text(teslaEvoRow, "OnAppearAction"));
    assertThat(teslaEvo.onDisappearAction()).isEqualTo(text(teslaEvoRow, "OnDisappearAction"));
    assertThat(tesla.onAppearAction()).isNull();
    // The evolved Cannon's two shadows are read by no battle logic.
    assertThat(records.unit("Cannon_EV1").unmodelledColumns()).isEmpty();
    // An elixir collector is modelled: an amount of elixir every so often; an Elixir Golem's
    // death pays its opponent.
    UnitData collector = records.unit("ElixirCollector");
    GameRow collectorRow = unitRow("ElixirCollector");
    assertThat(collector.unmodelledColumns()).isEmpty();
    assertThat(new int[] {collector.manaCollectAmount(), collector.manaGenerateTimeMs()})
        .containsExactly(
            number(collectorRow, "ManaCollectAmount"), number(collectorRow, "ManaGenerateTimeMs"));
    assertThat(records.unit("ElixirGolem1").manaOnDeathForOpponent())
        .isEqualTo(number(unitRow("ElixirGolem1"), "ManaOnDeathForOpponent"));
    // The Goblin Giant's Spear Goblins ride on it, at its spawn radius, turned by their own angle.
    UnitData goblinGiant = records.unit("GoblinGiant");
    GameRow goblinGiantRow = unitRow("GoblinGiant");
    assertThat(goblinGiant.unmodelledColumns()).isEmpty();
    assertThat(goblinGiant.spawnAttach()).isEqualTo(flag(goblinGiantRow, "SpawnAttach"));
    assertThat(goblinGiant.spawnNumber()).isEqualTo(number(goblinGiantRow, "SpawnNumber"));
    assertThat(goblinGiant.spawnRadius()).isEqualTo(number(goblinGiantRow, "SpawnRadius"));
    UnitData rider = records.unit("SpearGoblinGiant");
    GameRow riderRow = unitRow(text(goblinGiantRow, "SpawnCharacter"));
    assertThat(rider.spawnAngleShift()).isEqualTo(number(riderRow, "SpawnAngleShift"));
    assertThat(rider.spawnMaxAngle()).isEqualTo(number(riderRow, "SpawnMaxAngle"));
    assertThat(rider.spawnAttachMaxRotation())
        .isEqualTo(number(riderRow, "SpawnAttachMaxRotation"));
    assertThat(rider.flyingHeight()).isEqualTo(number(riderRow, "FlyingHeight"));
    assertThat(rider.deathInheritIgnoreList()).isEqualTo(flag(riderRow, "DeathInheritIgnoreList"));
    // A second spawn row is read.
    UnitData superWitch = records.unit("SuperWitch");
    assertThat(superWitch.spawnCharacter2())
        .isEqualTo(text(unitRow("SuperWitch"), "SpawnCharacter2"));
    assertThat(superWitch.spawnCharacter3()).isNull();
    assertThat(superWitch.unmodelledColumns()).isEmpty();
  }

  @Test
  @DisplayName(
      "the Skeleton Barrel flies direct paths and drains over its Kamikaze time; its container"
          + " gives its ring a fixed priority, which a spawner may not")
  void skeletonBarrelColumns() {
    UnitData barrel = records.unit("SkeletonBalloon");
    GameRow row = unitRow("SkeletonBalloon");
    assertThat(barrel.unmodelledColumns()).isEmpty();
    assertThat(barrel.flyDirectPaths()).isEqualTo(flag(row, "FlyDirectPaths"));
    assertThat(barrel.kamikaze()).isEqualTo(flag(row, "Kamikaze"));
    assertThat(barrel.kamikazeTimeMs()).isEqualTo(number(row, "KamikazeTime"));
    assertThat(barrel.deathSpawnCharacter()).isEqualTo(text(row, "DeathSpawnCharacter"));
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
    assertThat(phoenix.deathSpawnProjectile().name())
        .isEqualTo(text(unitRow("Phoenix"), "DeathSpawnProjectile"));
    // The loader keeps a count of at least one under a death projectile, as under a death spawn.
    assertThat(phoenix.deathSpawnCount())
        .isEqualTo(Math.max(1, number(unitRow("Phoenix"), "DeathSpawnCount")));
    assertThat(phoenix.deathSpawnCharacter()).isNull();
    assertThat(records.unit("Knight").deathSpawnCount()).isZero();
    UnitData egg = records.unit("PhoenixEgg");
    GameRow eggRow = unitRow("PhoenixEgg");
    assertThat(egg.unmodelledColumns()).isEmpty();
    assertThat(egg.spawnCharacter()).isEqualTo(text(eggRow, "SpawnCharacter"));
    assertThat(egg.spawnLimit()).isEqualTo(number(eggRow, "SpawnLimit"));
    assertThat(egg.destroyAtLimit()).isEqualTo(flag(eggRow, "DestroyAtLimit"));
    assertThat(egg.spawnCharacterWithDeploy()).isEqualTo(flag(eggRow, "SpawnCharacterWithDeploy"));
    assertThat(egg.untargetableWhenSpawned()).isEqualTo(flag(eggRow, "UntargetableWhenSpawned"));
    assertThat(egg.gameTagsToSet())
        .isEqualTo(
            BITS.noGiantbufferChefEnchantment()
                | BITS.avoidanceAsObstacle()
                | BITS.noMoveAllowAttract());
    // The same three written with spaces, and NO_ATTACK, are the same tags and that one.
    assertThat(records.unit("EliteArcherHero_Dummy").gameTagsToSet())
        .isEqualTo(egg.gameTagsToSet() | BITS.noAttack());
    // The Goblins hero's banner sets three more the battle reads: no damage, no contact, no
    // targeting; a row with one of them alone is taken too.
    assertThat(records.unit("GoblinHero_Flag_Building").gameTagsToSet())
        .isEqualTo(BITS.noDamage() | BITS.noCheckCollisions() | BITS.untargetable());
    assertThat(records.unit("GoblinHero_Flag_Building").unmodelledColumns()).isEmpty();
    assertThat(records.unit("RageBarbarianEvoGhost").gameTagsToSet()).isEqualTo(BITS.noDamage());
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
    GameRow bufferRow = row("character_abilities", text(unitRow("GiantBuffer"), "Ability"));
    assertThat(buffer.name()).isEqualTo(bufferRow.name());
    assertThat(buffer.castTimeMs()).isEqualTo(number(bufferRow, "CastTime"));
    assertThat(buffer.triggerDelayMs()).isEqualTo(number(bufferRow, "TriggerDelay"));
    assertThat(buffer.keepCurrentTarget()).isEqualTo(flag(bufferRow, "KeepCurrentTarget"));
    assertThat(buffer.champion()).isFalse();
    // Written inline, it is the actions table's row named after the ability and the column.
    assertThat(buffer.onActivationAction()).isEqualTo("giantbuffer_ability_OnActivationAction");
    assertThat(buffer.unmodelledColumns()).isEmpty();
    assertThat(buffer.buff()).isNull();
    // A champion's ability buffs the champion itself, and its controller reads its cost, cooldown
    // and charges.
    AbilityData queen = records.unit("ArcherQueen").ability();
    GameRow queenRow = row("character_abilities", text(unitRow("ArcherQueen"), "Ability"));
    assertThat(queen.champion()).isTrue();
    assertThat(queen.buff()).isEqualTo(text(queenRow, "Buff"));
    assertThat(queen.buffTimeMs()).isEqualTo(number(queenRow, "BuffTime"));
    assertThat(queen.manaCost()).isEqualTo(number(queenRow, "ManaCost"));
    assertThat(queen.cooldownMs()).isEqualTo(number(queenRow, "Cooldown"));
    assertThat(queen.maxCharges()).isEqualTo(number(queenRow, "MaxCharges"));
    assertThat(queen.unmodelledColumns()).isEmpty();
    assertThat(records.unit("BossBandit").ability().maxCharges())
        .isEqualTo(
            number(
                row("character_abilities", text(unitRow("BossBandit"), "Ability")), "MaxCharges"));
    // A lane switch, and the character the ability leaves behind, are read.
    AbilityData miner = records.unit("MightyMiner").ability();
    GameRow minerRow = row("character_abilities", text(unitRow("MightyMiner"), "Ability"));
    assertThat(miner.switchLanes()).isEqualTo(flag(minerRow, "SwitchLanes"));
    assertThat(miner.activationSpawnCharacter())
        .isEqualTo(text(minerRow, "ActivationSpawnCharacter"));
    assertThat(miner.unmodelledColumns()).isEmpty();
    assertThat(queen.switchLanes()).isFalse();
    assertThat(queen.activationSpawnCharacter()).isNull();
    // The follow-up state and the tags the unit carries in it are read; the Monk's row names no
    // area object (its activation action makes its Deflect).
    AbilityData monk = records.unit("Monk").ability();
    GameRow monkRow = row("character_abilities", text(unitRow("Monk"), "Ability"));
    assertThat(monk.areaEffectObject()).isNull();
    assertThat(monk.onActivationAction()).isEqualTo(text(monkRow, "OnActivationAction"));
    assertThat(monk.abilityStateDurationMs()).isEqualTo(number(monkRow, "AbilityStateDuration"));
    assertThat(monk.gameTagsWhileAbilityActive())
        .isEqualTo(BITS.avoidanceAsObstacle() | BITS.noMoveAllowAttract());
    assertThat(monk.unmodelledColumns()).isEmpty();
    assertThat(queen.areaEffectObject()).isNull();
    assertThat(queen.abilityStateDurationMs()).isZero();
    assertThat(queen.gameTagsWhileAbilityActive()).isZero();
    // The souls an area object counts to resurrect are refused.
    AbilityData souls = records.unit("SkeletonKing").ability();
    GameRow soulsRow = row("character_abilities", text(unitRow("SkeletonKing"), "Ability"));
    assertThat(souls.areaEffectObject()).isEqualTo(text(soulsRow, "AreaEffectObject"));
    assertThat(souls.resurrectBaseCount()).isEqualTo(number(soulsRow, "ResurrectBaseCount"));
    assertThat(souls.resurrectEnemies()).isEqualTo(flag(soulsRow, "ResurrectEnemies"));
    assertThat(souls.resurrectOwnTroops()).isEqualTo(flag(soulsRow, "ResurrectOwnTroops"));
    assertThat(souls.spawnLimit()).isEqualTo(number(soulsRow, "SpawnLimit"));
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
    GameRow shieldRow = row("character_buffs", "ShieldBoostMonk");
    assertThat(shield.damageReduction()).isEqualTo(number(shieldRow, "DamageReduction"));
    assertThat(shield.ignorePushBack()).isEqualTo(flag(shieldRow, "IgnorePushBack"));
    assertThat(shield.unmodelledColumns()).isEmpty();
    assertThat(records.buff("DarkElixirBuff").damageReduction())
        .isEqualTo(number(row("character_buffs", "DarkElixirBuff"), "DamageReduction"));
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
    assertThat(miner.ingamePathfindSpeed())
        .isEqualTo(number(unitRow("MightyMiner"), "IngamePathfindSpeed"));
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
    assertThat(records.unit("MiniPekka").globalId()).isEqualTo(unitRow("MiniPekka").globalId());
    assertThat(records.unit("KingTower").globalId()).isEqualTo(unitRow("KingTower").globalId());
    // A row named in an expression is looked up by name, characters first; this one hashes below 0.
    assertThat(records.unitGlobalId("DaggerDuchess")).isEqualTo(-1749071821);
    assertThat(records.unitGlobalId("MiniPekka")).isEqualTo(unitRow("MiniPekka").globalId());
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
        .containsExactlyElementsOf(
            texts(
                row("game_object_filters", "friendly_skeletons_can_be_dead"),
                "IncludeCharactersWithData"));
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
                  entry.put("CustomUnreadColumn", 1);
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
      "a game object filter that sets a column the filter does not read, as a base filter, is"
          + " refused rather than read without it")
  void aFilterColumnNotReadIsRefused(@TempDir Path folder) throws IOException {
    BattleRecords altered =
        new BattleRecords(
            GameData.altered(
                folder,
                "game_object_filters",
                rows -> {
                  ObjectNode troop = GameData.columns(rows, "friendly_troop");
                  troop.put("Base", "friendly_troop_no_buildings");
                }));
    assertThatThrownBy(() -> altered.filter("friendly_troop"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage("the game object filter friendly_troop sets columns not modelled: [Base]");
    // A filter that sets only what the filter reads, and the text the game shows for it, is built.
    assertThat(altered.filter("friendly_troop_no_buildings").isMatchTeamOwn()).isTrue();
  }

  @Test
  @DisplayName(
      "a filter whose Base names a filter all of whose columns it carries is built as its own"
          + " columns say; one missing a column of its base is refused")
  void aFilterBaseAlreadyResolvedIsRead(@TempDir Path folder) throws IOException {
    BattleRecords altered =
        new BattleRecords(
            GameData.altered(
                folder,
                "game_object_filters",
                rows -> {
                  ObjectNode base = GameData.columns(rows, "friendly_troop_no_buildings");
                  ObjectNode resolved = GameData.columns(rows, "friendly_troop");
                  resolved.removeAll();
                  resolved.setAll(base.deepCopy());
                  resolved.put("Base", "FILTER.friendly_troop_no_buildings");
                  // Its own columns add one its base leaves at the default.
                  resolved.put("FilterDead", false);
                  ObjectNode missing = GameData.columns(rows, "friendly_skeletons_can_be_dead");
                  missing.put("Base", "FILTER.friendly_troop_no_buildings");
                }));
    assertThat(altered.filter("friendly_troop").isFilterDead()).isFalse();
    assertThat(altered.filter("friendly_troop").isFilterBuildings()).isTrue();
    assertThat(altered.filter("friendly_troop").isMatchTeamOwn()).isTrue();
    assertThatThrownBy(() -> altered.filter("friendly_skeletons_can_be_dead"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(
            "the game object filter friendly_skeletons_can_be_dead sets columns not modelled:"
                + " [Base]");
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

    GameRow row = unitRow("DaggerDuchess");
    List<Integer> multipliers = new ArrayList<>();
    List<String> projectiles = new ArrayList<>();
    for (JsonNode entry : column(row, "AttackSequenceList")) {
      multipliers.add(entry.path("HitSpeedMultiplier").asInt());
      projectiles.add(entry.path("Projectile").asText());
    }

    assertThat(duchess.onStartingAction()).isEqualTo("DaggerDuchess_OnStartingAction");
    assertThat(duchess.attackSequence().mode()).isEqualTo(AttackSequence.MODE_NONE);
    assertThat(duchess.attackSequence().order())
        .containsExactlyElementsOf(numbers(row, "AttackSequence"));
    assertThat(duchess.attackSequence().entries())
        .extracting(AttackSequence.Entry::hitSpeedMultiplier)
        .containsExactlyElementsOf(multipliers);
    assertThat(duchess.attackSequence().entries())
        .extracting(entry -> entry.projectile().name())
        .containsExactlyElementsOf(projectiles);
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
    GameRow row = GameData.tables().inlineBuff("DarkMagicAOE_Damage_lv3");
    assertThat(strongest.damagePerSecond()).isEqualTo(number(row, "DamagePerSecond"));
    assertThat(strongest.crownTowerDamagePerHit()).isEqualTo(number(row, "CrownTowerDamagePerHit"));
    assertThat(strongest.hitFrequency()).isEqualTo(number(row, "HitFrequency"));
    assertThat(strongest.addAsIndividualBuff()).isEqualTo(flag(row, "AddAsIndividualBuff"));
    assertThat(strongest.unmodelledColumns()).isEmpty();
    assertThat(records.buff("DarkMagicAOE_Damage_lv1").damagePerSecond())
        .isEqualTo(
            number(GameData.tables().inlineBuff("DarkMagicAOE_Damage_lv1"), "DamagePerSecond"));
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
    assertThat(records.circleRadius("GiantHero_Slap_Shape"))
        .isEqualTo(number(row("shapes", "GiantHero_Slap_Shape"), "Radius"));
    assertThatThrownBy(() -> records.circleRadius("MegaMinion_hero_shape"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("is a Global");
    BuffData snare = records.buff("Vines_Trap_Snare_Large");
    GameRow snareRow = row("character_buffs", "Vines_Trap_Snare_Large");
    assertThat(snare.unmodelledColumns()).isEmpty();
    assertThat(snare.speedMultiplier()).isEqualTo(number(snareRow, "SpeedMultiplier"));
    assertThat(snare.hitSpeedMultiplier()).isEqualTo(number(snareRow, "HitSpeedMultiplier"));
    assertThat(snare.spawnSpeedMultiplier()).isEqualTo(number(snareRow, "SpawnSpeedMultiplier"));
    assertThat(snare.damagePerSecond()).isEqualTo(number(snareRow, "DamagePerSecond"));
    assertThat(snare.crownTowerDamagePerHit())
        .isEqualTo(number(snareRow, "CrownTowerDamagePerHit"));
    assertThat(snare.enableStacking()).isEqualTo(flag(snareRow, "EnableStacking"));
  }

  @Test
  @DisplayName(
      "a game mode's battle timeline, a card's cost, hand columns and options, and a global")
  void matchRows() {
    BattleTimeline ladder = records.gameModeTimeline("Ladder");
    GameRow timeline = row("battle_timelines", text(row("game_modes", "Ladder"), "BattleTimeline"));
    assertThat(ladder.name()).isEqualTo(timeline.name());
    assertThat(ladder.startingElixir()).isEqualTo(number(timeline, "StartingElixir"));
    assertThat(ladder.sectionLengths())
        .containsExactlyElementsOf(numbers(timeline, "SectionLength"));
    assertThat(ladder.sectionTypes())
        .containsExactlyElementsOf(
            texts(timeline, "SectionType").stream()
                .map(
                    type ->
                        switch (type) {
                          case "Normal" -> BattleTimeline.NORMAL;
                          case "Overtime" -> BattleTimeline.OVERTIME;
                          case "BonusTime" -> BattleTimeline.BONUS_TIME;
                          default -> throw new AssertionError("no section type " + type);
                        })
                .toList());
    assertThat(ladder.fullBarMs()).containsExactlyElementsOf(numbers(timeline, "ElixirFullBarMS"));
    assertThat(ladder.cooldownMs())
        .containsExactlyElementsOf(numbers(timeline, "NextSpellCooldownMS"));
    assertThat(records.matchCard("Knight").cost())
        .isEqualTo(number(row("spells_characters", "Knight"), "ManaCost"));
    assertThat(records.matchCard("Mirror").mirror()).isTrue();
    assertThat(records.matchCard("Mirror").omitFromStartingHand()).isTrue();
    assertThat(records.matchCard("Elixir Collector").omitFromStartingHand()).isTrue();
    assertThat(records.matchCard("Knight").variant()).isNull();
    // The Merge Maiden is played as one of its options: the triggers in ten-thousandths, each
    // option's cost and production stop its own row's.
    SpellVariant maiden = records.matchCard("MergeMaiden").variant();
    GameRow maidenRow = row("spells_other", "MergeMaiden");
    List<SpellVariant.Option> options = new ArrayList<>();
    for (JsonNode option : column(maidenRow, "Options")) {
      GameRow spell = row("spells_characters", option.path("SpellData").asText());
      options.add(
          new SpellVariant.Option(
              spell.name(),
              // The trigger in ten-thousandths of an elixir: the column's, times 10.
              option.path("AvailableManaTrigger").asInt() * 10,
              option.path("PrecastPendingTime").asInt(),
              number(spell, "ManaCost"),
              number(spell, "ElixirProductionStopTime")));
    }
    assertThat(maiden.useProjectedTimeSummon())
        .isEqualTo(flag(maidenRow, "UseProjectedTimeSummon"));
    assertThat(maiden.options()).containsExactlyElementsOf(options);
    assertThat(records.globalNumber("MAX_MANA"))
        .isEqualTo(number(row("globals", "MAX_MANA"), "NumberValue"));
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
    for (String name : List.of("HogRider", "Golem", "Balloon", "Knight")) {
      GameRow row = unitRow(name);
      // A character row that leaves its clip out, or 0, is loaded with 1000 behind.
      int clip = number(row, "SightClip");
      assertThat(records.unit(name).sightClip()).as(name).isEqualTo(clip == 0 ? 1000 : clip);
      assertThat(records.unit(name).sightClipSide())
          .as(name)
          .isEqualTo(number(row, "SightClipSide"));
    }
    assertThat(number(unitRow("Balloon"), "SightClip")).as("the Balloon leaves it 0").isZero();
    assertThat(number(unitRow("HogRider"), "SightClip")).as("the Hog Rider sets one").isPositive();
    UnitData knight = records.unit("Knight");
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
    GameRow ghostRow = unitRow("Ghost");
    assertThat(ghost.hovering()).isTrue();
    assertThat(ghost.buffWhenNotAttacking()).isEqualTo(text(ghostRow, "BuffWhenNotAttacking"));
    assertThat(ghost.buffWhenNotAttackingTimeMs())
        .isEqualTo(number(ghostRow, "BuffWhenNotAttackingTime"));
    assertThat(ghost.buffWhenNotAttackingUseAttackRange()).isTrue();
    assertThat(ghost.startWithBuffWhenNotAttacking()).as("the row leaves it empty").isTrue();
    assertThat(ghost.allowAreaDamageWhenInvisible()).isTrue();
    assertThat(ghost.unmodelledColumns()).as("its overlay is the view's").isEmpty();
    assertThat(records.unit("Ghost_EV1_Summon_Base").startWithBuffWhenNotAttacking()).isFalse();

    UnitData healer = records.unit("BattleHealer");
    assertThat(healer.hovering()).isTrue();
    assertThat(healer.areaEffectOnHit())
        .isEqualTo(text(unitRow("BattleHealer"), "AreaEffectOnHit"));
    // Its spawn heal is its starting action's, not an area object of its row.
    assertThat(healer.spawnAreaObject()).isNull();
    assertThat(healer.onStartingAction())
        .isEqualTo(text(unitRow("BattleHealer"), "OnStartingAction"));
    assertThat(healer.buffWhenNotAttacking()).isNull();
    assertThat(healer.unmodelledColumns()).isEmpty();
    assertThat(records.unit("Knight").areaEffectOnHit()).isNull();

    // A buff while not attacking without its range gate is read: a touch test holds its countdown.
    UnitData bush = records.unit("SuspiciousBush");
    assertThat(bush.buffWhenNotAttacking())
        .isEqualTo(text(unitRow("SuspiciousBush"), "BuffWhenNotAttacking"));
    assertThat(bush.buffWhenNotAttackingUseAttackRange()).isFalse();
    assertThat(bush.startWithBuffWhenNotAttacking()).isTrue();
    assertThat(bush.unmodelledColumns()).isEmpty();
    for (String unit : List.of("SuspiciousBush", "SuperKnight", "RageBarbarianEvoGhost")) {
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
    assertThat(cancel.onHitAction())
        .isEqualTo(text(row("area_effect_objects", "CancelTauntAEO"), "OnHitAction"));
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
            rows -> {
              // The configured Zap chooses by a filter; written with its hit switches instead.
              ObjectNode zap = GameData.columns(rows, "Zap");
              zap.remove("Filter");
              zap.put("Damage", 75);
              zap.put("HitsAir", true);
              zap.put("HitsGround", true);
              zap.put("OnlyEnemies", true);
              zap.put("OneHitPerTarget", true);
            });
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
    GameRow healRow = row("character_buffs", "BattleHealerAll");
    assertThat(heal.invisible()).isEqualTo(flag(healRow, "Invisible"));
    assertThat(heal.healPerSecond()).isEqualTo(number(healRow, "HealPerSecond"));
    assertThat(heal.hitFrequency()).isEqualTo(number(healRow, "HitFrequency"));
    assertThat(heal.allowedOverHealPercent()).isEqualTo(number(healRow, "AllowedOverHealPerc"));
    assertThat(heal.unmodelledColumns()).isEmpty();
    assertThat(records.buff("BatsEV1_Heal").allowedOverHealPercent())
        .isEqualTo(number(row("character_buffs", "BatsEV1_Heal"), "AllowedOverHealPerc"));
  }

  @Test
  @DisplayName("a projectile carries whether its impact fixes its children's priority")
  void aProjectileCarriesItsSpawnPriority() {
    BattleRecords records = GameData.records();
    assertThat(records.projectile("GoblinBarrelSpell").spawnConstPriority()).isTrue();
    assertThat(records.projectile("ArrowsSpell").spawnConstPriority()).isFalse();
  }
}
