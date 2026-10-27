import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

plugins {
	alias(libs.plugins.android.application)
}

android {
	namespace = "r3.graffiti"
	compileSdk = 37

	val versionPropsFile = file("version.properties")
	val versionProps = Properties()
	if (versionPropsFile.exists()) {
		versionPropsFile.inputStream().use { versionProps.load(it) }
	}
	val buildNumber = (versionProps.getProperty("BUILD_NUMBER") ?: "1").toInt()

	defaultConfig {
		applicationId = "r3.graffiti"
		minSdk = 33
		targetSdk = 37
		versionCode = buildNumber
		versionName = buildNumber.toString()

		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
	}

	buildTypes {
		release {
			isMinifyEnabled = false
			isShrinkResources = false
			ndk {
				debugSymbolLevel = "FULL"
			}
		}
	}
	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_11
		targetCompatibility = JavaVersion.VERSION_11
	}

	sourceSets {
		getByName("main") {
			java.srcDirs(
				file("src/main/java"),
				file("D:/IdeaProjects/GraffitiCore/src/main/kotlin"),
				file("D:/IdeaProjects/R3/src/main/kotlin")
			)
			assets.srcDirs(
				file("src/main/assets"),
				file("D:/IdeaProjects/GraffitiCore/src/main/resources/web")
			)
		}
	}
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
	source(file("D:/IdeaProjects/GraffitiCore/src/main/kotlin"))
	source(file("D:/IdeaProjects/R3/src/main/kotlin"))
}

dependencies {
	//noinspection UseTomlInstead
	implementation("androidx.browser:browser:1.10.0")
	implementation(libs.androidx.activity)
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.lifecycle.runtime.ktx)
	testImplementation(libs.junit)
	androidTestImplementation(libs.androidx.espresso.core)
	androidTestImplementation(libs.androidx.junit)
}

abstract class AutoIncrementBuildNumberTask : DefaultTask() {
	@get:OutputFile
	abstract val versionPropertiesFile: RegularFileProperty

	@TaskAction
	fun increment() {
		val file = versionPropertiesFile.get().asFile
		val versionProps = Properties()
		if (file.exists()) {
			file.inputStream().use { versionProps.load(it) }
		}
		val currentBuild = (versionProps.getProperty("BUILD_NUMBER") ?: "1").toInt()
		val nextBuild = currentBuild + 1
		versionProps.setProperty("BUILD_NUMBER", nextBuild.toString())
		file.outputStream().use { versionProps.store(it, null) }
		println("Build number incremented to: $nextBuild")
	}
}

val autoIncrementTask = tasks.register<AutoIncrementBuildNumberTask>("autoIncrementBuildNumber") {
	versionPropertiesFile.set(layout.projectDirectory.file("version.properties"))
}

// Hook into release builds
tasks.matching {
	(it.name.contains("assembleRelease") || it.name.contains("bundleRelease"))
}.configureEach {
	dependsOn(autoIncrementTask)
}

// Also allow manual trigger for testing
if (providers.gradleProperty("forceIncrement").isPresent) {
	tasks.named("preBuild") {
		dependsOn(autoIncrementTask)
	}
}

abstract class GenerateBuildDateTask : DefaultTask() {
	@get:OutputFile
	abstract val buildDateFile: RegularFileProperty

	@TaskAction
	fun generate() {
		val file = buildDateFile.get().asFile
		val now = ZonedDateTime.now()
		val formatted = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd:HH"))
		if (!file.exists() || file.readText().trim() != formatted) {
			file.parentFile?.mkdirs()
			file.writeText("$formatted\n")
			println("Updated build-date.txt to: $formatted")
		}
	}
}

val generateBuildDateTask = tasks.register<GenerateBuildDateTask>("generateBuildDate") {
	buildDateFile.set(file("D:/IdeaProjects/GraffitiCore/src/main/resources/web/build-date.txt"))
}

tasks.named("preBuild") {
	dependsOn(generateBuildDateTask)
}