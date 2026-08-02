import java.util.Properties

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
		versionProps.load(versionPropsFile.inputStream())
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

tasks.register("autoIncrementBuildNumber") {
	doLast {
		val versionPropsFile = file("version.properties")
		val versionProps = Properties()
		if (versionPropsFile.exists()) {
			versionProps.load(versionPropsFile.inputStream())
		}
		val currentBuild = (versionProps.getProperty("BUILD_NUMBER") ?: "1").toInt()
		versionProps.setProperty("BUILD_NUMBER", (currentBuild + 1).toString())
		versionProps.store(versionPropsFile.outputStream(), null)
		println("Build number incremented to: ${currentBuild + 1}")
	}
}

// Hook into release builds
tasks.matching {
	(it.name.contains("assembleRelease") || it.name.contains("bundleRelease"))
}.all {
	dependsOn("autoIncrementBuildNumber")
}

// Also allow manual trigger for testing
if (project.hasProperty("forceIncrement")) {
	tasks.named("preBuild") {
		dependsOn("autoIncrementBuildNumber")
	}
}