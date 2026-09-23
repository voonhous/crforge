package org.crforge.core.card;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Objects;
import org.crforge.core.battle.unit.UnitData;
import org.crforge.core.pathfinding.combat.RarityTable;
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
  @DisplayName("a card's rarity reaches the unit it deploys")
  void aCardsRarityReachesItsUnit() {
    Card miniPekka = Objects.requireNonNull(CardRegistry.get("minipekka"), "minipekka not found");
    assertThat(UnitDataMapper.toUnitData(miniPekka).rarity()).isEqualTo(RarityTable.RARE);
  }
}
