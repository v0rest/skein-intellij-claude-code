# Changelog

## [1.2.0](https://github.com/v0rest/skein-intellij-claude-code/compare/v1.1.0...v1.2.0) (2026-11-13)


### Features

* show a worktree's changes against its base in the IDE's diff viewer ([#13](https://github.com/v0rest/skein-intellij-claude-code/issues/13)) ([83d69b7](https://github.com/v0rest/skein-intellij-claude-code/commit/83d69b7d65c8740a19beb21682ea0461d42bb986))
* show whether a waiting thread waits for a permission or an answer ([#10](https://github.com/v0rest/skein-intellij-claude-code/issues/10)) ([57eee93](https://github.com/v0rest/skein-intellij-claude-code/commit/57eee93e2de8f66d08dd46c1f252d2bd130e245e))
* start a thread in a folder from the Project view with New Thread Here ([#11](https://github.com/v0rest/skein-intellij-claude-code/issues/11)) ([3d85617](https://github.com/v0rest/skein-intellij-claude-code/commit/3d856171911b777895d617a5482af54271efb63d))
* warn when a new worktree would start from a branch behind its remote ([#12](https://github.com/v0rest/skein-intellij-claude-code/issues/12)) ([3495dcf](https://github.com/v0rest/skein-intellij-claude-code/commit/3495dcf46bb6b81d69f2b1e4bf94cecbc908fc1d))


### Bug Fixes

* keep file names from being read as HTML in Show Changes ([#15](https://github.com/v0rest/skein-intellij-claude-code/issues/15)) ([6b98162](https://github.com/v0rest/skein-intellij-claude-code/commit/6b98162e223cef74f3ec3cc745fcf796f59e37de))

## [1.1.0](https://github.com/v0rest/skein-intellij-claude-code/compare/v1.0.1...v1.1.0) (2026-11-12)


### Features

* keep threads in the Terminal tool window if you like, and follow a tab moved there ([#6](https://github.com/v0rest/skein-intellij-claude-code/issues/6)) ([5838265](https://github.com/v0rest/skein-intellij-claude-code/commit/58382659801b3fbd0b72ef55dbe90ce3ba0c0b16))

## [1.0.1](https://github.com/v0rest/skein-intellij-claude-code/compare/v1.0.0...v1.0.1) (2026-11-10)


### Bug Fixes

* describe Skein fully on its Marketplace page ([#2](https://github.com/v0rest/skein-intellij-claude-code/issues/2)) ([4b40b11](https://github.com/v0rest/skein-intellij-claude-code/commit/4b40b1137b7bb820d93f3751ea4858216eeee5b7))

## 1.0.0 (2026-11-10)


### Features

* run Claude Code sessions as threads in editor tabs, with each thread's state, model and context in the Skein tool window
* resume past sessions from the History, and pin, name and mark them done
* notify when a thread needs you or finishes its turn, with a sound and a counter of threads waiting for you
* start threads in git worktrees, show a worktree's changes on its row and keep, open or remove kept worktrees
* end claude and its shell before a thread's tab closes, so no session is left running behind a closed tab
