package org.crforge.core.battle.unit;

import org.crforge.core.battle.filter.FilterSubject;

/**
 * An object of the battle that is not an arena entity - a projectile, or an action owner standing
 * in for an area effect - as a game object filter asks about it: by its kind and its team alone.
 * Those answer a filter's team and type gates; anything a filter asks past them is not modelled for
 * such an object, and asking fails rather than guess.
 */
final class KindOnlySubject implements FilterSubject {

  private final int kind;
  private final int team;

  /**
   * @param kind the object's kind, as the filter numbers kinds
   * @param team its side's low bit
   */
  KindOnlySubject(int kind, int team) {
    this.kind = kind;
    this.team = team;
  }

  @Override
  public int objectType() {
    return kind;
  }

  @Override
  public int team() {
    return team;
  }

  private UnsupportedOperationException unmodelled() {
    return new UnsupportedOperationException(
        "a filter that passes an object of kind " + kind + " asks more than it answers");
  }

  @Override
  public long tags() {
    throw unmodelled();
  }

  @Override
  public boolean crownTower() {
    throw unmodelled();
  }

  @Override
  public boolean building() {
    throw unmodelled();
  }

  @Override
  public boolean alive() {
    throw unmodelled();
  }

  @Override
  public boolean hidden() {
    throw unmodelled();
  }

  @Override
  public boolean underground() {
    throw unmodelled();
  }

  @Override
  public boolean summoner() {
    throw unmodelled();
  }

  @Override
  public boolean flying() {
    throw unmodelled();
  }

  @Override
  public boolean hasHitPoints() {
    throw unmodelled();
  }

  @Override
  public boolean princessTower() {
    throw unmodelled();
  }

  @Override
  public boolean isClone() {
    throw unmodelled();
  }

  @Override
  public boolean attachedChild() {
    throw unmodelled();
  }

  @Override
  public String rowName() {
    throw unmodelled();
  }

  @Override
  public int state() {
    throw unmodelled();
  }

  @Override
  public int dashImmuneMs() {
    throw unmodelled();
  }

  @Override
  public int invisibleCounter() {
    throw unmodelled();
  }

  @Override
  public boolean ignoresPushback() {
    throw unmodelled();
  }

  @Override
  public boolean kamikaze() {
    throw unmodelled();
  }

  @Override
  public boolean ignoresResurrect() {
    throw unmodelled();
  }
}
