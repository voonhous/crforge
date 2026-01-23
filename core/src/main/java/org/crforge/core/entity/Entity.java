package org.crforge.core.entity;

import org.crforge.core.component.Health;
import org.crforge.core.component.Position;
import org.crforge.core.player.Team;

public interface Entity {

  long getId();

  String getName();

  Team getTeam();

  Position getPosition();

  Health getHealth();

  float getSize();

  EntityType getEntityType();

  MovementType getMovementType();

  boolean isAlive();

  boolean isTargetable();

  void update(float deltaTime);

  void onSpawn();

  void onDeath();
}
