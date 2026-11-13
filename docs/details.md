# Skein in detail

How Skein works, what it does with worktrees, what is tested and how releases are made. The short version is the
[README](../README.md).

## How it works

The Skein tool window sits on the right by default; drag its icon to the left like any tool window. "+" opens a
terminal tab with your shell in the project folder and types `claude` into it, and when the project also has folders
outside the project folder or separate repositories inside it, it first asks which one. The tab opens in the editor
area, or in the Terminal tool window when Open threads in the editor area is off in Settings | Tools | Skein, which
also counts for the threads a restart brings back; threads already open stay where they are when you change it. A
thread's tab can move afterwards, by Move to Editor or by dragging it between the editor and the Terminal tool window,
and Skein follows it there, so the thread stays in the list until its tab really closes. The chevron next to it also
offers New Thread in Worktree… (see below). A thread is orange when it waits for you (a permission prompt, a
question, any other dialog), blue when it finished its turn and you have not looked yet, and shows a spinner while it
works, each with its own glyph. The orange one is a ring with a lock in it for a permission prompt, a ring with a
speech bubble for a question and a ring with a dot for any other dialog, all three orange because all three need you,
and the tooltip says "waiting for your permission" or "waiting for your answer". Grouping, the waiting-only filter, the
counter, Jump to Waiting Thread and the tool window badge treat the three alike. Clicking a row opens its tab, in the
Terminal tool window by bringing the tool window up on it, and Tools | Skein has Jump to Waiting Thread for the
keyboard. A thread whose tab you have in front, the selected editor or the selected tab of the visible Terminal tool
window, counts as seen and gets no balloon.


A row has two lines. The first holds the state glyph, the name, a pin when the thread is pinned, the counts of a
worktree thread (see Worktree threads) and the age on the right, and the name is bold while the thread is unread or
needs you. The second line starts with the worktree mark when there is one, then a preview of what happened last, the
answer, the tool a permission prompt waits on or what a working thread is doing, and at its right end the model and
the context in use once claude has answered, like Opus · 385k, with the full model id, the exact token count and the
time of the last answer in the tooltip, and no percentage because the transcript does not say how big the window is. A
marker at the left edge shows the thread whose tab is in front, and no row stays selected after a click. The View
Options menu in the panel's title bar switches Only Threads Waiting for Me (the ones that need you, the ones whose
turn finished, and the pinned ones), Group by State (sections by what the threads are doing), Show Last Message, Show
Time and Show Model and Context (also a checkbox in Settings | Tools | Skein), and the IDE remembers them for all
projects. The tool window's ⋮ menu, next to View Mode and Move to, has Skein Settings…, which opens
Settings | Tools | Skein.

The status comes from `~/.claude/sessions/<pid>.json`, which Claude Code itself keeps for every running session.
Skein ties each file to its tab through the process tree: a `claude` started in a Skein tab is a child of that tab's
shell, which the IDE started. Nothing is injected into `claude` for this.

When a thread stops while you look elsewhere, a balloon names the thread, says what happened (finished its turn,
needs your permission, has a question) and adds the session's name as the History shows it, when it has one that
differs from the thread's name (its rename, Claude Code's title or the last prompt, cut at 120 characters), with the
same orange or blue dot as the list. Claude's answers, tool calls and questions never go into it.
A thread has one balloon at a time, a newer event replaces it. A needs-you balloon stays until you act on it, a
finished-turn one fades like any IDE notification, and either goes away as soon as you open the thread, close its
tab, or the thread stops needing you. With the IDE in the background Skein also sends the event and the session's
name on its own line to the system notification centre, unless you turned system notifications off under
Settings | Appearance & Behavior | Notifications.

When the IDE restarts, Skein reopens the same thread tabs and runs `claude --resume <id>` in each one only when you
first open it. The history under the threads lists the project's past sessions, including those started elsewhere,
but leaves out the runs a program made through the Agent SDK on its own, so they do not bury yours; a run you went on
with in a terminal stays, and a session that moved to another folder shows up where it moved to. A click selects a
session, and a double click on it, Enter or Open in its right-click menu resumes it in a new tab; a session still
running in another terminal asks first. Drag the line above the history to give it more room; Skein remembers the
size and whether you left it open, the same for all projects.

Right-click a thread to pin it, rename it, give it a colour or close it. Close ends claude in the tab right away, and
for a Skein editor tab the IDE does not ask about that, so when claude is still starting, working or waiting for your
answer in one of the threads, or Skein cannot tell, Skein asks first and names them. A tab in the Terminal tool window
may also get the IDE's own question. A thread that starts working after you answered stays open, and a balloon says
so. Close, the editor tab's × and the close of a thread tab in the Terminal tool window first send claude and the
tab's shell the hangup a closing terminal sends, wait up to five seconds for them to finish while the tab still shows
them, and only then close the tab, since the IDE would otherwise leave both hanging with a dead terminal
(IJPL-259859). The editor tab's × asks nothing, in the Terminal tool window the IDE may ask its own question first, and
moving a tab there or out of it ends nothing. A Close All or Close Others with a running
thread in it closes the other files at once and the thread tabs once their claude has ended. With the editor split
into several windows it closes only the thread tabs at first, so run it again for the rest. With several threads the
tab in front closes last. A worktree thread then asks whether to keep its worktree, as after the tab's own close
button.
Right-click a history row to open it, pin it, rename it or mark it done. Pinned
threads and pinned sessions come first, in the threads and in the history. Pins, names, colours and done marks are
Skein's own, kept by the IDE in the project's workspace file; Skein never writes them into the session or anything
under the Claude home, so `claude -r` in a terminal still shows Claude Code's own name. The editor tab carries the
thread's name, follows a rename in Skein and Claude Code's own `/rename`, and so do the panel, the notifications and
the toolbar counter. A name Claude Code made up itself because nobody named the session, such as `acme-c8`, is not
shown, and the thread keeps the History title it was resumed under or goes by its worktree or folder. A session marked
done leaves the history unless you turn on the eye in its header. Switched on, the eye takes the theme's accent colour
and its tooltip reads Showing done sessions, switched off it is crossed out and reads Done sessions hidden. The mark
drops by itself when the session is used again. A colour label puts a 4 px stripe at the row's left edge and tints the
row and its editor tab. Claude Code's own `cleanupPeriodDays` (30 days by default) removes idle transcripts, and
Skein's marks for them go with them; to keep sessions longer, change it in your Claude Code settings.

The History header reads "History (N)" with the number of sessions it lists, and its rows have the same two lines as
the threads. In the new UI's main toolbar a counter shows how many threads need you, only those, and its switch is
under Settings | Tools | Skein. The tool window icon is the Skein spark, with an orange dot while a thread needs you.
Skein can play a sound when a thread needs you or finishes its turn, each on its own switch. On macOS it can keep the
computer awake while a thread works, through an IOKit power assertion held inside the IDE process, so no helper
process runs and nothing is written. What happens when a turn ends while a background process (a build, a dev server)
still runs is your choice next to the switch: no, 15 minutes, 30 minutes (the default), an hour, or as long as it
runs. Outside macOS both controls are shown disabled.

## Worktree threads

Tools | Skein | New Thread in Worktree… (also in the tool window's title bar) asks for a name, with a folder popup
first when the project has several folders, and starts `claude --worktree <name>` in a new tab. The field starts with
the next free `task-1`, `task-2` and so on, one that neither an existing worktree nor another Skein thread of the
repository has taken, and left empty it stands for that name. The name of an existing worktree reopens it. Claude Code
makes the worktree itself, as `<repo>/.claude/worktrees/<name>` on the branch `worktree-<name>`, and which branch it
starts from is Claude Code's `worktree.baseRef` setting, not Skein's. A folder outside a git repository, a repository
with no commit yet or a git that cannot run gets a message instead of a thread, and so does a repository that contains
the Claude home, such as a dotfiles repository in your home folder, since Claude Code would put the worktrees inside
its own home there. The tab and the row carry the worktree name, History lists the worktree sessions as
"worktree <name>", and after a restart the thread comes back and resumes into its worktree.

A worktree thread's row shows how its worktree stands, right before the age. `✎3` in amber counts what `git status`
lists as changed, staged, unstaged and untracked alike, one per path, and `↑2` counts the commits on the worktree's
branch that its base does not have. A clean worktree level with its base shows nothing, a rebase, merge or cherry-pick
stopped half way shows `rebase`, `merge` or `pick` instead of the numbers, a detached HEAD shows `✎3` alone, and a
worktree folder that is gone shows nothing. When git fails or takes longer than 15 seconds the row shows a grey `?`,
never zeros, and the tooltip says why. Otherwise the tooltip lists up to ten of the changed paths and how many more
there are, then the commits not in the base, or says the worktree is clean and level with it.

The base is the branch the repository had checked out when Skein started the thread, as `git branch --show-current`
names it, and it stays with the thread across restarts. A thread resumed from History, a worktree that already existed
and a repository on a detached HEAD have none, and neither has a thread whose branch was deleted since. Then the
remote default branch counts, the one `origin/HEAD` points to, else `origin/main`, `origin/master`, `main` or
`master`, whichever exists first, and with none of them the row shows the changed paths alone. A branch merged by
squash keeps counting as ahead, since its own commits never reach the base.

Skein looks again when a worktree thread finishes a turn, starts or closes, when the Skein window or the Worktrees
section comes into view and with the minute check, leaving 15 seconds between the end of one look and the start of the
next, and right away after its own Open or Remove. It never looks while the Skein window is hidden, and never in a
project you have not trusted, since `git status` can start programs a repository's configuration names, so there the
rows show no counts and the Worktrees section stays hidden, while the close question's Remove still works. git runs
off the UI thread, one call at a time, each stopped after 15 seconds, with `--no-optional-locks`, which is what
`GIT_OPTIONAL_LOCKS=0` does, so a look never takes the index lock your own git may need. Every git call of Skein's
that reads a working tree, `status`, `ls-files`, `ls-tree` and `worktree remove`, the minute check's included, runs
with `core.fsmonitor` turned off. There is no file watcher and no VCS listener, and a row is drawn again only when
what it shows changed.

Worktrees (N) under the threads lists the folders in `.claude/worktrees` of the project's repositories that no thread
in this window works in, kept worktrees for the most part, and it shows only while there is one. It opens and closes
like History, with its own line to drag while it is open, and Skein remembers its size and whether you left it open.
Each row has the worktree's name, its branch, "detached HEAD" or "not a git worktree" for a folder `git worktree list`
does not name, and the same counts as a thread row, which Skein reads only while the section is open. A folder git
does not list but that has a `.git` of its own says "worktree git links elsewhere", as after the repository moved or
was copied, with `git worktree repair <path>` in its tooltip to link it again, or "a repository of its own", and Skein
never deletes either. A double click, Enter or Open resumes the newest History session that ran in a worktree, with
History's question when that session runs elsewhere, and without one starts `claude --worktree <name>` there with the
checks of a new worktree thread. The menu also reveals the folder in the file manager, named as the IDE names that
action on each system, and has Remove….


Remove… goes the way of the close question's Remove, with the same reasons to keep the worktree, and a thread in any
open window counts. It asks first, with Cancel as the default, and the question says that the worktree's ignored files
go too. When git refuses to remove the worktree because of changed or untracked files, Skein shows git's own words and
asks a second time, saying that what is not committed there is lost for good. After a yes to that every check runs
once more and git is asked once more without force, and only the same refusal makes Skein run
`git worktree remove --force`. Any other refusal is git's last word and Skein only shows it, a worktree someone else
locked, one with a submodule, whose commits git's force would delete with the worktree, or a git that answers in
another language. The branch then goes with `git branch -d` and never with `-D`, so a branch with unmerged commits
stays and Skein says so. A folder git does not list as a worktree is deleted as a plain folder after a question of its
own, unless it has a `.git` anywhere inside, a session or a thread works in it, it is a link, git lists a worktree
inside it, git tracks files in it or an editor holds an unsaved change to one of its files. Closing a thread's tab
never forces anything.

A worktree is a repository of its own inside your working tree, so `git status` would list it and `git add -A` would
add it as an embedded repository. When you start a worktree thread, once you have picked its name, Skein therefore
writes `.claude/worktrees/.gitignore` containing `*`. That file ignores everything in the folder, itself included, and
the deepest `.gitignore` wins over every other rule, so while it is there git does not see the folder, whatever the
branch. As a second layer Skein adds the line `/.claude/worktrees/` to `.git/info/exclude`, a local file git never
commits. When the thread starts, a notification names what Skein wrote. A `.gitignore` already in that folder is never
changed, and it counts only when its one rule is `*`, so an empty one, one with comments only and a link are refused
too. The thread does not start, and Skein says why, when that `.gitignore` does not count, when git already tracks
something in the folder, when git tracks `.claude/worktrees/.gitignore` but the file is missing here (deleted, or left
out by a sparse checkout, so restore that file from git, in a sparse checkout by adding `.claude/worktrees` to it, and
Skein uses it as it is), when a stash holds the `.gitignore`, or when `.claude` or `.claude/worktrees` is a link. After
such a refusal the line in `.git/info/exclude` stays and folders Skein just made are gone again, and a `.gitignore`
Skein wrote stays when worktrees already live in the folder, since it changes nothing git tracks, and the message says
so. A thread resumed from History or restored after a restart gets the same check, since its worktree may have been
made outside Skein, but there Skein warns instead of refusing.

The `.gitignore` is an ignored file, so git treats it as expendable. `git clean -X` and a switch to a branch that
tracks or drops `.claude/worktrees/.gitignore` can remove or replace it, and `git stash -a` takes it into the stash.
While a stash of the repository holds it, one made in another worktree of the repository included, Skein does not
write it again and names that stash in its message. Popping that stash brings the guard back, while dropping it would
also lose any other files it holds, and once no stash holds it Skein writes it again if it is still missing. While a
worktree thread is open, Skein checks every minute and puts a removed one back with a notification. The minute check
repeats a warning only when its reason changes, while a resume or a restore warns each time. In the meantime git sees
the worktrees only where the line in `.git/info/exclude` does not hold them, as in a repository whose own rules
re-include them. The `.gitignore` and the line stay after Skein is uninstalled.

Skein does not add worktrees to the IDE's Git roots and never touches `.idea/vcs.xml`, so the Commit window does not
show a worktree's changes. Review them with git in the worktree, in the thread's terminal, before you merge the
branch. The IDE keeps worktree folders out of the index, by its own rule for the project root and by an exclude rule
of Skein's for other content roots, so their files open without code insight.

When you close the tab of a worktree thread, Skein asks whether to keep the worktree or remove it, and Keep is the
default. The question counts the changed files and the commits not pushed (with no remote, every commit on the branch
counts) and names the branch Remove would delete. Remove runs `git worktree remove` and then `git branch -d`, never
with force, so git refuses a worktree with changes and a branch that is not merged. If git refuses to remove the
worktree, nothing else changes. If it refuses to delete the branch (an unmerged one, say), the worktree is already
gone and the branch stays, and Skein says so. When git names no branch for the worktree, a detached one say, Remove
takes the worktree alone and Skein says that no branch was deleted. git would remove a detached worktree even when its
commits are held by no branch, tag or remote, and they would be lost, so Skein counts those commits itself. Keep is
the only answer, and Skein says why, while a session still runs inside the worktree, another Skein thread uses it, an
editor holds an unsaved change to one of its files (git cannot see such a change, so a remove would lose it), another
worktree lies inside it (git status cannot see that one, and the remove would delete it too), a repository of its own
lies inside it, ignored or not, which the remove would delete with its commits, or git cannot say so in time, its
detached HEAD has commits that no branch, tag or remote holds, git could not read the worktree, or the repository
contains the Claude home. After you answer Remove, Skein checks all of this again, the unsaved changes last, right
before git runs. Claude Code locks the worktree it makes and can leave that lock behind when its claude ends, so while
the process that lock names still runs Skein keeps the worktree and says so, and once it has ended the question says
Skein lifts the lock, which it does with `git worktree unlock` right before the remove, here and in the Worktrees
section, once git lists the same lock again. A lock anyone else made stays, and one Skein lifted stays lifted if git
then refuses the remove or you say no to its force in the Worktrees section, since the claude that held it had ended.
Closing the project or the IDE asks nothing. Git still deletes ignored files in a worktree it removes, such as build
output or `.env`, which the question says, and the changed-files count does not include them. A bare clone in an
ignored folder, a `name.git` folder with no `.git` entry, does not count as a repository of its own, so it goes with
the worktree, as it would with git itself.

## Checked

- 927 unit tests, among them a lifecycle test that drives a thread from start to restore against a copy of a Claude
  home and proves, byte for byte, that nothing under it changed (and fails as soon as anything does).
- Unit tests for which glyph and tooltip words a waiting thread gets for each reason (and that the three glyphs differ
  and stay orange in both themes), the thread order with pins, the History arrangement (pinned first, done hidden, a
  done mark dropping on newer activity), the sections and the waiting-only filter, the toolbar counter, the keep-awake
  policy and its hold
  and release logic, the colour palette, the session name a notification shows, the line a thread row takes from the
  transcript, the one-balloon-per-thread bookkeeping, the click rules of the thread list and the History, the History
  eye's state to look and tooltip mapping, and for Close which threads count as busy, the question's words, the
  coordinator handing back the right tabs with the claude and the shell to hang up in each, never a claude in another
  terminal, and leaving open a thread that turned busy after the question, the order of a close (claude's hangup, the
  shell's once claude is gone, the wait, each thread on its own, one close for the batch, and a close after the
  timeout), no signal to a tab whose session does not run or to a pid now held by another process, a terminal opened
  before a thread's shell started or after it, restored threads included, never hung up while a shell with an unknown
  or drifted start time still gives the thread its status, what the close check answers for a × or a Close All (a Skein
  tab or not, its session running or not, handed back, a second ×, an ending that never closed, two views of the tab,
  the project or the IDE closing), the IDE's selections during Skein's own close not counted as the user's, which other
  files of a held Close All Skein closes itself (with one editor window only, just the files the IDE asked about and
  still open), the balloon's words, and the order that puts the tab in front last, tested as a list function. For the
  Terminal tool window, where a thread's tab is after a move (a closed editor file or a content that left the tool
  window is not a close, only a disposed tab is), what the close of a thread tab there answers (a move always passes),
  and that a thread selected in the visible tool window counts as seen. A test sends a real SIGHUP through libc to a
  child process and sees it end. Unit tests for the worktree counts as well, the status and worktree list parsing, the
  base order, the badge, tooltip and words of a row, the folders the Worktrees section lists, the look's throttle and
  single flight, the base kept with a thread, and Remove… with its second question before git's force and `branch -d`
  only, and a lock Claude Code left behind lifted only once its claude has ended, against real git in a temporary
  repository for the counts, that lock and a rebase, a merge and a cherry-pick stopped half way, and on a real disk for
  the folder delete, which removes a link inside as a link.
- End-to-end tests against a fake claude, `tools/fake-claude`, a python3 script that writes the status files and
  transcript lines Skein reads, step by step as a scenario says. Its shapes come from Skein's own parsers and fixtures,
  never from Claude Code's program, and it writes only into a Claude home marked as its own, in a temporary folder. A
  real shell the test starts runs it when Skein types `claude`, so the chain claude → shell → test JVM is a real one,
  and the real watcher, mapper and coordinator follow a thread from working to a permission prompt to a finished turn,
  with its preview, model and context, and on to its exit, resume it from History in the same tab, find a worktree
  thread's claude in its git worktree, and close a thread with real SIGHUPs, the fake's first and then its shell's.
  Contract tests check every status and transcript line the fake writes against Skein's readers, that it refuses the
  real Claude home and an unmarked one, and that a fake left waiting ends once its test is gone. These tests are skipped
  where python3 3.8 or newer or `/bin/sh` is missing, the git worktree tests also without git, and the close test
  outside macOS and Linux.
- The IntelliJ Plugin Verifier against IntelliJ IDEA 2026.3 (263.7307.200): compatible, no internal API usage (the
  build fails on any), no deprecated API usage.
- In a sandbox IDE: the terminal tab opens in the editor area, the typed command reaches the shell (0.4 to 0.9 s),
  a closed tab is reported once, and restoring several tabs starts none of them on its own. With Open threads in the
  editor area off, "+" opens the thread in the Terminal tool window, a click on its row brings that tab up, its own
  close on a working thread ends claude first and leaves no session running, a thread in a second pane of a split
  tool window closes the same way, a thread selected in the visible tool window gets no balloon, and a restart brings
  the threads back into the tool window. With it on, an editor thread tab dragged into the tool window stays in the
  list and closes the same way there, Move to Editor or a drag back leaves one row with its colour on the editor tab,
  and the editor ×, Close All and Close work as before.
- On macOS with IntelliJ IDEA 2026.3: every `claude` started in an IDE terminal tab has the chain
  `claude → zsh → idea`, so the tab's shell is the process Skein ties the thread to; a `claude` in iTerm2 never
  reaches the IDE and is left alone.
- Not yet checked by hand: the full matrix on a real project (permission prompt, question, turn finished,
  notifications in front and in the background, `/clear`, close, exit and relaunch, restart, a session running in
  another terminal, `claude` missing from PATH) on macOS and Linux. The newer features still wait for their checks
  too: that the keep-awake assertion shows up in `pmset -g assertions` and the JNA module loads, that pins, names,
  colours and done marks survive a restart, the editor-tab tint, the toolbar counter and the sound. The refreshed tool
  window waits for its checks as well, the tab titles after the move into the editor and after a restart, the badge
  while the Skein window has not been opened yet, the split "+" in the title bar and the spinner in the new row.
  The lock and speech-bubble glyphs of a waiting thread, in a light and a dark theme at 100 % and 200 % scale (the
  headless paint is checked, not how they look in the row).
  For worktree threads: the `cwd` in the status file of a first worktree session, a restored worktree thread
  resuming into its worktree, the notice about the worktrees `.gitignore` and the exclude line on a first worktree
  thread, the close question and its Keep-only answer for an unsaved editor change, and the exclude rule in a window
  with several repositories. The History's double click and its menu's Open, the thread menu's Close (its question
  for a busy thread, a cancel that keeps the thread, the worktree question, a batch that holds the tab in front, a
  thread that turns busy while the question is open, a tab left in the Terminal tool window), the eye's two looks in
  a light and a dark theme and Skein Settings… in the ⋮ menu wait too. The worktree counts and the Worktrees section
  wait for theirs too, a dirty worktree thread, a rebase stopped half way, a kept worktree opened from the section,
  Remove… of a dirty one through its second question, a folder git does not list, and the amber in a light and a dark
  theme.
  The ending before a close waits for its checks in a sandbox IDE as well: the × and the menu's Close on a working
  thread (claude and its shell gone, the History no longer showing the session as running, the worktree question
  after), a Close All with a thread and other files, a session that outlives the five seconds, a drag of a thread tab
  to another split or window, which must not end claude, and closing the project with threads running.
  A plain IDE terminal opened in a thread's folder, with a claude of its own, must keep running when the thread
  closes.
  The fake claude waits for its run in a sandbox IDE, started as `tools/fake-claude/README.md` says, with `type claude`
  in a sandbox tab naming the fake, a thread going through the default scenario as you press Enter in its tab and turn
  to another tab while it works (working, the permission prompt with its balloon, the finished turn in blue with the
  model and the context on its row), the History listing the session, a restart resuming a thread that got its prompt,
  and a worktree thread in a git repository.

## Releasing

Releases come from a pull request that release-please keeps open on `main`. After each merge it raises the version in
`version.txt` from the commit types since the last release (`fix:` a patch, `feat:` a minor version, a breaking change
a major one) and adds the new version's section to `CHANGELOG.md`, which the build also turns into the change notes
the IDE shows for the plugin. The release pull request and its merge start no build of their own, since they touch
nothing but those files and the release-please manifest. Merging it tags `v<version>` and creates the GitHub release
as a draft, and a single build of that tag runs the tests and the verifier, attaches the zip and only then publishes
the release, so nobody sees a release without its zip. When that build fails the release stays a draft, and running
the Build workflow by hand with the tag builds it again, attaches the new zip to a draft or a published release alike
(or creates the release when the tag has none) and publishes a draft. That only helps when the failure was a flaky
one. When the tagged commit itself is broken, delete the stuck draft but keep its tag, fix the problem on `main` and
let the next release carry the fix - deleting the tag too would leave release-please without its last release and make
it go through the whole history again. The tag has to match the `version.txt` it points at. Once a week, and on
demand, the Newest IDE workflow runs the verifier against the newest IntelliJ IDEA releases, RCs and EAPs instead of
the pinned 2026.3, so an IDE that breaks Skein turns that run red, and nothing else waits for it. Publishing to the
JetBrains Marketplace runs while the repository variable `PUBLISH_TO_MARKETPLACE` is `true`. The upload
runs in the `marketplace` environment, the only place that holds the signing and token secrets, and
waits for the maintainer to approve it. When only the last step, publishing the GitHub release, fails after an upload,
re-run that run's failed jobs: a manual rebuild would upload the same version to the Marketplace again.
