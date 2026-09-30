// Top-level build file for the Timeline Visualizer MVP.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    // Phase 8: Room's annotation processor runs through KSP.
    id("com.google.devtools.ksp") version "2.0.21-1.0.25" apply false
}
