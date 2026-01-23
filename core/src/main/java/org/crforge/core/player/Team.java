package org.crforge.core.player;

public enum Team {
  BLUE,
  RED;

  public Team opposite() {
    return this == BLUE ? RED : BLUE;
  }
}
