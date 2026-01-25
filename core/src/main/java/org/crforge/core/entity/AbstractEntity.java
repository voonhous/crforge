package org.crforge.core.entity;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.component.SpawnerComponent;
import org.crforge.core.effect.AppliedEffect;
import org.crforge.core.player.Team;

@Getter
@SuperBuilder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(of = {"id", "name", "team"})
public abstract class AbstractEntity implements Entity {

  private static long nextId = 1;

  @EqualsAndHashCode.Include
  @Builder.Default
  protected final long id = nextId++;

  protected final String name;
  protected final Team team;
  protected final Position position;

  @Builder.Default
  protected final Health health = new Health(100);

  @Builder.Default
  protected final Movement movement = new Movement(0f, 0f, 1.0f, MovementType.GROUND);

  @Builder.Default
  protected final SpawnerComponent spawner = null;

  @Builder.Default
  protected final List<AppliedEffect> appliedEffects = new ArrayList<>();

  @Builder.Default
  protected boolean spawned = false;

  @Builder.Default
  protected boolean dead = false;

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
