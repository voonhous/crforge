package org.crforge.data.loader.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import lombok.Data;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UnitConfigDTO {

  private String name;
  private int health;
  private int damage;
  private float speed;
  private float mass;

  // Radius for collision
  private Float collisionRadius;
  // Radius for rendering
  private Float visualRadius;

  private float range;
  private float sightRange;
  private float attackCooldown;
  private Float loadTime;
  private float areaDamageRadius;
  private MovementType movementType;
  private TargetType targetType;
  private Float deployTime;

  // Projectile reference (string name into projectiles.json)
  private String projectile;

  // Targeting and combat modifiers
  private boolean targetOnlyBuildings;
  private float minimumRange;
  private int crownTowerDamagePercent;
  private boolean ignorePushback;

  // Shield
  private int shieldHitpoints;

  // Combat modifiers
  private int multipleTargets;
  private int multipleProjectiles;
  private boolean selfAsAoeCenter;
  private BuffOnDamageConfigDTO buffOnDamage;

  // Kamikaze: unit dies after delivering its attack (e.g. Battle Ram)
  private boolean kamikaze;

  // River jump: unit can leap over the river instead of routing through bridges
  private boolean jumpEnabled;

  // Death mechanics
  private DeathDamageConfigDTO deathDamage;

  // Building fields
  private float lifeTime;
  private LiveSpawnConfigDTO liveSpawn;
  private List<DeathSpawnConfigDTO> deathSpawn;
  private AreaEffectConfigDTO deathAreaEffect;

  // Abilities (Charge, Variable Damage, etc.)
  private List<AbilityConfigDTO> abilities;

  // Tunnel spawn pathfinding speed (e.g. Miner underground travel speed)
  private float spawnPathfindSpeed;
}
