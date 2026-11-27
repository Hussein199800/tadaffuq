import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    id("org.cyclonedx.bom") version "2.3.1"
}

// رقم الـ commit يظهر في «حول» وفي أسماء الحزم (CI يمرره عبر GITHUB_SHA)
val gitSha: String = (System.getenv("GITHUB_SHA")?.take(7) ?: runCatching {
    providers.exec { commandLine("git", "rev-parse", "--short=7", "HEAD") }.standardOutput.asText.get().trim()
}.getOrNull())?.ifBlank { null } ?: "local"
val appVersionCode = 11
val appVersionName = "1.2.0"
base { archivesName.set("tadaffuq-$appVersionName-$appVersionCode-$gitSha") }

android {
    namespace = "com.tadaffuq.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tadaffuq.app"
        minSdk = 28
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    // نسختان من الشيفرة نفسها: GitHub (فيها التحديث من داخل التطبيق وإذن التثبيت) وPlay (بدونهما:
    // سياسة Google Play تمنع التحديث الذاتي خارج المتجر وإذن REQUEST_INSTALL_PACKAGES لهذا الغرض)
    flavorDimensions += "dist"
    productFlavors {
        create("github") {
            dimension = "dist"
            buildConfigField("boolean", "SELF_UPDATE", "true")
        }
        create("play") {
            dimension = "dist"
            buildConfigField("boolean", "SELF_UPDATE", "false")
        }
    }
    // APK لكل معمارية + APK شامل؛ وحزمة AAB تقسّم تلقائياً في Play
    splits {
        abi {
            // AAB يقسّم المعماريات بنفسه في Play، وAGP لا يسمح بالتقسيم أثناء بناء الحزمة (issuetracker 402800800)
            isEnable = gradle.startParameter.taskNames.none { it.contains("bundle", ignoreCase = true) }
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    // مفتاح التوقيع لا يُرفع أبداً: keystore.properties (مستثنى من git) أو متغيرات TADAFFUQ_*.
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    fun signing(key: String, env: String): String? = keystoreProps.getProperty(key) ?: System.getenv(env)
    val releaseStore = signing("storeFile", "TADAFFUQ_KEYSTORE")
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = rootProject.file(releaseStore)
                storePassword = signing("storePassword", "TADAFFUQ_KEYSTORE_PASSWORD")
                keyAlias = signing("keyAlias", "TADAFFUQ_KEY_ALIAS") ?: "tadaffuq"
                keyPassword = signing("keyPassword", "TADAFFUQ_KEY_PASSWORD") ?: signing("storePassword", "TADAFFUQ_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            versionNameSuffix = "-debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources { generateLocaleConfig = true }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2/")
                System.getenv("TADAFFUQ_SHOTS")?.let { dir -> it.environment("TADAFFUQ_SHOTS", dir) }
                it.maxHeapSize = "3g"
            }
        }
    }
    packaging { jniLibs { useLegacyPackaging = false }; resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF") } }
    dependenciesInfo { includeInApk = false; includeInBundle = false }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":data"))
    implementation(project(":torrent"))
    implementation(libs.libtorrent4j.arm64)
    implementation(libs.libtorrent4j.arm)
    implementation(libs.libtorrent4j.x64)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.work.runtime)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.documentfile)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.work.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.room.testing)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

// SBOM بصيغة CycloneDX لمكتبات النسخة المنشورة فقط
tasks.named<org.cyclonedx.gradle.CycloneDxTask>("cyclonedxBom") {
    setIncludeConfigs(listOf("githubReleaseRuntimeClasspath"))
    setProjectType("application")
    setOutputName("tadaffuq-sbom")
    setOutputFormat("json")
}
