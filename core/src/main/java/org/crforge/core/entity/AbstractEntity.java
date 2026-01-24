package org.crforge.core.entity;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.player.Team;
import org.crforge.core.effect.AppliedEffect;

import java.util.ArrayList;
import java.util.List;

@Getter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(of = {"id", "name", "team"})
public abstract class AbstractEntity implements Entity {

  private static long nextId = 1;

  @EqualsAndHashCode.Include
  protected final long id;
  protected final String name;
  protected final Team team;
  protected final Position position;
  protected final Health health;
  protected final Movement movement;

  protected final List<AppliedEffect> appliedEffects = new ArrayList<>();

  protected boolean spawned;
  protected boolean dead;

  protected AbstractEntity(
      String name,
      Team team,
      float x,
      float y,
      int maxHealth,
      float speed,
      float mass,
      float size,
      MovementType movementType) {
    this.id = nextId++;
    this.name = name;
    this.team = team;
    this.position = new Position(x, y);
    this.health = new Health(maxHealth);
    this.movement = new Movement(speed, mass, size, movementType);
    this.spawned = false;
    this.dead = false;
  }

  public static void resetIdCounter() {
    nextId = 1;
  }

  @Override
  public float getSize() {
    return movement.getSize();
  }

  @Override
  public MovementType getMovementType() {
    return movement.getType();
  }

  @Override
  public boolean isAlive() {
    return !dead && health.isAlive();
  }

  @Override
  public boolean isTargetable() {
    return isAlive() && spawned;
  }

  public void markDead() {
    this.dead = true;
  }

  @Override
  public void onSpawn() {
    this.spawned = true;
  }

  @Override
  public void onDeath() {
    this.dead = true;
  }

  @Override
  public List<AppliedEffect> getAppliedEffects() {
    return appliedEffects;
  }

  @Override
  public void addEffect(AppliedEffect effect) {
    appliedEffects.add(effect);
  }
}
