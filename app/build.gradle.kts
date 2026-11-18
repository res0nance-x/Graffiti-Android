import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import javax.inject.Inject
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

plugins {
	alias(libs.plugins.android.application)
}

val externalGraffitiCoreDir = File(
	providers.gradleProperty("graffitiCoreDir").getOrElse("D:/IdeaProjects/GraffitiCore")
)
val externalR3Dir = File(
	providers.gradleProperty("r3Dir").getOrElse("D:/IdeaProjects/R3")
)
val hasExternalSources = externalGraffitiCoreDir.isAbsolute &&
	externalGraffitiCoreDir.exists() &&
	externalR3Dir.isAbsolute &&
	externalR3Dir.exists()

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

	dependenciesInfo {
		includeInApk = false
		includeInBundle = false
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
				file("src/main/kotlin-graffiticore"),
				file("src/main/kotlin-r3")
			)
			assets.srcDirs(
				file("src/main/assets")
			)
		}
	}
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
	source(file("src/main/kotlin-graffiticore"))
	source(file("src/main/kotlin-r3"))
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

// Hook into release builds only when local external sources are present
if (hasExternalSources) {
	tasks.matching {
		(it.name.contains("assembleRelease") || it.name.contains("bundleRelease"))
	}.configureEach {
		dependsOn(autoIncrementTask)
	}
}

// Also allow manual trigger for testing
if (providers.gradleProperty("forceIncrement").isPresent) {
	tasks.named("preBuild") {
		dependsOn(autoIncrementTask)
	}
}

abstract class GenerateBuildDateTask : DefaultTask() {
	@get:Internal
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
	buildDateFile.set(externalGraffitiCoreDir.resolve("src/main/resources/web/build-date.txt"))
	val externalAvailable = hasExternalSources
	onlyIf { externalAvailable }
}

abstract class SyncExternalSourcesTask : DefaultTask() {
	@get:Inject
	abstract val fs: FileSystemOperations

	@get:Internal
	abstract val graffitiCoreSrcDir: DirectoryProperty

	@get:Internal
	abstract val r3SrcDir: DirectoryProperty

	@get:Internal
	abstract val webAssetsSrcDir: DirectoryProperty

	@get:Internal
	abstract val graffitiCoreDestDir: DirectoryProperty

	@get:Internal
	abstract val r3DestDir: DirectoryProperty

	@get:Internal
	abstract val webAssetsDestDir: DirectoryProperty

	@TaskAction
	fun sync() {
		fs.sync {
			from(graffitiCoreSrcDir)
			into(graffitiCoreDestDir)
		}
		fs.sync {
			from(r3SrcDir)
			into(r3DestDir)
		}
		fs.sync {
			from(webAssetsSrcDir)
			into(webAssetsDestDir)
		}
	}
}

val syncExternalSourcesTask = tasks.register<SyncExternalSourcesTask>("syncExternalSources") {
	graffitiCoreSrcDir.set(externalGraffitiCoreDir.resolve("src/main/kotlin"))
	r3SrcDir.set(externalR3Dir.resolve("src/main/kotlin"))
	webAssetsSrcDir.set(externalGraffitiCoreDir.resolve("src/main/resources/web"))
	graffitiCoreDestDir.set(layout.projectDirectory.dir("src/main/kotlin-graffiticore"))
	r3DestDir.set(layout.projectDirectory.dir("src/main/kotlin-r3"))
	webAssetsDestDir.set(layout.projectDirectory.dir("src/main/assets"))
	val externalAvailable = hasExternalSources
	onlyIf { externalAvailable }
	dependsOn(generateBuildDateTask)
}

tasks.named("preBuild") {
	dependsOn(syncExternalSourcesTask)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
	dependsOn(syncExternalSourcesTask)
}