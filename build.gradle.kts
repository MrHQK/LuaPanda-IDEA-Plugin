import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(17)
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion").get())

        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }

    // Gson for JSON protocol processing (bundled with IntelliJ)
    compileOnly("com.google.code.gson:gson:2.10.1")

    // Test dependencies
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        vendor {
            name = providers.gradleProperty("pluginVendorName")
            email = providers.gradleProperty("pluginVendorEmail")
            url = providers.gradleProperty("pluginVendorUrl")
        }

        // NOTE: keep this in sync with the description in src/main/resources/META-INF/plugin.xml
        // (that copy is only for readability — this one is what ships in the release ZIP).
        description = """
            <h2>LuaPanda for IntelliJ IDEA</h2>
            <p>Lua debugging for IntelliJ IDEA, ported from the popular LuaPanda VSCode extension.</p>
            <p><b>Debugging only.</b> This plugin focuses on the debugger and works side by side with EmmyLua,
            which provides Lua syntax highlighting, code completion and navigation. Both can be installed together.</p>
            <h3>Features</h3>
            <ul>
                <li><b>Debugging</b>: line / conditional / logpoint breakpoints, step over / into / out, pause and resume</li>
                <li><b>Variables</b>: Locals / Upvalues / Globals inspection with expandable tables</li>
                <li><b>Call Stack</b>: full Lua call stack navigation, click a frame to jump to source</li>
                <li><b>Evaluate</b>: Evaluate Expression dialog, Watches and a REPL console</li>
                <li><b>Variable Search</b>: search the current stack frame by name or full path while paused (regex, recursive table expansion, add results to Watches)</li>
                <li><b>Breakpoint Manager</b>: view, jump to, enable/disable and filter all Lua breakpoints in one tool window</li>
                <li><b>Code Quality</b>: integration with lua-fmt (formatting) and luacheck (static analysis)</li>
                <li><b>Connection</b>: Server mode (local) and Client mode (device / remote), with optional C hook acceleration via the bundled libpdebug</li>
                <li><b>Compatibility</b>: fully compatible with the original LuaPanda.lua debugger over its TCP/JSON protocol</li>
            </ul>
        """.trimIndent()

        // Release notes shown on the Marketplace plugin page ("What's new").
        // Keep this in sync with the <change-notes> block in src/main/resources/META-INF/plugin.xml.
        changeNotes = """
            <h3>1.0.2</h3>
            <ul>
                <li><b>新增</b>：按变量名搜索当前堆栈帧变量 —— 断点暂停时可按名称或完整路径过滤 Locals / Upvalues / Globals，支持正则匹配与 table 递归展开，结果可一键加入 Watches</li>
                <li><b>修复</b>：演示点 5「调用堆栈」因 Lua 尾调用导致 <code>stack_level_1/2</code> 不出现在 Frames 面板，改为非尾调用后恢复完整三层栈</li>
                <li><b>文档</b>：修正插件描述，使其与「专注调试、语言特性交给 EmmyLua」的实际能力一致</li>
            </ul>
            <h3>1.0.1</h3>
            <ul>
                <li><b>修复</b>：远程调试场景下"僵尸 socket"导致 <code>Connection reset</code> 死循环（与 VSCode 扩展行为对齐）</li>
                <li><b>修复</b>：Hover 表达式求值总是显示路径最右段值，现在悬浮在哪段就显示到那段为止的值</li>
                <li><b>体验</b>：Console "LuaPanda connected" 提示改为握手成功后才打印，避免误导</li>
                <li><b>工具</b>：新增一键打包脚本 <code>scripts/pack.ps1</code>（Windows）</li>
            </ul>
            <h3>1.0.0</h3>
            <ul>
                <li>首个正式版本</li>
                <li>完整断点调试（行断点、条件断点、日志断点）</li>
                <li>Server / Client 双连接模式</li>
                <li>使用模板（配置界面实时代码模板）</li>
                <li>内置 libpdebug C Hook 库自动加载</li>
                <li>EmmyLua 断点兼容</li>
                <li>lua-fmt 格式化 + luacheck 静态分析集成</li>
            </ul>
        """.trimIndent()

        ideaVersion {
            sinceBuild = "241"
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }

    // ---------------------------------------------------------------- Signing
    // Signing is required to publish on JetBrains Marketplace.
    // Run './gradlew signPlugin' to produce a signed ZIP in build/distributions,
    // or './gradlew publishPlugin', which runs signPlugin first automatically.
    //
    // Both PEM values are multi-line: pass them Base64-encoded (the task decodes
    // Base64 automatically) via environment variables:
    //   CERTIFICATE_CHAIN      <- [Convert]::ToBase64String([IO.File]::ReadAllBytes("chain.crt"))
    //   PRIVATE_KEY            <- [Convert]::ToBase64String([IO.File]::ReadAllBytes("private.pem"))
    //   PRIVATE_KEY_PASSWORD   <- password used when generating the key
    // Alternatively point at files on disk with certificateChainFile / privateKeyFile.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    // ------------------------------------------------------------- Publishing
    // Token: https://plugins.jetbrains.com/author/me/tokens  (Profile -> My Tokens)
    // Official convention is the Gradle property `intellijPlatformPublishingToken`, which Gradle
    // also picks up from the environment:
    //   $env:ORG_GRADLE_PROJECT_intellijPlatformPublishingToken = "perm:..."
    //   (or on the command line: -PintellijPlatformPublishingToken=perm:...)
    // $env:PUBLISH_TOKEN is kept as a fallback for the existing local workflow.
    // NOTE: the first version must be uploaded manually on the Marketplace website;
    //       publishPlugin only works for subsequent versions.
    publishing {
        token = providers.gradleProperty("intellijPlatformPublishingToken")
            .orElse(providers.environmentVariable("PUBLISH_TOKEN"))
        // Release channel - keep empty for the default public repository,
        // or use e.g. listOf("beta") to publish into a separate beta channel.
        // channels = listOf("beta")
    }
}

tasks {
    // Ensure './gradlew build' also produces the distributable zip
    named("build") {
        dependsOn("buildPlugin")
    }

    test {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }

    wrapper {
        gradleVersion = "8.13"
    }

    // Deploy the built plugin to the local IntelliJ IDEA plugins directory.
    // Usage: ./gradlew deployToIdea
    // NOTE: IDEA must be closed before running this task, otherwise jar files are locked.
    register<Copy>("deployToIdea") {
        dependsOn("build")
        group = "deployment"
        description = "Deploy plugin to local IntelliJ IDEA plugins directory"

        val pluginName = providers.gradleProperty("pluginName").get()
        val userHome = System.getProperty("user.home")
        // Search for IntelliJ IDEA config directories
        val jetbrainsDir = file("$userHome/AppData/Roaming/JetBrains")
        val ideaDirs = if (jetbrainsDir.exists()) {
            jetbrainsDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("IntelliJ") }
                ?.sortedByDescending { it.name } ?: emptyList()
        } else emptyList()

        val targetDir = if (ideaDirs.isNotEmpty()) {
            file("${ideaDirs.first()}/plugins/$pluginName")
        } else {
            file("$userHome/AppData/Roaming/JetBrains/IntelliJIdea2024.1/plugins/$pluginName")
        }

        // Clean target directory first
        doFirst {
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }
            targetDir.mkdirs()
            println("Deploying to: $targetDir")
        }

        // Copy from the built distribution (unzipped)
        val distDir = layout.buildDirectory.dir("distributions")
        from(zipTree(distDir.map { it.file("$pluginName-${project.version}.zip") }))
        into(targetDir.parentFile)

        doLast {
            println("✅ Plugin deployed successfully! Please restart IntelliJ IDEA.")
        }
    }
}
