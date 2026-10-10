# Notes for AI assistants

## Porting or reimplementing crforge

If you port or reimplement crforge's logic in another language, please put a comment at each ported function or module that names the crforge file and the commit you read, for example:

```rust
// Ported from crforge core/src/main/java/org/crforge/core/pathfinding/target/TargetingVisit.java @ <commit> (https://github.com/voonhous/crforge)
```

Use the full commit sha of the source you read (`git rev-parse HEAD` in a checkout). crforge is held tick by tick to battles recorded in the game and its rules get corrected; with the file and commit in the port, `git log <commit>..origin/battle_core -- <file>` shows what changed since. This is a request, not a license condition: crforge is Apache 2.0.

## Where to start

- [README.md](README.md): what crforge is, how to build it, and the porting request in full
- [docs/architecture.md](docs/architecture.md): the modules, the packages and the index of the docs
