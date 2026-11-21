plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}


val generatedMegpoidAssets = layout.buildDirectory.dir("generated/megpoidAssets")
val prepareMegpoidModelAssets by tasks.registering {
    val bankArchive = rootProject.file("megpoidV7.zip")
    val sharedArchive = rootProject.file("AI Model.zip")
    inputs.files(bankArchive, sharedArchive)
    outputs.dir(generatedMegpoidAssets)

    doLast {
        check(bankArchive.isFile) { "Required bundled archive is missing: ${bankArchive.absolutePath}" }
        check(sharedArchive.isFile) { "Required bundled archive is missing: ${sharedArchive.absolutePath}" }

        val outputDir = generatedMegpoidAssets.get().dir("megpoid-models").asFile
        outputDir.deleteRecursively()
        check(outputDir.mkdirs()) { "Could not create generated Megpoid model asset directory." }

        copy {
            from(zipTree(bankArchive))
            include("**/AI_Megpoid.vtb2", "**/AI_Megpoid.vpit")
            eachFile { path = name }
            includeEmptyDirs = false
            into(outputDir)
        }
        copy {
            from(zipTree(sharedArchive))
            include("**/COMMON7.vpit", "**/VOCALOID7.vhnv", "**/VOCALOID7.vtmg")
            eachFile { path = name }
            includeEmptyDirs = false
            into(outputDir)
        }

        val required = listOf(
            "AI_Megpoid.vtb2", "AI_Megpoid.vpit", "COMMON7.vpit",
            "VOCALOID7.vhnv", "VOCALOID7.vtmg"
        )
        val missing = required.filterNot { File(outputDir, it).isFile }
        check(missing.isEmpty()) { "Megpoid model archives are missing required members: ${missing.joinToString()}" }
    }
}

android.sourceSets.getByName("main").assets.srcDir(generatedMegpoidAssets)
tasks.named("preBuild").configure {
    dependsOn(prepareMegpoidModelAssets)
}

android {
    namespace = "com.kmj.vea"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kmj.vea"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    ndkVersion = "27.2.12479018"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
