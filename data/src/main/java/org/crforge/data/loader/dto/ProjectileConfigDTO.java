package org.crforge.data.loader.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProjectileConfigDTO {

  private String name;
  private int damage;
  private float speed;
  private float radius;
  private float radiusY;
  private Boolean homing;

  /** Buff name applied to the target on hit (e.g. "IceWizardSlowDown"). */
  private String targetBuff;

  /** Duration of the targetBuff in seconds. */
  private float buffDuration;

  // AOE targeting flags
  private boolean aoeToAir;
  private boolean aoeToGround;

  // Chain lightning (ElectroDragon, ElectroSpirit)
  private float chainedHitRadius;
  private int chainedHitCount;

  // Scatter and range (Hunter, non-homing projectiles)
  private String scatter;
  private float projectileRange;

  // Returning: piercing projectile reverses at max range and travels back (Executioner)
  private boolean returning;

  // Pingpong: projectile reverses at max range and returns to the thrower (Executioner axe)
  private boolean pingpong;

  // Pingpong moving shooter: thrower keeps moving while returning projectile is in flight
  private float pingpongMovingShooter;

  // Spawn sub-projectile on impact (Firecracker explosion) - string reference
  private String spawnProjectile;
  private int spawnCount;
  private float spawnRadius;

  // Character spawning on impact (GoblinBarrel, BarbLog)
  private SpawnConfigDTO spawn;

  // Knockback on hit (raw CSV units: divide by 1000 for tiles)
  private int pushback;
  private boolean pushbackAll;

  // Spawn area effect on impact (Heal Spirit heal zone, etc.)
  private AreaEffectConfigDTO spawnAreaEffect;

  // Crown tower damage reduction (e.g. -70 = 30% damage to towers)
  private int crownTowerDamagePercent;

  // Projectile stops on first hit instead of piercing through (e.g. Hunter pellets)
  private boolean checkCollisions;

  // Deflect behaviours (stored for future use)
  private List<String> deflectBehaviours;

  // Piercing hit detection radius (distinct from AOE splash radius)
  private float projectileRadius;

  // Rectangular collision depth (Log/BarbLog only, stored for future use)
  private float projectileRadiusY;

  // Min travel distance before hits register (e.g. Log must roll past deploy point)
  private float minDistance;

  // Data-only fields (already handled by existing logic or visual-only)
  private boolean onlyEnemies;
  private int spawnChain;
  private int gravity;
}
