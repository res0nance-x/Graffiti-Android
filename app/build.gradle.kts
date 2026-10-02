import java.util.Properties
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlin.compose)
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
		minSdk = 35
		targetSdk = 37
		versionCode = buildNumber
		versionName = buildNumber.toString()

		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
	}

	buildTypes {
		release {
			isMinifyEnabled = true
			isShrinkResources = true
			proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
			ndk {
				debugSymbolLevel = "FULL"
			}
		}
	}
	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_11
		targetCompatibility = JavaVersion.VERSION_11
	}
	buildFeatures {
		compose = true
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
	implementation(platform(libs.androidx.compose.bom))
	implementation(libs.androidx.activity.compose)
	implementation(libs.androidx.compose.material3)
	implementation(libs.androidx.compose.ui)
	implementation(libs.androidx.compose.ui.graphics)
	implementation(libs.androidx.compose.ui.tooling.preview)
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.lifecycle.runtime.ktx)
	testImplementation(libs.junit)
	androidTestImplementation(libs.androidx.compose.ui.test.junit4)
	androidTestImplementation(libs.androidx.espresso.core)
	androidTestImplementation(libs.androidx.junit)
	debugImplementation(libs.androidx.compose.ui.test.manifest)
	debugImplementation(libs.androidx.compose.ui.tooling)
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