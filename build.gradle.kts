import org.gradle.api.tasks.compile.JavaCompile

// 根构建脚本：集中声明插件，并强制所有模块的 Java 编译器按 UTF-8 读取源码。
plugins {
    alias(libs.plugins.android.application) apply false
}

subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }
}
