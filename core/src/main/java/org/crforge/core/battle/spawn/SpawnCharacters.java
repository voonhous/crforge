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
 * <p>Refused rather than guessed: a row that asks for a building's placement, a row with no source,
 * and a row whose spawn would make any other call after it - the shared-target schedule or the
 * clone - neither of which is modelled. The champion hand-over is not asked for: the battle's units
 * carry no ability to tell a champion by.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the perform's block from the owner and the cause, handed to the battle's"
            + " spawner, and the group link after the spawn. Not modelled: the action's target,"
            + " the building placement search, the other calls after the spawn and the champion"
            + " hand-over; a row that needs one is refused, but a champion is not recognised.")
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
    if (spawn.validatePlacementAsBuilding()) {
      throw new UnsupportedOperationException(
          name() + " asks for a building's placement, which is not modelled");
    }
    SpawnArguments arguments = SpawnPerform.arguments(spawn, owner, cause, false, false, null);
    if (arguments.source() == null) {
      throw new UnsupportedOperationException(name() + " has no source to spawn from");
    }
    List<SpawnPerform.AfterSpawnCall> unmodelled =
        SpawnPerform.afterSpawn(
                spawn, arguments.source(), false, false, Math.max(arguments.count(), 0))
            .stream()
            .filter(call -> call.kind() != SpawnPerform.AfterSpawnKind.GROUP_LINK)
            .toList();
    if (!unmodelled.isEmpty()) {
      throw new UnsupportedOperationException(
          name() + " would make calls after the spawn, which are not modelled: " + unmodelled);
    }
    SpawnHost source = (SpawnHost) arguments.source();
    List<SpawnHost> children = source.spawnCharacters(arguments);
    // The calls after the spawn run over the children it made, once every one is in the battle.
    for (SpawnPerform.AfterSpawnCall call :
        SpawnPerform.afterSpawn(spawn, source, false, false, children.size())) {
      source.linkIntoGroup(children.get(call.child()));
    }
    return null;
  }
}
