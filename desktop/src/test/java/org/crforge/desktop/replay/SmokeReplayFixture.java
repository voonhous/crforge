package org.crforge.desktop.replay;

import java.nio.file.Path;

public class SmokeReplayFixture {
  public static ReplayFile create(boolean refused) {
    var doc = Replays.archerQueen();
    if (refused) doc.put("smoke_unknown_field", 1);
    return ReplayFile.parse(Path.of("smoke-replay.json"), doc, Replays.tables());
  }
}
