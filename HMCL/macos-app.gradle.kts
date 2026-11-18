// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL
//
// macOS arm64 .app / .dmg / .pkg packaging for HMCL.
//
// Opt-in: run `./gradlew :HMCL:makeDmg :HMCL:makePkg` on an arm64 macOS host
// with a JDK 17+ that provides jpackage. NOT wired into `build`, because
// jpackage cannot cross-compile: the native launcher always targets the
// architecture of the JDK running Gradle.

//// JavaFX modules bundled into the app runtime.
//// HMCL treats JavaFX as compileOnly and normally self-downloads it at first
//// launch. Bundling the arm64 modules here makes the .app fully offline.
val jfxArm64 by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    val jfxVersion = "25"
    jfxArm64("org.openjfx:javafx-base:$jfxVersion:mac-aarch64")
    jfxArm64("org.openjfx:javafx-graphics:$jfxVersion:mac-aarch64")
    jfxArm64("org.openjfx:javafx-controls:$jfxVersion:mac-aarch64")
}

//// Application display metadata.
//// The GPLv3 additional terms require modified builds to change the name or
//// version; override these with -PmacAppName / -PmacAppIdentifier when needed.
val appName = (findProperty("macAppName") as String?) ?: "HMCL"
val appVersionRaw = project.version.toString()
// jpackage requires app-version to be 1-3 dot-separated integers. Project
// versions like "3.18.SNAPSHOT" or "3.18.dev-abc1234" are normalized here.
val appVersion = run {
    val numeric = appVersionRaw.split('.').mapNotNull { part ->
        val leading = part.takeWhile { it.isDigit() }
        leading.toIntOrNull()
    }
    val padded = (numeric + List(3 - numeric.size) { 0 }).take(3)
    if (padded.isEmpty()) "1.0.0" else padded.joinToString(".")
}
val appIdentifier = (findProperty("macAppIdentifier") as String?) ?: "org.jackhuang.hmcl"

//// Shared output directory for all macOS artifacts.
val macOutDir = layout.buildDirectory.dir("macos")
val appBundle = macOutDir.map { it.dir("$appName.app") }

//// Reflective access flags carried by the fat-jar Manifest.
val addOpens = listOf(
    "java.base/java.lang", "java.base/java.lang.reflect",
    "java.base/jdk.internal.loader",
    "javafx.base/com.sun.javafx.binding", "javafx.base/com.sun.javafx.event",
    "javafx.base/com.sun.javafx.runtime", "javafx.base/javafx.beans.property",
    "javafx.graphics/javafx.css", "javafx.graphics/javafx.stage",
    "javafx.graphics/javafx.scene", "javafx.graphics/com.sun.glass.ui",
    "javafx.graphics/com.sun.javafx.stage", "javafx.graphics/com.sun.javafx.util",
    "javafx.graphics/com.sun.prism",
    "javafx.controls/com.sun.javafx.scene.control",
    "javafx.controls/com.sun.javafx.scene.control.behavior",
    "javafx.graphics/com.sun.javafx.tk.quantum",
    "javafx.controls/javafx.scene.control",
    "javafx.controls/javafx.scene.control.skin",
    "jdk.attach/sun.tools.attach",
)
val javaOptions = addOpens.flatMap { listOf("--java-options", "--add-opens=$it=ALL-UNNAMED") }

//// Generates app.icns from the bundled 512x512 RGBA icon via sips + iconutil.
//// Implemented as a doLast on a plain task using ProcessBuilder, because
//// Gradle 9 removed Project.exec() and script plugins have no ExecOperations.
val generateIcns = tasks.register("generateMacIcns") {
    group = "macos"
    description = "Generates app.icns from the bundled macOS icon PNG."

    val sourceIcon = layout.projectDirectory.file("src/main/resources/assets/img/icon-mac.png")
    val icnsFile = macOutDir.map { it.file("app.icns") }
    val iconsetDir = macOutDir.map { it.dir("app.iconset") }

    inputs.file(sourceIcon)
    outputs.file(icnsFile)

    doLast {
        val setDir = iconsetDir.get().asFile
        val out = icnsFile.get().asFile
        setDir.mkdirs()
        out.parentFile.mkdirs()

        fun run(vararg cmd: String) {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val log = p.inputStream.bufferedReader().readText()
            if (p.waitFor() != 0) throw GradleException("Command failed: ${cmd.joinToString(" ")}\n$log")
        }

        for (size in listOf(16, 32, 128, 256, 512)) {
            run("sips", "-z", size.toString(), size.toString(),
                sourceIcon.asFile.absolutePath, "--out", File(setDir, "icon_${size}x${size}.png").absolutePath)
            run("sips", "-z", (size * 2).toString(), (size * 2).toString(),
                sourceIcon.asFile.absolutePath, "--out", File(setDir, "icon_${size}x${size}@2x.png").absolutePath)
        }
        run("iconutil", "-c", "icns", setDir.absolutePath, "-o", out.absolutePath)
    }
}

//// Builds the arm64 .app with JavaFX bundled into its runtime image.
val makeMacApp = tasks.register("makeMacApp") {
    group = "macos"
    description = "Builds the macOS arm64 HMCL.app using jpackage."

    dependsOn(tasks.named("shadowJar"), generateIcns, jfxArm64)

    val jarProvider = tasks.named<Jar>("shadowJar").flatMap { it.archiveFile }
    val icnsProvider = macOutDir.map { it.file("app.icns") }

    inputs.files(jarProvider, icnsProvider, jfxArm64)
    outputs.dir(appBundle)

    doLast {
        // jpackage refuses to overwrite an existing app-image directory.
        val existingApp = appBundle.get().asFile
        if (existingApp.exists()) existingApp.deleteRecursively()

        val jar = jarProvider.get().asFile
        val icns = icnsProvider.get().asFile
        val modulePath = jfxArm64.files.joinToString(File.pathSeparator) { it.absolutePath }

        val args = buildList {
            addAll(listOf("--type", "app-image"))
            addAll(listOf("--name", appName))
            addAll(listOf("--app-version", appVersion))
            // --identifier is only valid for dmg/pkg, not app-image.
            addAll(listOf("--input", jar.parentFile.absolutePath))
            addAll(listOf("--main-jar", jar.name))
            addAll(listOf("--main-class", "org.jackhuang.hmcl.EntryPoint"))
            addAll(listOf("--icon", icns.absolutePath))
            addAll(listOf("--dest", macOutDir.get().asFile.absolutePath))
            addAll(listOf("--mac-package-name", appName))
            addAll(javaOptions)
            addAll(listOf("--module-path", modulePath))
            addAll(listOf("--add-modules", "javafx.base,javafx.graphics,javafx.controls"))
        }

        val p = ProcessBuilder(listOf("jpackage") + args).redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText()
        println(log)
        if (p.waitFor() != 0) throw GradleException("jpackage (app-image) failed")
    }
}

//// Packages the .app into a drag-and-drop .dmg.
val makeDmg = tasks.register("makeDmg") {
    group = "macos"
    description = "Packages the macOS arm64 app into a .dmg."

    dependsOn(makeMacApp)

    val dmgFile = macOutDir.map { it.file("$appName-$appVersion-arm64.dmg") }
    outputs.file(dmgFile)

    doLast {
        val args = listOf(
            "jpackage", "--type", "dmg",
            "--name", appName,
            "--app-version", appVersion,
            // In --app-image mode the identifier is inherited from the app's
            // Info.plist; jpackage rejects an explicit --identifier here.
            "--app-image", appBundle.get().asFile.absolutePath,
            "--dest", macOutDir.get().asFile.absolutePath
        )
        val p = ProcessBuilder(args).redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText()
        println(log)
        if (p.waitFor() != 0) throw GradleException("jpackage (dmg) failed")
    }
}

//// Packages the .app into an installer .pkg.
val makePkg = tasks.register("makePkg") {
    group = "macos"
    description = "Packages the macOS arm64 app into an installer .pkg."

    dependsOn(makeMacApp)

    val pkgFile = macOutDir.map { it.file("$appName-$appVersion-arm64.pkg") }
    outputs.file(pkgFile)

    doLast {
        val args = listOf(
            "jpackage", "--type", "pkg",
            "--name", appName,
            "--app-version", appVersion,
            // In --app-image mode the identifier is inherited from the app's
            // Info.plist; jpackage rejects an explicit --identifier here.
            "--app-image", appBundle.get().asFile.absolutePath,
            "--dest", macOutDir.get().asFile.absolutePath
        )
        val p = ProcessBuilder(args).redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText()
        println(log)
        if (p.waitFor() != 0) throw GradleException("jpackage (pkg) failed")
    }
}
