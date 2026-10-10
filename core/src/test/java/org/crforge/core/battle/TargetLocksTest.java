/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the battle's target locks grant, refuse, release and drop. */
class TargetLocksTest {

  private static final int CHANNEL = 1;

  @Test
  @DisplayName("a request is only filed; the post-pass grants the highest priority per target")
  void thePostPassGrantsTheHighestPriority() {
    TargetLocks locks = new TargetLocks();
    assertThat(locks.request(10, 99, CHANNEL, 5, 0)).isTrue();
    assertThat(locks.request(11, 99, CHANNEL, 7, 0)).isTrue();
    assertThat(locks.claim(11, 99, CHANNEL)).as("nothing held before the post-pass").isFalse();

    locks.postPass();

    assertThat(locks.claim(11, 99, CHANNEL)).isTrue();
    assertThat(locks.claim(10, 99, CHANNEL)).isFalse();
    assertThat(locks.heldByOther(10, 99, CHANNEL)).isTrue();
    assertThat(locks.heldByOther(10, 99, 0)).as("another channel").isFalse();
  }

  @Test
  @DisplayName("of two requests of equal priority the first keeps the target")
  void theFirstOfEqualsKeepsIt() {
    TargetLocks locks = new TargetLocks();
    locks.request(10, 99, CHANNEL, 5, 0);
    locks.request(11, 99, CHANNEL, 5, 0);

    locks.postPass();

    assertThat(locks.claim(10, 99, CHANNEL)).isTrue();
    assertThat(locks.claim(11, 99, CHANNEL)).isFalse();
  }

  @Test
  @DisplayName("a locked target answers a further request only to its holder under flag bit 1")
  void aLockedTargetAnswersOnlyItsHolder() {
    TargetLocks locks = new TargetLocks();
    locks.request(10, 99, CHANNEL, 5, 0);
    locks.postPass();

    assertThat(locks.request(10, 99, CHANNEL, 5, 2)).isTrue();
    assertThat(locks.request(10, 99, CHANNEL, 5, 0)).as("without the flag").isFalse();
    assertThat(locks.request(11, 99, CHANNEL, 9, 2)).as("another asker").isFalse();
  }

  @Test
  @DisplayName("a release is queued, and the pre-pass acts on it for the lock's own holder")
  void aReleaseWaitsForThePrePass() {
    TargetLocks locks = new TargetLocks();
    locks.request(10, 99, CHANNEL, 5, 0);
    locks.postPass();

    locks.release(11, 99, CHANNEL);
    locks.release(10, 99, CHANNEL);
    assertThat(locks.claim(10, 99, CHANNEL)).as("until the pre-pass").isTrue();

    locks.prePass(id -> true);

    assertThat(locks.claim(10, 99, CHANNEL)).isFalse();
  }

  @Test
  @DisplayName("the pre-pass drops a lock whose target or holder is no longer listed alive")
  void thePrePassDropsTheDead() {
    TargetLocks locks = new TargetLocks();
    locks.request(10, 98, CHANNEL, 5, 0);
    locks.request(10, 99, CHANNEL, 5, 0);
    locks.postPass();

    locks.prePass(id -> !Set.of(99).contains(id));

    assertThat(locks.claim(10, 98, CHANNEL)).isTrue();
    assertThat(locks.claim(10, 99, CHANNEL)).isFalse();
  }
}
