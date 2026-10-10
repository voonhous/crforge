/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.replay;

import java.nio.file.Path;

public class SmokeReplayFixture {
  public static ReplayFile create(boolean refused) {
    var doc = Replays.archerQueen();
    if (refused) doc.put("smoke_unknown_field", 1);
    return ReplayFile.parse(Path.of("smoke-replay.json"), doc, Replays.tables());
  }

  /** Two real spell commands over a tower, for replay orientation and status rendering. */
  public static ReplayFile statuses() {
    var doc = Replays.archerQueen();
    var deck = doc.path("battle").path("deck0").path("sp");
    ((com.fasterxml.jackson.databind.node.ObjectNode) deck.get(0)).put("d", 28000005);
    ((com.fasterxml.jackson.databind.node.ObjectNode) deck.get(7)).put("d", 28000008);
    var commands = doc.putArray("cmd");
    for (int i = 0; i < 2; i++) {
      var command = commands.addObject().put("ct", Replays.PLAY).putObject("c");
      command.put("t", 200 + i * 40).put("t2", 220 + i * 40);
      command.put("idHi", 0).put("idLo", 1).put("px", 14500).put("py", 25500).put("sid", -1);
      command
          .putObject("sel")
          .put("os", i == 0 ? 28000005 : 28000008)
          .put("pd", i == 0 ? (4 << 28) | (1 << 22) | (5 << 10) : (2 << 28) | (8 << 22));
    }
    return ReplayFile.parse(Path.of("status-replay.json"), doc, Replays.tables());
  }
}
