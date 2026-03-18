package org.crforge.core.engine;

import java.util.List;
import org.crforge.core.card.Card;
import org.crforge.core.card.EffectStats;
import org.crforge.core.card.LevelScaling;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.combat.AoeDamageService;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;

/** Creates spell effects: summon characters, area effects, and projectile-based spells. */
class SpellFactory {

  static final float SPELL_TRAVEL_DISTANCE = 10f;

  private final GameState state;
  private final AoeDamageService aoeDamageService;
  private final TroopFactory troopFactory;
  private final AreaEffectFactory areaEffectFactory;

  SpellFactory(
      GameState state,
      AoeDamageService aoeDamageService,
      TroopFactory troopFactory,
      AreaEffectFactory areaEffectFactory) {
    this.state = state;
    this.aoeDamageService = aoeDamageService;
    this.troopFactory = troopFactory;
    this.areaEffectFactory = areaEffectFactory;
  }

  void castSpell(Team team, Card card, float x, float y, int level) {
    // Summon character spells (Rage -> RageBarbarianBottle, Heal -> HealSpirit)
    if (card.getSummonTemplate() != null) {
      var summonStats = card.getSummonTemplate();
      if (summonStats.getHealth() <= 0) {
        // Bomb entity (e.g. RageBarbarianBottle): 1 HP, selfDestruct, carries death mechanics
        Troop summoned =
            troopFactory.spawnBombSummon(team, summonStats, x, y, level, card.getRarity());
        state.spawnEntity(summoned);
      } else {
        Troop summoned =
            troopFactory.createTroop(
                team, summonStats, x, y, null, level, card.getRarity(), 0, 1, 0f, null);
        state.spawnEntity(summoned);
      }
    }

    // Area effect spells (Zap, Freeze, Poison, Earthquake, etc.)
    if (card.getAreaEffect() != null) {
      areaEffectFactory.deployAreaEffect(team, card.getAreaEffect(), x, y, card.getRarity(), level);
      return;
    }

    // Projectile-based spells (Fireball, Arrows, Rocket, etc.)
    ProjectileStats proj = card.getProjectile();
    if (proj == null) {
      return;
    }

    // Scale spell damage by card level and rarity
    int damage = LevelScaling.scaleCard(proj.getDamage(), card.getRarity(), level);
    float speed = proj.getSpeed();
    float radius = proj.getRadius();
    List<EffectStats> effects = proj.getHitEffects();

    if (speed > 0) {
      // Traveling spell -- may be a multi-wave spell (e.g. Arrows fires 3 staggered projectiles)
      float startX, startY, destX, destY;

      if (card.isSpellAsDeploy()) {
        // spellAsDeploy: projectile starts at deploy point, travels forward
        startX = x;
        startY = y;
        destX = x;
        float forward = proj.getMinDistance() > 0 ? proj.getMinDistance() / 1000f : 3.0f;
        destY = (team == Team.BLUE) ? y + forward : y - forward;
      } else {
        // Standard: projectile flies from the player's crown tower to the target
        Tower crownTower = state.getCrownTower(team);
        if (crownTower != null) {
          startX = crownTower.getPosition().getX();
          startY = crownTower.getPosition().getY();
        } else {
          // Fallback if crown tower destroyed: fly in from behind (old behavior)
          startX = x;
          startY = (team == Team.BLUE) ? y - SPELL_TRAVEL_DISTANCE : y + SPELL_TRAVEL_DISTANCE;
        }
        destX = x;
        destY = y;
      }

      int waves = card.getProjectileWaves() > 1 ? card.getProjectileWaves() : 1;
      int waveDelayFrames =
          Math.round(card.getProjectileWaveInterval() * GameEngine.TICKS_PER_SECOND);
      for (int i = 0; i < waves; i++) {
        Projectile p =
            new Projectile(
                team,
                startX,
                startY,
                destX,
                destY,
                damage,
                radius,
                speed,
                effects,
                proj.getCrownTowerDamagePercent());
        p.setPushback(proj.getPushback());
        p.setPushbackAll(proj.isPushbackAll());
        if (i > 0) {
          p.setDelayFrames(i * waveDelayFrames);
        }

        // Wire spawn character info for projectile spawn-on-impact (e.g. GoblinBarrel)
        if (proj.getSpawnCharacter() != null) {
          p.setSpawnCharacterStats(proj.getSpawnCharacter());
          p.setSpawnCharacterCount(proj.getSpawnCharacterCount());
          p.setSpawnCharacterRarity(card.getRarity());
          p.setSpawnCharacterLevel(level);
          p.setSpawnDeployTime(proj.getSpawnDeployTime());
        }

        // Wire spawnProjectile for sub-projectile spawning on impact (e.g. Log rolling)
        if (proj.getSpawnProjectile() != null) {
          p.setSpawnProjectile(proj.getSpawnProjectile());
          p.setSpellRarity(card.getRarity());
          p.setSpellLevel(level);
        }

        state.spawnProjectile(p);
      }
    } else {
      // Instant spell
      aoeDamageService.applySpellDamage(
          team, x, y, damage, radius, effects, proj.getCrownTowerDamagePercent());
    }
  }

  void fireSpawnProjectile(Team team, Card card, float x, float y, int level) {
    ProjectileStats stats = card.getSpawnProjectile();
    int damage = LevelScaling.scaleCard(stats.getDamage(), card.getRarity(), level);
    float startX, startY;
    Tower crownTower = state.getCrownTower(team);
    if (crownTower != null) {
      startX = crownTower.getPosition().getX();
      startY = crownTower.getPosition().getY();
    } else {
      startX = x;
      startY = (team == Team.BLUE) ? y - SPELL_TRAVEL_DISTANCE : y + SPELL_TRAVEL_DISTANCE;
    }
    Projectile p =
        new Projectile(
            team,
            startX,
            startY,
            x,
            y,
            damage,
            stats.getRadius(),
            stats.getSpeed(),
            stats.getHitEffects(),
            stats.getCrownTowerDamagePercent());
    p.setPushback(stats.getPushback());
    p.setPushbackAll(stats.isPushbackAll());
    state.spawnProjectile(p);
  }
}
