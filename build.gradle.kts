// Top-level build file. Plugins are declared here (with apply false) so the
// :app module can apply them without each module re-resolving the classpath.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
}
