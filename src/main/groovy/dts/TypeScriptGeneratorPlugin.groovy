package dts

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Gradle plugin for generating TypeScript definition files from Java API sources.
 */
class TypeScriptGeneratorPlugin implements Plugin<Project> {
    
    void apply(Project project) {
        // Register the task type
        project.tasks.registerIfAbsent('generateTypeScriptDefinitions', GenerateTypeScriptTask)
    }
}
