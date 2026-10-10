/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * A spawn row of the buff type: it puts its buff on its owner for its spawn time, at the level and
 * for the side of the entity that caused it, that entity as the buff's source; with no cause, or
 * for a row that takes its owner as the source, the owner's own. A buff written inline is the buff
 * row of its Name. With InstigatorAsBuffController the source is also the buff's parent: kept by a
 * buff that stacks, its leaving removes the instance. It does not last.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Held by the reference battles card_Clone and spell_clone_into_push: the Clone's buff on"
            + " the unit it clones, from the Clone; and by card_GoblinCurse and"
            + " spell_goblincurse_into_push: the curse and its damage over time on every enemy"
            + " the base's hit reaches, from the base, refreshed every tick; and by the random"
            + " battles that play Dark Magic (random_battle16_s0010, random_battle16_s0013,"
            + " random_battle16_s0014, random_battle16_s0024): a buff written inline, from the"
            + " area effect whose laser ball scheduled it on each target, at its level; and by"
            + " ability_little_prince, card_LittlePrince and random_battle16_s0047: the Little"
            + " Prince's speed-ups on itself, from itself, the unit whose attack start ran the"
            + " row; and by evo_royalrecruits_vs_musketeer: a row that takes its owner as the"
            + " source, from the Recruit whose shield broke, not the arrow that broke it; and by"
            + " ability_hero_mega_minion_vs_musketeer: the hero's bot buff on the troop it marks,"
            + " from the hero, the hero its parent. Refused as the row is built: a buff its"
            + " parent controls without the source as its parent, a source taken from the owner's"
            + " parent, and a spawn time below 1.")
public final class SpawnBuff extends RowAction {

  private final String buff;
  private final int spawnTimeMs;
  private final boolean parentGoAsSource;
  private final boolean sourceAsParent;

  /**
   * @param row the row's shared columns
   * @param buff the buff row's name
   * @param spawnTimeMs how long the buff lasts
   * @param parentGoAsSource true to take the holder's owner as the source, its level and its side
   *     in place of the cause's
   */
  public SpawnBuff(ActionRow row, String buff, int spawnTimeMs, boolean parentGoAsSource) {
    this(row, buff, spawnTimeMs, parentGoAsSource, false);
  }

  /**
   * @param row the row's shared columns
   * @param buff the buff row's name
   * @param spawnTimeMs how long the buff lasts
   * @param parentGoAsSource true to take the holder's owner as the source, its level and its side
   *     in place of the cause's
   * @param sourceAsParent true to make the source the buff's parent too
   *     (InstigatorAsBuffController)
   */
  public SpawnBuff(
      ActionRow row,
      String buff,
      int spawnTimeMs,
      boolean parentGoAsSource,
      boolean sourceAsParent) {
    super(row);
    this.buff = buff;
    this.spawnTimeMs = spawnTimeMs;
    this.parentGoAsSource = parentGoAsSource;
    this.sourceAsParent = sourceAsParent;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    ActionOwner owner = holder.getOwner();
    ActionOwner cause = instigator == null ? null : instigator.getOwner();
    ActionOwner source = cause != null && !parentGoAsSource ? cause : owner;
    owner.spawnBuff(name(), buff, spawnTimeMs, source, sourceAsParent);
    return null;
  }
}
