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
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line and held by the recorded cases: the team and type gates, the tag"
            + " exclusion, the slot exclusions in their order and each asked only when set, the"
            + " name comparison, the character-only block and the two lists, and the one default.")
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
  @Builder.Default private final Set<String> includeCharactersWithData = Set.of();
  @Builder.Default private final Set<String> excludeCharactersWithData = Set.of();

  /**
   * Whether an object passes the filter.
   *
   * @param object the object
   * @param team the team the filter is asked for
   * @param name the row name the same-objects exclusion compares with
   */
  public boolean matches(FilterSubject object, int team, String name) {
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
        || filterPushbackIgnore && object.ignoresPushback()) {
      return false;
    }
    if (!includeCharactersWithData.isEmpty()
        && !includeCharactersWithData.contains(object.rowName())) {
      return false;
    }
    return !excludeCharactersWithData.contains(object.rowName());
  }
}
