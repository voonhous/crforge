package org.crforge.core.battle.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.projectile.ProjectileData;
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
    assertThatThrownBy(() -> records.card("IceWizard"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("SpellAsDeploy");
    assertThatThrownBy(() -> records.card("NoSuchCard"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("NoSuchCard");
  }
}
