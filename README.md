# dts-gradle-plugin

This repo contains the Gradle plugin for generating TypeScript `.d.ts` files for the CustomNPC-Plus API.

These files are used for the generation of auto-completion suggestions and type/interface documentation when scripting
in the Script Editor in-game.

### Implementation using JitPack

Add JitPack to the buildscript and depend on the plugin JAR. Using `master-SNAPSHOT` will build the latest `main` commit;
for reproducible builds use a tag or commit hash instead.

`build.gradle` (consumer):

```gradle
buildscript {
  repositories {
    maven { url 'https://jitpack.io' }
    mavenLocal()
    mavenCentral()
  }
  dependencies {
    // Use a tag or commit for reproducibility.
    classpath 'com.github.bigguy345:dts-gradle-plugin:master-SNAPSHOT'
  }
}

import dts.GenerateTypeScriptTask

task generateTypeScriptDefinitions(type: GenerateTypeScriptTask) {
  sourceDirectories = [file('src/api/java')]
  outputDirectory = file('src/main/resources/assets/customnpcs/api')
  apiPackages = ['noppes.npcs.api'] as Set
  cleanOutputFirst = true
}

processResources.dependsOn generateTypeScriptDefinitions
```
