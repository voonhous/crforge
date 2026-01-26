package org.crforge.core.card;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;

/**
 * Defines the base statistics for a unit (Troop or Building turret) spawned by a {@link Card}.
 * <p>
 * <b>Blueprint Pattern:</b><br>
 * This class is a Data Transfer Object (DTO) holding the <i>initial</i> values for an entity's
 * components. It shares attribute names with components (like 'health' or 'speed') because it
 * provides the source data for them.
 * <p>
 * When a card is played, the {@link org.crforge.core.engine.DeploymentSystem} reads these values to
 * create new instances of:
 * <ul>
 * <li>{@link org.crforge.core.component.Health} (initialized with {@code health})</li>
 * <li>{@link org.crforge.core.component.Movement} (initialized with {@code speed}, {@code mass}, etc.)</li>
 * <li>{@link org.crforge.core.component.Combat} (initialized with {@code damage}, {@code range}, etc.)</li>
 * </ul>
 */
@Getter
@Builder
public class TroopStats {

  private final String name;
  @Builder.Default
  private final int health = 100;
  @Builder.Default
  private final int damage = 50;
  @Builder.Default
  private final float speed = 1.0f;
  @Builder.Default
  private final float mass = 1.0f;
  @Builder.Default
  private final float size = 1.0f;
  @Builder.Default
  private final float range = 1.0f;
  @Builder.Default
  private final float sightRange = 5.5f;
  @Builder.Default
  private final float attackCooldown = 1.0f;
  @Builder.Default
  private final float aoeRadius = 0f;
  @Builder.Default
  private final MovementType movementType = MovementType.GROUND;
  @Builder.Default
  private final TargetType targetType = TargetType.ALL;
  @Builder.Default
  private final boolean ranged = false;
  @Builder.Default
  private final float deployTime = 1.0f;
  @Builder.Default
  private final float offsetX = 0f;
  @Builder.Default
  private final float offsetY = 0f;
  @Builder.Default
  private final List<EffectStats> hitEffects = new ArrayList<>();
}
