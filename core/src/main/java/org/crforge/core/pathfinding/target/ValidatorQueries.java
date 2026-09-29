package org.crforge.core.pathfinding.target;

/**
 * The answers the reference validator needs from outside the targeting pass.
 *
 * <p>Every method has a default that gives the answer the standard 1v1 mode gives for an ordinary
 * troop or crown tower, so a caller only overrides what its own mode changes. The two game-mode
 * questions ({@link #avatarCount()} and {@link #excludedConfigKey()}) describe the match rather
 * than the entities.
 */
public interface ValidatorQueries {

  /** The standard 1v1 answers. */
  static ValidatorQueries standard1v1() {
    return new ValidatorQueries() {};
  }

  /**
   * Number of avatars in the match. Two tower rules only apply with four or six avatars, so the
   * standard mode's two switch both of them off.
   */
  default int avatarCount() {
    return 2;
  }

  /** Identity of the configuration row no entity may ever be targeted through. */
  default String excludedConfigKey() {
    return "HeistStorage3";
  }

  /** True when the owner has put this target's id on the list it must not attack. */
  default boolean ownerIgnores(int targetId) {
    return false;
  }

  /**
   * True when the target's side maps to a tower slot of the match. With four avatars a dead tower
   * is only rejected when it does map to one.
   */
  default boolean mapsToTowerSlot(TargetView target) {
    return false;
  }

  /** The answer an owner that is not a character needs before it may take a target at all. */
  default boolean nonCharacterOwnerAccepts(TargetView target) {
    return false;
  }

  /**
   * True when a unit with an attack sequence must skip this target. The question is only asked
   * about a target answering the presence flag.
   */
  default boolean attackSequenceRejects(TargetView target) {
    return false;
  }

  /** True when the target carries the buff the owner's IgnoreTargetsWithBuff column names. */
  default boolean carriesIgnoredBuff(TargetView target) {
    return false;
  }

  /**
   * True when a buff the target carries heals it: the pending-damage rule then keeps a target whose
   * full hit points exceed the damage on its way.
   */
  default boolean pendingDamageBuffHolds(TargetView target) {
    return false;
  }

  /**
   * True when the target will start a dash that makes it immune to damage before the damage on its
   * way lands, so the pending-damage rule keeps it: the question is the dash test, not an age.
   */
  default boolean pendingDamageIsRecent(TargetView target, int duration) {
    return false;
  }

  /**
   * The target row's full hit points at the level the validator hands over, the target's own. A
   * healing target is kept only while they exceed the damage on its way.
   */
  default int committedDamage(TargetView target, int key) {
    return 0;
  }

  /**
   * True when the damage on its way is lethal to the object holding the target's hit points: no
   * shield up, the target not untouchable, and the damage at least the hit points left.
   */
  default boolean pendingDamageAccepted(TargetView target, int amount) {
    return false;
  }
}
