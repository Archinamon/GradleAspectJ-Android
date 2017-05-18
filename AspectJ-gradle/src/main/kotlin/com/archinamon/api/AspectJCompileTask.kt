package com.archinamon.api

import com.archinamon.AndroidConfig
import com.archinamon.AspectJExtension
import com.archinamon.utils.*
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.file.collections.SimpleFileCollection
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.api.tasks.compile.JavaCompile
import java.io.File
import java.util.*

internal open class AspectJCompileTask: AbstractCompile() {

    internal class Builder(val project: Project) {

        lateinit var plugin: Plugin<Project>
        lateinit var config: AspectJExtension
        lateinit var javaCompiler: JavaCompile
        lateinit var variantName: String
        lateinit var taskName: String

        fun plugin(plugin: Plugin<Project>): Builder {
            this.plugin = plugin
            return this
        }

        fun config(extension: AspectJExtension): Builder {
            this.config = extension
            return this
        }

        fun compiler(compiler: JavaCompile): Builder {
            this.javaCompiler = compiler
            return this
        }

        fun variant(name: String): Builder {
            this.variantName = name
            return this
        }

        fun name(name: String): Builder {
            this.taskName = name
            return this
        }

        fun buildAndAttach(android: AndroidConfig) {
            val options = mutableMapOf(
                Pair("overwrite", true),
                Pair("group", "build"),
                Pair("description", "Compile .aj source files into java .class with meta instructions"),
                Pair("type", AspectJCompileTask::class.java)
            )

            val task = project.task(options, taskName) as AspectJCompileTask
            val buildDir = project.file("${project.buildDir}/aspectj/$variantName")
            val sources = findAjSourcesForVariant(project, variantName)

            task.apply {
                destinationDir = buildDir
                aspectJWeaver = AspectJWeaver(project)

                source(sources)
                classpath = classpath()
                findCompiledAspectsInClasspath(this, config.includeAspectsFromJar)
            }

            task.aspectJWeaver.apply {
                ajSources = sources
                inPath shl buildDir shl javaCompiler.destinationDir

                targetCompatibility = JavaVersion.VERSION_1_7.toString()
                sourceCompatibility = JavaVersion.VERSION_1_7.toString()
                destinationDir = buildDir.absolutePath
                bootClasspath = android.getBootClasspath().joinToString(separator = File.pathSeparator)
                encoding = javaCompiler.options.encoding

                compilationLogFile = config.compilationLogFile
                addSerialVUID = config.addSerialVersionUID
                debugInfo = config.debugInfo
                addSerialVUID = config.addSerialVersionUID
                noInlineAround = config.noInlineAround
                ignoreErrors = config.ignoreErrors
                breakOnError = config.breakOnError
                experimental = config.experimental
                ajcArgs from config.ajcArgs
            }

            // uPhyca's fix
            // javaCompile.classpath does not contain exploded-aar/**/jars/*.jars till first run
            javaCompiler.doLast {
                task.classpath = classpath()
                findCompiledAspectsInClasspath(task, config.includeAspectsFromJar)
            }

            //apply behavior
            javaCompiler.finalizedBy(task)
        }

        private fun classpath(): FileCollection {
            return SimpleFileCollection(javaCompiler.classpath.files + javaCompiler.destinationDir)
        }

        private fun findCompiledAspectsInClasspath(task: AspectJCompileTask, aspectsFromJar: Collection<String>) {
            val classpath: FileCollection = task.classpath
            val aspects: MutableSet<File> = mutableSetOf()

            classpath.forEach { file ->
                if (aspectsFromJar.isNotEmpty() && DependencyFilter.isIncludeFilterMatched(file, aspectsFromJar)) {
                    logJarAspectAdded(file)
                    aspects shl file
                }
            }

            if (aspects.isNotEmpty()) task.aspectJWeaver.aspectPath from aspects
        }
    }

    lateinit var aspectJWeaver: AspectJWeaver

    @TaskAction
    override fun compile() {
        logCompilationStart()

        destinationDir.deleteRecursively()

        aspectJWeaver.classPath = LinkedHashSet(classpath.files)
        aspectJWeaver.doWeave()

        logCompilationFinish()
    }
}
