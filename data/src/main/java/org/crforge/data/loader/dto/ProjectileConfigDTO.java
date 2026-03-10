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
  private Boolean homing;

  /**
   * Buff name applied to the target on hit (e.g. "IceWizardSlowDown").
   */
  private String targetBuff;

  /**
   * Duration of the targetBuff in seconds.
   */
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

  // Spawn sub-projectile on impact (Firecracker explosion) - string reference
  private String spawnProjectile;
  private int spawnCount;
  private float spawnRadius;

  // Character spawning on impact (GoblinBarrel, BarbLog)
  private SpawnConfigDTO spawn;

  // Knockback on hit (raw CSV units: divide by 1000 for tiles)
  private int pushback;
  private boolean pushbackAll;

  // Deflect behaviours (stored for future use)
  private List<String> deflectBehaviours;
}
