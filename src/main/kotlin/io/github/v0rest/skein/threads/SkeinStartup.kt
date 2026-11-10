package io.github.v0rest.skein.threads

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.v0rest.skein.notify.ThreadNotifier

/** Restores the saved threads once the project is open, or says once why Skein is off here. */
class SkeinStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        val service = ThreadService.getInstance(project)
        val unsupported = service.unsupported
        if (unsupported == null) {
            service.restoreOnce()
            return
        }
        val properties = PropertiesComponent.getInstance()
        val key = "skein.unsupportedNoticeShown.${unsupported.name}"
        if (properties.getBoolean(key)) return
        properties.setValue(key, true)
        NotificationGroupManager.getInstance().getNotificationGroup(ThreadNotifier.GROUP_ID)
            .createNotification("Skein is off in this IDE", unsupported.message, NotificationType.INFORMATION)
            .notify(project)
    }
}
