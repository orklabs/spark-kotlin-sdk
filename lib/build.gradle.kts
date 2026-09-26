import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode
import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    // AGP 9.1+ has built-in Kotlin support — no longer apply kotlin-android.
    // See https://developer.android.com/build/migrate-to-built-in-kotlin
    alias(libs.plugins.protobuf)
    alias(libs.plugins.spotless)
    alias(libs.plugins.dokka)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
    `maven-publish`
    signing
}

// -----------------------------------------------------------------------------
// Kover — line coverage for this Android library module.
// Run `./gradlew :lib:koverHtmlReport` (HTML) or `:lib:koverXmlReport` (CI).
// We pin the Android variant explicitly so Kover doesn't have to guess between
// debug and release.
// -----------------------------------------------------------------------------
kover {
    reports {
        filters {
            excludes {
                packages(
                    "gy.pig.spark.frost.uniffi.*",
                    "uniffi.*",
                    "spark",
                    "spark_authn",
                    "spark_token",
                    "validate",
                )
                annotatedBy("kotlin.PublishedApi")
            }
        }
    }
}

// TODO(api-stability): kotlinx-binary-compatibility-validator 0.16.x does not
// auto-discover AGP 9.1's built-in Kotlin target (no separate `kotlin-android`
// plugin). Re-enable when BCV ships first-class AGP 9 support, or migrate to
// AndroidX `metalava` for `.api` snapshot checking. Until then, public-API
// drift is caught by explicit-API mode + Dokka diff + CODEOWNERS review.

// Coordinates and version are sourced from gradle.properties so a release script
// can bump them without touching this file.
val sdkGroupId: String = (findProperty("sdk.groupId") as String?) ?: "gy.pig"
val sdkArtifactId: String = (findProperty("sdk.artifactId") as String?) ?: "spark-kotlin-sdk"
val sdkVersion: String = (findProperty("sdk.version") as String?) ?: "0.1.0-SNAPSHOT"

group = sdkGroupId
version = sdkVersion

// -----------------------------------------------------------------------------
// Integration-test secret loader.
//
// Mnemonics and other live-network test inputs are loaded with this priority:
//   1. Gradle properties from `<repo>/local.properties` (gitignored), keys:
//        spark.test.walletA.mnemonic
//        spark.test.walletB.mnemonic
//        spark.test.lnAddress
//   2. Environment variables:
//        SPARK_TEST_WALLET_A_MNEMONIC
//        SPARK_TEST_WALLET_B_MNEMONIC
//        SPARK_TEST_LN_ADDRESS
//   3. Empty string — integration tests that need the value will skip via
//      JUnit's `Assume.assumeFalse(value.isEmpty(), ...)`.
//
// Nothing read here is ever logged or written to disk.
// -----------------------------------------------------------------------------
fun loadTestSecret(
    propertyKey: String,
    envVar: String,
): String {
    val localProps = Properties()
    val f = rootProject.file("local.properties")
    if (f.exists()) {
        f.inputStream().use { localProps.load(it) }
    }
    return localProps.getProperty(propertyKey)
        ?: System.getenv(envVar)
        ?: ""
}

val testWalletAMnemonic = loadTestSecret("spark.test.walletA.mnemonic", "SPARK_TEST_WALLET_A_MNEMONIC")
val testWalletBMnemonic = loadTestSecret("spark.test.walletB.mnemonic", "SPARK_TEST_WALLET_B_MNEMONIC")
val testLnAddress = loadTestSecret("spark.test.lnAddress", "SPARK_TEST_LN_ADDRESS")

android {
    namespace = "gy.pig.spark"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        // Integration-test secrets — injected into `gy.pig.spark.BuildConfig`
        // for the `androidTest` source set. Empty defaults make CI builds (which
        // never see real mnemonics) succeed; the tests themselves skip when the
        // value is empty. Never log these.
        buildConfigField("String", "SPARK_TEST_WALLET_A_MNEMONIC", "\"$testWalletAMnemonic\"")
        buildConfigField("String", "SPARK_TEST_WALLET_B_MNEMONIC", "\"$testWalletBMnemonic\"")
        buildConfigField("String", "SPARK_TEST_LN_ADDRESS", "\"$testLnAddress\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

// -----------------------------------------------------------------------------
// Kotlin — explicit API mode for the public surface.
// Generated UniFFI / protobuf code is excluded via the source-set filter below.
// -----------------------------------------------------------------------------
kotlin {
    explicitApi = ExplicitApiMode.Warning
}

// -----------------------------------------------------------------------------
// Spotless — ktlint for hand-written Kotlin; generated code is excluded.
// -----------------------------------------------------------------------------
spotless {
    kotlin {
        target("src/**/*.kt")
        targetExclude(
            // Generated UniFFI bindings — managed by build-frost-android.sh.
            "src/main/kotlin/gy/pig/spark/frost/**/*.kt",
            // Anything inside build/ (protobuf, Dokka, generated test sources).
            "**/build/**/*.kt",
        )
        ktlint(libs.versions.ktlint.get())
            .editorConfigOverride(
                mapOf(
                    "max_line_length" to "160",
                    // Filenames in this repo are descriptive rather than tied to the
                    // primary declaration (`SparkConfig.kt` defines several types).
                    "ktlint_standard_filename" to "disabled",
                    // protobuf `import gy.pig.spark.frost.uniffi.*` is intentional.
                    "ktlint_standard_no-wildcard-imports" to "disabled",
                    // Trailing commas are allowed and idiomatic, but enforcing them
                    // across every site would be churn. Keep the IDE-friendly hints
                    // on but don't fail CI over them.
                    "ktlint_standard_trailing-comma-on-call-site" to "disabled",
                    "ktlint_standard_trailing-comma-on-declaration-site" to "disabled",
                    "ij_kotlin_allow_trailing_comma" to "true",
                    "ij_kotlin_allow_trailing_comma_on_call_site" to "true",
                ),
            )
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
    }
    format("misc") {
        target("*.md", ".gitignore", ".editorconfig")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

// -----------------------------------------------------------------------------
// Dokka — API documentation. Output: lib/build/dokka/html/
//
// Dokka 1.x doesn't auto-discover Android library source sets — we register a
// "main" source set explicitly so the public surface under
// `lib/src/main/kotlin/` gets documented.
// -----------------------------------------------------------------------------
tasks.withType<org.jetbrains.dokka.gradle.DokkaTask>().configureEach {
    moduleName.set("spark-kotlin-sdk")

    dokkaSourceSets {
        register("main") {
            displayName.set("android")
            platform.set(org.jetbrains.dokka.Platform.jvm)
            sourceRoots.from(file("src/main/kotlin"))

            // Hide internal/generated packages from the published API surface.
            perPackageOption {
                matchingRegex.set("gy\\.pig\\.spark\\.frost(\\..*)?")
                suppress.set(true)
            }
            perPackageOption {
                matchingRegex.set("uniffi(\\..*)?")
                suppress.set(true)
            }
            perPackageOption {
                matchingRegex.set("spark(_authn|_token)?(\\..*)?")
                suppress.set(true)
            }

            includeNonPublic.set(false)
            reportUndocumented.set(false)
            skipEmptyPackages.set(true)
            jdkVersion.set(11)

            externalDocumentationLink {
                url.set(uri("https://kotlinlang.org/api/kotlinx.coroutines/").toURL())
            }
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.get()}"
        }
        create("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:${libs.versions.grpcKotlin.get()}:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
                create("kotlin") {
                    option("lite")
                }
            }
            task.plugins {
                create("grpc") {
                    option("lite")
                }
                create("grpckt") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // gRPC
    implementation(libs.grpc.okhttp)
    implementation(libs.grpc.protobuf.lite)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.kotlin.stub)

    // Protobuf
    implementation(libs.protobuf.kotlin.lite)

    // HTTP
    implementation(libs.okhttp)

    // Crypto
    implementation(libs.bouncycastle)

    // JNA (for UniFFI FROST bindings)
    implementation(libs.jna) { artifact { type = "aar" } }

    // Testing
    testImplementation(libs.junit)
    // Plain JNA jar for JVM unit tests: it carries the desktop libjnidispatch the aar lacks
    // (only used when SPARK_FROST_HOST_LIBRARY is set, see below).
    testImplementation(libs.jna)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// -----------------------------------------------------------------------------
// Optional host FROST library for JVM unit tests.
//
// A few unit tests exercise the UniFFI FROST bindings (connector-refund sighashes,
// VSS share mapping, ECIES). The bundled .so files are Android-only, so those tests
// are skipped unless SPARK_FROST_HOST_LIBRARY points at a spark-frost build for the
// host machine (e.g. a libspark_frost.dylib built from buildonspark/spark's
// signer/spark-frost-uniffi at the same revision as the bundled bindings).
// -----------------------------------------------------------------------------
tasks.withType<Test>().configureEach {
    val hostFrostLibrary = System.getenv("SPARK_FROST_HOST_LIBRARY")
    if (!hostFrostLibrary.isNullOrBlank()) {
        systemProperty("uniffi.component.spark_frost.libraryOverride", hostFrostLibrary)
    }
}

// -----------------------------------------------------------------------------
// Detekt — static analysis. Run: `./gradlew :lib:detekt`.
// Reports under `lib/build/reports/detekt/`.
// -----------------------------------------------------------------------------
detekt {
    toolVersion = libs.versions.detekt.get()
    buildUponDefaultConfig = true
    allRules = false
    autoCorrect = false
    parallel = true
    source.setFrom(files("src/main/kotlin", "src/test/kotlin", "src/androidTest/kotlin"))
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    baseline = file("$projectDir/config/detekt/baseline.xml")
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    exclude("gy/pig/spark/frost/**")
    exclude("**/generated/**")
    reports {
        html.required.set(true)
        xml.required.set(true)
        sarif.required.set(true)
        md.required.set(false)
        txt.required.set(false)
    }
    jvmTarget = "11"
}

tasks.withType<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>().configureEach {
    jvmTarget = "11"
}

// -----------------------------------------------------------------------------
// Reproducible builds — strip timestamps + sort entries inside the AAR and the
// sources / javadoc JARs so byte-identical inputs produce byte-identical outputs.
// -----------------------------------------------------------------------------
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    dirPermissions { unix("755") }
    filePermissions { unix("644") }
}

// -----------------------------------------------------------------------------
// Publishing — Maven Central via Sonatype OSSRH.
//
// Required gradle properties (typically in ~/.gradle/gradle.properties):
//   ossrhUsername=...
//   ossrhPassword=...
//   signing.keyId=...
//   signing.password=...
//   signing.secretKeyRingFile=/abs/path/to/secring.gpg
//
// Run locally:        ./gradlew :lib:publishToMavenLocal
// Stage to Sonatype:  ./gradlew :lib:publishReleasePublicationToOssrhRepository
// -----------------------------------------------------------------------------
// Publish POM-only metadata — no Gradle Module Metadata (.module). GMM strips
// the `artifact { type = "aar" }` selector off the jna dependency, so consumers
// resolving through GMM pull the plain jna JAR (no libjnidispatch.so) and every
// UniFFI/FROST native call dies at runtime with
// `NoClassDefFoundError: com.sun.jna.Native` (observed live in piggy-android:
// claims/withdraws crashed until the app added an explicit jna@aar dependency).
// The POM correctly records `<type>aar</type>`, which Gradle honors — disabling
// GMM makes every consumer resolve the aar. Consumers are still advised to
// declare jna@aar explicitly (JNA's own guidance for Android apps).
tasks.withType<GenerateModuleMetadata>().configureEach {
    enabled = false
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId = sdkGroupId
                artifactId = sdkArtifactId
                version = sdkVersion

                pom {
                    name.set("spark-kotlin-sdk")
                    description.set(
                        "Kotlin / Android SDK for the Spark protocol — self-custodial " +
                            "Bitcoin wallets powered by threshold FROST signing.",
                    )
                    url.set("https://github.com/p-i-g-g-y/spark-kotlin-sdk")

                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                            distribution.set("repo")
                        }
                    }

                    developers {
                        developer {
                            id.set("piggy")
                            name.set("Piggy")
                            email.set("gm@orklabs.com")
                        }
                    }

                    scm {
                        connection.set("scm:git:git://github.com/p-i-g-g-y/spark-kotlin-sdk.git")
                        developerConnection.set("scm:git:ssh://github.com:p-i-g-g-y/spark-kotlin-sdk.git")
                        url.set("https://github.com/p-i-g-g-y/spark-kotlin-sdk")
                    }

                    issueManagement {
                        system.set("GitHub")
                        url.set("https://github.com/p-i-g-g-y/spark-kotlin-sdk/issues")
                    }
                }
            }
        }

        repositories {
            maven {
                name = "ossrh"
                val releasesUrl = "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
                val snapshotsUrl = "https://s01.oss.sonatype.org/content/repositories/snapshots/"
                url = uri(if (sdkVersion.endsWith("SNAPSHOT")) snapshotsUrl else releasesUrl)
                credentials {
                    username = (findProperty("ossrhUsername") as String?) ?: ""
                    password = (findProperty("ossrhPassword") as String?) ?: ""
                }
            }
        }
    }

    signing {
        // Only sign release builds when signing credentials are configured.
        val signingKeyId = findProperty("signing.keyId") as String?
        isRequired = signingKeyId != null && !sdkVersion.endsWith("SNAPSHOT")
        if (isRequired) {
            sign(publishing.publications["release"])
        }
    }
}
