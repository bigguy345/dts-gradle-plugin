package dts

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.file.FileCollection

/**
 * Gradle task that generates TypeScript definition (.d.ts) files from Java API sources.
 * 
 * Usage in build.gradle:
 * 
 * task generateTypeScriptDefinitions(type: dts.GenerateTypeScriptTask) {
 *     sourceDirectories = [file('src/api/java')]
 *     outputDirectory = file('src/main/resources/assets/customnpcs/api')
 *     apiPackages = ['noppes.npcs.api', 'kamkeel.npcdbc.api']
 * }
 */
abstract class GenerateTypeScriptTask extends DefaultTask {
    
    @Input
    List<File> sourceDirectories = []
    
    @OutputDirectory
    File outputDirectory
    
    @Input
    Set<String> apiPackages = []
    
    @Input
    Boolean cleanOutputFirst = false
    
    @Input
    List<String> excludePatterns = []
    
    GenerateTypeScriptTask() {
        group = 'build'
        description = 'Generates TypeScript definition files from Java API sources'
    }
    
    @TaskAction
    void generate() {
        logger.lifecycle("=".multiply(60))
        logger.lifecycle("Generating TypeScript definitions...")
        logger.lifecycle("=".multiply(60))
        
        if (sourceDirectories.isEmpty()) {
            logger.warn("No source directories specified!")
            return
        }
        
        // Validate directories
        sourceDirectories.each { dir ->
            if (!dir.exists()) {
                logger.warn("Source directory does not exist: ${dir}")
            } else {
                logger.lifecycle("Source: ${dir}")
            }
        }
        
        logger.lifecycle("Output: ${outputDirectory}")
        logger.lifecycle("API Packages: ${apiPackages}")
        
        // Clean output if requested
        if (cleanOutputFirst && outputDirectory.exists()) {
            logger.lifecycle("Cleaning output directory...")
            outputDirectory.eachFileRecurse { file ->
                if (file.name.endsWith('.d.ts') && !file.name.equals('minecraft-raw.d.ts') && !file.name.equals('forge-events-raw.d.ts')) {
                    file.delete()
                }
            }
        }
        
        // Create converter and process
        JavaToTypeScriptConverter converter = new JavaToTypeScriptConverter(outputDirectory, apiPackages)
        
        List<File> validDirs = sourceDirectories.findAll { it.exists() }
        if (validDirs.isEmpty()) {
            logger.error("No valid source directories found!")
            return
        }
        
        converter.processDirectories(validDirs)
        
        logger.lifecycle("=".multiply(60))
        logger.lifecycle("TypeScript definition generation complete!")
        logger.lifecycle("=".multiply(60))
    }
}
