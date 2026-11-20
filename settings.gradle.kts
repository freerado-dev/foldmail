pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "FoldMail"

include(":app")
include(":core-domain")
include(":core-designsystem")
// Planned modules (not created yet, see README):
// :core-database, :data-gmail, :data-imap, :data-smtp, :data-pop3,
// :feature-attachments, :feature-auth, :feature-sync, :feature-settings
