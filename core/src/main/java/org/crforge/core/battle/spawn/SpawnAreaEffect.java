package org.crforge.core.battle.spawn;

import java.util.function.IntSupplier;
import org.crforge.core.battle.action.ActionContext;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.ActionRow;
import org.crforge.core.battle.action.RowAction;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that spawns an area effect: a spawn row of the area-effect type. When it starts, the
 * area effect is created at the point of the holder's owner, moved by the row's offsets - the one
 * along the width as it stands, the one along the length turned toward the far side of the owner's
 * side - for the side and at the level of the entity that caused the action, that entity its source
 * and its parent, and handed to the holder, which gives it its id at once and admits it at the next
 * cleanup, so it first updates on the next tick. A row that takes its parent as the source takes
 * the holder's owner in place of the cause: the side, the level and the parent are then the
 * owner's. The level is re-based on the area effect's own rarity. A row that follows its parent
 * follows the holder's owner. It does not last.
 *
 * <p>Refused rather than guessed, as the row is built: a row of the location class, one that sets
 * any spawn column besides its data, its type, the owner as the source and the two offsets (a level
 * index, a count), and an area effect whose row sets a column not modelled. Refused as it starts: a
 * row with no cause that does not take the owner as the source, and a source that is a clone, whose
 * byte the area effect would copy.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the point of the holder's owner, the side, level and parent from"
            + " the cause, the level re-based on the area effect's rarity, and the queue in the"
            + " pass that ran the action; held by goblin_curse_knights, where the Goblin Curse's"
            + " area effect spawns its base on its first pass, and by goblin_demolisher_knight,"
            + " where the Goblin Demolisher spawns the area effect that follows it. The owner as"
            + " the source, its side, level and parent the owner's, held by"
            + " valkyrie_ev1_barbarians, where the area effect follows the owner too, and by"
            + " royal_giant_ev1_knights. The offsets, the one along the length by the owner's side,"
            + " held by ability_hero_wizard, where the hero Wizard's air projectile spawns its two"
            + " area effects 1000 beyond its point. Refused: the location class, the level index,"
            + " a cause that is missing or a clone, and an area effect that follows its target.")
public final class SpawnAreaEffect extends RowAction {

  private final String areaEffect;

  /** True when the holder's owner is the source in place of the cause. */
  private final boolean parentGoAsSource;

  /** The offset along the width, added to the owner's point as it stands. */
  private final int offsetX;

  /** The offset along the length, turned toward the far side of the owner's side. */
  private final int offsetY;

  /**
   * A location row's point, its two expressions evaluated with the context as the action starts;
   * null for the plain class, which takes the owner's point.
   */
  private final IntSupplier x;

  private final IntSupplier y;

  /** The name of the row scheduled on the area effect it spawns, or null for none. */
  private final String onSpawned;

  /** True when that row carries the context the start carried. */
  private final boolean shareContext;

  /**
   * @param row the row's shared columns
   * @param areaEffect the area effect row's name
   * @param parentGoAsSource true to take the holder's owner as the source in place of the cause
   * @param offsetX the offset along the width
   * @param offsetY the offset along the length, before the owner's side turns it
   */
  public SpawnAreaEffect(
      ActionRow row, String areaEffect, boolean parentGoAsSource, int offsetX, int offsetY) {
    this(row, areaEffect, parentGoAsSource, offsetX, offsetY, null, null);
  }

  /**
   * @param row the row's shared columns
   * @param areaEffect the area effect row's name
   * @param parentGoAsSource true to take the holder's owner as the source in place of the cause
   * @param offsetX the offset along the width
   * @param offsetY the offset along the length, before the owner's side turns it
   * @param x a location row's point along the width, or null for the owner's point
   * @param y a location row's point along the length, or null for the owner's point
   */
  public SpawnAreaEffect(
      ActionRow row,
      String areaEffect,
      boolean parentGoAsSource,
      int offsetX,
      int offsetY,
      IntSupplier x,
      IntSupplier y) {
    this(row, areaEffect, parentGoAsSource, offsetX, offsetY, x, y, null, false);
  }

  /**
   * @param row the row's shared columns
   * @param areaEffect the area effect row's name
   * @param parentGoAsSource true to take the holder's owner as the source in place of the cause
   * @param offsetX the offset along the width
   * @param offsetY the offset along the length, before the owner's side turns it
   * @param x a location row's point along the width, or null for the owner's point
   * @param y a location row's point along the length, or null for the owner's point
   * @param onSpawned the name of the row scheduled on the area effect it spawns, or null
   * @param shareContext true to hand that row the context the start carried
   */
  public SpawnAreaEffect(
      ActionRow row,
      String areaEffect,
      boolean parentGoAsSource,
      int offsetX,
      int offsetY,
      IntSupplier x,
      IntSupplier y,
      String onSpawned,
      boolean shareContext) {
    super(row);
    this.onSpawned = onSpawned;
    this.shareContext = shareContext;
    this.areaEffect = areaEffect;
    this.parentGoAsSource = parentGoAsSource;
    this.offsetX = offsetX;
    this.offsetY = offsetY;
    this.x = x;
    this.y = y;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    return start(holder, instigator, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator, ActionContext context) {
    if (!(holder.getOwner() instanceof SpawnHost owner)) {
      throw new UnsupportedOperationException(name() + " runs on an object that cannot spawn");
    }
    SpawnHost source;
    if (parentGoAsSource) {
      source = owner;
    } else if (instigator != null && instigator.getOwner() instanceof SpawnHost cause) {
      source = cause;
    } else {
      throw new UnsupportedOperationException(name() + " has no source to spawn from");
    }
    if (x != null || onSpawned != null) {
      owner.spawnAreaEffect(
          name(),
          areaEffect,
          source,
          x == null ? null : x.getAsInt(),
          y == null ? null : y.getAsInt(),
          offsetX,
          offsetY,
          onSpawned,
          shareContext ? context : null,
          holder.passPhase());
      return null;
    }
    owner.spawnAreaEffect(name(), areaEffect, source, offsetX, offsetY, holder.passPhase());
    return null;
  }
}
