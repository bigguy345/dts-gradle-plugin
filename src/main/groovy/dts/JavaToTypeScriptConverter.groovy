package dts

import groovy.transform.TypeChecked
import java.util.regex.Pattern
import java.util.regex.Matcher

/**
 * Parses Java source files and converts them to TypeScript definition (.d.ts) files.
 * Handles interfaces, classes, nested types, generics, and JavaDoc preservation.
 */
class JavaToTypeScriptConverter {
    
    // Common Java type mappings to TypeScript
    private static final Map<String, String> PRIMITIVE_MAPPINGS = [
        'void': 'void',
        'boolean': 'boolean',
        'byte': 'number',
        'short': 'number',
        'int': 'number',
        'long': 'number',
        'float': 'number',
        'double': 'number',
        'char': 'string',
        'String': 'string',
        'Object': 'any',
        'Boolean': 'boolean',
        'Byte': 'number',
        'Short': 'number',
        'Integer': 'number',
        'Long': 'number',
        'Float': 'number',
        'Double': 'number',
        'Character': 'string',
        'Number': 'number',
    ]
    
    // Java functional interface mappings
    private static final Map<String, String> FUNCTIONAL_MAPPINGS = [
        'Consumer': '(arg: %s) => void',
        'Supplier': '() => %s',
        'Function': '(arg: %s) => %s',
        'Predicate': '(arg: %s) => boolean',
        'BiConsumer': '(arg1: %s, arg2: %s) => void',
        'BiFunction': '(arg1: %s, arg2: %s) => %s',
        'Runnable': '() => void',
        'Callable': '() => %s',
    ]
    
    // Packages that are part of API (generate imports to local .d.ts)
    private Set<String> apiPackages = [] as Set
    
    // Base output directory for generated files
    private File outputDir
    
    // Track all generated types for index.d.ts
    private List<TypeInfo> generatedTypes = []
    
    // Track hooks for hooks.d.ts
    private Map<String, List<HookInfo>> hooks = [:]
    
    JavaToTypeScriptConverter(File outputDir, Set<String> apiPackages) {
        this.outputDir = outputDir
        this.apiPackages = apiPackages
    }
    
    /**
     * Process all Java files in the given directories
     */
    void processDirectories(List<File> sourceDirs) {
        sourceDirs.each { dir ->
            if (dir.exists()) {
                processDirectory(dir, dir)
            }
        }
        
        // Generate index.d.ts
        generateIndexFile()
        
        // Generate hooks.d.ts
        generateHooksFile()
    }
    
    private void processDirectory(File dir, File baseDir) {
        dir.eachFileRecurse { file ->
            if (file.name.endsWith('.java') && !file.name.equals('package-info.java')) {
                processJavaFile(file, baseDir)
            }
        }
    }
    
    /**
     * Process a single Java file
     */
    void processJavaFile(File javaFile, File baseDir) {
        String content = javaFile.text
        ParsedJavaFile parsed = parseJavaFile(content)
        
        if (parsed == null || parsed.types.isEmpty()) return
        
        // Determine output path
        String relativePath = baseDir.toPath().relativize(javaFile.toPath()).toString()
        String dtsPath = relativePath.replace('.java', '.d.ts').replace('\\', '/')
        File outputFile = new File(outputDir, dtsPath)
        
        // Generate TypeScript content
        String tsContent = generateTypeScript(parsed, dtsPath)
        
        // Write file
        outputFile.parentFile.mkdirs()
        outputFile.text = tsContent
        
        // Track for index generation
        parsed.types.each { type ->
            generatedTypes << new TypeInfo(
                name: type.name,
                packageName: parsed.packageName,
                filePath: dtsPath,
                isClass: type.isClass,
                isInterface: type.isInterface,
                extendsType: type.extendsType
            )
            
            // Track nested types
            type.nestedTypes.each { nested ->
                generatedTypes << new TypeInfo(
                    name: "${type.name}.${nested.name}",
                    packageName: parsed.packageName,
                    filePath: dtsPath,
                    isClass: nested.isClass,
                    isInterface: nested.isInterface,
                    parentType: type.name
                )
            }
        }
        
        // Collect hooks from event interfaces
        collectHooks(parsed)
    }
    
    /**
     * Parse a Java file into structured data
     */
    ParsedJavaFile parseJavaFile(String content) {
        ParsedJavaFile result = new ParsedJavaFile()
        
        // Extract package
        def packageMatcher = content =~ /package\s+([\w.]+)\s*;/
        if (packageMatcher.find()) {
            result.packageName = packageMatcher.group(1)
        }
        
        // Extract imports
        def importMatcher = content =~ /import\s+([\w.*]+)\s*;/
        while (importMatcher.find()) {
            result.imports << importMatcher.group(1)
        }
        
        // Parse types (interfaces and classes)
        parseTypes(content, result)
        
        return result
    }
    
    private void parseTypes(String content, ParsedJavaFile result) {
        // Match ONLY top-level (public) interface or class declarations
        // Top-level types MUST have 'public' modifier in Java
        // Capture JSDoc in group 1, abstract in group 2, interface/class in group 3, name in group 4, etc.
        def typePattern = ~/(\/\*\*[\s\S]*?\*\/\s*)?public\s+(abstract\s+)?(interface|class)\s+(\w+)(?:<([^>]+)>)?(?:\s+extends\s+([\w.<>,\s]+))?(?:\s+implements\s+([\w.<>,\s]+))?\s*\{/
        
        def matcher = content =~ typePattern
        while (matcher.find()) {
            JavaType type = new JavaType()
            type.jsdoc = matcher.group(1)?.trim()
            type.isInterface = matcher.group(3) == 'interface'
            type.isClass = matcher.group(3) == 'class'
            type.name = matcher.group(4)
            type.typeParams = matcher.group(5)
            type.extendsType = matcher.group(6)?.trim()
            type.implementsTypes = matcher.group(7)?.split(',')?.collect { it.trim() } ?: []
            
            // Find the body of this type
            int bodyStart = matcher.end() - 1
            int bodyEnd = findMatchingBrace(content, bodyStart)
            if (bodyEnd > bodyStart) {
                String body = content.substring(bodyStart + 1, bodyEnd)
                
                // Parse methods - pass original body for JSDoc extraction
                type.methods = parseMethods(body)
                
                // Parse nested types
                type.nestedTypes = parseNestedTypes(body, type.name)
                
                // Parse fields (for classes)
                if (type.isClass) {
                    type.fields = parseFields(body)
                }
            }
            
            result.types << type
        }
    }
    
    /**
     * Remove nested type bodies from a string so we only parse top-level methods
     */
    private String removeNestedTypeBodies(String body) {
        StringBuilder result = new StringBuilder()
        int depth = 0
        boolean inNestedType = false
        int nestedStart = -1
        
        // Find nested type declarations and remove their bodies
        def nestedPattern = ~/(?:public\s+)?(?:static\s+)?(interface|class)\s+\w+/
        
        int i = 0
        while (i < body.length()) {
            char c = body.charAt(i)
            
            if (c == '{') {
                if (!inNestedType) {
                    // Check if this brace starts a nested type
                    String before = body.substring(Math.max(0, i - 100), i)
                    if (before =~ /(?:public\s+)?(?:static\s+)?(?:interface|class)\s+\w+[^{]*$/) {
                        inNestedType = true
                        nestedStart = i
                        depth = 1
                        i++
                        continue
                    }
                }
                if (inNestedType) {
                    depth++
                }
            } else if (c == '}') {
                if (inNestedType) {
                    depth--
                    if (depth == 0) {
                        inNestedType = false
                        // Don't add the nested type body to result
                        i++
                        continue
                    }
                }
            }
            
            if (!inNestedType) {
                result.append(c)
            }
            i++
        }
        
        return result.toString()
    }
    
    private List<JavaMethod> parseMethods(String body) {
        List<JavaMethod> methods = []
        
        // Remove nested type bodies first to only get top-level methods
        String topLevelBody = removeNestedTypeBodies(body)
        
        // Match method signatures - handles complex generics
        // Capture JSDoc in group 1, returnType in group 2, methodName in group 3, params in group 4
        def methodPattern = ~/(\/\*\*[\s\S]*?\*\/\s*)?(?:@\w+(?:\([^)]*\))?\s*)*(?:public\s+|protected\s+|private\s+)?(?:static\s+)?(?:abstract\s+)?(?:default\s+)?(?:synchronized\s+)?(?:final\s+)?(?:<[^>]+>\s+)?(\w[\w.<>,\[\]\s]*?)\s+(\w+)\s*\(([^)]*)\)\s*(?:throws\s+[\w,\s]+)?[;{]/
        
        def matcher = topLevelBody =~ methodPattern
        while (matcher.find()) {
            String jsdoc = matcher.group(1)?.trim()
            String returnType = matcher.group(2).trim()
            String methodName = matcher.group(3)
            
            // Skip constructors - where return type is a visibility modifier
            // or the method name matches the class name (which we'd need to track)
            if (['public', 'protected', 'private', 'abstract', 'static', 'final', 'synchronized', 'native', 'strictfp'].contains(returnType)) {
                continue
            }
            
            JavaMethod method = new JavaMethod()
            method.returnType = returnType
            method.name = methodName
            method.parameters = parseParameters(matcher.group(4))
            method.jsdoc = jsdoc
            
            methods << method
        }
        
        return methods
    }
    
    private List<JavaField> parseFields(String body) {
        List<JavaField> fields = []
        
        // Remove nested type bodies first
        String topLevelBody = removeNestedTypeBodies(body)
        
        // Capture JSDoc in group 1, visibility in group 2, fieldType in group 3, fieldName in group 4
        def fieldPattern = ~/(\/\*\*[\s\S]*?\*\/\s*)?(public\s+|protected\s+|private\s+)(?:static\s+)?(?:final\s+)?(\w[\w.<>,\[\]]*)\s+(\w+)\s*[;=]/
        
        def matcher = topLevelBody =~ fieldPattern
        while (matcher.find()) {
            String jsdoc = matcher.group(1)?.trim()
            String fieldType = matcher.group(3).trim()
            String fieldName = matcher.group(4)
            
            // Skip Java keywords that might be mismatched
            if (['return', 'if', 'else', 'for', 'while', 'switch', 'case', 'break', 'continue', 'throw', 'try', 'catch', 'finally', 'new', 'this', 'super'].contains(fieldType)) {
                continue
            }
            
            JavaField field = new JavaField()
            field.type = fieldType
            field.name = fieldName
            field.jsdoc = jsdoc
            
            fields << field
        }
        
        return fields
    }
    
    private List<JavaType> parseNestedTypes(String body, String parentName) {
        List<JavaType> nestedTypes = []
        
        // Capture JSDoc in group 1, interface/class in group 2, name in group 3, typeParams in group 4, extends in group 5
        def nestedPattern = ~/(\/\*\*[\s\S]*?\*\/\s*)?(?:@\w+(?:\([^)]*\))?\s*)*(?:public\s+)?(?:static\s+)?(interface|class)\s+(\w+)(?:<([^>]+)>)?(?:\s+extends\s+([\w.<>,\s]+))?\s*\{/
        
        // We need to track position and skip over bodies of found types to avoid finding nested-nested types
        int searchStart = 0
        def matcher = nestedPattern.matcher(body)
        
        while (matcher.find(searchStart)) {
            JavaType nested = new JavaType()
            nested.jsdoc = matcher.group(1)?.trim()
            nested.isInterface = matcher.group(2) == 'interface'
            nested.isClass = matcher.group(2) == 'class'
            nested.name = matcher.group(3)
            nested.typeParams = matcher.group(4)
            nested.extendsType = matcher.group(5)?.trim()
            
            int bodyStart = matcher.end() - 1
            int bodyEnd = findMatchingBrace(body, bodyStart)
            if (bodyEnd > bodyStart) {
                String nestedBody = body.substring(bodyStart + 1, bodyEnd)
                nested.methods = parseMethods(nestedBody)
                // Recursively parse nested types within this nested type
                nested.nestedTypes = parseNestedTypes(nestedBody, nested.name)
                
                // Skip past the entire body of this type for the next search
                searchStart = bodyEnd + 1
            } else {
                // If we couldn't find the matching brace, move past this match
                searchStart = matcher.end()
            }
            
            nestedTypes << nested
        }
        
        return nestedTypes
    }
    
    private List<JavaParameter> parseParameters(String paramsStr) {
        List<JavaParameter> params = []
        if (paramsStr == null || paramsStr.trim().isEmpty()) return params
        
        // Handle complex generic parameters
        List<String> paramParts = splitParameters(paramsStr)
        
        paramParts.each { part ->
            part = part.trim()
            if (part.isEmpty()) return
            
            // Handle varargs
            boolean isVarargs = part.contains('...')
            part = part.replace('...', '[]')
            
            // Split type and name
            int lastSpace = part.lastIndexOf(' ')
            if (lastSpace > 0) {
                JavaParameter param = new JavaParameter()
                param.type = part.substring(0, lastSpace).trim()
                param.name = part.substring(lastSpace + 1).trim()
                param.isVarargs = isVarargs
                params << param
            }
        }
        
        return params
    }
    
    /**
     * Split parameters handling nested generics
     */
    private List<String> splitParameters(String params) {
        List<String> result = []
        int depth = 0
        StringBuilder current = new StringBuilder()
        
        params.each { ch ->
            if (ch == '<') depth++
            else if (ch == '>') depth--
            else if (ch == ',' && depth == 0) {
                result << current.toString()
                current = new StringBuilder()
                return
            }
            current.append(ch)
        }
        
        if (current.length() > 0) {
            result << current.toString()
        }
        
        return result
    }
    
    private String extractJsDocBefore(String content, int position) {
        // Look backwards for JSDoc
        String before = content.substring(0, position)
        def jsdocMatcher = before =~ /\/\*\*[\s\S]*?\*\/\s*$/
        if (jsdocMatcher.find()) {
            return jsdocMatcher.group(0).trim()
        }
        return null
    }
    
    private int findMatchingBrace(String content, int start) {
        int depth = 0
        for (int i = start; i < content.length(); i++) {
            char c = content.charAt(i)
            if (c == '{') depth++
            else if (c == '}') {
                depth--
                if (depth == 0) return i
            }
        }
        return -1
    }
    
    private String removeBlockComments(String content) {
        // Remove JSDoc and block comments for structure parsing
        return content.replaceAll(/\/\*[\s\S]*?\*\//, '')
    }
    
    /**
     * Generate TypeScript content from parsed Java
     */
    String generateTypeScript(ParsedJavaFile parsed, String currentPath) {
        StringBuilder sb = new StringBuilder()
        
        // Header comment
        sb.append('/**\n')
        sb.append(' * Generated from Java file for CustomNPC+ Minecraft Mod 1.7.10\n')
        sb.append(" * Package: ${parsed.packageName}\n")
        sb.append(' */\n\n')
        
        parsed.types.each { type ->
            generateType(sb, type, parsed, currentPath, '')
        }
        
        return sb.toString()
    }
    
    private void generateType(StringBuilder sb, JavaType type, ParsedJavaFile parsed, String currentPath, String indent) {
        // JSDoc
        if (type.jsdoc) {
            sb.append(convertJsDoc(type.jsdoc, indent))
            sb.append('\n')
        }
        
        // Type declaration
        String keyword = type.isClass ? 'export class' : 'export interface'
        sb.append("${indent}${keyword} ${type.name}")
        
        // Type parameters
        if (type.typeParams) {
            sb.append("<${convertTypeParams(type.typeParams)}>")
        }
        
        // Extends
        if (type.extendsType) {
            sb.append(" extends ${convertType(type.extendsType, parsed, currentPath)}")
        }
        
        sb.append(' {\n')
        
        // Methods - compact format, no comments
        type.methods.each { method ->
            generateMethod(sb, method, parsed, currentPath, indent + '    ')
        }
        
        // Fields (for classes)
        type.fields.each { field ->
            generateField(sb, field, parsed, currentPath, indent + '    ')
        }
        
        sb.append("${indent}}\n")
        
        // Nested types as namespace
        if (!type.nestedTypes.isEmpty()) {
            sb.append("\n${indent}export namespace ${type.name} {\n")
            type.nestedTypes.each { nested ->
                // Check if it is an empty interface that extends parent (no methods AND no nested types)
                if (nested.methods.isEmpty() && nested.nestedTypes.isEmpty() && nested.extendsType) {
                    // Use type alias
                    if (nested.jsdoc) {
                        sb.append(convertJsDoc(nested.jsdoc, indent + '    '))
                        sb.append('\n')
                    }
                    // For nested types extending the parent, use local reference
                    String extendsRef = convertTypeForNested(nested.extendsType, type.name, parsed, currentPath)
                    sb.append("${indent}    export type ${nested.name} = ${extendsRef}\n")
                } else {
                    generateNestedType(sb, nested, type.name, parsed, currentPath, indent + '    ')
                }
            }
            sb.append("${indent}}\n")
        }
        sb.append('\n')
    }
    
    /**
     * Generate a nested type (interface/class within a namespace)
     * Recursively handles nested types that themselves have nested types
     */
    private void generateNestedType(StringBuilder sb, JavaType type, String parentTypeName, ParsedJavaFile parsed, String currentPath, String indent) {
        // JSDoc
        if (type.jsdoc) {
            sb.append(convertJsDoc(type.jsdoc, indent))
            sb.append('\n')
        }
        
        // Type declaration
        String keyword = type.isClass ? 'export class' : 'export interface'
        sb.append("${indent}${keyword} ${type.name}")
        
        // Type parameters
        if (type.typeParams) {
            sb.append("<${convertTypeParams(type.typeParams)}>")
        }
        
        // Extends - handle parent type reference specially
        if (type.extendsType) {
            String extendsRef = convertTypeForNested(type.extendsType, parentTypeName, parsed, currentPath)
            sb.append(" extends ${extendsRef}")
        }
        
        sb.append(' {\n')
        
        // Methods - compact format, no comments
        type.methods.each { method ->
            generateMethod(sb, method, parsed, currentPath, indent + '    ')
        }
        
        sb.append("${indent}}\n")
        
        // If this nested type also has nested types, create a namespace for them
        if (!type.nestedTypes.isEmpty()) {
            sb.append("${indent}export namespace ${type.name} {\n")
            type.nestedTypes.each { nested ->
                // Check if it is an empty interface that extends its parent
                if (nested.methods.isEmpty() && nested.nestedTypes.isEmpty() && nested.extendsType) {
                    // Use type alias
                    if (nested.jsdoc) {
                        sb.append(convertJsDoc(nested.jsdoc, indent + '    '))
                        sb.append('\n')
                    }
                    // For nested types extending the parent, use local reference
                    String extendsRef = convertTypeForNested(nested.extendsType, type.name, parsed, currentPath)
                    sb.append("${indent}    export type ${nested.name} = ${extendsRef}\n")
                } else {
                    // Recursively generate the nested type
                    generateNestedType(sb, nested, type.name, parsed, currentPath, indent + '    ')
                }
            }
            sb.append("${indent}}\n")
        }
    }
    
    /**
     * Convert type reference for nested types - handle parent type specially
     */
    private String convertTypeForNested(String javaType, String parentTypeName, ParsedJavaFile parsed, String currentPath) {
        if (javaType == null || javaType.isEmpty()) return 'any'
        
        javaType = javaType.trim()
        
        // If the type is the parent type name, use it directly (not as import from same file)
        if (javaType == parentTypeName) {
            return parentTypeName
        }
        
        // Otherwise use normal conversion
        return convertType(javaType, parsed, currentPath)
    }
    
    private void generateMethod(StringBuilder sb, JavaMethod method, ParsedJavaFile parsed, String currentPath, String indent) {
        // JSDoc
        if (method.jsdoc) {
            sb.append(convertJsDoc(method.jsdoc, indent))
            sb.append('\n')
        }
        
        sb.append("${indent}${method.name}(")
        
        // Parameters
        List<String> paramStrs = method.parameters.collect { param ->
            String tsType = convertType(param.type, parsed, currentPath)
            if (param.isVarargs) {
                return "...${param.name}: ${tsType}"
            }
            return "${param.name}: ${tsType}"
        }
        sb.append(paramStrs.join(', '))
        
        sb.append('): ')
        sb.append(convertType(method.returnType, parsed, currentPath))
        sb.append(';\n')
    }
    
    private void generateField(StringBuilder sb, JavaField field, ParsedJavaFile parsed, String currentPath, String indent) {
        if (field.jsdoc) {
            sb.append(convertJsDoc(field.jsdoc, indent))
            sb.append('\n')
        }
        sb.append("${indent}${field.name}: ${convertType(field.type, parsed, currentPath)};\n")
    }
    
    /**
     * Convert Java type to TypeScript type
     */
    String convertType(String javaType, ParsedJavaFile parsed, String currentPath) {
        if (javaType == null || javaType.isEmpty()) return 'any'
        
        javaType = javaType.trim()
        
        // Check primitives first
        if (PRIMITIVE_MAPPINGS.containsKey(javaType)) {
            return PRIMITIVE_MAPPINGS[javaType]
        }
        
        // Handle arrays
        if (javaType.endsWith('[]')) {
            String baseType = javaType.substring(0, javaType.length() - 2)
            return convertType(baseType, parsed, currentPath) + '[]'
        }
        
        // Handle generics
        if (javaType.contains('<')) {
            return convertGenericType(javaType, parsed, currentPath)
        }
        
        // Handle functional interfaces from java.util.function
        if (FUNCTIONAL_MAPPINGS.containsKey(javaType)) {
            return 'Function'  // Simplified - will be expanded in generic handling
        }
        
        // Check if it is an API type (needs import)
        String importPath = resolveImportPath(javaType, parsed, currentPath)
        if (importPath != null) {
            return "import('${importPath}').${javaType}"
        }
        
        // Check if it is a java.* type
        String fullType = resolveFullType(javaType, parsed)
        if (fullType != null && fullType.startsWith('java.')) {
            return "Java.${fullType}"
        }
        
        // Default - return as is (might be a type parameter like T, or unknown type)
        return javaType
    }
    
    private String convertGenericType(String type, ParsedJavaFile parsed, String currentPath) {
        int ltIndex = type.indexOf('<')
        String baseType = type.substring(0, ltIndex).trim()
        String genericPart = type.substring(ltIndex + 1, type.lastIndexOf('>')).trim()
        
        // Handle common collections
        switch (baseType) {
            case 'List':
            case 'ArrayList':
            case 'LinkedList':
            case 'Collection':
            case 'Set':
            case 'HashSet':
            case 'Queue':
                return convertType(genericPart, parsed, currentPath) + '[]'
            
            case 'Map':
            case 'HashMap':
            case 'LinkedHashMap':
                List<String> parts = splitGenericParams(genericPart)
                if (parts.size() >= 2) {
                    String keyType = convertType(parts[0], parsed, currentPath)
                    String valueType = convertType(parts[1], parsed, currentPath)
                    return "Record<${keyType}, ${valueType}>"
                }
                return 'Record<any, any>'
            
            case 'Optional':
                return convertType(genericPart, parsed, currentPath) + ' | null'
            
            // Functional interfaces
            case 'Consumer':
                return "(arg: ${convertType(genericPart, parsed, currentPath)}) => void"
            
            case 'Supplier':
                return "() => ${convertType(genericPart, parsed, currentPath)}"
            
            case 'Function':
                List<String> funcParts = splitGenericParams(genericPart)
                if (funcParts.size() >= 2) {
                    return "(arg: ${convertType(funcParts[0], parsed, currentPath)}) => ${convertType(funcParts[1], parsed, currentPath)}"
                }
                return '(arg: any) => any'
            
            case 'Predicate':
                return "(arg: ${convertType(genericPart, parsed, currentPath)}) => boolean"
            
            case 'BiConsumer':
                List<String> biParts = splitGenericParams(genericPart)
                if (biParts.size() >= 2) {
                    return "(arg1: ${convertType(biParts[0], parsed, currentPath)}, arg2: ${convertType(biParts[1], parsed, currentPath)}) => void"
                }
                return '(arg1: any, arg2: any) => void'
            
            case 'BiFunction':
                List<String> biFuncParts = splitGenericParams(genericPart)
                if (biFuncParts.size() >= 3) {
                    return "(arg1: ${convertType(biFuncParts[0], parsed, currentPath)}, arg2: ${convertType(biFuncParts[1], parsed, currentPath)}) => ${convertType(biFuncParts[2], parsed, currentPath)}"
                }
                return '(arg1: any, arg2: any) => any'
            
            default:
                // Regular generic type
                String convertedBase = convertType(baseType, parsed, currentPath)
                List<String> convertedParams = splitGenericParams(genericPart).collect { 
                    convertType(it, parsed, currentPath) 
                }
                // For import types, we cannot add generics easily, so simplify
                if (convertedBase.startsWith('import(')) {
                    return convertedBase
                }
                return "${convertedBase}<${convertedParams.join(', ')}>"
        }
    }
    
    private List<String> splitGenericParams(String params) {
        List<String> result = []
        int depth = 0
        StringBuilder current = new StringBuilder()
        
        params.each { ch ->
            if (ch == '<') depth++
            else if (ch == '>') depth--
            else if (ch == ',' && depth == 0) {
                result << current.toString().trim()
                current = new StringBuilder()
                return
            }
            current.append(ch)
        }
        
        if (current.length() > 0) {
            result << current.toString().trim()
        }
        
        return result
    }
    
    private String convertTypeParams(String typeParams) {
        // Convert Java type parameters to TypeScript
        // e.g., "T extends Comparable<T>" -> "T extends Comparable<T>"
        return typeParams
    }
    
    private String resolveImportPath(String typeName, ParsedJavaFile parsed, String currentPath) {
        // Check imports for this type
        String fullType = resolveFullType(typeName, parsed)
        
        if (fullType == null) return null
        
        // Check if it is an API type
        String packagePrefix = fullType.substring(0, fullType.lastIndexOf('.'))
        if (apiPackages.any { fullType.startsWith(it) }) {
            // Calculate relative path
            String typeFilePath = fullType.replace('.', '/') + '.d.ts'
            return calculateRelativePath(currentPath, typeFilePath)
        }
        
        return null
    }
    
    private String resolveFullType(String typeName, ParsedJavaFile parsed) {
        // Check explicit imports
        String explicitImport = parsed.imports.find { it.endsWith(".${typeName}") }
        if (explicitImport) return explicitImport
        
        // Check wildcard imports
        parsed.imports.each { imp ->
            if (imp.endsWith('.*')) {
                // Would need classpath to resolve, skip for now
            }
        }
        
        // Same package
        return "${parsed.packageName}.${typeName}"
    }
    
    private String calculateRelativePath(String fromPath, String toPath) {
        // Calculate relative path between two .d.ts files
        String[] fromParts = fromPath.split('/')
        String[] toParts = toPath.split('/')
        
        // Find common prefix
        int common = 0
        while (common < fromParts.length - 1 && common < toParts.length && fromParts[common] == toParts[common]) {
            common++
        }
        
        // Build relative path
        StringBuilder result = new StringBuilder()
        
        // Go up from current location
        int ups = fromParts.length - common - 1
        if (ups == 0) {
            result.append('./')
        } else {
            for (int i = 0; i < ups; i++) {
                result.append('../')
            }
        }
        
        // Go down to target
        for (int i = common; i < toParts.length; i++) {
            if (i > common) result.append('/')
            result.append(toParts[i])
        }
        
        // Remove .d.ts extension for imports
        String path = result.toString()
        if (path.endsWith('.d.ts')) {
            path = path.substring(0, path.length() - 5)
        }
        
        return path
    }
    
    /**
     * Convert JavaDoc to JSDoc format
     */
    private String convertJsDoc(String jsdoc, String indent) {
        if (jsdoc == null) return ''
        
        // Already in JSDoc format, just fix indentation
        String[] lines = jsdoc.split('\n')
        return lines.collect { line ->
            String trimmed = line.trim()
            if (trimmed.startsWith('*')) {
                return "${indent} ${trimmed}"
            } else if (trimmed.startsWith('/**') || trimmed.startsWith('*/')) {
                return "${indent}${trimmed}"
            } else {
                return "${indent}${trimmed}"
            }
        }.join('\n')
    }
    
    /**
     * Collect hook information from event interfaces
     */
    private void collectHooks(ParsedJavaFile parsed) {
        parsed.types.each { type ->
            // Event interfaces typically end with 'Event'
            if (type.name.endsWith('Event') && type.isInterface) {
                type.nestedTypes.each { nested ->
                    // Create hook entry
                    String hookName = deriveHookName(nested.name)
                    if (!hooks.containsKey(hookName)) {
                        hooks[hookName] = []
                    }
                    hooks[hookName] << new HookInfo(
                        eventType: type.name,
                        subEvent: nested.name,
                        fullType: "${type.name}.${nested.name}",
                        packageName: parsed.packageName
                    )
                }
            }
        }
    }
    
    private String deriveHookName(String eventName) {
        // Convert event names to hook function names
        // e.g., "InitEvent" -> "init", "DamagedEvent" -> "damaged"
        String name = eventName
        if (name.endsWith('Event')) {
            name = name.substring(0, name.length() - 5)
        }
        // Convert to camelCase
        String hookName = name.substring(0, 1).toLowerCase() + name.substring(1)
        
        // Handle JavaScript reserved words
        Set<String> reservedWords = ['break', 'case', 'catch', 'continue', 'debugger', 'default', 'delete', 
                                      'do', 'else', 'finally', 'for', 'function', 'if', 'in', 'instanceof', 
                                      'new', 'return', 'switch', 'this', 'throw', 'try', 'typeof', 'var', 
                                      'void', 'while', 'with', 'class', 'const', 'enum', 'export', 'extends', 
                                      'import', 'super', 'implements', 'interface', 'let', 'package', 'private', 
                                      'protected', 'public', 'static', 'yield'] as Set
        
        if (reservedWords.contains(hookName)) {
            // Prefix with 'on' for reserved words
            hookName = 'on' + name
        }
        
        return hookName
    }
    
    /**
     * Generate index.d.ts with all type aliases
     */
    private void generateIndexFile() {
        StringBuilder sb = new StringBuilder()
        
        sb.append('/**\n')
        sb.append(' * Centralized global declarations for CustomNPC+ scripting.\n')
        sb.append(' * Auto-generated - do not edit manually.\n')
        sb.append(' */\n\n')
        
        sb.append('declare global {\n')
        sb.append('    // ============================================================================\n')
        sb.append('    // TYPE ALIASES - Make all interfaces available globally\n')
        sb.append('    // ============================================================================\n\n')
        
        generatedTypes.sort { a, b -> a.name <=> b.name }
        
        generatedTypes.each { type ->
            if (!type.name.contains('.')) {  // Skip nested types here
                sb.append("    type ${type.name} = import('./${type.filePath.replace('.d.ts', '')}').${type.name};\n")
            }
        }
        
        sb.append('}\n\n')
        sb.append('export {};\n')
        
        new File(outputDir, 'index.d.ts').text = sb.toString()
    }
    
    /**
     * Generate hooks.d.ts with event hook function declarations
     */
    private void generateHooksFile() {
        StringBuilder sb = new StringBuilder()
        
        sb.append('/**\n')
        sb.append(' * CustomNPC+ Event Hook Overloads\n')
        sb.append(' * Auto-generated - do not edit manually.\n')
        sb.append(' */\n\n')
        
        sb.append("import './minecraft-raw.d.ts';\n")
        sb.append("import './forge-events-raw.d.ts';\n\n")
        
        sb.append('declare global {\n')
        
        hooks.each { hookName, hookInfos ->
            hookInfos.each { info ->
                sb.append("    function ${hookName}(${info.eventType}: ${info.fullType}): void;\n")
            }
        }
        
        sb.append('}\n\n')
        sb.append('export {};\n')
        
        new File(outputDir, 'hooks.d.ts').text = sb.toString()
    }
    
    // Data classes
    static class ParsedJavaFile {
        String packageName = ''
        List<String> imports = []
        List<JavaType> types = []
    }
    
    static class JavaType {
        String name
        String typeParams
        String extendsType
        List<String> implementsTypes = []
        boolean isInterface
        boolean isClass
        String jsdoc
        List<JavaMethod> methods = []
        List<JavaField> fields = []
        List<JavaType> nestedTypes = []
    }
    
    static class JavaMethod {
        String name
        String returnType
        List<JavaParameter> parameters = []
        String jsdoc
    }
    
    static class JavaParameter {
        String name
        String type
        boolean isVarargs
    }
    
    static class JavaField {
        String name
        String type
        String jsdoc
    }
    
    static class TypeInfo {
        String name
        String packageName
        String filePath
        boolean isClass
        boolean isInterface
        String extendsType
        String parentType
    }
    
    static class HookInfo {
        String eventType
        String subEvent
        String fullType
        String packageName
    }
}
