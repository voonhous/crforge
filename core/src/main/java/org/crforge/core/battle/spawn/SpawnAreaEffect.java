package org.crforge.core.battle.spawn;

import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.ActionRow;
import org.crforge.core.battle.action.RowAction;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that spawns an area effect: a spawn row of the area-effect type. When it starts, the
 * area effect is created at the point of the holder's owner, for the side and at the level of the
 * entity that caused the action, that entity its source and its parent, and handed to the holder,
 * which gives it its id at once and admits it at the next cleanup, so it first updates on the next
 * tick. The level is re-based on the area effect's own rarity. A row that follows its parent
 * follows the holder's owner. It does not last.
 *
 * <p>Refused rather than guessed, as the row is built: a row of the location class, one that sets
 * any spawn column besides its data and type (the source taken from the owner, a level index, the
 * offsets), and an area effect whose row sets a column not modelled. Refused as it starts: a row
 * with no cause, and a cause that is a clone, whose byte the area effect would copy.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the point of the holder's owner, the side, level and parent from"
            + " the cause, the level re-based on the area effect's rarity, and the queue in the"
            + " pass that ran the action; held by goblin_curse_knights, where the Goblin Curse's"
            + " area effect spawns its base on its first pass, and by goblin_demolisher_knight,"
            + " where the Goblin Demolisher spawns the area effect that follows it. Refused: the"
            + " location class, the owner as the source, the level index, the offsets, a cause"
            + " that is missing or a clone, and an area effect that follows its target.")
public final class SpawnAreaEffect extends RowAction {

  private final String areaEffect;

  /**
   * @param row the row's shared columns
   * @param areaEffect the area effect row's name
   */
  public SpawnAreaEffect(ActionRow row, String areaEffect) {
    super(row);
    this.areaEffect = areaEffect;
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
    if (instigator == null || !(instigator.getOwner() instanceof SpawnHost source)) {
      throw new UnsupportedOperationException(name() + " has no source to spawn from");
    }
    owner.spawnAreaEffect(name(), areaEffect, source, holder.passPhase());
    return null;
  }
}
