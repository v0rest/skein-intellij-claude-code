# Changelog

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
