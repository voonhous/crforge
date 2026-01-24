package org.crforge.core.entity;

import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.player.Team;
import org.crforge.core.effect.AppliedEffect;
import java.util.List;

public interface Entity {

  long getId();

  String getName();

  Team getTeam();

  Position getPosition();

  Movement getMovement();

  Combat getCombat();

  Health getHealth();

  float getSize();

  EntityType getEntityType();

  MovementType getMovementType();

  boolean isAlive();

  boolean isTargetable();

  void update(float deltaTime);

  void onSpawn();

  void onDeath();

  List<AppliedEffect> getAppliedEffects();

  void addEffect(AppliedEffect effect);
}
