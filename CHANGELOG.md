# Changelog

## [1.5.5](https://github.com/v0rest/skein-intellij-claude-code/compare/v1.5.4...v1.5.5) (2026-11-26)


### Bug Fixes

* keep the clicked thread row selected and clear it with a click below the rows ([#46](https://github.com/v0rest/skein-intellij-claude-code/issues/46)) ([52d651d](https://github.com/v0rest/skein-intellij-claude-code/commit/52d651d78c13c4b1591097469bdb2149aeaa6bf9))

## [1.5.4](https://github.com/v0rest/skein-intellij-claude-code/compare/v1.5.3...v1.5.4) (2026-11-25)


### Bug Fixes

* keep a thread's colour with its session and make thread colours stronger ([#44](https://github.com/v0rest/skein-intellij-claude-code/issues/44)) ([db1f9d9](https://github.com/v0rest/skein-intellij-claude-code/commit/db1f9d946261fc5190752f54be590b9a17a263e5))
* resume a restored thread whose tab is in front, without a click ([#43](https://github.com/v0rest/skein-intellij-claude-code/issues/43)) ([5484c3d](https://github.com/v0rest/skein-intellij-claude-code/commit/5484c3d64aa438d4a931be155fc5f2b216a236c1))

## [1.5.3](https://github.com/v0rest/skein-intellij-claude-code/compare/v1.5.2...v1.5.3) (2026-11-18)


### Bug Fixes

* end claude when a thread tab closes past Skein's close check, by a middle click or Shift+click ([#40](https://github.com/v0rest/skein-intellij-claude-code/issues/40)) ([c46cf01](https://github.com/v0rest/skein-intellij-claude-code/commit/c46cf01de6b7346c1fcbd317ca745aa54997176b))

## [1.5.2](https://github.com/v0rest/skein-intellij-claude-code/compare/v1.5.1...v1.5.2) (2026-11-18)


### Bug Fixes

* keep thread tabs through the IDE's branch workspace restore ([#38](https://github.com/v0rest/skein-intellij-claude-code/issues/38)) ([ef19f38](https://github.com/v0rest/skein-intellij-claude-code/commit/ef19f38fc339fa3b432918a796c4c37ed451c4a4))

## [1.5.1](https://github.com/v0rest/skein-intellij-claude-code/compare/v1.5.0...v1.5.1) (2026-11-17)


### Bug Fixes

* end claude when a tab closes right after it starts or its project closes ([#33](https://github.com/v0rest/skein-intellij-claude-code/issues/33)) ([0c3924a](https://github.com/v0rest/skein-intellij-claude-code/commit/0c3924a6ba9738441b92628e6a5133ff6201b5c4))
* name the other project in the balloon's text and keep the sound pickers inside narrow settings ([#34](https://github.com/v0rest/skein-intellij-claude-code/issues/34)) ([0d388b8](https://github.com/v0rest/skein-intellij-claude-code/commit/0d388b8339e855146004a41ee4892bb6b7e19c64))

## [1.5.0](https://github.com/v0rest/skein-intellij-claude-code/compare/v1.4.1...v1.5.0) (2026-11-16)


### Features

* choose the sound for each kind of notification ([#31](https://github.com/v0rest/skein-intellij-claude-code/issues/31)) ([28edabe](https://github.com/v0rest/skein-intellij-claude-code/commit/28edabee54d481f1b626af9ff8c7682b64c9e20a))

## [1.4.1](https://github.com/v0rest/skein-intellij-claude-code/compare/v1.4.0...v1.4.1) (2026-11-16)


### Bug Fixes

* rescan an open History when you come back to the IDE or into the Skein panel ([#28](https://github.com/v0rest/skein-intellij-claude-code/issues/28)) ([ef86d4f](https://github.com/v0rest/skein-intellij-claude-code/commit/ef86d4fb7c6ae34a1d59c4feb64bb65b435cc5e5))
* show a thread's balloon in the project in front when its own project is behind ([#29](https://github.com/v0rest/skein-intellij-claude-code/issues/29)) ([2c68a49](https://github.com/v0rest/skein-intellij-claude-code/commit/2c68a49f40d54709fd38e7574442ba5b29b6f060))

## [1.4.0](https://github.com/v0rest/skein-intellij-claude-code/compare/v1.3.1...v1.4.0) (2026-11-15)


### Features

* let Rename… also rename the session in Claude Code ([#27](https://github.com/v0rest/skein-intellij-claude-code/issues/27)) ([9bb8288](https://github.com/v0rest/skein-intellij-claude-code/commit/9bb8288361b5335603e6865dee3f077735e92399))


### Bug Fixes

* keep the Skein title bar buttons in sight while the tool window is not active ([#25](https://github.com/v0rest/skein-intellij-claude-code/issues/25)) ([c67ca0f](https://github.com/v0rest/skein-intellij-claude-code/commit/c67ca0f8a23fe087b795d42de158a8a2e21c3b91))

## [1.3.1](https://github.com/v0rest/skein-intellij-claude-code/compare/v1.3.0...v1.3.1) (2026-11-15)


### Bug Fixes

* ask for an IDE restart on install and update, since Skein loads native libraries ([#23](https://github.com/v0rest/skein-intellij-claude-code/issues/23)) ([925246f](https://github.com/v0rest/skein-intellij-claude-code/commit/925246fb46569d88b8cb5950f44f5cf75d025be0))

## [1.3.0](https://github.com/v0rest/skein-intellij-claude-code/compare/v1.2.0...v1.3.0) (2026-11-15)


### Features

* give New Thread in Worktree its own button next to + ([#18](https://github.com/v0rest/skein-intellij-claude-code/issues/18)) ([1373cc6](https://github.com/v0rest/skein-intellij-claude-code/commit/1373cc6d0631bf107080a2a6168356318922e3e0))
* run Skein on native Windows ([#19](https://github.com/v0rest/skein-intellij-claude-code/issues/19)) ([4a24215](https://github.com/v0rest/skein-intellij-claude-code/commit/4a242150f0c68e4b172e9068608d30453c73c5df))


### Bug Fixes

* show a thread that waits for claude's folder-trust question and warn before a worktree in an untrusted folder ([#21](https://github.com/v0rest/skein-intellij-claude-code/issues/21)) ([8b6c8cd](https://github.com/v0rest/skein-intellij-claude-code/commit/8b6c8cd3015c0f15e8648d98c61d4fc526995009))

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
