/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.spawn;

import java.util.List;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.ActionRow;
import org.crforge.core.battle.action.RowAction;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that spawns characters: a spawn row of either class, with its spawn type the character
 * one. When it starts, its perform works out the block from the owner and the entity that caused
 * it, the battle's spawner creates the children, and each child is linked into its source's group
 * when the row asks and the source is a character; it does not last.
 *
 * <p>A champion it spawns is then handed to its side's champion slots, which follow its play.
 *
 * <p>A row that validates its point as a building's places its child where the point is free of
 * buildings other than the row's owner and its cell can be stood on; a blocked point, whose search
 * for another row is not modelled, is refused.
 *
 * <p>Refused rather than guessed: a row with no source, and a row whose spawn would make any other
 * call after it - the shared-target schedule or the clone - neither of which is modelled.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the perform's block from the owner and the cause, handed to the battle's"
            + " spawner, the group link after the spawn and the champion hand-over after it; a"
            + " building placement's free point kept, the owner passed by, held by hero_goblins"
            + " and seen in ability_hero_musketeer, whose dummy building places its turret on its"
            + " own point. Not modelled: the"
            + " action's target, the building placement's search off a blocked point and the other"
            + " calls after the spawn; a row that needs one of them is refused.")
public final class SpawnCharacters extends RowAction {

  private final SpawnRow spawn;

  /**
   * @param row the row's shared columns
   * @param spawn the row's spawn columns
   */
  public SpawnCharacters(ActionRow row, SpawnRow spawn) {
    super(row);
    this.spawn = spawn;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    if (!(holder.getOwner() instanceof SpawnHost owner)) {
      throw new UnsupportedOperationException(name() + " runs on an object that cannot spawn");
    }
    SpawnHost cause =
        instigator != null && instigator.getOwner() instanceof SpawnHost host ? host : null;
    // A row that validates its point as a building's asks its owner for the placement, for the
    // row it spawns: the point is kept when it is free; a blocked one is refused.
    SpawnPerform.PlacementSearch placement =
        spawn.validatePlacementAsBuilding() ? owner.buildingPlacement(spawn.spawnData()) : null;
    SpawnArguments arguments = SpawnPerform.arguments(spawn, owner, cause, false, false, placement);
    if (arguments.source() == null) {
      throw new UnsupportedOperationException(name() + " has no source to spawn from");
    }
    boolean champion = arguments.configuration().champion();
    List<SpawnPerform.AfterSpawnCall> unmodelled =
        SpawnPerform.afterSpawn(
                spawn, arguments.source(), false, champion, Math.max(arguments.count(), 0))
            .stream()
            .filter(
                call ->
                    call.kind() != SpawnPerform.AfterSpawnKind.GROUP_LINK
                        && call.kind() != SpawnPerform.AfterSpawnKind.CHAMPION)
            .toList();
    if (!unmodelled.isEmpty()) {
      throw new UnsupportedOperationException(
          name() + " would make calls after the spawn, which are not modelled: " + unmodelled);
    }
    SpawnHost source = (SpawnHost) arguments.source();
    List<SpawnHost> children = source.spawnCharacters(arguments);
    // The calls after the spawn run over the children it made, once every one is in the battle.
    for (SpawnPerform.AfterSpawnCall call :
        SpawnPerform.afterSpawn(spawn, source, false, champion, children.size())) {
      SpawnHost child = children.get(call.child());
      if (call.kind() == SpawnPerform.AfterSpawnKind.GROUP_LINK) {
        source.linkIntoGroup(child);
      } else {
        source.handOverChampion(child);
      }
    }
    return null;
  }
}
