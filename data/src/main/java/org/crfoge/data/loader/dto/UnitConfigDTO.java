package org.crfoge.data.loader.dto;

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
  private float aoeRadius;
  private MovementType movementType;
  private TargetType targetType;
  private Float deployTime;
  private float offsetX;
  private float offsetY;
  private List<EffectConfigDTO> hitEffects;
  private ProjectileConfigDTO projectile;

  // Spawn Mechanics
  private int spawnDamage;
  private float spawnRadius;
  private List<EffectConfigDTO> spawnEffects;

  // Building fields
  private float lifeTime;
  private LiveSpawnConfigDTO liveSpawn;
  private List<DeathSpawnConfigDTO> deathSpawn;

  // Configuration convenience
  private int count = 1; // Default to 1 unit
}
