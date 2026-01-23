package org.crforge.core.card;

import lombok.Builder;
import lombok.Getter;
import org.crforge.core.entity.MovementType;
import org.crforge.core.entity.TargetType;

@Getter
@Builder
public class TroopStats {
  private final String name;
  @Builder.Default private final int health = 100;
  @Builder.Default private final int damage = 50;
  @Builder.Default private final float speed = 1.0f;
  @Builder.Default private final float mass = 1.0f;
  @Builder.Default private final float size = 1.0f;
  @Builder.Default private final float range = 1.0f;
  @Builder.Default private final float sightRange = 5.5f;
  @Builder.Default private final float attackCooldown = 1.0f;
  @Builder.Default private final float aoeRadius = 0f;
  @Builder.Default private final MovementType movementType = MovementType.GROUND;
  @Builder.Default private final TargetType targetType = TargetType.ALL;
  @Builder.Default private final boolean ranged = false;
  @Builder.Default private final float deployTime = 1.0f;
  @Builder.Default private final float offsetX = 0f;
  @Builder.Default private final float offsetY = 0f;
}
