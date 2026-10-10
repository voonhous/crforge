/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.filter;

import java.util.Set;
import lombok.Builder;
import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntityState;

/**
 * One game object filter row: whether an object passes it, for a team and a name.
 *
 * <p>The test runs in three stages. The team gate: an object of the given team needs {@code
 * matchTeamOwn}, one of the other team {@code matchTeamEnemy}. The type gate: a projectile, an
 * area-effect object and a goblin reference each need their own column; a character needs {@code
 * matchTypeCharacters}, or {@code matchTowers} and to be a crown tower, or {@code
 * matchTypeBuildings} and to be a building; nothing else passes. Then a run of exclusions, the
 * first that holds rejecting: a shared tag, then dead, building, hidden, underground, tower,
 * summoner, flying, without hit points, princess tower, a row of the given name and clone, each
 * asked only when its column is set. For a character alone there follow an attached child (unless
 * allowed), jumping, dash immunity while dashing, dragging, invisibility, cloning and ignoring
 * pushback, then the include and the exclude lists of rows.
 *
 * <p>{@code filterDead} is the one column set by default.
 *
 * <p>{@code matchSelf} passes only the object that asks: a filter that sets it is asked with
 * whether the object is its asker, and refused when asked without.
 *
 * <p>A Filters list may also name three kinds no switch tests: {@code filterSelf} drops the asker
 * itself, and is refused, like {@code matchSelf}, when asked without whether the object is its
 * asker; {@code filterKamikaze} and {@code filterIgnoreResurrect} drop a character whose row sets
 * Kamikaze or IgnoreResurrect, in the character-only block. Each is a plain question, so its place
 * among the other exclusions does not change the answer.
 *
 * <p>The two buff checkers come last: FilterIfNotBuffedByChecker keeps only an object that carries
 * one of its buff rows applied by the asker, FilterIfBuffedByChecker drops one that does. A buff
 * counts as the asker's when the asker is its source, or when its source was a projectile the asker
 * launched. An object with no buffs fails the first and passes the second. A filter with either is
 * asked with the asker's id, and refused when asked without.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line and held by the recorded cases: the team and type gates, the tag"
            + " exclusion, the slot exclusions in their order and each asked only when set, the"
            + " name comparison, the character-only block and the two lists, and the one default."
            + " MatchSelf is read from the build's test: the"
            + " asker's own identity before the team gate. The two buff checkers"
            + " from the same test: a listed row whose source, or whose source"
            + " projectile's launcher, is the asker; held by BattleRunOnResolvedTest and the Ice"
            + " Wizard hero's tap. The Filters list's kinds Self, Kamikaze and IgnoreResurrect"
            + " from the same test: the object's id against the asker's, and"
            + " the character row's Kamikaze and IgnoreResurrect columns; IgnoreResurrect, which the"
            + " Skeleton King's death listener asks, held by BattleFilterKindsTest.")
@Getter
@Builder(toBuilder = true)
public final class GameObjectFilter {

  private final boolean matchTeamOwn;
  private final boolean matchTeamEnemy;
  private final boolean matchTypeCharacters;
  private final boolean matchTypeBuildings;
  private final boolean matchTypeProjectiles;
  private final boolean matchTypeAoe;
  private final boolean matchTypeGoblinRef;
  private final boolean matchTowers;
  private final boolean filterHidden;
  private final boolean filterInvisible;
  private final boolean filterUnderground;
  private final boolean filterBuildings;
  private final boolean filterTowers;
  private final boolean filterSummoner;
  private final boolean filterFlying;
  private final boolean filterJumping;
  private final boolean filterDashImmune;
  private final boolean filterDragging;
  private final boolean filterCloning;
  private final boolean filterIfNoHitpointComponent;
  private final boolean filterPushbackIgnore;
  private final boolean matchAttachedChildren;
  private final boolean filterSameObjects;
  private final long filterTags;
  private final boolean filterPrincessTowers;
  @Builder.Default private final boolean filterDead = true;
  private final boolean filterClones;
  private final boolean matchSelf;

  /** The Filters list's kind Self: the asker itself is dropped. */
  private final boolean filterSelf;

  /** The Filters list's kind Kamikaze: a character whose row sets Kamikaze is dropped. */
  private final boolean filterKamikaze;

  /**
   * The Filters list's kind IgnoreResurrect: a character whose row sets IgnoreResurrect is dropped.
   */
  private final boolean filterIgnoreResurrect;

  @Builder.Default private final Set<String> includeCharactersWithData = Set.of();
  @Builder.Default private final Set<String> excludeCharactersWithData = Set.of();

  /**
   * FilterIfNotBuffedByChecker: the buff rows of which an object must carry one its asker applied;
   * empty for no such test.
   */
  @Builder.Default private final Set<String> requireBuffsFromAsker = Set.of();

  /**
   * FilterIfBuffedByChecker: the buff rows of which an object must carry none its asker applied;
   * empty for no such test.
   */
  @Builder.Default private final Set<String> refuseBuffsFromAsker = Set.of();

  /**
   * Whether an object passes the filter.
   *
   * @param object the object
   * @param team the team the filter is asked for
   * @param name the row name the same-objects exclusion compares with
   */
  public boolean matches(FilterSubject object, int team, String name) {
    if (matchSelf) {
      throw new UnsupportedOperationException(
          "a game object filter that sets MatchSelf is asked without its asker, which is not"
              + " modelled");
    }
    if (filterSelf) {
      throw new UnsupportedOperationException(
          "a game object filter that lists the kind Self is asked without its asker, which is not"
              + " modelled");
    }
    refuseCheckersWithoutAsker();
    return passes(object, team, name);
  }

  /** Refuses a buff checker asked without the asker's id. */
  private void refuseCheckersWithoutAsker() {
    if (!requireBuffsFromAsker.isEmpty() || !refuseBuffsFromAsker.isEmpty()) {
      throw new UnsupportedOperationException(
          "a game object filter with a buff checker is asked without its asker, which is not"
              + " modelled");
    }
  }

  /**
   * Whether an object passes the filter, asked by an object that may be the one tested: with {@code
   * matchSelf} set, only the asker itself passes; with {@code filterSelf}, the asker itself fails.
   *
   * @param object the object
   * @param team the asker's team
   * @param name the row name the same-objects exclusion compares with: the asker's
   * @param asker true when the object is the asker itself
   */
  public boolean matches(FilterSubject object, int team, String name, boolean asker) {
    if (matchSelf && !asker || filterSelf && asker) {
      return false;
    }
    refuseCheckersWithoutAsker();
    return passes(object, team, name);
  }

  /**
   * Whether an object passes the filter, asked by an object whose id the buff checkers compare: an
   * object kept by FilterIfNotBuffedByChecker carries a listed buff the asker applied, and one kept
   * by FilterIfBuffedByChecker carries none.
   *
   * @param object the object
   * @param team the asker's team
   * @param name the row name the same-objects exclusion compares with: the asker's
   * @param asker true when the object is the asker itself
   * @param askerId the asker's id
   */
  public boolean matches(FilterSubject object, int team, String name, boolean asker, int askerId) {
    if (matchSelf && !asker || filterSelf && asker) {
      return false;
    }
    if (!passes(object, team, name)) {
      return false;
    }
    if (!requireBuffsFromAsker.isEmpty() && !object.buffedBy(requireBuffsFromAsker, askerId)) {
      return false;
    }
    return refuseBuffsFromAsker.isEmpty() || !object.buffedBy(refuseBuffsFromAsker, askerId);
  }

  /** The test of every column but {@code matchSelf}, {@code filterSelf} and the buff checkers. */
  private boolean passes(FilterSubject object, int team, String name) {
    if (object.team() == team ? !matchTeamOwn : !matchTeamEnemy) {
      return false;
    }
    int type = object.objectType();
    if (type == FilterSubject.AREA_EFFECT) {
      if (!matchTypeAoe) {
        return false;
      }
    } else if (type == FilterSubject.PROJECTILE) {
      if (!matchTypeProjectiles) {
        return false;
      }
    } else if (type == FilterSubject.GOBLIN_REF) {
      if (!matchTypeGoblinRef) {
        return false;
      }
    } else if (type == FilterSubject.CHARACTER) {
      if (!(matchTowers && object.crownTower()
          || matchTypeBuildings && object.building()
          || matchTypeCharacters)) {
        return false;
      }
    } else {
      return false;
    }

    if ((object.tags() & filterTags) != 0) {
      return false;
    }
    if (filterDead && !object.alive()
        || filterBuildings && object.building()
        || filterHidden && object.hidden()
        || filterUnderground && object.underground()
        || filterTowers && object.crownTower()
        || filterSummoner && object.summoner()
        || filterFlying && object.flying()
        || filterIfNoHitpointComponent && !object.hasHitPoints()
        || filterPrincessTowers && object.princessTower()
        || filterSameObjects && object.rowName().equals(name)
        || filterClones && object.isClone()) {
      return false;
    }
    if (type != FilterSubject.CHARACTER) {
      return true;
    }

    int state = object.state();
    if (!matchAttachedChildren && object.attachedChild()
        || filterJumping && state == GridEntityState.JUMPING
        || filterDashImmune && state == GridEntityState.DASHING && object.dashImmuneMs() > 0
        || filterDragging
            && (state == GridEntityState.FOLLOWING_REMOVED
                || state == GridEntityState.FOLLOWING_REMOVED_BUILDING)
        || filterInvisible && object.invisibleCounter() > 0
        || filterCloning && state == GridEntityState.CLONE_SETUP
        || filterPushbackIgnore && object.ignoresPushback()
        || filterKamikaze && object.kamikaze()
        || filterIgnoreResurrect && object.ignoresResurrect()) {
      return false;
    }
    if (!includeCharactersWithData.isEmpty()
        && !includeCharactersWithData.contains(object.rowName())) {
      return false;
    }
    return !excludeCharactersWithData.contains(object.rowName());
  }
}
