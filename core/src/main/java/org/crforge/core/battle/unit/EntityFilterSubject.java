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
            + " whether it is a clone,"
            + " hit points, row name, state, whether it rides on a parent and whether its row ignores"
            + " pushback, its buffs' invisible count, and whether it is hidden, which asks the"
            + " entity's own hidden test as the filter's hidden flag does; the summoner is the"
            + " king tower. Supplied: a princess tower is a row with the summoner-tower column,"
            + " and nothing is underground or immune while dashing, neither of which the battle"
            + " models yet.")
final class EntityFilterSubject implements FilterSubject {

  private final WorldEntity entity;

  EntityFilterSubject(WorldEntity entity) {
    this.entity = entity;
  }

  /** The entity it answers for. */
  WorldEntity entity() {
    return entity;
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

  /**
   * The filter's hidden flag asks the entity's own hidden test, the one every hit, area and
   * attacker asks: so a filter that drops hidden objects drops a unit that still waits its turn to
   * deploy, one in its tunnel and a hidden Tesla alike.
   */
  @Override
  public boolean hidden() {
    return entity.hidden();
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
    return entity instanceof CharacterEntity character && character.isClone();
  }

  @Override
  public boolean attachedChild() {
    return entity instanceof CharacterEntity character && character.getParent() != null;
  }

  @Override
  public String rowName() {
    return entity.getData().name();
  }

  @Override
  public int rowGlobalId() {
    return entity.getData().globalId();
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
    return entity.getBuffs().invisibleCount();
  }

  @Override
  public boolean ignoresPushback() {
    return entity.getData().ignorePushback() || entity.getBuffs().ignoresPushBack();
  }
}
