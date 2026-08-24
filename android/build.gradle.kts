plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

val lintFixRequested = gradle.startParameter.taskNames.any { taskName ->
    taskName == "lintFix" || taskName.endsWith(":lintFix")
}

val lintFixTask = tasks.register("lintFix") {
    group = "verification"
    description = "Formats Kotlin sources and auto-corrects supported Detekt findings."
}

subprojects {
    plugins.withId("org.jlleitschuh.gradle.ktlint") {
        lintFixTask.configure {
            dependsOn(tasks.named("ktlintFormat"))
        }
    }

    plugins.withId("io.gitlab.arturbosch.detekt") {
        tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
            autoCorrect = lintFixRequested
        }

        lintFixTask.configure {
            dependsOn(tasks.named("detekt"))
        }
    }
}
