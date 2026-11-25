plugins {
    alias(libs.plugins.kotlin.jvm)
}
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

val nativeTest by configurations.creating

dependencies {
    api(libs.libtorrent4j)
    api(libs.coroutines.core)
    testImplementation(libs.junit)
    nativeTest(libs.libtorrent4j.linux)
}

// المكتبة الأصلية لـ Linux x86_64 تُستخرج للاختبارات فقط
val extractNative by tasks.registering(Copy::class) {
    from({ nativeTest.map { zipTree(it) } }) { include("lib/x86_64/*.so") }
    into(layout.buildDirectory.dir("native"))
}
tasks.test {
    dependsOn(extractNative)
    systemProperty("libtorrent4j.jni.path", layout.buildDirectory.file("native/lib/x86_64/libtorrent4j.so").get().asFile.absolutePath)
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
