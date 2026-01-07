package dts

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.file.FileCollection

/**
 * Gradle task that generates TypeScript definition (.d.ts) files from Java API sources.
 * 
 * Usage in build.gradle:
 * 
 * tasks.withType(GenerateTypeScriptTask).configureEach {
 *     sourceDirectories = ['src/api/java']  // Strings are converted to Files
 *     outputDirectory = 'src/main/resources/${modid}/api'  // String converted to File
 *     apiPackages = ['noppes.npcs.api', 'kamkeel.npcdbc.api']
 * }
 */
abstract class GenerateTypeScriptTask extends DefaultTask {
    
    // Internal representations
    private List<Object> _sourceDirectoriesInput = []
    private Object _outputDirectoryInput
    
    @Input
    Set<String> apiPackages = []
    
    @Input
    Boolean cleanOutputFirst = false
    
    @Input
    List<String> excludePatterns = []
    
    // Setters accept strings or Files
    void setSourceDirectories(Object value) {
        if (value instanceof List) {
            _sourceDirectoriesInput = value
        } else {
            _sourceDirectoriesInput = [value]
        }
    }
    
    void setOutputDirectory(Object value) {
        _outputDirectoryInput = value
    }
    
    // Provide task input/output properties that Gradle can validate
    @InputFiles
    protected List<File> getSourceDirectories() {
        return _sourceDirectoriesInput.collect { convertToFile(it) }
    }
    
    @OutputDirectory
    protected File getOutputDirectory() {
        return convertToFile(_outputDirectoryInput)
    }
    
    /**
     * Converts a value (String or File) to a File object.
     * Supports special tokens like ${modid}.
     */
    private File convertToFile(Object value) {
        if (value instanceof File) {
            return value
        }
        
        String pathStr = value.toString()
        
        // Handle ${modid} token
        if (pathStr.contains('${modid}')) {
            String modid = project.archivesBaseName ?: 'mod'
            pathStr = pathStr.replace('${modid}', modid)
        }
        
        // Resolve relative paths from project directory
        File file = new File(pathStr)
        if (!file.isAbsolute()) {
            file = new File(project.projectDir, pathStr)
        }
        
        return file
    }
    
    GenerateTypeScriptTask() {
        group = 'build'
        description = 'Generates TypeScript definition files from Java API sources'
    }
    
    @TaskAction
    void generate() {
        List<File> sourceDirectories = getSourceDirectories()
        File outputDirectory = getOutputDirectory()
        
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
