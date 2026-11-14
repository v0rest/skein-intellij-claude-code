package io.github.v0rest.skein.ui

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.CommonContainerFixture
import com.intellij.remoterobot.search.locators.byXpath
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

    /** A new git repository with one commit, in a folder of its own on the IDE's machine; its full path. */
    fun newRepository(): String = js(
        """
        var base = new java.io.File(java.lang.System.getProperty('user.home'), 'skein-ci/ui-work/demo-' + java.lang.System.currentTimeMillis());
        base.mkdirs();
        $GIT
        git(base, ['init', '-q', '-b', 'main']);
        java.nio.file.Files.writeString(new java.io.File(base, 'README.md').toPath(), 'demo\n');
        git(base, ['add', '.']);
        git(base, ['commit', '-q', '-m', 'init']);
        base.getCanonicalPath()
        """,
    )

    /** Opens [path] as a project in a frame of its own and returns the project's name. */
    fun open(path: String): String {
        val name = js(
            """
            var p = com.intellij.ide.impl.ProjectUtil.openOrImport(java.nio.file.Path.of('${slashed(path)}'), com.intellij.ide.impl.OpenProjectTask.build().withForceOpenInNewFrame(true));
            p.getName()
            """,
            edt = true,
        )
        waitFor(Duration.ofSeconds(60), Duration.ofSeconds(1), "the frame of $name") { frames(name).isNotEmpty() }
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
            var cli = c.endsWith('claude.exe') || c.endsWith('/claude') || c.endsWith('\\\\claude');
            if (!cli || c.indexOf('windowsapps') >= 0) continue;
            var shell = p.parent();
            var owner = shell.isPresent() ? shell.get().parent() : java.util.Optional.empty();
            if (owner.isPresent() && owner.get().pid() == ide) out.add('' + p.pid());
        }
        out.toString()
        """,
    ).trim('[', ']').split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()

    /** Closes every project a UI test opened (`demo-…`), for a clean start. */
    fun closeTestProjects() {
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

    /** `git status --porcelain` in [repo], `--ignored` left out. */
    fun gitStatus(repo: String): String = js(
        """
        $GIT
        gitOut(new java.io.File('${slashed(repo)}'), ['status', '--porcelain'])
        """,
    )

    fun exists(path: String): Boolean = js("'' + new java.io.File('${slashed(path)}').isDirectory()") == "true"

    fun screenshot(file: File) {
        file.parentFile.mkdirs()
        ImageIO.write(robot.getScreenshot(), "png", file)
    }

    /** [path] as a JavaScript string literal's content: forward slashes, and a quote or backslash escaped. */
    private fun slashed(path: String) = path.replace('\\', '/').replace("'", "\\'")

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
