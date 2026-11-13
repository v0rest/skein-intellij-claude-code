# Fake claude

`bin/claude` is a small python3 script that behaves, as far as Skein can see, like Claude Code's `claude` in a terminal.
Skein's thread states come from Claude Code's status files and transcripts, which have no official contract, so the
end-to-end tests (`FakeClaudeEndToEndTest`, `FakeClaudeContractTest`) and a sandbox IDE without a Claude Code login use
this instead. Every shape it writes is one Skein's own parsers and test fixtures accept, `claude/SessionFile.kt`,
`claude/Transcripts.kt`, `claude/History.kt`, `threads/ContextUse.kt` and `notify/NotificationContent.kt`, and nothing
in it comes from Claude Code's program. It needs Python 3.8 or newer and nothing outside the standard library.

## What it does

It starts only with a Claude home of its own in `CLAUDE_CONFIG_DIR`, an absolute folder that is not `~/.claude`, does
not lie inside it and does not hold it, and that holds the empty marker file `.fake-claude-home`. Anywhere else it
refuses to run and writes nothing, so it never touches the real `~/.claude`, nor a Claude home of your own somewhere
else. It writes `<CLAUDE_CONFIG_DIR>/sessions/<pid>.json` with its own pid, status `idle` and the time it started,
appends transcript lines to `<CLAUDE_CONFIG_DIR>/projects/<slug of its folder>/<session id>.jsonl`, follows the steps of
a scenario, then removes the status file and exits. SIGHUP, SIGTERM and SIGINT take the status file along too, and the
fake then dies of that signal, as a real process would.

It takes the arguments Skein types and nothing else - `--resume <session id>` (or `-r`), which carries on that session's
transcript and goes back to the folder it recorded, a worktree for a worktree session, and `--worktree <name>`, which
starts in `<repository>/.claude/worktrees/<name>`. In a git repository that folder becomes a real git worktree on the
branch `worktree-<name>`, elsewhere a plain folder, and it is never made inside a Claude home. Claude Code's worktree
lock is not imitated. Besides, `--scenario <file>` picks a scenario and `--fake-check-scenario <file>` only checks one.
An unknown session id ends it with 1 and the message "No conversation found with session ID", anything it refuses with
2, and it writes nothing then.

## Scenarios

A scenario is a JSON file, either a list of steps or an object with `steps` and, if you like, `model` (the default is
`claude-opus-5-5`), `sessionId`, `name`, `nameSource` and `version` for the status file. The fake takes it from
`--scenario`, else from the `FAKE_CLAUDE_SCENARIO` variable, else `scenarios/default.json`. Each step does exactly one
of these things.

- `{"status": "busy"}` rewrites the status file, `idle`, `busy`, `shell` (claude's own background task) or `waiting`
  with `"waitingFor": "permission prompt"` or `"input needed"`. Any other value is written as it is, which Skein shows
  as a status it does not know.
- `{"sleep": 1.5}` waits that many seconds.
- `{"wait": "allow"}` waits for a go-ahead, see below.
- `{"prompt": "text"}` writes the user's prompt.
- `{"answer": "text", "usage": {...}, "model": "..."}` writes an answer. Without `usage` the row shows no model and no
  context for it, Skein sums `input_tokens`, `cache_creation_input_tokens` and `cache_read_input_tokens`.
- `{"toolUse": "Bash", "input": {"command": "make"}, "id": "...", "usage": {...}}` writes a tool call, with an id of
  its own when none is given. `AskUserQuestion` with `{"questions": [{"question": "..."}]}` is the question Skein shows.
- `{"toolResult": "text", "id": "..."}` answers a tool call, the oldest open one when no id is given.
- `{"customTitle": "..."}`, `{"aiTitle": "..."}`, `{"agentName": "..."}` and `{"lastPrompt": "..."}` write the
  title entries the History reads, `/rename` first, then the AI title, the agent name and the last prompt.
- `{"compact": true}` writes a compaction, after which Skein shows no context until the next answer.
- `{"entry": {...}}` appends any JSON object as one transcript line, for shapes the list above lacks.
- `{"name": "checkout work", "nameSource": "user"}` names the session in the status file, as `/rename` does there.
  `"nameSource": "derived"` is a name Claude Code made up, which Skein ignores.
- `{"clear": true}` starts a new session id in the same process, as `/clear` does.
- `{"exit": 0}` stops there with that exit code. Running out of steps exits with 0.

Write transcript lines before the status that follows them, as the scenarios here do, so whoever reacts to the status
reads a transcript that is already there. `scenarios/` has a few to start from, `default.json` (a permission prompt and
a finished turn), `question.json`, `background.json` and `timed.json`, which goes through its states on timers alone.
`bin/claude --fake-check-scenario <file>` checks a file without running it.

A `wait` step opens on Enter in the terminal the fake runs in, and the fake says which step it waits at. With
`FAKE_CLAUDE_GATES` set to a folder it waits for a file of that name there instead and removes it as it goes on, which
is how the tests let it move only once they have seen the state before. Gate names are plain names such as `allow` or
`step-2`, and the folder must lie outside the Claude home. A fake waiting at a gate never waits for ever. It ends, with
exit code 1 and its status file removed, once the gates folder is gone, once its parent process is, as when its shell
was killed or the test JVM died, or after `FAKE_CLAUDE_GATE_TIMEOUT` seconds, 120 by default.

## In a sandbox IDE

The sandbox needs no Claude Code login. Start it from the repository with a clean environment, the fake first on `PATH`
and a Claude home of its own with the marker in it, which also keeps the variables of a Claude Code session out of the
sandbox.

```sh
fake_home="$(mktemp -d)/claude-home" && mkdir -p "$fake_home" && touch "$fake_home/.fake-claude-home"
env -i HOME="$HOME" USER="$USER" LOGNAME="$LOGNAME" SHELL=/bin/zsh LANG=en_US.UTF-8 TMPDIR="${TMPDIR:-/tmp}" \
  PATH="$PWD/tools/fake-claude/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin" \
  CLAUDE_CONFIG_DIR="$fake_home" JAVA_HOME="$JAVA_HOME" \
  ./gradlew runIde --no-daemon
```

Add `FAKE_CLAUDE_SCENARIO="$PWD/tools/fake-claude/scenarios/question.json"` to that line for another scenario. Every
`claude` Skein types then runs the fake, which waits for Enter at its `wait` steps, so you decide in the thread's tab
when it sends its prompt, gets its permission and exits. A thread whose tab you look at gets no balloon, and its
finished turn reads idle rather than blue, so after Enter turn to another tab while the fake works (the default scenario
pauses two seconds), or use `timed.json`, which goes on by itself. A thread that never got its prompt has no transcript,
so resuming it after a restart ends with the fake's "No conversation found".

Before you trust what you see, run `type claude` and `echo $CLAUDE_CONFIG_DIR` in a terminal tab of the sandbox. The
first must name `tools/fake-claude/bin/claude`, since your shell's rc files may put a real `claude` first on `PATH` (on
macOS `/etc/zprofile` also moves the folders of `/etc/paths`, `/usr/local/bin` among them, in front of it). The second
must name the folder the recipe made. If either does not, start the sandbox with `ZDOTDIR` set to an empty folder in the
line above, so zsh skips your own rc files, or set the sandbox's shell to `/bin/zsh -f` in Settings | Tools | Terminal.

The fake never writes into a Claude home without the marker. If your `~/.zshrc` or `~/.zshenv` exports a
`CLAUDE_CONFIG_DIR` of your own, a login zsh in the sandbox applies it over the recipe's folder, and the fake then
refuses to start there, says that the folder has no `.fake-claude-home` and writes nothing, so your own Claude home is
never written to. Without any `CLAUDE_CONFIG_DIR` it refuses too, rather than fall back to `~/.claude`, and it refuses
`~/.claude` even with the marker in it.
