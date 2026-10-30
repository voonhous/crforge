/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.action.ActionContext;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.ConeShape;
import org.crforge.core.battle.action.RunOnResolvedObjects;
import org.crforge.core.battle.action.SetIndicatorOnTarget;
import org.crforge.core.battle.data.ActionBinding;
import org.crforge.core.battle.filter.GameObjectFilter;

/**
 * What an action that runs another on what its resolver finds asks of the battle whoever its owner
 * is: the objects the filter lets through, asked by the owner for its team and row, the objects a
 * cone from the owner's point lets through, and a row built for and scheduled on what it found or
 * on the owner. The owner answers for itself: its point, the heading a cone turns with, and the
 * binding and the holder a row scheduled on it takes.
 */
abstract class WorldResolvedObjectsHost implements RunOnResolvedObjects.Host {

  private final BattleWorld world;
  private final BattleAction action;
  private final BattleEntity asker;

  /**
   * @param world the battle
   * @param action the action that resolves the objects, for a refusal
   * @param asker the owner, which a filter's own-object test reads
   */
  WorldResolvedObjectsHost(BattleWorld world, BattleAction action, BattleEntity asker) {
    this.world = world;
    this.action = action;
    this.asker = asker;
  }

  /** The owner's team a filter tests: its side's lowest bit. */
  abstract int team();

  /** The owner's row name a filter tests. */
  abstract String rowName();

  /** The heading a cone that turns with the owner points along. */
  abstract int heading();

  /** What a row built for the owner reads from it. */
  abstract ActionBinding ownerBinding();

  /** The holder a row scheduled on the owner runs in. */
  abstract ActionHolder ownerHolder();

  @Override
  public List<SetIndicatorOnTarget.Candidate> candidates(GameObjectFilter filter) {
    List<SetIndicatorOnTarget.Candidate> out = new ArrayList<>();
    for (WorldEntity entity :
        world.resolverCandidates(filter, team(), rowName(), action.name(), asker)) {
      out.add(new MarkCandidate(entity, action.name()));
    }
    return out;
  }

  @Override
  public List<SetIndicatorOnTarget.Candidate> candidates(GameObjectFilter filter, ConeShape cone) {
    List<SetIndicatorOnTarget.Candidate> out = new ArrayList<>();
    for (WorldEntity entity :
        world.resolverConeCandidates(x(), y(), heading(), cone, filter, team(), rowName(), asker)) {
      out.add(new MarkCandidate(entity, action.name()));
    }
    return out;
  }

  @Override
  public void schedule(
      SetIndicatorOnTarget.Candidate target,
      String row,
      ActionHolder cause,
      ActionContext context) {
    WorldEntity entity = ((MarkCandidate) target).entity();
    BattleAction built = world.getActions().build(row, world.binding(entity));
    entity.actionHolder().schedule(built, ActionHolder.OWN_DELAY, false, cause, context);
  }

  @Override
  public void scheduleOnOwner(String row, ActionHolder cause, ActionContext context) {
    BattleAction built = world.getActions().build(row, ownerBinding());
    ownerHolder().schedule(built, ActionHolder.OWN_DELAY, false, cause, context);
  }
}
