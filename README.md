# dts-gradle-plugin

This repo contains the Gradle plugin for generating TypeScript `.d.ts` files from the CustomNPC Java API.

## Goal
Host this `gradle-plugins` project on GitHub so other repositories can depend on the plugin directly from the remote and (optionally) consume the latest build.

## Recommended consumption method: JitPack

JitPack builds artifacts directly from GitHub refs. This makes it easy for other projects to depend on the latest code without publishing to a central registry.

### Push to GitHub (example)

```bash
git remote add origin git@github.com:<your-user>/dts-gradle-plugin.git
git push -u origin main
git tag v1.0.0
git push origin v1.0.0
```

### Use JitPack in a consuming project (classpath approach — recommended)

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
    // Replace <your-user> accordingly. Use a tag or commit for reproducibility.
    classpath 'com.github.<your-user>:dts-gradle-plugin:master-SNAPSHOT'
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

### Alternative: Gradle `plugins {}` block

If you prefer `plugins { id 'dts.typescript-generator' version 'x.y.z' }` you must publish plugin marker metadata (the `java-gradle-plugin` and `publishing` blocks must emit the marker artifacts). This can be published to JitPack (if the build produces them), GitHub Packages, or the Gradle Plugin Portal.

### Notes & recommendations

- `master-SNAPSHOT` gives you the freshest code, but is not reproducible — prefer tags for CI.
- For local development, publishing to `mavenLocal()` is convenient; consumers can use `mavenLocal()` in `pluginManagement` or `buildscript`.
- If you want me to prepare plugin marker publishing (so `plugins {}` works), I can adjust `build.gradle` to emit the marker artifacts.

---

If you want, tell me your GitHub username and I will:

- create a `v1.0.0` tag locally here, and
- add a short `USAGE.md` with copy/paste snippets for consumers tailored to your username.
