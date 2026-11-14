pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Xposed API (compileOnly; the framework provides it at runtime).
        maven("https://api.xposed.info/")
    }
}
rootProject.name = "AgentVScreen"
include(":app")
