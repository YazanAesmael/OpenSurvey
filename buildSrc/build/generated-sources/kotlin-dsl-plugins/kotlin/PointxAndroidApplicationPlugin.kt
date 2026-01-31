/**
 * Precompiled [pointx-android-application.gradle.kts][Pointx_android_application_gradle] script plugin.
 *
 * @see Pointx_android_application_gradle
 */
public
class PointxAndroidApplicationPlugin : org.gradle.api.Plugin<org.gradle.api.Project> {
    override fun apply(target: org.gradle.api.Project) {
        try {
            Class
                .forName("Pointx_android_application_gradle")
                .getDeclaredConstructor(org.gradle.api.Project::class.java, org.gradle.api.Project::class.java)
                .newInstance(target, target)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }
}
