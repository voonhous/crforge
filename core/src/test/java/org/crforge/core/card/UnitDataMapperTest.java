package org.crforge.core.card;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Objects;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.unit.UnitData;
import org.crforge.core.pathfinding.combat.RarityTable;
import org.crforge.core.pathfinding.combat.ScalingMode;
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The card library's unit stats as the battle's columns. */
class UnitDataMapperTest {

  @Test
  @DisplayName("the Knight's columns: whole milliseconds, level-1 stats and the card's rarity")
  void theKnightsColumns() {
    Card knight = Objects.requireNonNull(CardRegistry.get("knight"), "knight not found");

    UnitData data = UnitDataMapper.toUnitData(knight);

    assertThat(data.name()).isEqualTo("Knight");
    assertThat(data.speed()).isEqualTo(60);
    assertThat(data.range()).isEqualTo(1200);
    assertThat(data.sightRange()).isEqualTo(5500);
    assertThat(data.collisionRadius()).isEqualTo(500);
    assertThat(data.hitSpeedMs()).isEqualTo(1200);
    assertThat(data.loadTimeMs()).isEqualTo(700);
    assertThat(data.deployTimeMs()).isEqualTo(1000);
    assertThat(data.hitpoints()).isEqualTo(690);
    assertThat(data.damage()).isEqualTo(79);
    assertThat(data.rarity()).isEqualTo(RarityTable.COMMON);
    assertThat(data.attacksGround()).isTrue();
    assertThat(data.attacksAir()).isFalse();
    assertThat(data.air()).isFalse();
    assertThat(data.building()).isFalse();
    assertThat(data.crownTowerDamagePercent())
        .as("the Knight hits a crown tower for what it hits anything else for")
        .isZero();
  }

  @Test
  @DisplayName("a unit that spares crown towers carries its percentage")
  void aUnitThatSparesCrownTowers() {
    Card miner = Objects.requireNonNull(CardRegistry.get("miner"), "miner not found");

    assertThat(UnitDataMapper.toUnitData(miner).crownTowerDamagePercent()).isEqualTo(-75);
  }

  @Test
  @DisplayName("every card rarity maps to its published row, an unknown one to Common")
  void theRarityRows() {
    assertThat(UnitDataMapper.rarityTable(Rarity.COMMON)).isEqualTo(RarityTable.COMMON);
    assertThat(UnitDataMapper.rarityTable(Rarity.RARE)).isEqualTo(RarityTable.RARE);
    assertThat(UnitDataMapper.rarityTable(Rarity.EPIC)).isEqualTo(RarityTable.EPIC);
    assertThat(UnitDataMapper.rarityTable(Rarity.LEGENDARY)).isEqualTo(RarityTable.LEGENDARY);
    assertThat(UnitDataMapper.rarityTable(Rarity.CHAMPION)).isEqualTo(RarityTable.CHAMPION);
    assertThat(UnitDataMapper.rarityTable(Rarity.UNKNOWN)).isEqualTo(RarityTable.COMMON);
  }

  @Test
  @DisplayName(
      "a unit is scaled by its own row's rarity, not the rarity of the card that deploys it")
  void aUnitIsScaledByItsOwnRowsRarity() {
    // The Musketeer's and the Mini P.E.K.K.A.'s rows are Common although both cards are Rare: the
    // row's stats are published at the first level and the card's rarity only positions the level.
    Card musketeer = Objects.requireNonNull(CardRegistry.get("musketeer"), "musketeer not found");
    Card miniPekka = Objects.requireNonNull(CardRegistry.get("minipekka"), "minipekka not found");
    assertThat(musketeer.getRarity()).isEqualTo(Rarity.RARE);
    assertThat(UnitDataMapper.toUnitData(musketeer).rarity()).isEqualTo(RarityTable.COMMON);
    assertThat(UnitDataMapper.toUnitData(miniPekka).rarity()).isEqualTo(RarityTable.COMMON);
  }

  @Test
  @DisplayName("a unit whose row carries no rarity is scaled by the card's")
  void aRowWithoutARarityTakesTheCards() {
    Card miniPekka = Objects.requireNonNull(CardRegistry.get("minipekka"), "minipekka not found");
    TroopStats withoutRarity =
        TroopStats.builder().name("Nameless").rawSpeed(60).rarity(Rarity.UNKNOWN).build();
    Card card = miniPekka.toBuilder().unitStats(withoutRarity).build();

    assertThat(UnitDataMapper.toUnitData(card).rarity()).isEqualTo(RarityTable.RARE);
  }

  @Test
  @DisplayName("a unit that fires carries its projectile's columns and its launch columns")
  void aUnitThatFiresCarriesItsProjectile() {
    Card musketeer = Objects.requireNonNull(CardRegistry.get("musketeer"), "musketeer not found");

    UnitData data = UnitDataMapper.toUnitData(musketeer);

    assertThat(data.hasProjectile()).isTrue();
    ProjectileData shot = data.projectile();
    assertThat(shot.name()).isEqualTo("MusketeerProjectile");
    assertThat(shot.speed()).as("game units per step").isEqualTo(1000);
    assertThat(shot.gravity()).isZero();
    assertThat(shot.homing()).isTrue();
    assertThat(shot.homingTimeMs()).isZero();
    assertThat(shot.damage()).isEqualTo(85);
    assertThat(shot.crownTowerDamagePercent()).isZero();
    assertThat(shot.rarity()).isEqualTo(RarityTable.COMMON);
    assertThat(shot.damageMode()).isEqualTo(ScalingMode.CARD_DAMAGE);
    assertThat(shot.radius()).isZero();
    assertThat(shot.homingLike()).isFalse();
    assertThat(data.projectileStartRadius()).isEqualTo(450);
    assertThat(data.projectileStartZ()).isEqualTo(450);
    assertThat(data.projectileYOffset()).isZero();
    assertThat(data.multipleProjectiles()).isZero();
    assertThat(data.areaDamageRadius()).isZero();
  }

  @Test
  @DisplayName("a unit whose projectile damages an area carries the area's columns")
  void aUnitWhoseProjectileDamagesAnAreaCarriesTheAreasColumns() {
    Card wizard = Objects.requireNonNull(CardRegistry.get("wizard"), "wizard not found");

    ProjectileData fireball = UnitDataMapper.toUnitData(wizard).projectile();

    assertThat(fireball.name()).isEqualTo("chr_wizardProjectile");
    assertThat(fireball.radius()).as("game units").isEqualTo(1500);
    assertThat(fireball.aoeToAir()).isTrue();
    assertThat(fireball.aoeToGround()).isTrue();
    assertThat(fireball.onlyEnemies()).isTrue();
  }

  @Test
  @DisplayName(
      "a unit that splashes with a direct hit carries its radius, its centre and its own"
          + " target-lost wait")
  void aUnitThatSplashesCarriesItsAreaColumns() {
    Card valkyrie = Objects.requireNonNull(CardRegistry.get("valkyrie"), "valkyrie not found");

    UnitData data = UnitDataMapper.toUnitData(valkyrie);

    assertThat(data.hasProjectile()).isFalse();
    assertThat(data.areaDamageRadius()).as("game units").isEqualTo(2000);
    assertThat(data.selfAsAoeCenter()).isTrue();
    assertThat(data.overrideAttackFinishTime()).isTrue();
    assertThat(data.attackFinishTimeMs()).isEqualTo(100);
  }

  @Test
  @DisplayName("a unit without its own target-lost wait keeps the global one")
  void aUnitWithoutItsOwnWaitKeepsTheGlobalOne() {
    Card knight = Objects.requireNonNull(CardRegistry.get("knight"), "knight not found");

    UnitData data = UnitDataMapper.toUnitData(knight);

    assertThat(data.overrideAttackFinishTime()).isFalse();
    assertThat(data.attackFinishTimeMs()).isZero();
    assertThat(data.areaDamageRadius()).isZero();
  }

  @Test
  @DisplayName("a unit that hits directly carries no projectile, but still its launch columns")
  void aUnitThatHitsDirectlyCarriesNoProjectile() {
    Card knight = Objects.requireNonNull(CardRegistry.get("knight"), "knight not found");

    UnitData data = UnitDataMapper.toUnitData(knight);

    assertThat(data.hasProjectile()).isFalse();
    assertThat(data.projectile()).isNull();
    assertThat(data.projectileStartRadius()).isEqualTo(450);
    assertThat(data.projectileStartZ()).isEqualTo(450);
  }

  @Test
  @DisplayName(
      "a projectile row's scaling mode names the tower rules, everything else the card rule")
  void theDamageModes() {
    assertThat(UnitDataMapper.damageMode(null)).isEqualTo(ScalingMode.CARD_DAMAGE);
    assertThat(UnitDataMapper.damageMode("")).isEqualTo(ScalingMode.CARD_DAMAGE);
    assertThat(UnitDataMapper.damageMode("Default")).isEqualTo(ScalingMode.CARD_DAMAGE);
    assertThat(UnitDataMapper.damageMode("KingTower")).isEqualTo(ScalingMode.KING_DAMAGE);
    assertThat(UnitDataMapper.damageMode("PrincessTower")).isEqualTo(ScalingMode.TOWER_DAMAGE);
  }
}
