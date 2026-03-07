package org.crforge.core.util;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@ToString
public class Vector2 {

  private float x;
  private float y;

  public Vector2(Vector2 other) {
    this.x = other.x;
    this.y = other.y;
  }

  public void set(float x, float y) {
    this.x = x;
    this.y = y;
  }

  public void set(Vector2 other) {
    this.x = other.x;
    this.y = other.y;
  }

  public void add(float dx, float dy) {
    this.x += dx;
    this.y += dy;
  }

  public void add(Vector2 other) {
    this.x += other.x;
    this.y += other.y;
  }

  /**
   * Returns the Euclidean distance between two arbitrary points.
   */
  public static float distance(float x1, float y1, float x2, float y2) {
    float dx = x2 - x1;
    float dy = y2 - y1;
    return (float) Math.sqrt(dx * dx + dy * dy);
  }

  public float distanceTo(Vector2 other) {
    return distance(this.x, this.y, other.x, other.y);
  }

  public float distanceToSquared(Vector2 other) {
    float dx = other.x - this.x;
    float dy = other.y - this.y;
    return dx * dx + dy * dy;
  }

  public float angleTo(Vector2 other) {
    return (float) Math.atan2(other.y - this.y, other.x - this.x);
  }

  public float length() {
    return (float) Math.sqrt(x * x + y * y);
  }

  public void normalize() {
    float len = length();
    if (len > 0) {
      x /= len;
      y /= len;
    }
  }

  public void scale(float factor) {
    x *= factor;
    y *= factor;
  }

  public Vector2 copy() {
    return new Vector2(this);
  }
}
