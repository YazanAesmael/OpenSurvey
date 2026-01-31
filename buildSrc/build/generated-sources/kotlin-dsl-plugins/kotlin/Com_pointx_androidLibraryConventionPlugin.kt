/**
 * Precompiled [com.pointx.android-library-convention.gradle.kts][Com_pointx_android_library_convention_gradle] script plugin.
 *
 * @see Com_pointx_android_library_convention_gradle
 */
public
class Com_pointx_androidLibraryConventionPlugin : org.gradle.api.Plugin<org.gradle.api.Project> {
    override fun apply(target: org.gradle.api.Project) {
        try {
            Class
                .forName("Com_pointx_android_library_convention_gradle")
                .getDeclaredConstructor(org.gradle.api.Project::class.java, org.gradle.api.Project::class.java)
                .newInstance(target, target)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }
}
