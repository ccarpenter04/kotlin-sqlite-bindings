/*
 * Copyright 2020 Google, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.birbit.ksqlite.build.internal

import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.Project
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Exec
import org.gradle.kotlin.dsl.get
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform
import java.io.File

internal object AndroidSetup {
    private const val DOWNLOAD_CMD_LINE_TOOLS_TASK = "downloadAndroidCommandLineTools"
    private const val DOWNLOAD_NDK_TASK = "downloadNdk"
    fun configure(project: Project) {
        val androidLibrary: LibraryExtension = project.extensions
            .findByType(LibraryExtension::class.java)
            ?: error("cannot find library extension on $project")
        val androidComponents = project.extensions
            .findByType(AndroidComponentsExtension::class.java)
            ?: error("cannot find android components extension")
        androidComponents.beforeVariants {
            it.enableUnitTest = false
        }
        androidLibrary.compileSdk = 29
        androidLibrary.defaultConfig.let {
            it.minSdk = 21
            it.targetSdk = 29
            it.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            it.ndk.abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
        androidLibrary.sourceSets {
            getByName("androidTest").java
                .srcDir(project.file("src/androidTest/kotlin"))
        }
        androidLibrary.ndkVersion = "21.3.6528147"
        val debugSigningConfig = androidLibrary.signingConfigs.getByName("debug")
        // Use a local debug keystore to have reproducible test apks
        debugSigningConfig.storeFile = project.getDebugKeystore()
        androidLibrary.buildTypes.all { buildType ->
            // Sign all the builds (including release) with debug key
            buildType.signingConfig = debugSigningConfig
        }

        createInstallNdkTask(project)
    }

    fun createInstallNdkTask(project: Project) {
        // find a reference to android
        val exists = project.rootProject.tasks.findByName(DOWNLOAD_NDK_TASK)
        if (exists != null) {
            return
        }
        val androidComponents = project.extensions.findByType(LibraryAndroidComponentsExtension::class.java)
            ?: return
        val androidLibraryExt = project.extensions.findByType(LibraryExtension::class.java)
            ?: return

        val rootProject = project.rootProject
        val buildDir = rootProject.buildDir.resolve("android-cmd-line-tools")
        val toolsZip = buildDir.resolve("tools.zip")
        val downloadTask = rootProject.tasks.register("downloadAndroidCmdLineTools", DownloadTask::class.java) {
            it.downloadUrl = buildCommandLineToolsUrl()
            it.downloadTargetFile = toolsZip
        }
        val cmdLineToolsFolder = buildDir.resolve("tools")
        val unzipCommandLineToolsTask = rootProject.tasks.register("unzipCommandLineTools", Copy::class.java) {
            it.from(project.zipTree(toolsZip))
            it.into(cmdLineToolsFolder)
            it.dependsOn(downloadTask)
        }
        rootProject.tasks.register("downloadNdk", Exec::class.java) {
            val os = DefaultNativePlatform.getCurrentOperatingSystem()
            val ext = if (os.isWindows) {
                ".bat"
            } else {
                ""
            }
            val sdkPath = androidComponents.sdkComponents.sdkDirectory.get().asFile.absolutePath
            if (os.isLinux) {
                it.doFirst {
                    Runtime.getRuntime().exec("sudo chown \$USER:\$USER $sdkPath -R")
                }
            }
            it.executable(cmdLineToolsFolder.resolve("tools/bin/sdkmanager$ext"))
            it.args("--install", "ndk;${androidLibraryExt.ndkVersion}", "--verbose")
            it.args("--sdk_root=$sdkPath")
            // pass y to accept licenses
            it.standardInput = "y".byteInputStream(Charsets.UTF_8)
            it.dependsOn(unzipCommandLineToolsTask)
        }
    }

    private fun Project.getDebugKeystore(): File {
        return rootProject.rootDir.resolve("keystore/debug.keystore").also {
            check(it.exists()) {
                "Cannot find keystore file in path: ${it.absolutePath}"
            }
        }
    }

    private fun buildCommandLineToolsUrl(): String {
        val os = DefaultNativePlatform.getCurrentOperatingSystem()
        val osKey = when {
            os.isWindows -> "win"
            os.isLinux -> "linux"
            os.isMacOsX -> "mac"
            else -> error("unsupported build OS: ${os.displayName}")
        }
        return "https://dl.google.com/android/repository/commandlinetools-$osKey-6514223_latest.zip"
    }
}
