package org.crforge.core.component;

import lombok.Getter;
import lombok.Setter;
import org.crforge.core.util.Vector2;

@Getter
public class Position {

  private final Vector2 position;
  @Setter
  private float rotation;

  public Position(float x, float y) {
    this.position = new Vector2(x, y);
    this.rotation = 0;
  }

  public Position(float x, float y, float rotation) {
    this.position = new Vector2(x, y);
    this.rotation = rotation;
  }

  public float getX() {
    return position.getX();
  }

  public float getY() {
    return position.getY();
  }

  public Vector2 asVector() {
    return position;
  }

  public void set(float x, float y) {
    position.set(x, y);
  }

  public void add(float dx, float dy) {
    position.add(dx, dy);
  }

  public float distanceTo(Position other) {
    return position.distanceTo(other.position);
  }

  public float distanceTo(float x, float y) {
    return Vector2.distance(position.getX(), position.getY(), x, y);
  }

  public float angleTo(Position other) {
    return position.angleTo(other.position);
  }

  public Position copy() {
    return new Position(position.getX(), position.getY(), rotation);
  }
}
