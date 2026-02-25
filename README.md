# dts-gradle-plugin

This repo contains the Gradle plugin for generating TypeScript `.d.ts` files from Java sources.

These files are used for the generation of auto-completion suggestions and type/interface documentation when scripting
in the Script Editor in-game.

## What It Generates

The `generateTypeScriptDefinitions` task generates `.d.ts` files for the Java sources under `sourceDirectories`, limited
to the packages listed in `apiPackages`.

In addition to generating method signatures from the API interfaces/classes, the generator can optionally **enrich API
interfaces with public instance fields that exist on shipped implementation classes**. This is used to support existing
scripts that access implementation fields directly (for example: 'event.npc', 'event.player').

Enrichment rules (high level):
- Only `public` instance fields are considered (no `static`).
- `final` fields become TypeScript `readonly` fields.
- Enrichment is "best effort":
  - 0 implementers for a contract interface: skipped.
  - >1 implementer for a contract interface: skipped (to avoid lying).
- Output is deterministic (stable ordering) given the same sources.

### Implementation using JitPack

Add JitPack to the buildscript and depend on the plugin JAR. Using `main-SNAPSHOT` will build the latest `main` commit;
for reproducible builds use a tag or commit hash instead.

`settings.gradle`
```gradle
pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if(requested.id.toString() == "dts.typescript-generator") {
                useModule("com.github.bigguy345:dts-gradle-plugin:main-SNAPSHOT")
            }
        }
    }

    
    repositories {
        maven { url "https://jitpack.io" }
        gradlePluginPortal()
        mavenCentral()
        mavenLocal()
    }
}
```




`build.gradle`:

```gradle
plugins {
    id 'dts.typescript-generator'
}

// ============================================================================
// TypeScript Definition Generation Task
// Generates .d.ts files from Java API sources for scripting IDE support
// ============================================================================
// TypeScript plugin is applied above in the main plugins block

tasks.named("generateTypeScriptDefinitions").configure {
    // Source directories containing the Java contract sources AND (optionally) implementation sources.
    // If you enable enrichment via implementationPackages, make sure the relevant implementation sources
    // are included here so they can be scanned.
    // Example (CustomNPC-Plus layout): contracts under src/api/java, implementations under src/main/java
    sourceDirectories = ['src/api/java', 'src/main/java']
    
    // Packages in source directories to generate .d.ts files for
    apiPackages = ['noppes.npcs.api'] as Set

    // Output directory for the generated .d.ts files
    // Must be within resources/assets/${modId}/api to be detected by CNPC+
    outputDirectory = "src/main/resources/assets/${modId}/api"
    
    // Whether to clean old generated files before regenerating
    cleanOutputFirst = true 

    // Whether to map Java primitives (int, float, double, etc.) to TypeScript 'number'
    // Set to false (default) to preserve Java primitive types in generated .d.ts for better API clarity
    // Set to true to map all Java primitives to TS equivalents (int -> number, float -> number, etc.)
    mapJavaPrimitivesToJS = false
    
    // Optional: copy external patch .d.ts files into assets/<modid>/api/patches
    patchesDirectory = "dts-patches"

    // Optional: enable implementation-backed field enrichment.
    // These are package prefixes for implementation classes. When provided, the generator scans
    // sourceDirectories for classes under these packages that implement interfaces under apiPackages,
    // and injects their public instance fields into the generated contract interface .d.ts.
    //
    // IMPORTANT: Avoid including your API package itself (e.g. '...api') here, otherwise you may
    // scan API helper classes and create unnecessary ambiguity/overhead.
    implementationPackages = ['noppes.npcs.scripted'] as Set
}


// Optional: To ensure definitions are generated on processing resources on jar build
// But in most cases, you may want to run the task manually when needed
// processResources.dependsOn generateTypeScriptDefinitions
```

---

## Patches (optional overrides)

Use `dts-patches/` to override or refine generated types. Files in this folder are copied to
`assets/<modid>/api/patches` after generation.

### Example: override `IPlayer.getDBCPlayer()` to return `IDBCAddon` instead of `IDBCPlayer`

File: `dts-patches/IPlayer.d.ts`

```ts
/**
 * DBC Addon patch for IPlayer
 * @javaFqn noppes.npcs.api.entity.IPlayer
 */
export interface IPlayer<T> {
  getDBCPlayer(): IDBCAddon;
}
```

