package org.crfoge.data.loader.dto;

import java.util.List;
import lombok.Data;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;

@Data
public class UnitConfigDTO {

  private String name;
  private int health;
  private int damage;
  private float speed;
  private float mass;
  private float size;
  private float range;
  private float sightRange;
  private float attackCooldown;
  private float aoeRadius;
  private MovementType movementType;
  private TargetType targetType;
  private boolean ranged;
  private float deployTime;
  private float offsetX;
  private float offsetY;
  private List<EffectConfigDTO> hitEffects;

  // Configuration convenience
  private int count = 1; // Default to 1 unit
}
