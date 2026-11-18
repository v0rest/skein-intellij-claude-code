package io.github.v0rest.skein.ui

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.CommonContainerFixture
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.keyboard
import com.intellij.remoterobot.utils.waitFor
import org.junit.rules.ExternalResource
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File
import java.time.Duration
import javax.imageio.ImageIO

/**
 * The IDE under test, started elsewhere with `runIdeForUiTests` and reached through its robot server. Everything
 * about the machine it runs on (processes, Claude Code's files, git) is asked from inside that IDE with JavaScript, so
 * the tests need nothing but the robot's port.
 */
object RemoteIde {
    val robot = RemoteRobot(System.getProperty("robot.url", "http://127.0.0.1:8082"))

    fun js(script: String, edt: Boolean = false): String = robot.callJs(script, edt)

    /** Whether the IDE under test runs on macOS, where select-all and the like take Cmd instead of Ctrl. */
    val onMac: Boolean by lazy { js("'' + java.lang.System.getProperty('os.name')").startsWith("Mac") }

    /** Replaces the text of the focused field with [text] the way a person would: select all, then type. */
    fun replaceTextWith(text: String) {
        val modifier = if (onMac) java.awt.event.KeyEvent.VK_META else java.awt.event.KeyEvent.VK_CONTROL
        robot.keyboard {
            hotKey(modifier, java.awt.event.KeyEvent.VK_A)
            enterText(text)
        }
    }

    /** A new git repository with one commit, in a folder of its own on the IDE's machine; its full path. */
    fun newRepository(): String = js(
        """
        var base = new java.io.File(java.lang.System.getProperty('user.home'), 'skein-ci/ui-work/demo-' + java.lang.System.currentTimeMillis());
        base.mkdirs();
        $GIT
        git(base, ['init', '-q', '-b', 'main']);
        // the IDE keeps its own project files in .idea, and saves them whenever its window loses the focus on macOS;
        // they are no business of Skein's, so git status, which the tests check, never shows them
        java.nio.file.Files.writeString(new java.io.File(base, '.git/info/exclude').toPath(), '.idea/\n', java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        java.nio.file.Files.writeString(new java.io.File(base, 'README.md').toPath(), 'demo\n');
        git(base, ['add', '.']);
        git(base, ['commit', '-q', '-m', 'init']);
        base.getCanonicalPath()
        """,
    )

    /**
     * Opens [path] as a project in a frame of its own and returns the project's name, which is its folder's name. The
     * open is queued rather than run inside the robot's call: right after the IDE starts it can take longer than the
     * robot's read timeout, and the call would fail although the project opens fine.
     */
    fun open(path: String): String {
        val name = js(
            """
            var path = java.nio.file.Path.of('${slashed(path)}');
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({
                run: function() { com.intellij.ide.impl.ProjectUtil.openOrImport(path, com.intellij.ide.impl.OpenProjectTask.build().withForceOpenInNewFrame(true)); }
            }));
            '' + path.getFileName()
            """,
        )
        waitFor(Duration.ofSeconds(120), Duration.ofSeconds(1), "the frame of $name") { frames(name).isNotEmpty() }
        return name
    }

    /** Closes the project named [name] without asking. */
    fun close(name: String) {
        // closing needs the write-intent lock, which invokeLater gives and a plain call on the EDT does not
        js(
            """
            var ps = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects();
            for (var i = 0; i < ps.length; i++) if (ps[i].getName() == '$name') {
                var project = ps[i];
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({
                    run: function() { com.intellij.openapi.project.ex.ProjectManagerEx.getInstanceEx().forceCloseProject(project); }
                }));
            }
            'ok'
            """,
        )
        waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "the frame of $name closed") { frames(name).isEmpty() }
    }

    /** Closes the project named [name] the way File | Close Project does (`ProjectManager.closeAndDispose`), on the EDT. */
    fun closeAndDispose(name: String) {
        // invokeLater for the write-intent lock, as in [close]
        js(
            """
            var ps = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects();
            for (var i = 0; i < ps.length; i++) if (ps[i].getName() == '$name') {
                var project = ps[i];
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({
                    run: function() { com.intellij.openapi.project.ProjectManager.getInstance().closeAndDispose(project); }
                }));
            }
            'ok'
            """,
        )
        waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "the frame of $name closed") { frames(name).isEmpty() }
    }

    /**
     * How many rows the Skein thread list of the project named [name] shows, or -1 while there is no such list. The
     * thread list keeps its "No threads" empty text with rows too, which tells it from the History's list.
     */
    fun threadRows(name: String): Int =
        frames(name).firstOrNull()?.findAll(ComponentFixture::class.java, byXpath("//div[@class='JBList']"))?.maxOfOrNull { list ->
            list.callJs<String>("('' + component.getEmptyText().getText()).indexOf('No threads') == 0 ? '' + component.getModel().getSize() : '-1'")
                .trim().toIntOrNull() ?: -1
        } ?: -1

    /** When process [pid] started, as the IDE under test reads it, or "" when it is gone or does not say. */
    fun startOf(pid: Long): String = js(
        """
        var h = java.lang.ProcessHandle.of($pid);
        var start = h.isPresent() ? h.get().info().startInstant() : java.util.Optional.empty();
        start.isPresent() ? '' + start.get() : ''
        """,
    )

    /** Whether process [pid] still runs on the machine under test, whoever its parent is now. */
    fun alive(pid: Long): Boolean = js("var h = java.lang.ProcessHandle.of($pid); '' + (h.isPresent() && h.get().isAlive())") == "true"

    /** Ends process [pid], but only while it is still the one that started at [start], so a pid reused since is left alone. */
    fun destroy(pid: Long, start: String) {
        if (start.isEmpty() || startOf(pid) != start) return
        js("java.lang.ProcessHandle.of($pid).ifPresent(function(p) { p.destroy(); }); 'ok'")
    }

    /** Whether the project named [name] is indexing (dumb mode); false once it is not, or when no such project is open. */
    fun isDumb(name: String): Boolean = js(
        """
        var ps = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects(); var out = 'false';
        for (var i = 0; i < ps.length; i++) if (ps[i].getName() == '$name') out = '' + com.intellij.openapi.project.DumbService.isDumb(ps[i]);
        out
        """,
    ) == "true"

    /**
     * Clicks the "Start claude in a new tab" link of the empty thread list of [name], which runs New Thread like "+"
     * does. "+" itself sits in the title bar, which the new UI shows only while the IDE's window has the system's focus -
     * something a robot-driven window on another machine does not reliably get - so the link is the dependable way in.
     * On a cold IDE the first click can go unheard, so this waits until the project is out of dumb mode first, and while
     * no new claude has started and the list still has no row it clicks again every [LINK_RETRY] seconds, for up to a
     * minute. Once a row is there it never clicks again, so a slow claude is never started twice. Returns the list,
     * which then shows the thread's row; the caller waits for the claude as before.
     */
    fun startClaudeFromTheEmptyList(name: String): ComponentFixture {
        waitFor(Duration.ofSeconds(120), Duration.ofSeconds(1), "$name out of dumb mode") { !isDumb(name) }
        toFront(name)
        val list = frame(name).find(ComponentFixture::class.java, byXpath(EMPTY_LIST), Duration.ofSeconds(30))
        // the robot reads the text the list paints, with where it painted it
        waitFor(Duration.ofSeconds(10), Duration.ofSeconds(1), "the link in the empty list") { list.findAllText().any { it.text.contains(LINK) } }
        val before = claudePids()
        var clickedAt: Long? = null
        waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "a thread started from the empty list of $name") {
            when {
                (claudePids() - before).isNotEmpty() || rows(list) > 0 -> true
                clickedAt.let { it == null || System.nanoTime() - it >= LINK_RETRY * 1_000_000_000L } -> {
                    // looked at once more right before the click: a row that came meanwhile means the thread is on its way
                    if (rows(list) == 0) list.findAllText().firstOrNull { it.text.contains(LINK) }?.click()
                    clickedAt = System.nanoTime()
                    false
                }
                else -> false
            }
        }
        return list
    }

    /** How many rows the thread list holds; 0 when it cannot be read. */
    private fun rows(list: ComponentFixture): Int = list.callJs<String>("'' + component.getModel().getSize()").trim().toIntOrNull() ?: 0

    /** The thread list while it is empty, with its link. */
    private const val EMPTY_LIST = "//div[@class='JBList' and contains(@visible_text, 'No threads yet')]"
    private const val LINK = "Start claude"

    /** Seconds between two clicks on the link while nothing comes of them. */
    private const val LINK_RETRY = 15L

    fun frame(name: String): CommonContainerFixture =
        robot.find(CommonContainerFixture::class.java, byXpath(frameXpath(name)), Duration.ofSeconds(30))

    private fun frames(name: String) = robot.findAll(CommonContainerFixture::class.java, byXpath(frameXpath(name)))

    private fun frameXpath(name: String) = "//div[@class='IdeFrameImpl' and starts-with(@accessiblename, '$name ')]"

    /**
     * The pids of Claude Code's CLIs this IDE started - `claude.exe` outside Claude Desktop's package whose shell is a
     * child of the IDE under test - so a claude the person runs elsewhere on that machine never counts.
     */
    fun claudePids(): Set<Long> = js(
        """
        var ide = java.lang.ProcessHandle.current().pid();
        var out = new java.util.ArrayList();
        var it = java.lang.ProcessHandle.allProcesses().iterator();
        while (it.hasNext()) {
            var p = it.next();
            var c = ('' + p.info().command().orElse('')).toLowerCase();
            // the native installer's `claude` on macOS and Linux is a link, and the process runs the version it points to
            var cli = c.endsWith('claude.exe') || c.endsWith('/claude') || c.endsWith('\\\\claude') || c.indexOf('/claude/versions/') >= 0;
            if (!cli || c.indexOf('windowsapps') >= 0) continue;
            var shell = p.parent();
            var owner = shell.isPresent() ? shell.get().parent() : java.util.Optional.empty();
            if (owner.isPresent() && owner.get().pid() == ide) out.add('' + p.pid());
        }
        out.toString()
        """,
    ).trim('[', ']').split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()

    /**
     * Closes every project a UI test opened (`demo-…`), for a clean start - after the modal dialogs a failed test may
     * have left open, which would keep a project from closing.
     */
    fun closeTestProjects() {
        js(
            """
            // ModalityState.any(), since a runnable queued with the default one waits for the modal dialog to close
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({
                run: function() {
                    var ws = java.awt.Window.getWindows();
                    for (var i = 0; i < ws.length; i++) {
                        var w = ws[i];
                        if (!(w instanceof javax.swing.JDialog) || !w.isShowing() || !w.isModal()) continue;
                        var dw = com.intellij.openapi.ui.DialogWrapper.findInstance(w.getContentPane());
                        if (dw != null) dw.doCancelAction(); else w.dispose();
                    }
                }
            }), com.intellij.openapi.application.ModalityState.any());
            'ok'
            """,
        )
        val names = js(
            """
            var ps = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects(); var out = new java.util.ArrayList();
            for (var i = 0; i < ps.length; i++) if (('' + ps[i].getName()).startsWith('demo-')) out.add('' + ps[i].getName());
            out.toString()
            """,
        ).trim('[', ']').split(',').map { it.trim() }.filter { it.isNotEmpty() }
        names.forEach(::close)
    }

    /**
     * Shows and activates the Skein tool window of [name]. A click on its stripe button would toggle it, and the sandbox
     * keeps the layout of the last project, so it might close it instead; the title bar buttons show only while it is
     * active.
     */
    fun activateSkein(name: String) {
        js(
            """
            var ps = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects();
            for (var i = 0; i < ps.length; i++) if (ps[i].getName() == '$name') {
                var project = ps[i];
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({
                    run: function() { com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow('Skein').activate(null); }
                }));
            }
            'ok'
            """,
        )
    }

    /** Puts the window (or window tab) of project [name] in front with the focus, the way a person switches to it. */
    fun focusProject(name: String) {
        js(
            """
            var ps = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects();
            for (var i = 0; i < ps.length; i++) if (ps[i].getName() == '$name') {
                var project = ps[i];
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({
                    run: function() { com.intellij.ide.impl.ProjectUtil.focusProjectWindow(project, true); }
                }));
            }
            'ok'
            """,
        )
    }

    /** The project whose window or tab is in front with the IDE active, or "" when there is none. */
    fun inFront(): String = js(
        """
        var f = com.intellij.openapi.wm.IdeFocusManager.getGlobalInstance().getLastFocusedFrame();
        var active = com.intellij.openapi.application.ApplicationManager.getApplication().isActive();
        (active && f != null && f.getProject() != null) ? '' + f.getProject().getName() : ''
        """,
        edt = true,
    )

    /** The title, subtitle and text of each notification project [name] shows now, one "title | subtitle | text" line each. */
    fun notificationsOf(name: String): List<String> = js(
        """
        var ps = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects(); var out = new java.util.ArrayList();
        for (var i = 0; i < ps.length; i++) if (ps[i].getName() == '$name') {
            var ns = com.intellij.notification.NotificationsManager.getNotificationsManager().getNotificationsOfType(com.intellij.notification.Notification, ps[i]);
            for (var j = 0; j < ns.length; j++) out.add(ns[j].getTitle() + ' | ' + ns[j].getSubtitle() + ' | ' + ns[j].getContent());
        }
        '' + java.lang.String.join('\n', out)
        """,
    ).lines().filter { it.isNotBlank() }

    /** Brings the frame of [name] to the front, so a screenshot shows it. */
    fun toFront(name: String) {
        js(
            """
            var ps = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects();
            for (var i = 0; i < ps.length; i++) if (ps[i].getName() == '$name') {
                var f = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(ps[i]); if (f != null) f.toFront();
            }
            'ok'
            """,
            edt = true,
        )
    }

    /** The pids that have a status file under Claude Code's home on that machine. */
    fun sessionPids(): Set<Long> = js(
        """
        var dir = new java.io.File(java.lang.System.getProperty('user.home'), '.claude/sessions');
        var names = dir.list(); var out = new java.util.ArrayList();
        if (names != null) for (var i = 0; i < names.length; i++) if (('' + names[i]).endsWith('.json')) out.add(('' + names[i]).replace('.json', ''));
        out.toString()
        """,
    ).trim('[', ']').split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()

    /** A text field of the status file of claude [pid] (`status`, `sessionId`), or "" when there is none. */
    fun sessionField(pid: Long, name: String): String = js(
        """
        var f = new java.io.File(java.lang.System.getProperty('user.home'), '.claude/sessions/$pid.json');
        var text = f.isFile() ? '' + java.nio.file.Files.readString(f.toPath()) : '';
        var m = /"$name"\s*:\s*"([^"]*)"/.exec(text);
        m ? m[1] : ''
        """,
    )

    /** The lines of session [sessionId]'s transcript under Claude Code's `projects` folder on that machine, none when it has none. */
    fun transcript(sessionId: String): List<String> = js(
        """
        var root = new java.io.File(java.lang.System.getProperty('user.home'), '.claude/projects');
        var dirs = root.listFiles(); var text = '';
        if (dirs != null) for (var i = 0; i < dirs.length; i++) {
            var f = new java.io.File(dirs[i], '$sessionId.jsonl');
            if (f.isFile()) text = '' + java.nio.file.Files.readString(f.toPath());
        }
        text
        """,
    ).lines().filter { it.isNotBlank() }

    /** The `cwd` in the status file of claude [pid], or "" when there is none. */
    fun sessionCwd(pid: Long): String = js(
        """
        var f = new java.io.File(java.lang.System.getProperty('user.home'), '.claude/sessions/$pid.json');
        var text = f.isFile() ? '' + java.nio.file.Files.readString(f.toPath()) : '';
        var m = /"cwd"\s*:\s*"((?:[^"\\\\]|\\\\.)*)"/.exec(text);
        m ? JSON.parse('"' + m[1] + '"') : ''
        """,
    )

    /** Whether [a] and [b] name one folder on that machine, as its file system says. */
    fun sameFolder(a: String, b: String): Boolean = js(
        """
        var x = new java.io.File('${slashed(a)}'), y = new java.io.File('${slashed(b)}');
        '' + (x.exists() && y.exists() && java.nio.file.Files.isSameFile(x.toPath(), y.toPath()))
        """,
    ) == "true"

    /** A folder under [base] on that machine, joined there with its own separator. */
    fun child(base: String, vararg names: String): String = js(
        "var f = new java.io.File('${slashed(base)}'); " + names.joinToString(" ") { "f = new java.io.File(f, '${slashed(it)}');" } +
            " f.getPath()",
    )

    /**
     * Ends the claude processes of the IDE under test that are still there - only those, by pid - and deletes a test's
     * repository, which lives under `skein-ci/ui-work`.
     */
    fun cleanUp(repo: String) {
        claudePids().forEach { pid -> js("java.lang.ProcessHandle.of($pid).ifPresent(function(p) { p.destroy(); }); 'ok'") }
        js(
            """
            var f = new java.io.File('${slashed(repo)}').getCanonicalFile();
            var work = new java.io.File(java.lang.System.getProperty('user.home'), 'skein-ci/ui-work').getCanonicalFile();
            if (f.getParentFile() != null && f.getParentFile().equals(work)) com.intellij.openapi.util.io.FileUtil.delete(f);
            'ok'
            """,
        )
    }

    /** A new branch [branch] at the commit [repo] has checked out, left unchecked-out. */
    fun newBranch(repo: String, branch: String) {
        js(
            """
            $GIT
            git(new java.io.File('${slashed(repo)}'), ['branch', '${slashed(branch)}']);
            'ok'
            """,
        )
    }

    /** The branch [repo] has checked out, as git says. */
    fun currentBranch(repo: String): String = js(
        """
        $GIT
        gitOut(new java.io.File('${slashed(repo)}'), ['rev-parse', '--abbrev-ref', 'HEAD'])
        """,
    ).trim()

    /** Whether project [name] has the IDE's Git as an active version control, which a checkout through the IDE needs. */
    fun gitActive(name: String): Boolean = js(
        """
        $PROJECT
        $GIT_CLASS
        var project = projectNamed('$name');
        '' + (project != null && gitClass(project, 'git4idea.branch.GitBrancher') != null)
        """,
    ) == "true"

    /**
     * Turns the IDE's "Restore workspace on branch switching" on for project [name] (`VcsConfiguration.RELOAD_CONTEXT`,
     * on by default), so a checkout of a branch the IDE saved a workspace for restores it.
     */
    fun restoreWorkspaceOnBranchSwitch(name: String) {
        js(
            """
            $PROJECT
            $GIT_CLASS
            var project = projectNamed('$name');
            var cls = gitClass(project, 'com.intellij.openapi.vcs.VcsConfiguration');
            var config = cls.getMethod('getInstance', com.intellij.openapi.project.Project).invoke(null, project);
            cls.getField('RELOAD_CONTEXT').setBoolean(config, true);
            'ok'
            """,
        )
    }

    /**
     * Checks out [branch] in project [name] through the IDE's own Git (`GitBrancher`, its class loaded through the Git
     * version control's own class loader), as the branch popup does. The IDE saves the workspace of the branch it leaves
     * and restores the one of the branch it reaches, if it has one. Returns at once, and the checkout runs in the background.
     */
    fun checkoutThroughTheIde(name: String, branch: String) {
        js(
            """
            $PROJECT
            $GIT_CLASS
            var project = projectNamed('$name');
            var manager = gitClass(project, 'git4idea.repo.GitRepositoryManager').getMethod('getInstance', com.intellij.openapi.project.Project).invoke(null, project);
            var brancher = gitClass(project, 'git4idea.branch.GitBrancher').getMethod('getInstance', com.intellij.openapi.project.Project).invoke(null, project);
            brancher.checkout('${slashed(branch)}', false, manager.getRepositories(), null);
            'ok'
            """,
        )
    }

    /** Whether project [name] shows the IDE's balloon that it restored the workspace of a branch. */
    fun workspaceRestored(name: String): Boolean = js(
        """
        $PROJECT
        var project = projectNamed('$name'); var out = 'false';
        if (project != null) {
            var ns = com.intellij.notification.NotificationsManager.getNotificationsManager().getNotificationsOfType(com.intellij.notification.Notification, project);
            for (var j = 0; j < ns.length; j++) if (ns[j].getGroupId() == 'Branch Context group') out = 'true';
        }
        out
        """,
    ) == "true"

    /** The editor files project [name] has open, each as its name and its class, "README.md{VirtualFileImpl}". */
    fun openEditorFiles(name: String): List<String> = js(
        """
        $PROJECT
        var project = projectNamed('$name'); var out = new java.util.ArrayList();
        if (project != null) {
            var fs = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getOpenFiles();
            for (var i = 0; i < fs.length; i++) out.add('' + fs[i].getName() + '{' + fs[i].getClass().getSimpleName() + '}');
        }
        '' + java.lang.String.join('\n', out)
        """,
    ).lines().filter { it.isNotBlank() }

    /** The selected editor file of project [name], as its name and its class, or "" without one. */
    fun selectedEditorFile(name: String): String = js(
        """
        $PROJECT
        var project = projectNamed('$name');
        var f = project == null ? null : com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getSelectedEditor();
        f == null || f.getFile() == null ? '' : '' + f.getFile().getName() + '{' + f.getFile().getClass().getSimpleName() + '}'
        """,
        edt = true,
    )

    /**
     * Announces a branch switch to project [name]'s listeners (`BranchChangeListener.branchWillChange`), the way the IDE's
     * Git does right before a checkout, on the EDT, without checking anything out - Skein marks its thread tabs then. The
     * topic's class is loaded through the Git version control's class loader, as in [checkoutThroughTheIde].
     */
    fun announceBranchSwitch(name: String, branch: String) {
        js(
            """
            $PROJECT
            $GIT_CLASS
            var project = projectNamed('$name');
            var topic = gitClass(project, 'com.intellij.openapi.vcs.BranchChangeListener').getField('VCS_BRANCH_CHANGED').get(null);
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({
                run: function() { project.getMessageBus().syncPublisher(topic).branchWillChange('${slashed(branch)}'); }
            }));
            'ok'
            """,
        )
    }

    /**
     * Announces the end of a branch switch to project [name]'s listeners (`BranchChangeListener.branchHasChanged`), the
     * way the IDE's Git does after a checkout, on the EDT, without checking anything out. For a [branch] the IDE saved no
     * workspace for, nothing is restored.
     */
    fun announceBranchSwitchOver(name: String, branch: String) {
        js(
            """
            $PROJECT
            $GIT_CLASS
            var project = projectNamed('$name');
            var topic = gitClass(project, 'com.intellij.openapi.vcs.BranchChangeListener').getField('VCS_BRANCH_CHANGED').get(null);
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({
                run: function() { project.getMessageBus().syncPublisher(topic).branchHasChanged('${slashed(branch)}'); }
            }));
            'ok'
            """,
        )
    }

    /**
     * Whether the thread's terminal tab in project [name]'s editor carries the IDE's "closes to reopen" mark
     * (`FileEditorManagerKeys.CLOSING_TO_REOPEN`), which Skein puts there when a branch switch is announced.
     */
    fun threadTabMarkedToReopen(name: String): Boolean = js(
        """
        $PROJECT
        var project = projectNamed('$name'); var out = 'false';
        if (project != null) {
            var fs = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getOpenFiles();
            for (var i = 0; i < fs.length; i++) {
                if (fs[i].getClass().getSimpleName() == 'TerminalViewVirtualFile' &&
                    fs[i].getUserData(com.intellij.openapi.fileEditor.FileEditorManagerKeys.CLOSING_TO_REOPEN) == java.lang.Boolean.TRUE) out = 'true';
            }
        }
        out
        """,
        edt = true,
    ) == "true"

    /**
     * Closes the thread's terminal tab in project [name]'s editor the way a middle click or an Alt+click on a tab does,
     * `closeFile(file, window)` without the close checks, through invokeLater for the write-intent lock.
     */
    fun closeTheThreadsTabWithoutChecks(name: String) {
        js(
            """
            $PROJECT
            var project = projectNamed('$name');
            var editors = com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project);
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({
                run: function() {
                    var fs = editors.getOpenFiles();
                    for (var i = 0; i < fs.length; i++) {
                        if (fs[i].getClass().getSimpleName() != 'TerminalViewVirtualFile') continue;
                        var ws = editors.getWindows();
                        for (var j = 0; j < ws.length; j++) if (ws[j].isFileOpen(fs[i])) { editors.closeFile(fs[i], ws[j]); return; }
                    }
                }
            }));
            'ok'
            """,
        )
    }

    /** Whether project [name] has a thread's terminal open in the editor area. */
    fun terminalInTheEditor(name: String): Boolean = openEditorFiles(name).any { it.contains("{TerminalViewVirtualFile}") }

    /**
     * Clears project [name]'s workspace the way the restore balloon's Rollback and the Tasks plugin's Load Context do,
     * `WorkingContextManager.clearContext()`, which closes every editor without asking. Its class is loaded through the
     * class loader of one of the Tasks plugin's own context providers, and it runs through invokeLater for the
     * write-intent lock, as in [close].
     */
    fun clearWorkspace(name: String) {
        js(
            """
            $PROJECT
            var project = projectNamed('$name');
            var providers = com.intellij.openapi.extensions.ExtensionPointName.create('com.intellij.tasks.contextProvider').getExtensionList();
            var cls = java.lang.Class.forName('com.intellij.tasks.context.WorkingContextManager', true, providers.get(0).getClass().getClassLoader());
            var manager = cls.getMethod('getInstance', com.intellij.openapi.project.Project).invoke(null, project);
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({
                run: function() { manager.clearContext(); }
            }));
            'ok'
            """,
        )
    }

    /** `git status --porcelain` in [repo], `--ignored` left out. */
    fun gitStatus(repo: String): String = js(
        """
        $GIT
        gitOut(new java.io.File('${slashed(repo)}'), ['status', '--porcelain'])
        """,
    )

    /**
     * Answers Claude Code's "Accessing workspace" question in the thread's tab of [project], which has the focus. Its
     * first answer is "No, exit", which ends claude, and its menu may not take a key the moment it is drawn, so Down goes
     * again until the pointer stands on "Yes, I trust this folder", and only then Enter. Skein's row and balloon say
     * "asks whether to trust this folder" too, and those are not the question.
     */
    fun acceptTheTrustQuestion(project: String) {
        val frame = frame(project)
        waitFor(Duration.ofSeconds(30), Duration.ofSeconds(1), "claude's question about trusting the folder") {
            frame.findAllText().any { it.text.contains("trust this folder") && !it.text.contains("asks whether") }
        }
        waitFor(Duration.ofSeconds(20), Duration.ofSeconds(2), "the pointer on \"Yes, I trust this folder\"") {
            if (!editorsContain(TRUST_YES)) robot.keyboard { key(java.awt.event.KeyEvent.VK_DOWN) }
            editorsContain(TRUST_YES)
        }
        robot.keyboard { enter() }
    }

    /** The trust question's second answer with claude's pointer on it. */
    private const val TRUST_YES = "\u276F Yes, I trust this folder"

    /**
     * Whether [text] is in the document of an editor on screen. The terminal draws its output, claude's input box among
     * it, into an editor, and the robot's painted-text search may see a line in pieces, so the document is read instead.
     */
    fun editorsContain(text: String): Boolean = js(
        """
        var found = false;
        function walk(c) {
            if (found) return;
            if (c instanceof com.intellij.openapi.editor.impl.EditorComponentImpl &&
                ('' + c.getEditor().getDocument().getText()).indexOf('${text.replace("\\", "\\\\").replace("'", "\\'")}') >= 0) { found = true; return; }
            if (c instanceof java.awt.Container) { var cs = c.getComponents(); for (var i = 0; i < cs.length; i++) walk(cs[i]); }
        }
        var ws = java.awt.Window.getWindows();
        for (var i = 0; i < ws.length && !found; i++) if (ws[i].isShowing()) walk(ws[i]);
        '' + found
        """,
        edt = true,
    ) == "true"

    fun exists(path: String): Boolean = js("'' + new java.io.File('${slashed(path)}').isDirectory()") == "true"

    fun screenshot(file: File) {
        file.parentFile.mkdirs()
        ImageIO.write(robot.getScreenshot(), "png", file)
    }

    /** [path] as a JavaScript string literal's content: forward slashes, and a quote or backslash escaped. */
    private fun slashed(path: String) = path.replace('\\', '/').replace("'", "\\'")

    /** The open project named by its argument, or null. */
    private const val PROJECT = """
        function projectNamed(name) {
            var ps = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects();
            for (var i = 0; i < ps.length; i++) if (ps[i].getName() == name) return ps[i];
            return null;
        }
    """

    /**
     * A class of the IDE's Git plugin or of what it depends on, loaded through the class loader of the project's active
     * Git version control, since the robot's own scripts do not see that plugin, and null while Git is not active there.
     */
    private const val GIT_CLASS = """
        function gitClass(project, name) {
            var vcss = com.intellij.openapi.vcs.ProjectLevelVcsManager.getInstance(project).getAllActiveVcss();
            for (var i = 0; i < vcss.length; i++) {
                if (('' + vcss[i].getClass().getName()).indexOf('git4idea') >= 0) return java.lang.Class.forName(name, true, vcss[i].getClass().getClassLoader());
            }
            return null;
        }
    """

    /** git on the IDE's machine, with a throwaway identity for the commits a test makes. */
    private const val GIT = """
        function command(args) {
            var cmd = new java.util.ArrayList();
            cmd.add('git'); cmd.add('-c'); cmd.add('user.name=ui-test'); cmd.add('-c'); cmd.add('user.email=ui-test@example.invalid');
            for (var i = 0; i < args.length; i++) cmd.add(args[i]);
            return cmd;
        }
        function git(dir, args) {
            var p = new java.lang.ProcessBuilder(command(args)).directory(dir).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes(); return p.waitFor();
        }
        function gitOut(dir, args) {
            var p = new java.lang.ProcessBuilder(command(args)).directory(dir).redirectErrorStream(true).start();
            var s = new java.lang.String(p.getInputStream().readAllBytes()); p.waitFor(); return '' + s;
        }
    """
}

/** A screenshot of the IDE's machine when a test fails, with [frame]'s window in front, under build/reports/ui-screenshots. */
class ScreenshotOnFailure(private val frame: () -> String?) : TestWatcher() {
    override fun failed(e: Throwable, description: Description) {
        val dir = File(System.getProperty("ui.screenshots", "build/reports/ui-screenshots"))
        runCatching {
            frame()?.let(RemoteIde::toFront)
            RemoteIde.screenshot(File(dir, "${description.testClass.simpleName}.${description.methodName}.png"))
        }
    }
}

/** A fresh git repository opened as a project with the Skein tool window shown; closed again afterwards. */
class DemoProject : ExternalResource() {
    lateinit var repo: String
    var name: String? = null

    override fun before() {
        RemoteIde.closeTestProjects()
        repo = RemoteIde.newRepository()
        name = RemoteIde.open(repo)
        RemoteIde.activateSkein(name!!)
    }

    override fun after() {
        name?.let(RemoteIde::close)
        if (::repo.isInitialized) RemoteIde.cleanUp(repo)
    }
}
