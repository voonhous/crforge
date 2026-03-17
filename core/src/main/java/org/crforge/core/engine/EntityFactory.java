package org.crforge.core.engine;

import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.Card;
import org.crforge.core.card.Rarity;
import org.crforge.core.combat.AoeDamageService;
import org.crforge.core.player.Team;

/**
 * Facade that delegates entity construction to specialized sub-factories. Preserves all 6 entry
 * points used by {@link DeploymentSystem} so callers require zero changes.
 */
class EntityFactory {

  private final TroopFactory troopFactory;
  private final BuildingFactory buildingFactory;
  private final SpellFactory spellFactory;
  private final AreaEffectFactory areaEffectFactory;

  EntityFactory(GameState state, AoeDamageService aoeDamageService) {
    this.troopFactory = new TroopFactory(state);
    this.areaEffectFactory = new AreaEffectFactory(state);
    this.buildingFactory = new BuildingFactory(state, troopFactory);
    this.spellFactory = new SpellFactory(state, aoeDamageService, troopFactory, areaEffectFactory);
  }

  /**
   * Spawns a non-troop card (building or spell). Troop cards are handled directly by the stagger
   * loop in {@link DeploymentSystem#update(float)}.
   */
  void spawnCard(Team team, Card card, float x, float y, int level) {
    switch (card.getType()) {
      case BUILDING -> buildingFactory.spawnBuilding(team, card, x, y, level);
      case SPELL -> spellFactory.castSpell(team, card, x, y, level);
      default -> {}
    }
  }

  void spawnSingleTroop(Team team, Card card, float x, float y, int level, int idx) {
    troopFactory.spawnSingleTroop(team, card, x, y, level, idx);
  }

  void deployAreaEffect(
      Team team, AreaEffectStats stats, float x, float y, Rarity rarity, int level) {
    areaEffectFactory.deployAreaEffect(team, stats, x, y, rarity, level);
  }

  void fireSpawnProjectile(Team team, Card card, float x, float y, int level) {
    spellFactory.fireSpawnProjectile(team, card, x, y, level);
  }

  void spawnTunnelBuilding(Team team, Card card, float x, float y, int level) {
    buildingFactory.spawnTunnelBuilding(team, card, x, y, level);
  }
}
