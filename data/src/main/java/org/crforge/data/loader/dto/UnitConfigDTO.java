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
  private BuffOnDamageConfigDTO buffOnDamage;

  // Death mechanics
  private DeathDamageConfigDTO deathDamage;

  // Building fields
  private float lifeTime;
  private LiveSpawnConfigDTO liveSpawn;
  private List<DeathSpawnConfigDTO> deathSpawn;

  // Abilities (Charge, Variable Damage, etc.)
  private List<AbilityConfigDTO> abilities;
}
