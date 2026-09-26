package org.crforge.core.battle.filter;

/** An object as a game object filter asks about it. */
public interface FilterSubject {

  /** The kind of an area-effect object. */
  int AREA_EFFECT = 3;

  /** The kind of a projectile. */
  int PROJECTILE = 4;

  /** The kind of a character: a troop, a building or a tower. */
  int CHARACTER = 5;

  /** The kind of the object a goblin reference is. */
  int GOBLIN_REF = 6;

  /** The object's kind. */
  int objectType();

  /** The object's team: its side's low bit. */
  int team();

  /** The object's tag word. */
  long tags();

  /** True for a crown tower. */
  boolean crownTower();

  /** True for a building. */
  boolean building();

  /** True while the object is alive. */
  boolean alive();

  /** True while the object is hidden. */
  boolean hidden();

  /** True while the object is underground. */
  boolean underground();

  /** True for a summoner: the king tower. */
  boolean summoner();

  /** True for a flying object. */
  boolean flying();

  /** True for an object with hit points. */
  boolean hasHitPoints();

  /** True for a princess tower. */
  boolean princessTower();

  /** True for a clone. */
  boolean isClone();

  /** True for a character attached to a parent. */
  boolean attachedChild();

  /** The name of the object's row. */
  String rowName();

  /** A character's state. */
  int state();

  /** A character's row's dash immunity time, in milliseconds. */
  int dashImmuneMs();

  /** A character's invisibility counter, which its buffs raise. */
  int invisibleCounter();

  /** True for a character whose row or one of whose buffs ignores pushback. */
  boolean ignoresPushback();
}
