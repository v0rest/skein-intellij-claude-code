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
and Skein follows it there, so the thread stays in the list until its tab really closes. The branch button next to "+"
starts New Thread in Worktree… (see below). New Thread Here, in the right-click menu of a folder or a file in the
Project view and of an editor tab, starts a thread the same way in that folder, or in the file's folder. It shows only
for a folder of the project on the local disk, the project folder, one of its content roots or anything inside them,
so not on a library jar, a scratch file, a folder outside the project or a folder in a worktree, which the Worktrees
section opens unless the project itself is that worktree, and with several items selected only while they all lead
to the same folder.
A thread is orange when it waits for you (a permission prompt, a
question, any other dialog), blue when it finished its turn and you have not looked yet, and shows a spinner while it
works, each with its own glyph. The orange one is a ring with a lock in it for a permission prompt, a ring with a
speech bubble for a question and a ring with a dot for any other dialog, all three orange because all three need you,
and the tooltip says "waiting for your permission" or "waiting for your answer". Grouping, the waiting-only filter, the
counter, Jump to Waiting Thread and the tool window badge treat the three alike. In a folder Claude Code has not been
told to trust yet, claude first asks whether to trust it and writes no status file while it asks, so about half a
minute after the launch such a thread shows as waiting too, with "claude asks whether to trust this folder", where
Skein used to say it could not read the session's status. Skein learns that answer from Claude Code's `.claude.json`,
in the home folder or in `CLAUDE_CONFIG_DIR`. It keeps only each folder's trust from that file, never shows or logs
anything else from it and never writes it. Clicking a row opens its tab, in the
Terminal tool window by bringing the tool window up on it, and Tools | Skein has Jump to Waiting Thread for the
keyboard. A thread whose tab you have in front, the selected editor or the selected tab of the visible Terminal tool
window, counts as seen and gets no balloon.


A row has two lines. The first holds the state glyph, the name, a pin when the thread is pinned, the counts of a
worktree thread (see Worktree threads) and the age on the right, and the name is bold while the thread is unread or
needs you. The second line starts with the worktree mark when there is one, or for a thread started in a folder inside
the project folder with that folder's path from the project folder unless the name already says just that,
then a preview of what happened last, the
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
tab, or the thread stops needing you. With another project's window in front, or its tab when the IDE keeps projects
as tabs of one window, or with that project the last one you were in before you left the IDE, the balloon shows there,
its text starting with "in" and the thread's project, and Open goes to the thread's project first. Closing the thread's
project takes its balloons down wherever they show. With the IDE in the background Skein also sends the event and the
session's name on its own line to the system notification centre, unless you turned system notifications off under
Settings | Appearance & Behavior | Notifications.

When the IDE restarts, or you open the project again, Skein reopens the same thread tabs, and a thread goes on with
`claude --resume <id>` when you open its tab or when its tab stays in front a moment. So the thread tab in front after a
restart goes on by itself and the others wait for you, unless the IDE put a file of yours in front - then none goes on
until you open one. A tab whose shell you typed into first waits for you to open it too, so it does not go on by itself
into a line of yours. In a project you have not trusted Skein brings back no thread, as the IDE does with its own
terminal tabs there, and the list says how many wait - once you trust the project they come back as after a restart.
With Open threads in the editor area off, the one that goes on is the thread selected in the Terminal tool window while
that window is open, one for each pane of a split. The
history under the threads lists the project's past sessions, including those started elsewhere and those started in a
folder inside the project, which show that folder after their title, but leaves out the runs a program made through the
Agent SDK on its own, so they do not bury yours; a run you went on with in a terminal stays, and a session that moved to
another folder shows up where it moved to.
An open history reads the transcripts again when a thread comes or goes, and also when you come back to
the IDE from another app or move into the Skein panel from anywhere else in the IDE, at most every few seconds, so a
session you went on with in a plain terminal shows its new state then. A click selects a session,
and a double click on it, Enter or Open in its right-click menu resumes it in a new tab; a session still running in
another terminal asks first. Drag the line above the history to give it more room; Skein remembers the
size and whether you left it open, the same for all projects.

Right-click a thread to pin it, rename it, give it a colour or close it. Close ends claude in the tab right away, and
for a Skein editor tab the IDE does not ask about that, so when claude is still starting, working or waiting for your
answer in one of the threads, or Skein cannot tell, Skein asks first and names them. A tab in the Terminal tool window
may also get the IDE's own question. A thread that starts working after you answered stays open, and a balloon says
so. Close, the editor tab's × and the close of a thread tab in the Terminal tool window first send claude and the
tab's shell the hangup a closing terminal sends, wait up to five seconds for them to finish while the tab still shows
them, and only then close the tab, since the IDE would otherwise leave both hanging with a dead terminal
(IJPL-259859). Closing the project does the same for all its threads before the project goes, waiting about two
seconds at most for all of them together. A middle click or a Shift+click on the tab, and a Shift+Alt+click that
closes the other tabs of its window, skip the IDE's close check, so Skein ends claude right as each tab closes, waiting
about two seconds at most for it, one tab after another when several close in one go, and the thread goes as after
Close. The
editor tab's × asks nothing, in the Terminal tool window the IDE may
ask its own question first, and moving a tab there or out of it ends nothing. A Close All or Close Others with a running
thread in it closes the other files at once and the thread tabs once their claude has ended. With the editor split
into several windows it closes only the thread tabs at first, so run it again for the rest. With several threads the
tab in front closes last. A worktree thread then asks whether to keep its worktree, as after the tab's own close
button. A worktree thread's menu also has Show Changes, see Worktree threads.

A checkout through the IDE can also bring back the editors you had open on that branch, when the IDE's Restore
workspace on branch switching is on, as it is by default. That restore closes every editor tab without asking anyone,
thread tabs included, so right before a checkout Skein marks its thread tabs the way the IDE marks a tab it moves to
another window, and their terminals live through it - claude goes on undisturbed, even in the middle of an answer, and
its tab comes back right after the branch's editors, the one that was in front selected again. A restored thread nobody
had opened yet gets no mark, since opening its tab again would start it, and comes back not started, unless its tab ends
up in front, where it goes on like any restored tab. When the
IDE closes thread tabs that way without a mark - Rollback in the balloon about the restored workspace, the Tasks
plugin's Load Context, another plugin closing all editors - Skein ends the claude of all of them together first, as it
does when the project closes, and opens each thread again right after as a Resume of its session, so the row stays and
the conversation goes on, though an answer claude was in the middle of is lost. A session that never had a turn has no
conversation to resume, so that thread starts a fresh claude, and one whose claude had exited or never started comes
back the way a restart brings it back. On Windows the IDE ends claude itself there, so Skein only brings the thread back.
A mark lasts until the checkout is over, so a slow one keeps its threads too, and at most ten minutes when the checkout
never says it is. Your own close of a thread tab works as before and never brings it back - one you close during a
checkout with a middle click, which skips the IDE's close check, ends claude the way Close does a few seconds later,
once Skein has seen it was no drag to another window - and threads in the Terminal tool window are not touched by any
of this. The checkout part works through the IDE's Version Control plugin and loads only while that plugin is on.

Right-click a history row to open it, pin it, rename it or mark it done. Pinned
threads and pinned sessions come first, in the threads and in the history. Pins, names, colours and done marks are
Skein's own, kept by the IDE in the project's workspace file, and Skein never writes them into the session or anything
under the Claude home. A name can reach Claude Code too - with Also rename in Claude Code ticked in the Rename dialog,
which is on at first and then stays as you left it, Skein types `/rename <name>` into the session's claude the way you
would, so `claude -r` shows the name as well, and Claude Code notes the rename in the conversation as it does for
yours. Skein types it only while claude is idle in that tab and nothing was typed there since your last message went,
which it knows once claude got to work right after your Enter, and a few seconds after the last key, since otherwise the
line would answer a permission question, land in a menu such as `/config`, queue behind a turn or go to the model
together with your draft. A paste and an answer picked with the arrows count as typing, and so does a message whose
turn Skein sees start only more than five seconds after your Enter, or does not see at all because claude answered
within a second, so after those the name waits for your next message. A claude Skein has just started may ask
something before you send anything, so a fresh or resumed thread gets the name after the first message you send there.
Two things Skein cannot see, a file dropped on the
tab and text another tool puts into claude's input, such as an @-mention Claude Code's own IDE plugin inserts from the
editor - in those cases send one message first or untick the box. With Claude Code in vim mode (`editorMode` in its
settings or in the project's), where typed text runs as commands, the box is greyed out, ticked while a rename waits,
nothing is typed, and a rename already waiting goes once vim mode is off again, unless you change or clear the name
meanwhile. A History session gets the name once you open it and send it a message, after an IDE restart too. Because the
tab is a shell underneath claude, such a name holds letters, digits, spaces and `- _ . , : + # /` only, up to 100
characters, and the dialog says when a name cannot go. Once the transcript shows Claude Code took the name, Skein drops
its own, so a later `/rename` in claude is followed again. An empty name clears only the Skein name. The editor tab
carries the thread's name, follows a rename in Skein and Claude Code's own `/rename`, and so do the panel, the
notifications and
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
Skein can play a sound when a thread needs you or finishes its turn, each on its own switch and with its own sound
picked under it - the system beep, which stays the default, one of the alert sounds your system already has, or a
file of your own from Choose File…, a `.wav` or `.aiff` anywhere, a `.caf` on macOS and an `.au` elsewhere, and ▶
plays it once. The system's sounds come from `/System/Library/Sounds` and `~/Library/Sounds` on macOS,
`C:\Windows\Media` on Windows and the `.wav` files under `/usr/share/sounds` on Linux, Skein ships none of its own and
plays them at the system's volume. On macOS it plays through the system's own alert sound service, so nothing there
ever asks for the microphone, and that service plays short alert sounds, up to 30 seconds. The list marks a choice
that would only beep, a file or a system sound that is gone, a file over 5 MB or one with another extension, and a
file the system cannot decode beeps when it plays. Threads that finish together sound once. On macOS Skein can keep
the computer awake while a thread works, through an IOKit power assertion held inside the IDE process, so no helper
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

When the new worktree starts from the repository's local branch, the name dialog warns under the name field if that
branch is behind its remote-tracking branch or has diverged from it, with the counts and "as of your last fetch", since
Skein never fetches and only reads what your last fetch left. That is the case when Claude Code's `worktree.baseRef`
is `head`, and also when the repository has no `origin` at all, since Claude Code's default `fresh` then falls back to
the local HEAD. OK goes on as before and Cancel stops, the warning never blocks. With the default `fresh` and an
`origin`, Claude Code starts from the remote's default branch and fetches it itself when it is old, so Skein says
nothing, and it says nothing either when `origin` has no cached default branch, since what happens then depends on
Claude Code's own fetch. A level or ahead-only branch, a branch with no upstream or one that tracks a local branch, a
detached HEAD and a git error give no warning, and the dialog looks as it always did. In a shallow repository the
counts cannot be trusted, so the warning says the branch differs from the remote and leaves the numbers out. Typing the
name of an existing worktree, which reopens it, hides the warning too. When Claude Code has not been told to trust the
repository yet, the dialog also warns that `claude --worktree` refuses such a folder and that a plain thread there has
to answer its question first. OK still works, since a newer Claude Code may decide otherwise, and a worktree thread
that ends that way gets the same words as a balloon.

Skein reads `worktree.baseRef` from the project's `.claude/settings.local.json` and `.claude/settings.json` and the
user's `settings.json`, in that order and as strict JSON, so a file Claude Code would reject (a comment, a trailing
comma) is skipped and the next one decides. For a repository that is a linked worktree it reads the project files from
the main checkout's root, as Claude Code does. It never writes them. It does not see managed settings or `--settings`,
so a `fresh` set there over a `head` in a file can give a warning that is not needed, which is rare.

A worktree thread's row shows how its worktree stands, right before the age. `✎3` in amber counts what `git status`
lists as changed, staged, unstaged and untracked alike, one per path, and `↑2` counts the commits on the worktree's
branch that its base does not have. A clean worktree level with its base shows nothing, a rebase, merge or cherry-pick
stopped half way shows `rebase`, `merge` or `pick` instead of the numbers, a detached HEAD shows `✎3` alone, and a
worktree folder that is gone shows nothing. When git fails or takes longer than 15 seconds the row shows a grey `?`,
never zeros, and the tooltip says why. Otherwise the tooltip lists up to ten of the changed paths, numbered, and how
many more there are, then the commits not in the base, or says the worktree is clean and level with it. A line longer
than 60 characters wraps, a path after a `/`, and the tooltip then keeps a fixed width instead of growing as wide as
its longest path.

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
that reads a working tree, `status`, `ls-files`, `ls-tree`, Show Changes' `diff` and `worktree remove`, the minute
check's included, runs with `core.fsmonitor` turned off. There is no file watcher and no VCS listener, and a row is
drawn again only when what it shows changed.

Worktrees (N) under the threads lists the folders in `.claude/worktrees` of the project's repositories that no thread
in this window works in, kept worktrees for the most part, and it shows only while there is one. It opens and closes
like History, with its own line to drag while it is open, and Skein remembers its size and whether you left it open.
Each row has the worktree's name, its branch, "detached HEAD" or "not a git worktree" for a folder `git worktree list`
does not name, and the same counts as a thread row, which Skein reads only while the section is open. Its tooltip
names the folder from the project's own folder, like `.claude/worktrees/task-1`, and gives the whole path only for a
repository outside the project. A folder git does not list but that has a `.git` of its own says "worktree git links
elsewhere", as after the repository moved or was copied, with `git worktree repair <path>` in its tooltip to link it
again, or "a repository of its own", and Skein never deletes either. A double click, Enter or Open resumes the newest
History session that ran in a worktree, with History's question when that session runs elsewhere, and without one
starts `claude --worktree <name>` there with the checks of a new worktree thread. The menu also has Show Changes (see
below), reveals the folder in the file manager, named as the IDE names that action on each system, and has Remove….


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
show a worktree's changes. Show Changes in the right-click menu of a worktree thread, and of a row in the Worktrees
section, shows them in the IDE's own diff window instead, one file after the other, and the window's title, which its
editor tab shows too, names the worktree and where in the list the file is, like `task-1 · 2/6 · src/a.c`. Each
worktree has one such tab. Show Changes again on the same worktree reads the list afresh, opens it on the file the old
tab showed while that file is still in the list, else on the first, and closes the old tab, while Show Changes on
another worktree opens a tab of its own. A diff in a separate editor window counts the same, and only one the IDE
shows in a frame of its own, from a modal dialog, with the registry key `show.diff.as.frame` or in an external diff
tool, is not replaced. Before git reads the list, Skein saves what the editor holds unsaved for files in that
worktree, so an edit just made on the worktree side is in it. A worktree with more than 1,000 changed files gets the
first 1,000 and, as the last entry, a note saying how many more there are. It compares the worktree as it is on disk
with the commit its branch shares with its base, the merge-base, so what the base got after the branch started does
not count, and takes in the commits on the worktree's branch and what is staged, unstaged or untracked alike, one
entry per file, with added, deleted and renamed files named as such and the files an ignore rule hides left out. The
base is the one the row counts against, and for a kept worktree, which has no thread, the repository's own. The base
side is read-only, the worktree side is the file itself, so an edit there goes into the file when the IDE saves it.
Each file is read only when the window gets to it, and one bigger than 10 MB on either side, a submodule or a
repository inside the worktree gets a note instead of a diff. A deleted file always shows against an empty side,
whatever lies at its path now, and the worktree side is read only inside the worktree, never through a link on the
way, so a path behind a link gets a note too. A folder that is gone, one git does not list as a worktree, a rebase,
merge or cherry-pick stopped half way, no base branch, a branch with no commit in common with its base, a worktree
with nothing to show and a git that fails or takes longer than 30 seconds each get a balloon instead of an empty
window. git runs off the UI thread with the same care as for the counts, `--no-optional-locks`, with `core.fsmonitor`
off and no external diff program, and only in a project you trust. The IDE keeps worktree folders out of the index, by
its own rule for the project root and by an exclude rule of Skein's for other content roots, so their files open
without code insight.

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

## Windows

Skein runs on native Windows the way it runs on macOS and Linux, and it reads what Claude Code writes there in its own
shapes. Paths arrive in several forms, `C:\...` from the status files and java.nio and `C:/...` from git and the IDE,
so Skein compares them through one set of rules that takes either separator, any case and the drive letter, and that
answers "not sure" for a path only the disk could place, a `..` or a name ending in a dot or a space. Where a yes lets
Skein act, as in deleting a leftover worktree folder, not sure counts as no, and where a yes holds an action back, as
in a session still working in a worktree, it counts as yes. The one recursive delete refuses a symbolic link or an
NTFS junction on its way, and a session also holds its worktree when its folder is the same one through a link, a
`subst` drive or a short 8.3 name.

A status file's process start is a Windows FILETIME there, the CLI is `claude.exe` and not Claude Desktop, whose
processes have the same name, and Claude Code's worktree lock carries no start date, so a lock counts as left behind
only once its process is gone or clearly runs something else. The IDE ends claude and its shell itself when a tab
closes on Windows, so Skein closes the tab at once instead of hanging claude up first, and a permission prompt on the
PowerShell tool shows its command like one on Bash. Claude Code's installer does not put its folder on PATH, and the
warning about missing status files says so when claude is only there. Git for Windows checks files out with CRLF by
default, which the worktrees `.gitignore` takes as it comes. Keep the Mac awake stays a macOS setting, and the fake
claude for the end-to-end tests is a POSIX script, so those tests skip on Windows.

## Checked

- 1681 unit tests (six of them need Windows and skip on macOS), among them a lifecycle test that drives a thread from
  start to restore against a copy of a Claude home and proves, byte for byte, that nothing under it changed (and fails
  as soon as anything does).
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
  the project or the IDE closing), the IDE's selections during Skein's own close not counted as the user's, a thread
  waiting to be opened started as a click starts it once its tab stays in view a moment (never out of view, before its
  shell started or after it exited, after the user typed into it, running elsewhere, exited, while Skein closes it or
  while the project closes, and a moment again once its session stops running elsewhere), which keys count as typing
  into a tab, a launch that waited for another window's asked again whether its session runs elsewhere by then, so two
  windows that restored one session start one claude (a resume you asked for anyway and one after a lost tab whose old
  claude is gone still go), a tab on its way into the editor not in view yet, which other
  files of a held Close All Skein closes itself (with one editor window only, just the files the IDE asked about and
  still open), the balloon's words, and the order that puts the tab in front last, tested as a list function. For the
  Terminal tool window, where a thread's tab is after a move (a closed editor file or a content that left the tool
  window is not a close, only a disposed tab is), what the close of a thread tab there answers (a move always passes),
  and that a thread selected in the visible tool window counts as seen. A test sends a real SIGHUP through libc to a
  child process and sees it end. For the IDE's restore of a branch's workspace, which thread tabs get the reopen mark
  before a checkout (not one Skein closes or ends, one the IDE marked for a move, nor a restored one nobody opened),
  what a close of a marked, an unmarked, a moved, a user-closed and a project-closing tab leads to, inside a clear of
  the editor area or on its own, an unmarked running tab closed on its own past the check (claude ended first and the
  thread closed as after Close, a plain close on Windows or with nothing running), a marked tab closed on its own that
  turns up elsewhere within the wait (a drag, which
  cancels it) or nowhere (a real close, ended and never reopened even when the checkout ends during the wait, or a tab
  the restore closed, its change come before the close or in the same turn, brought back as a Resume), a terminal that
  ended although marked, which thread tabs a clear ends together, the
  marks that come off once the checkout is over and, when it never is, after ten minutes, the tab that comes back selected
  and a never-started one that does not, and the thread coming back in a new tab - resumed when its claude ran there,
  as decided before the hangup, and only with a conversation on disk, a fresh claude otherwise, unstarted like after a
  restart when it never ran, after a claude of its session that has not ended yet, with its pin, colour and worktree
  and no worktree question. Tripwires keep the IDE's reopen mark in one file and Version Control in the optional part. Unit tests for the worktree counts as well, the status and worktree list parsing, the
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
  where python3 3.8 or newer or `/bin/sh` is missing, the git worktree tests also without git, and all of them on
  Windows, where the fake does not run.
- Unit tests for New Thread Here as well, the folder a selection leads to (a file its folder, a folder itself, several
  items only when they lead to one folder, none outside the project, off the local disk or in a worktree), the folder
  a subfolder thread's row names, a subfolder thread tied to its own claude and restored to its folder, and the
  History listing sessions deep inside the project and in a subfolder of another root, scripted ones left out, without
  reading the transcripts of a project whose folder name does not start with the project's own.
- Unit tests for the base warning before a worktree thread (the `worktree.baseRef` precedence over the three settings
  files read as strict JSON, when behind or diverged warns and the words, no warning for a level or ahead-only branch,
  `fresh` with an origin, no upstream, a local upstream, a detached HEAD, a gone upstream and no remote, a shallow
  clone's wording and a linked worktree's main checkout), the git parts against real git in a temporary clone of a
  local remote, and a tripwire that no source hands git a command that talks to a remote.
- Unit tests for Show Changes, the reading of git's raw diff and untracked list (renames, copies, deletions, a type
  change, a file taken out of the index but still on disk, paths with spaces, line breaks or a leading colon, an entry
  cut short), its git commands and every refusal, how each file is shown (a note for a submodule, a folder, a path
  behind a link, a file over the limit on either side or one gone since the list, and a deletion against an empty side
  whatever lies at its path now), its words, the title with the worktree and the position, which the window's loading
  and error pages carry too, which unsaved edits are saved before git reads the list, the first 1,000 files and a
  closing note past them, the file a second Show Changes opens on, the one tab per worktree it remembers until the tab
  closes, and the thread menu's rule, on a real disk with real links that the worktree side is never read through a
  link on the way, and against real git in a temporary repository the list (committed, staged, unstaged and untracked
  together, the base moving on after the branch started, a rename, a detached HEAD, a repository inside, names with a
  CRLF, a backspace or a tab, a folder replaced by a link to the outside, a rebase stopped half way, no commit in
  common, a folder git does not list) and the base side's bytes exactly as git stored them, a CRLF, a lone CR, a NUL,
  bytes that are not UTF-8 and a backspace included. Unit tests for the worktree tooltip too, its numbered paths, a
  long line wrapped after a `/` or at a space under its number, the fixed width only once a line wrapped, and the
  folder named from the project's folder or in full outside it.
- Unit tests for Rename's way into Claude Code: the name rule (letters of any script, every shell character refused,
  trimmed, 100 at most), the dialog's answer with its box (an unchanged Skein name still sent, an empty one clearing
  only Skein's, an untouched automatic name left alone), when `/rename` is typed (every state, only the claude under the
  tab's shell that runs that session, the draft guard's keys, a just-started claude dirty until its first turn, an Enter
  that counts only once a turn follows it within five seconds and never with a modifier, after a `\` or while claude is
  not idle, the pause after the last key, the actions that count as typing, every part of the last look before the line
  goes, vim mode in either settings file), the waiting renames kept over a restart, replaced,
  carried along with the name and pruned, and Claude Code's own record dropping the Skein name, with tripwires that
  Skein types into a terminal in two places only and `/rename` from one call right after its last look.
- Unit tests for the Windows rules, which run on macOS with Windows-shaped input as well - the path rules over a
  generated corpus (either separator, case, drive letters, UNC and `\\?\` paths, a `..`, names ending in a dot or a
  space, a `ſ` or a Kelvin sign that NTFS keeps apart from s and k), git's forward-slash worktree list, the FILETIME
  start, `claude.exe` against Claude Desktop and node, bun or deno, the lock without a start date, the History of a
  `C:\` project, the PATH hint and the PowerShell preview. The whole suite runs on Windows too, in CI in a
  `windows-latest` job that does not block, and it passed on a Windows 11 ARM64 machine with the POSIX-only tests
  skipped, where the tests that need the real system ran as well, a junction refused on the way of the folder delete
  and of the worktrees `.gitignore`, a OneDrive-like folder that resolves to itself let through, and Git for Windows'
  CRLF.
- The IntelliJ Plugin Verifier against IntelliJ IDEA 2026.3 (263.7307.200): compatible, no internal API usage (the
  build fails on any), no deprecated API usage.
- In a sandbox IDE: the terminal tab opens in the editor area, the typed command reaches the shell (0.4 to 0.9 s), and
  a closed tab is reported once. With Open threads in the editor area off, "+" opens the thread in the Terminal tool
  window, a click on its row brings that tab up, its own close on a working thread ends claude first and leaves no
  session running, a thread in a second pane of a split tool window closes the same way, a thread selected in the
  visible tool window gets no balloon, and a restart brings the threads back into the tool window. With it on, an editor
  thread tab dragged into the tool window stays in the list and closes the same way there, Move to Editor or a drag
  back leaves one row with its colour on the editor tab, and the editor ×, Close All and Close work as before.
  With a demo repository whose main is a commit behind its origin, New Thread Here starts a thread in a folder, in a
  file's folder and from an editor tab, is missing on external libraries, scratches and a worktree folder, and a
  subfolder thread comes back after a restart and resumes in its folder, with its session in the History. A waiting
  thread shows the lock for a permission and the speech bubble for a question, with their tooltips, on a selected row
  and in a dark theme, and the toolbar counter and Jump to Waiting Thread count both. The base warning shows with
  `head`, goes away for an existing worktree's name and comes back for a new one, and stays away with `fresh`. Show
  Changes on a kept worktree steps through committed, staged, unstaged, untracked, deleted and renamed files with the
  worktree and the position in the tab title, a commit the base got later does not show up, a second Show Changes keeps
  one tab, and with 17 changed files the worktree tooltip numbers ten of them and says how many more.
- On macOS with IntelliJ IDEA 2026.3: every `claude` started in an IDE terminal tab has the chain
  `claude → zsh → idea`, so the tab's shell is the process Skein ties the thread to; a `claude` in iTerm2 never
  reaches the IDE and is left alone.
- Not yet checked by hand: the full matrix on a real project (permission prompt, question, turn finished,
  notifications in front and in the background, `/clear`, close, exit and relaunch, restart, a session running in
  another terminal, `claude` missing from PATH) on macOS and Linux. The newer features still wait for their checks
  too: that the keep-awake assertion shows up in `pmset -g assertions` and the JNA module loads, that pins, names,
  colours and done marks survive a restart, the editor-tab tint, a click on the toolbar counter and the sound. The
  refreshed tool window waits for its checks as well, the tab titles after the move into the editor and after a
  restart, the badge while the Skein window has not been opened yet, "+" and the branch button in the title bar and
  the spinner in the new row.
  The lock and speech-bubble glyphs of a waiting thread in a light theme, and at 100 % and 200 % scale side by side.
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
  The restore of several threads, moved into the editor one at a time with only the tab left in front going on, waits
  for its UI test on the macOS test machine, and for a run of that test against Skein 1.5.3, which must fail at the
  start with no click. By hand it waits for the same with Open threads in the editor area off, two threads and no plain
  terminal tab, a project opened with a modal dialog in front, a Close right after a restore of many threads, a thread
  whose claude you end in a plain terminal while its tab is in front, a restored tab you type into before it goes on,
  and two projects that restore a thread on one session.
  The workspace restore passed its UI tests on the macOS test machine and its branch UI tests on the Windows one, and by
  hand on macOS a workspace cleared as Rollback does with three busy threads and a real middle click during a slow
  checkout. With Version Control turned off IntelliJ 2026.3 did not load Skein 1.5.1 at all - its product compatibility
  rules give every plugin from outside JetBrains a dependency on the IDE's collaboration tools, which need Version
  Control, and by that rule as read in the IDE's code it holds for later versions of Skein too, not run - so the
  optional dependency only keeps Skein from adding a need of its own. It still waits, by hand, for a busy claude through
  a checkout, a restored thread nobody opened and a run on
  Windows.
  The close of a busy thread's tab past the close check passed its UI test on the macOS test machine, and by hand there
  a real middle click on a busy thread (five of five ended) and three busy thread tabs closed past the check one after
  another. It waits for its UI test on Windows, and by hand for Shift+click, Shift+Alt+click, the Switcher's close and a
  floating editor window closed with busy threads in it.
  The fake claude waits for its run in a sandbox IDE, started as `tools/fake-claude/README.md` says, with `type claude`
  in a sandbox tab naming the fake, a thread going through the default scenario as you press Enter in its tab and turn
  to another tab while it works (working, the permission prompt with its balloon, the finished turn in blue with the
  model and the context on its row), the History listing the session, a restart resuming a thread that got its prompt,
  and a worktree thread in a git repository.
  New Thread Here waits for the rest of its checks, several files of one folder, a folder outside the project, a
  thread's own tab and the folder a subfolder thread's row names.
  The base warning waits for its look and its wrapping in the name dialog.
  Show Changes waits for the rest of its checks, the item in a worktree thread's menu and not in a plain thread's,
  an image and a binary file, a note for a big file and for a repository inside, an edit saved on the worktree side,
  an unsaved edit on the worktree side showing up in the list of a second Show Changes, a second Show Changes landing
  on the same file while another worktree gets its own tab, also in a separate editor window, the balloons for a
  clean worktree, a rebase stopped half way and an untrusted project, and the note past 1,000 files. The worktree
  tooltip waits for a long path wrapping, its width and the folder named from the project's folder in the Worktrees
  section.
  Windows waits for its checks by hand on Windows 11, with the plugin installed from disk - a thread's states and the
  glyphs of a waiting one, the balloons, the editor ×, the Terminal tool window's × and Close on a working thread, a
  restart that resumes the threads, History, New Thread Here, a worktree thread with its counts, Show Changes and the
  base warning, a worktree lock while its claude runs, Remove… of a leftover behind a junction at `.claude`, a claude
  started from a `subst` drive, a project opened through a junction and its History, a project under OneDrive, the
  PATH hint with `.local\bin` taken off PATH, and a run on x64 Windows.

## UI tests

`src/uiTest` drives a real IDE through JetBrains' Remote Robot, so a thread's life can be checked on a machine Skein
does not build on, Windows above all. The IDE under test runs on that machine with `./gradlew runIdeForUiTests`, which
starts it with the robot server on `127.0.0.1:8082`, in the desktop session of the person logged in there, since an IDE
started over SSH has no windows. The tests run elsewhere with `./gradlew uiTest`, against `-ProbotUrl` or an SSH tunnel
to that port, and they need nothing else - whatever they check on that machine, its processes, Claude Code's status
files and git, they ask from inside the IDE. Each test opens a fresh git repository as a project and closes it again,
and a failing one leaves a screenshot of that machine under `build/reports/ui-screenshots`. They start the user's real
`claude` and answer its trust question for the fresh folder, and only the Rename tests, two workspace restore tests, the
project restore test and the middle click test send it prompts - one or two short ones each, three for the project
restore test, and one long one the middle click test cuts short - so the rest cost no tokens. Neither `test` nor CI runs
them.

The tests check that the Skein tool window is on, that a thread started from the empty list starts claude with its
status file and that the tab's × ends both, that a busy thread's tab closed past the close check, the way a middle
click closes it, ends claude, its status file and its row, and that New Thread in Worktree…, from its own button, starts
claude
with the worktree as its `cwd` while the repository's `git status` stays clean. Two more check the trust question of a
fresh folder - the thread's row says claude asks about trust until the answer, and New Thread in Worktree… warns about
a repository nobody trusted yet. Three check Rename with Also rename in Claude Code - on an idle thread the transcript
gets the name as its custom title, and with a draft typed or text pasted into the thread's tab nothing reaches the
transcript while it sits there. Each of them first sends claude one short prompt, since a thread's first rename waits
for its first message, so these three cost a few tokens. Three check the IDE's restore of a branch's workspace - a
checkout through the IDE to another branch and back keeps an idle thread's claude, the same process, its tab in the
editor and in front, and its row, and claude answers a prompt after. After a first turn, the IDE's own clear of the
workspace, what Rollback runs, ends claude, leaves none behind and brings the thread back as a Resume, whose second
prompt and answer land in the same session's transcript. And a thread tab marked for an announced branch switch that is
closed past the close check, the way a middle click does, ends its claude and stays closed. One more checks a project
opened again - three threads with a turn each, the project closed and opened again, and then with no click and no focus
moved by the test the first saved thread, the one left in front, goes on by itself, with a new claude on its session and
none on the other two, whatever claude another open project starts meanwhile, the IDE never turns active again, the
other two stay not started, and the IDE's log shows no failed move. It logs the Skein build the IDE runs, so a run
against 1.5.3 shows it was one. After each test they end the claude processes the IDE under test still has, by pid, and
delete the test's repository under `skein-ci/ui-work` in the home folder of that machine. A thread starts from the
empty list's link, after indexing ended, and on a cold IDE, where the first click can go unheard, the link is clicked
again every 15 seconds while no claude started and no row came, for up to a minute. The title bar's buttons are reached
through a click into the list first, since the new UI shows a tool window's title bar buttons only while it has the
focus.

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
