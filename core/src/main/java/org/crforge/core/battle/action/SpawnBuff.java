package org.crforge.core.battle.action;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * A spawn row of the buff type: it puts its buff on its owner for its spawn time, at the level and
 * for the side of the entity that caused it, that entity as the buff's source; with no cause, the
 * owner's own. A buff written inline is the buff row of its Name. It does not last.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Held by clone_golem_group: the Clone's buff on the unit it clones, from the Clone; and by"
            + " goblin_curse_knights: the curse and its damage over time on every enemy the base's"
            + " hit reaches, from the base, refreshed every tick; and by dark_magic_knight and"
            + " dark_magic_group: a buff written inline, from the area effect whose laser ball"
            + " scheduled it on each target, at its level; and by little_prince_giant and"
            + " little_prince_retarget: the Little Prince's speed-ups on itself, from itself, the"
            + " unit whose attack start ran the row. Refused"
            + " as the row is built: a buff its parent controls, a source taken from the owner's"
            + " parent, and a spawn time below 1.")
public final class SpawnBuff extends RowAction {

  private final String buff;
  private final int spawnTimeMs;

  /**
   * @param row the row's shared columns
   * @param buff the buff row's name
   * @param spawnTimeMs how long the buff lasts
   */
  public SpawnBuff(ActionRow row, String buff, int spawnTimeMs) {
    super(row);
    this.buff = buff;
    this.spawnTimeMs = spawnTimeMs;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    ActionOwner owner = holder.getOwner();
    ActionOwner cause = instigator == null ? null : instigator.getOwner();
    owner.spawnBuff(name(), buff, spawnTimeMs, cause != null ? cause : owner);
    return null;
  }
}
