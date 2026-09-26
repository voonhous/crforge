package org.crforge.core.battle.unit;

import org.crforge.core.battle.filter.FilterSubject;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;

/** A battle entity as a game object filter asks about it. */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Answered from the entity: its kind, team, tag word, crown tower, building, alive, flying,"
            + " hit points, row name, state and whether its row ignores pushback; the summoner is the"
            + " king tower. Supplied: a princess tower is a row with the summoner-tower column, and"
            + " nothing is hidden, underground, a clone, attached to a parent, invisible or immune"
            + " while dashing, none of which the battle models yet.")
final class EntityFilterSubject implements FilterSubject {

  private final WorldEntity entity;

  EntityFilterSubject(WorldEntity entity) {
    this.entity = entity;
  }

  private GridEntity view() {
    return entity.getView();
  }

  @Override
  public int objectType() {
    return CHARACTER;
  }

  @Override
  public int team() {
    return entity.side() & 1;
  }

  @Override
  public long tags() {
    return view().getFlags();
  }

  @Override
  public boolean crownTower() {
    return view().isCrownTower();
  }

  @Override
  public boolean building() {
    return entity.getData().building();
  }

  @Override
  public boolean alive() {
    return view().isAlive();
  }

  @Override
  public boolean hidden() {
    return false;
  }

  @Override
  public boolean underground() {
    return false;
  }

  @Override
  public boolean summoner() {
    return entity.getData().king();
  }

  @Override
  public boolean flying() {
    return entity.getData().air();
  }

  @Override
  public boolean hasHitPoints() {
    return entity.getHitPoints() != null;
  }

  @Override
  public boolean princessTower() {
    return entity.getData().summonerTower();
  }

  @Override
  public boolean isClone() {
    return false;
  }

  @Override
  public boolean attachedChild() {
    return false;
  }

  @Override
  public String rowName() {
    return entity.getData().name();
  }

  @Override
  public int state() {
    return view().getState();
  }

  @Override
  public int dashImmuneMs() {
    return 0;
  }

  @Override
  public int invisibleCounter() {
    return 0;
  }

  @Override
  public boolean ignoresPushback() {
    return entity.getData().ignorePushback();
  }
}
