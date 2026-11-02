pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral(); maven(url = "https://jitpack.io") } }
rootProject.name = "claude-droid"
include(":app")
