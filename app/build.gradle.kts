import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    alias(libs.plugins.android.application)
    // Baseline profile plugin disabled for F-Droid reproducible builds
    // alias(libs.plugins.baseline.profile)
    alias(libs.plugins.detekt)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
}

android {
    compileSdk = 34
    namespace = "com.huntercoles.splatman"

    defaultConfig {
        applicationId = "com.huntercoles.splatman"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.0.1"
        testInstrumentationRunner = "com.huntercoles.splatman.HiltTestRunner"
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    signingConfigs {
        // Debug signing (always available)
        getByName("debug")

        // Production signing (only if keystore exists and properties are set)
        val storeFile = project.findProperty("RELEASE_STORE_FILE") as String?
        val storePassword = project.findProperty("RELEASE_STORE_PASSWORD") as String?
        val keyAlias = project.findProperty("RELEASE_KEY_ALIAS") as String?
        val keyPassword = project.findProperty("RELEASE_KEY_PASSWORD") as String?

        if (storeFile != null && storePassword != null && keyAlias != null && keyPassword != null) {
            create("release") {
                this.storeFile = project.file(storeFile)
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            // proguardFiles(
            //     getDefaultProguardFile("proguard-android.txt"),
            //     "proguard-rules.pro"
            // )
            // Use production signing if available, otherwise use debug signing for development
            signingConfig = if (signingConfigs.names.contains("release")) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "assets/dexopt/baseline.prof"
            excludes += "assets/dexopt/baseline.profm"
            excludes += "**/baseline.prof"
            excludes += "**/baseline.profm"
            excludes += "META-INF/version-control-info.textproto"
        }
    }

    applicationVariants.all {
        val variantName = name
        val variantVersion = versionName ?: "unspecified"
        outputs
            .mapNotNull { it as? BaseVariantOutputImpl }
            .forEach { output ->
                output.outputFileName = "Splatman-v$variantVersion-$variantName.apk"
            }
    }
}

configurations {
    all {
        exclude(group = "androidx.profileinstaller", module = "profileinstaller")
    }
}

androidComponents {
    onVariants { variant ->
        // Transform merged assets to drop baseline profile artifacts before packaging/signing.
        val taskProvider = tasks.register(
            "strip${variant.name.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase(Locale.US) else ch.toString() }}BaselineProfiles",
            StripBaselineProfilesTask::class.java
        )

        variant.artifacts
            .use(taskProvider)
            .wiredWithDirectories(
                StripBaselineProfilesTask::inputDir,
                StripBaselineProfilesTask::outputDir
            )
            .toTransform(SingleArtifact.ASSETS)

        // About > Open-source licences reads this list of the variant's dependencies (no runtime dependency).
        val capitalised = variant.name.replaceFirstChar { it.titlecase(Locale.US) }
        val runtime = configurations.named("${variant.name}RuntimeClasspath")
        val licences = tasks.register("generate${capitalised}Licences", GenerateLicencesTask::class.java) {
            rows.set(provider { licenceRows(runtime.get()) })
        }
        variant.sources.assets?.addGeneratedSourceDirectory(licences, GenerateLicencesTask::outputDir)
    }
}

tasks.configureEach {
    // Disable ArtProfile task graph entirely to avoid generating non-deterministic baseline files.
    if (name.contains("ArtProfile")) {
        enabled = false
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":capture-feature"))
    implementation(project(":library-feature"))
    implementation(project(":viewer-feature"))

    implementation(libs.hilt)
    implementation(libs.navigation) // needed for Room
    implementation(libs.room.ktx)
    implementation(libs.timber)

    // Baseline profiles disabled for F-Droid reproducible builds
    // implementation(libs.test.android.profile.installer)
    // baselineProfile(project(":baseline-profiles"))

    ksp(libs.hilt.compiler)
    ksp(libs.room.compiler)

    // tools/e2e.sh: the device tests (app/src/androidTest), on Hilt's test application (#120: fake tracker)
    androidTestImplementation(project(":splat-io"))
    androidTestImplementation(libs.hilt.testing)
    kspAndroidTest(libs.hilt.compiler)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.test.android.compose)
    androidTestImplementation(libs.test.android.junit)
    androidTestImplementation(libs.test.android.runner)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

abstract class StripBaselineProfilesTask : DefaultTask() {
    @get:InputDirectory
    abstract val inputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun strip() {
        val output = outputDir.asFile.get()
        project.delete(output)
        project.copy {
            from(inputDir)
            into(output)
            exclude("**/dexopt/baseline.prof", "**/dexopt/baseline.profm")
        }
    }
}

/** Writes `licences.tsv` (module, version, licence, licence URL; one dependency a line) into generated assets. */
abstract class GenerateLicencesTask : DefaultTask() {
    @get:Input
    abstract val rows: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun write() {
        val dir = outputDir.asFile.get()
        dir.mkdirs()
        File(dir, "licences.tsv").writeText(rows.get().joinToString(separator = "\n", postfix = "\n"))
    }
}

/** The licence of every external module on [runtime], read from its POM or, failing that, its parents' POMs. */
fun licenceRows(runtime: Configuration): List<String> {
    val ids = runtime.incoming.resolutionResult.allComponents
        .mapNotNull { it.id as? ModuleComponentIdentifier }
    val poms = dependencies.createArtifactResolutionQuery()
        .forComponents(ids)
        .withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java)
        .execute()
        .resolvedComponents
        .associate { component ->
            component.id to component.getArtifacts(MavenPomArtifact::class.java)
                .filterIsInstance<ResolvedArtifactResult>()
                .firstOrNull()?.file
        }
    val parents = mutableMapOf<String, File?>()
    return ids.map { id ->
        val (name, url) = poms[id]?.let { licenceOf(it, parents, depth = 0) } ?: ("Not stated" to "")
        listOf("${id.group}:${id.module}", id.version, name, url).joinToString("\t") { it.replace('\t', ' ').trim() }
    }.distinct().sorted()
}

val maxParentDepth = 4

/** The first licence (name, URL) in [pom], else in its parent POM, up to [maxParentDepth] parents up. */
fun licenceOf(pom: File, parents: MutableMap<String, File?>, depth: Int): Pair<String, String>? {
    val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom).documentElement
    fun Element.child(tag: String): Element? = (0 until childNodes.length)
        .map { childNodes.item(it) }
        .filterIsInstance<Element>()
        .firstOrNull { it.tagName == tag }
    val licence = root.child("licenses")?.child("license")
    if (licence != null) {
        val name = licence.child("name")?.textContent?.trim().orEmpty()
        val url = licence.child("url")?.textContent?.trim().orEmpty()
        if (name.isNotEmpty() || url.isNotEmpty()) return name.ifEmpty { url } to url
    }
    val parent = root.child("parent") ?: return null
    if (depth >= maxParentDepth) return null
    val coordinates = listOf("groupId", "artifactId", "version").map { parent.child(it)?.textContent?.trim() }
    if (coordinates.any { it.isNullOrEmpty() }) return null
    val key = coordinates.joinToString(":")
    val parentPom = parents.getOrPut(key) {
        runCatching {
            configurations.detachedConfiguration(dependencies.create("$key@pom")).apply { isTransitive = false }
                .singleFile
        }.getOrNull()
    }
    return parentPom?.let { licenceOf(it, parents, depth + 1) }
}
