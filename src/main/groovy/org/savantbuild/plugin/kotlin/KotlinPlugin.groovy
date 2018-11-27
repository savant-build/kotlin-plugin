/*
 * Copyright (c) 2018, Inversoft Inc., All Rights Reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package org.savantbuild.plugin.kotlin

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.function.Function
import java.util.function.Predicate

import org.savantbuild.dep.domain.ArtifactID
import org.savantbuild.domain.Project
import org.savantbuild.io.FileTools
import org.savantbuild.output.Output
import org.savantbuild.plugin.dep.DependencyPlugin
import org.savantbuild.plugin.file.FilePlugin
import org.savantbuild.plugin.groovy.BaseGroovyPlugin
import org.savantbuild.runtime.RuntimeConfiguration

/**
 * The Kotlin plugin. The public methods on this class define the features of the plugin.
 */
class KotlinPlugin extends BaseGroovyPlugin {
  public static
  final String ERROR_MESSAGE = "You must create the file [~/.savant/plugins/org.savantbuild.plugin.kotlin.properties] " +
      "that contains the system configuration for the Kotlin plugin. This file should include the location of the Kotlin SDK " +
      "(kotlin and kotlinc) by version. These properties look like this:\n\n" +
      "  1.3=/Users/tyler/.sdkman/candidates/kotlin/1.3.10\n"
  public static
  final String JAVA_ERROR_MESSAGE = "You must create the file [~/.savant/plugins/org.savantbuild.plugin.java.properties] " +
      "that contains the system configuration for the Java system. This file should include the location of the JDK " +
      "(java and javac) by version. These properties look like this:\n\n" +
      "  1.6=/Library/Java/JavaVirtualMachines/1.6.0_65-b14-462.jdk/Contents/Home\n" +
      "  1.7=/Library/Java/JavaVirtualMachines/jdk1.7.0_10.jdk/Contents/Home\n" +
      "  1.8=/Library/Java/JavaVirtualMachines/jdk1.8.0.jdk/Contents/Home\n"
  KotlinLayout layout = new KotlinLayout()
  KotlinSettings settings = new KotlinSettings()
  Properties properties
  Properties javaProperties
  String kotlinHome
  Path kotlincPath
  Path dokkaPath
  String javaHome
  FilePlugin filePlugin
  DependencyPlugin dependencyPlugin

  KotlinPlugin(Project project, RuntimeConfiguration runtimeConfiguration, Output output) {
    super(project, runtimeConfiguration, output)
    filePlugin = new FilePlugin(project, runtimeConfiguration, output)
    dependencyPlugin = new DependencyPlugin(project, runtimeConfiguration, output)
    properties = loadConfiguration(new ArtifactID("org.savantbuild.plugin", "kotlin", "kotlin", "jar"), ERROR_MESSAGE)
    javaProperties = loadConfiguration(new ArtifactID("org.savantbuild.plugin", "java", "java", "jar"), JAVA_ERROR_MESSAGE)
  }

  /**
   * Cleans the build directory by completely deleting it.
   * <p>
   * Here is an example of calling this method:
   * <p>
   * <pre>
   *   kotlin.clean()
   * </pre>
   */
  void clean() {
    Path buildDir = project.directory.resolve(layout.buildDirectory)
    output.infoln "Cleaning [${buildDir}]"
    FileTools.prune(buildDir)
  }

  /**
   * Compiles the main and test Java files (src/main/kotlin and src/test/kotlin).
   * <p>
   * Here is an example of calling this method:
   * <p>
   * <pre>
   *   kotlin.compile()
   * </pre>
   */
  void compile() {
    compileMain()
    compileTest()
  }

  /**
   * Compiles the main Kotlin files (src/main/kotlin by default).
   * <p>
   * Here is an example of calling this method:
   * <p>
   * <pre>
   *   kotlin.compileMain()
   * </pre>
   */
  void compileMain() {
    initialize()
    compile(layout.mainSourceDirectory, layout.mainBuildDirectory, settings.mainDependencies, layout.mainBuildDirectory)
    copyResources(layout.mainResourceDirectory, layout.mainBuildDirectory)
  }

  /**
   * Compiles the test Kotlin files (src/test/kotlin by default).
   * <p>
   * Here is an example of calling this method:
   * <p>
   * <pre>
   *   kotlin.compileTest()
   * </pre>
   */
  void compileTest() {
    initialize()
    compile(layout.testSourceDirectory, layout.testBuildDirectory, settings.testDependencies, layout.mainBuildDirectory, layout.testBuildDirectory)
    copyResources(layout.testResourceDirectory, layout.testBuildDirectory)
  }

  /**
   * Compiles an arbitrary source directory to an arbitrary build directory.
   * <p>
   * Here is an example of calling this method:
   * <p>
   * <pre>
   *   kotlin.compile(Paths.get("src/foo"), Paths.get("build/bar"), [[group: "compile", transitive: false, fetchSource: false]], Paths.get("additionalClasspathDirectory"))
   * </pre>
   *
   * @param sourceDirectory The source directory that contains the kotlin source files.
   * @param buildDirectory The build directory to compile the kotlin files to.
   * @param dependencies The dependencies of the project to include in the compile classpath.
   */
  void compile(Path sourceDirectory, Path buildDirectory, List<Map<String, Object>> dependencies, Path... additionalClasspath) {
    Path resolvedSourceDir = project.directory.resolve(sourceDirectory)
    Path resolvedBuildDir = project.directory.resolve(buildDirectory)
    Files.createDirectories(resolvedBuildDir)

    output.debugln("Looking for modified files to compile in [${resolvedSourceDir}] compared with [${resolvedBuildDir}]")

    Predicate<Path> filter = FileTools.extensionFilter(".kt")
    Function<Path, Path> mapper = FileTools.extensionMapper(".kt", ".class")
    List<Path> filesToCompile = FileTools.modifiedFiles(resolvedSourceDir, resolvedBuildDir, filter, mapper)
        .collect({ path -> sourceDirectory.resolve(path) })
    if (filesToCompile.isEmpty()) {
      output.infoln("Skipping compile for source directory [${sourceDirectory}]. No files need compiling")
      return
    }

    output.infoln "Compiling [${filesToCompile.size()}] Kotlin classes from [${sourceDirectory}] to [${buildDirectory}]"

    String command = "${kotlincPath} ${settings.compilerArguments} ${classpath(dependencies, settings.libraryDirectories, additionalClasspath)} -jdk-home ${javaHome} -d ${buildDirectory} ${filesToCompile.join(" ")}"
    output.debugln("Executing [${command}]")

    Process process = command.execute(["JAVA_HOME=${javaHome}", "KOTLIN_HOME=${kotlinHome}"], project.directory.toFile())
    process.consumeProcessOutput((Appendable) System.out, System.err)
    process.waitFor()

    int exitCode = process.exitValue()
    if (exitCode != 0) {
      fail("Compilation failed")
    }
  }

  /**
   * Copies the resource files from the source directory to the build directory. This copies all of the files
   * recursively to the build directory.
   * <p>
   * Here is an example of calling this method:
   * <p>
   * <pre>
   *   kotlin.copyResources(Paths.get("src/some-resources"), Paths.get("build/output-dir"))
   * </pre>
   *
   * @param sourceDirectory The source directory that contains the files to copy.
   * @param buildDirectory The build directory to copy the files to.
   */
  void copyResources(Path sourceDirectory, Path buildDirectory) {
    if (!Files.isDirectory(project.directory.resolve(sourceDirectory))) {
      return
    }

    filePlugin.copy(to: buildDirectory) {
      fileSet(dir: sourceDirectory)
    }
  }

  /**
   * Creates the project's KDoc. This executes the dokka command and outputs the docs to the {@code layout.docDirectory}
   * <p>
   * Here is an example of calling this method:
   * <p>
   * <pre>
   *   kotlin.document()
   * </pre>
   */
  void document() {
    // TODO
//    initialize()
//
//    output.infoln "Generating KDoc to [${layout.docDirectory}]"
//
//    FileSet fileSet = new FileSet(project.directory.resolve(layout.mainSourceDirectory))
//    Set<String> packages = fileSet.toFileInfos()
//                                  .stream()
//                                  .map({ info -> info.relative.getParent().toString().replace("/", ".") })
//                                  .collect(Collectors.toSet())
//
//    String command = "${dokkaPath} ${layout.mainSourceDirectory} ${classpath(settings.mainDependencies, settings.libraryDirectories)} ${settings.docArguments} -output ${layout.docDirectory} ${packages.join(" ")}"
//    output.debugln("Executing [${command}]")
//
//    Process process = command.execute(["JAVA_HOME=${javaHome}", "KOTLIN_HOME=${kotlinHome}"], project.directory.toFile())
//    process.consumeProcessOutput((Appendable) System.out, System.err)
//    process.waitFor()
//
//    int exitCode = process.exitValue()
//    if (exitCode != 0) {
//      fail("dokka failed")
//    }
  }

  /**
   * Creates the project's Jar files. This creates four Jar files. The main Jar, main source Jar, test Jar and test
   * source Jar.
   * <p>
   * Here is an example of calling this method:
   * <p>
   * <pre>
   *   kotlin.jar()
   * </pre>
   */
  void jar() {
    initialize()

    jar(layout.jarOutputDirectory.resolve(project.toArtifact().getArtifactFile()), layout.mainBuildDirectory)
    jar(layout.jarOutputDirectory.resolve(project.toArtifact().getArtifactSourceFile()), layout.mainSourceDirectory, layout.mainResourceDirectory)
    jar(layout.jarOutputDirectory.resolve(project.toArtifact().getArtifactTestFile()), layout.testBuildDirectory)
    jar(layout.jarOutputDirectory.resolve(project.toArtifact().getArtifactTestSourceFile()), layout.testSourceDirectory, layout.testResourceDirectory)
  }

  /**
   * Creates a single Jar file by adding all of the files in the given directories.
   * <p>
   * Here is an example of calling this method:
   * <p>
   * <pre>
   *   kotlin.jar(Paths.get("foo/bar.jar"), Paths.get("src/main/kotlin"), Paths.get("some-other-dir"))
   * </pre>
   *
   * @param jarFile The Jar file to create.
   * @param directories The directories to include in the Jar file.
   */
  void jar(Path jarFile, Path... directories) {
    output.infoln("Creating JAR [${jarFile}]")

    filePlugin.jar(file: jarFile) {
      directories.each { dir ->
        optionalFileSet(dir: dir)
      }
      manifest(map: settings.jarManifest)
    }
  }

  private String classpath(List<Map<String, Object>> dependenciesList, List<Path> libraryDirectories, Path... additionalPaths) {
    List<Path> additionalJARs = new ArrayList<>()
    if (libraryDirectories != null) {
      libraryDirectories.each { path ->
        Path dir = project.directory.resolve(FileTools.toPath(path))
        if (!Files.isDirectory(dir)) {
          return
        }

        Files.list(dir).filter(FileTools.extensionFilter(".jar")).forEach { file -> additionalJARs.add(file.toAbsolutePath()) }
      }
    }

    return dependencyPlugin.classpath {
      dependenciesList.each { deps -> dependencies(deps) }
      additionalPaths.each { additionalPath -> path(location: additionalPath) }
      additionalJARs.each { additionalJAR -> path(location: additionalJAR) }
    }.toString("-classpath ")
  }

  private void initialize() {
    if (!settings.kotlinVersion) {
      fail("You must configure the Kotlin version to use with the settings object. It will look something like this:\n\n" +
          "  kotlin.settings.kotlinVersion=\"2.1\"")
    }

    kotlinHome = properties.getProperty(settings.kotlinVersion)
    if (!kotlinHome) {
      fail("No SDK is configured for version [${settings.kotlinVersion}].\n\n" + ERROR_MESSAGE)
    }

    kotlincPath = Paths.get(kotlinHome, "bin/kotlinc")
    if (!Files.isRegularFile(kotlincPath)) {
      fail("The kotlinc compiler [${kotlincPath.toAbsolutePath()}] does not exist.")
    }
    if (!Files.isExecutable(kotlincPath)) {
      fail("The kotlinc compiler [${kotlincPath.toAbsolutePath()}] is not executable.")
    }

    // TODO
//    dokkaPath = Paths.get(kotlinHome, "bin/dokka-fatjar.jar")
//    if (!Files.isRegularFile(dokkaPath)) {
//      fail("The dokka-fatjar.jar [${dokkaPath.toAbsolutePath()}] does not exist.")
//    }

    if (!settings.javaVersion) {
      fail("You must configure the Java version to use with the settings object. It will look something like this:\n\n" +
          "  kotlin.settings.javaVersion=\"1.8\"")
    }

    javaHome = javaProperties.getProperty(settings.javaVersion)
    if (!javaHome) {
      fail("No JDK is configured for version [${settings.javaVersion}].\n\n" + JAVA_ERROR_MESSAGE)
    }
  }


}
