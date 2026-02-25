package dts

import org.gradle.api.logging.Logger

import java.util.regex.Pattern

/**
 * Scans implementation source files under configured impl package prefixes and
 * builds a mapping from contract interface FQNs (under configured contract prefixes)
 * to the public instance fields declared in the implementing classes.
 *
 * Only contracts with exactly one implementing class are enriched.
 * Contracts with zero or multiple implementers are skipped to avoid ambiguity.
 *
 * This allows the TypeScript generator to emit field properties on API interfaces
 * that are defined only in the shipped implementation classes.
 */
class ImplementationFieldEnricher {

    private final Set<String> contractPackagePrefixes
    private final Set<String> implPackagePrefixes

    private Logger logger

    ImplementationFieldEnricher(Logger logger, Set<String> contractPackagePrefixes = null, Set<String> implPackagePrefixes = null) {
        this.logger = logger
        this.contractPackagePrefixes = (contractPackagePrefixes != null && !contractPackagePrefixes.isEmpty())
            ? contractPackagePrefixes
            : (['noppes.npcs.api'] as Set)
        this.implPackagePrefixes = (implPackagePrefixes != null && !implPackagePrefixes.isEmpty())
            ? implPackagePrefixes
            : (['noppes.npcs.scripted'] as Set)
    }

    /**
     * Scan all Java files in the given source directories that fall under
     * the configured impl package prefixes and return enrichment data keyed by
     * contract interface FQN.
     *
     * Two-phase approach:
     *   Phase 1: Parse all impl classes, build contract→set(implFqn) and implFqn→fields.
     *   Phase 2: Produce enrichmentMap only for contracts with exactly one implementer.
     *
     * @param implSourceDirs directories to scan (e.g. {@code src/main/java})
     * @return map of contract FQN → list of {@link EnrichedField}
     */
    Map<String, List<EnrichedField>> buildEnrichmentMap(List<File> implSourceDirs) {
        // Phase 1: parse all impl classes and their fields + implements edges
        Map<String, ImplClassInfo> implClasses = [:] // implFQN → info

        List<File> implFiles = []
        implSourceDirs.each { baseDir ->
            if (!baseDir.exists()) return
            baseDir.eachFileRecurse { file ->
                if (!file.name.endsWith('.java') || file.name == 'package-info.java') return

                String relativePath = baseDir.toPath().relativize(file.toPath()).toString()
                String packagePath = relativePath.replace('\\', '/').replace('.java', '').replace('/', '.')

                if (!startsWithAnyPrefix(packagePath, this.implPackagePrefixes)) return

                implFiles << file
            }
        }
        implFiles.sort { a, b -> a.path <=> b.path }
        implFiles.each { file ->
            parseImplFile(file, implClasses)
        }

        logger.lifecycle("ImplementationFieldEnricher: parsed ${implClasses.size()} impl class(es)")

        // Phase 2: build contract→set(implFqn) mapping
        Map<String, Set<String>> contractToImpls = new TreeMap<>() // sorted for determinism
        Map<String, List<EnrichedField>> implToFields = [:] // implFqn → its fields

        implClasses.keySet().toList().sort().each { implFqn ->
            ImplClassInfo info = implClasses[implFqn]
            info.implementsTypes.each { contractFqn ->
                if (!startsWithAnyPrefix(contractFqn, this.contractPackagePrefixes)) return

                contractToImpls.computeIfAbsent(contractFqn) { new TreeSet<>() }.add(implFqn)
                // Store fields keyed by implFqn (only if non-empty and not already stored)
                if (!info.fields.isEmpty() && !implToFields.containsKey(implFqn)) {
                    implToFields[implFqn] = info.fields
                }
            }
        }

        // Phase 3: produce enrichmentMap only for contracts with exactly one impl
        Map<String, List<EnrichedField>> enrichmentMap = new TreeMap<>() // sorted for stable output

        contractToImpls.each { contractFqn, implSet ->
            if (implSet.size() == 0) {
                // No implementers - skip silently (shouldn't happen since we build from impls)
                return
            }
            if (implSet.size() > 1) {
                logger.warn("ImplementationFieldEnricher: skipping ${contractFqn} — ambiguous, multiple implementers: ${implSet.toList().sort().join(', ')}")
                return
            }

            String soleImpl = implSet.first()
            List<EnrichedField> fields = implToFields.getOrDefault(soleImpl, [])
            if (!fields.isEmpty()) {
                enrichmentMap[contractFqn] = fields
            }
        }

        logger.lifecycle("ImplementationFieldEnricher: ${contractToImpls.size()} contract(s) found, ${enrichmentMap.size()} enriched (single-impl with fields)")
        return enrichmentMap
    }

    /**
     * Parse a single Java file and extract all top-level and nested static class infos.
     */
    private void parseImplFile(File javaFile, Map<String, ImplClassInfo> result) {
        String content = javaFile.text

        // Extract package
        def pkgMatcher = content =~ /package\s+([\w.]+)\s*;/
        String packageName = ''
        if (pkgMatcher.find()) {
            packageName = pkgMatcher.group(1)
        }

        // Extract imports for FQN resolution
        List<String> imports = []
        def importMatcher = content =~ /import\s+([\w.*]+)\s*;/
        while (importMatcher.find()) {
            imports << importMatcher.group(1)
        }

        // Parse top-level class
        parseClassesRecursive(content, packageName, '', imports, result)
    }

    /**
     * Recursively parse class declarations (top-level and nested static) from content.
     */
    private void parseClassesRecursive(String content, String packageName, String parentFqnPrefix, List<String> imports, Map<String, ImplClassInfo> result) {
        boolean requireStatic = !parentFqnPrefix.isEmpty()
        def classPattern = requireStatic
            ? ~/(?:@\w+(?:\([^)]*\))?\s*)*(?:public\s+)?(?:(?:abstract|final)\s+)*static\s+(?:(?:abstract|final)\s+)*class\s+(\w+)(?:<[^>]+>)?(?:\s+extends\s+([\w.]+))?(?:\s+implements\s+([\w.,<>\s]+))?\s*\{/
            : ~/(?:@\w+(?:\([^)]*\))?\s*)*(?:public\s+)?(?:(?:abstract|final|static)\s+)*class\s+(\w+)(?:<[^>]+>)?(?:\s+extends\s+([\w.]+))?(?:\s+implements\s+([\w.,<>\s]+))?\s*\{/

        int searchStart = 0
        def matcher = classPattern.matcher(content)

        while (matcher.find(searchStart)) {
            String className = matcher.group(1)
            String extendsType = matcher.group(2)?.trim()
            String implementsClause = matcher.group(3)?.trim()

            int bodyStart = matcher.end() - 1 // position of '{'
            int bodyEnd = findMatchingBrace(content, bodyStart)
            if (bodyEnd <= bodyStart) {
                searchStart = matcher.end()
                continue
            }

            String classFqn
            if (parentFqnPrefix.isEmpty()) {
                classFqn = "${packageName}.${className}"
            } else {
                classFqn = "${parentFqnPrefix}.${className}"
            }

            // Resolve extends FQN
            String extendsFqn = null
            if (extendsType != null && !extendsType.isEmpty()) {
                // Handle qualified names like "PlayerEvent.EffectEvent"
                if (extendsType.contains('.')) {
                    // Could be ParentClass.NestedClass - resolve the root
                    String[] parts = extendsType.split('\\.')
                    String rootFqn = resolveSimpleName(parts[0], packageName, imports)
                    extendsFqn = rootFqn
                    for (int i = 1; i < parts.length; i++) {
                        extendsFqn += '.' + parts[i]
                    }
                } else {
                    extendsFqn = resolveSimpleName(extendsType, packageName, imports)
                }
            }

            // Parse implements types
            List<String> implementsList = []
            if (implementsClause != null && !implementsClause.isEmpty()) {
                // Split by comma, ignoring generics
                splitByCommaRespectingGenerics(implementsClause).each { rawType ->
                    String clean = rawType.trim()
                    // Strip generic parameters for FQN resolution
                    int angleIdx = clean.indexOf('<')
                    if (angleIdx > 0) clean = clean.substring(0, angleIdx)
                    // Resolve to FQN
                    String fqn = resolveContractFqn(clean, packageName, imports)
                    if (fqn != null) {
                        implementsList << fqn
                    }
                }
            }

            // Parse public instance fields from the class body
            String body = content.substring(bodyStart + 1, bodyEnd)
            List<EnrichedField> fields = parsePublicInstanceFields(body)

            ImplClassInfo info = new ImplClassInfo(
                fqn: classFqn,
                extendsFqn: extendsFqn,
                implementsTypes: implementsList,
                fields: fields
            )
            result[classFqn] = info

            // Recursively parse nested classes inside this class body
            parseClassesRecursive(body, packageName, classFqn, imports, result)

            searchStart = bodyEnd + 1
        }
    }

    /**
     * Parse public instance fields from a class body.
     * Only top-level fields (not inside nested classes).
     */
    private List<EnrichedField> parsePublicInstanceFields(String body) {
        List<EnrichedField> fields = []

        // Remove nested class/interface/enum bodies first
        String topLevelBody = removeNestedTypeBodies(body)

        def fieldPattern = ~/(?m)^\s*(?:@\w+(?:\([^)]*\))?\s*)*public\s+(?!.*\bstatic\b)(final\s+)?(?:transient\s+|volatile\s+)?(\w[\w.<>,\[\]?\s]*?)\s+([^;\r\n]+);/

        def matcher = topLevelBody =~ fieldPattern
        while (matcher.find()) {
            boolean isFinal = matcher.group(1) != null
            String fieldType = matcher.group(2).trim().replaceAll(/\s+/, ' ')
            String declarators = matcher.group(3).trim()

            if (isJavaKeyword(fieldType)) continue

            splitByCommaRespectingScopes(declarators).each { part ->
                String p = part.trim()
                if (p.isEmpty()) return
                def nameMatcher = (p =~ /^([A-Za-z_][A-Za-z0-9_]*)\b/)
                if (!nameMatcher.find()) return
                String fieldName = nameMatcher.group(1)
                fields << new EnrichedField(
                    name: fieldName,
                    type: fieldType,
                    isFinal: isFinal
                )
            }
        }

        return fields
    }

    /**
     * Split a comma-separated list while respecting nested scopes.
     * Used for parsing Java multi-declarator fields like `a = new Foo(1,2), b = new Foo(3,4)`.
     */
    private List<String> splitByCommaRespectingScopes(String input) {
        List<String> parts = []
        if (input == null || input.isEmpty()) return parts

        int angle = 0
        int paren = 0
        int bracket = 0
        int brace = 0
        int start = 0

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i)
            switch (c) {
                case '<': angle++; break
                case '>': if (angle > 0) angle--; break
                case '(': paren++; break
                case ')': if (paren > 0) paren--; break
                case '[': bracket++; break
                case ']': if (bracket > 0) bracket--; break
                case '{': brace++; break
                case '}': if (brace > 0) brace--; break
                case ',':
                    if (angle == 0 && paren == 0 && bracket == 0 && brace == 0) {
                        parts << input.substring(start, i)
                        start = i + 1
                    }
                    break
            }
        }

        if (start <= input.length()) {
            parts << input.substring(start)
        }
        return parts
    }

    /**
     * Collect fields from a class walking up its extends chain (subclass-first),
     * de-duplicating by name.
     */
    private List<EnrichedField> collectFieldsWithInheritance(String classFqn, Map<String, ImplClassInfo> allClasses) {
        List<EnrichedField> result = []
        Set<String> seenNames = [] as Set
        Set<String> visited = [] as Set // prevent cycles

        String current = classFqn
        while (current != null && !visited.contains(current)) {
            visited << current
            ImplClassInfo info = allClasses[current]
            if (info == null) break

            info.fields.each { field ->
                if (!seenNames.contains(field.name)) {
                    result << field
                    seenNames << field.name
                }
            }

            current = info.extendsFqn
        }

        return result
    }

    /**
     * Resolve a potentially nested contract interface name to its FQN.
     * Handles patterns like:
     * - {@code IPlayerEvent} → {@code noppes.npcs.api.event.IPlayerEvent}
     * - {@code IPlayerEvent.ChatEvent} → {@code noppes.npcs.api.event.IPlayerEvent.ChatEvent}
     * - {@code IForgeEvent.WorldEvent} → {@code noppes.npcs.api.event.IForgeEvent.WorldEvent}
     */
    private String resolveContractFqn(String typeName, String packageName, List<String> imports) {
        if (typeName == null || typeName.isEmpty()) return null

        // If already fully qualified
        if (typeName.contains('.') && typeName.startsWith('noppes.')) {
            return typeName
        }

        // Handle nested: e.g., "IPlayerEvent.ChatEvent"
        String rootName = typeName.contains('.') ? typeName.substring(0, typeName.indexOf('.')) : typeName
        String suffix = typeName.contains('.') ? typeName.substring(typeName.indexOf('.')) : ''

        // Check explicit imports
        String fromImport = imports.find { it.endsWith(".${rootName}") }
        if (fromImport != null) {
            return fromImport + suffix
        }

        // Check wildcard imports for event packages
        for (String imp : imports) {
            if (imp.endsWith('.*')) {
                String pkg = imp.substring(0, imp.length() - 2)
                if (startsWithAnyPrefix(pkg, this.contractPackagePrefixes)) {
                    // Assume the type is in this package
                    return "${pkg}.${rootName}${suffix}"
                }
            }
        }

        if (packageName != null && startsWithAnyPrefix(packageName, this.contractPackagePrefixes)) {
            return "${packageName}.${rootName}${suffix}"
        }
        return null
    }

    private boolean startsWithAnyPrefix(String value, Set<String> prefixes) {
        if (value == null || value.isEmpty()) return false
        if (prefixes == null || prefixes.isEmpty()) return false
        for (String p : prefixes) {
            if (p == null || p.isEmpty()) continue
            if (value.startsWith(p)) return true
        }
        return false
    }

    /**
     * Resolve a simple class name to its FQN using imports and package.
     */
    private String resolveSimpleName(String simpleName, String packageName, List<String> imports) {
        if (simpleName == null || simpleName.isEmpty()) return null

        // Check explicit imports
        String fromImport = imports.find { it.endsWith(".${simpleName}") }
        if (fromImport != null) return fromImport

        // Same package
        return "${packageName}.${simpleName}"
    }

    /**
     * Split a comma-separated string respecting angle brackets for generics.
     */
    private List<String> splitByCommaRespectingGenerics(String input) {
        List<String> result = []
        int depth = 0
        StringBuilder current = new StringBuilder()

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i)
            if (c == '<') depth++
            else if (c == '>') depth--
            else if (c == ',' && depth == 0) {
                result << current.toString()
                current = new StringBuilder()
                continue
            }
            current.append(c)
        }

        if (current.length() > 0) {
            result << current.toString()
        }
        return result
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

    /**
     * Remove nested type bodies (class, interface, enum) from a string so we only parse
     * top-level fields.
     */
    private String removeNestedTypeBodies(String body) {
        StringBuilder result = new StringBuilder()
        int depth = 0
        boolean inNestedType = false

        int i = 0
        while (i < body.length()) {
            char c = body.charAt(i)

            if (c == '{') {
                if (!inNestedType) {
                    String before = body.substring(Math.max(0, i - 200), i)
                    if (before =~ /(?:public\s+)?(?:static\s+)?(?:abstract\s+)?(?:final\s+)?(?:class|interface|enum)\s+\w+[^{]*$/) {
                        inNestedType = true
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

    private boolean isJavaKeyword(String word) {
        return ['return', 'if', 'else', 'for', 'while', 'switch', 'case', 'break',
                'continue', 'throw', 'try', 'catch', 'finally', 'new', 'this',
                'super', 'void', 'class', 'interface', 'enum', 'abstract'].contains(word)
    }

    // ── Data classes ──────────────────────────────────────────────

    static class ImplClassInfo {
        String fqn
        String extendsFqn
        List<String> implementsTypes = []
        List<EnrichedField> fields = []
    }

    static class EnrichedField {
        String name
        String type
        boolean isFinal
    }
}
