package org.crforge.core.entity.base;

import java.util.List;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.component.SpawnerComponent;
import org.crforge.core.effect.AppliedEffect;
import org.crforge.core.player.Team;

public interface Entity {

  long getId();

  String getName();

  Team getTeam();

  Position getPosition();

  Movement getMovement();

  Combat getCombat();

  Health getHealth();

  SpawnerComponent getSpawner();

  int getLevel();

  /**
   * The radius used for collision detection and range calculations.
   */
  float getCollisionRadius();

  /**
   * The radius used for visual rendering.
   */
  float getVisualRadius();

  EntityType getEntityType();

  MovementType getMovementType();

  boolean isAlive();

  boolean isTargetable();

  boolean isInvulnerable();

  void update(float deltaTime);

  void onSpawn();

  void onDeath();

  List<AppliedEffect> getAppliedEffects();

  void addEffect(AppliedEffect effect);
}
