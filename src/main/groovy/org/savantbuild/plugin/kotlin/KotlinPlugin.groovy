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
import java.util.stream.Collectors

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
      "  1.3=/Library/Kotlin/1.3.10\n"
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
    compileInternal(layout.mainSourceDirectory, layout.javaSourceDirectory, layout.mainBuildDirectory, settings.mainDependencies, layout.mainBuildDirectory)
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
    compileInternal(layout.testSourceDirectory, layout.testJavaSourceDirectory, layout.testBuildDirectory, settings.testDependencies, layout.mainBuildDirectory, layout.testBuildDirectory)
    copyResources(layout.testResourceDirectory, layout.testBuildDirectory)
  }

  /**
   * This could go in file tools, but I put it here to avoid rolling a release of core
   * @param sourceDir The source directory to look for things
   * @param filter A filter to limit what returns
   * @return The files it found
   */
  private static List<Path> allFiles(Path sourceDir, Predicate<Path> filter) {
    if (!Files.isDirectory(sourceDir)) {
      return Collections.emptyList()
    }

    try {
      return Files.walk(sourceDir).filter(filter)
          .map({ path -> path.subpath(sourceDir.getNameCount(), path.getNameCount()) })
          .collect(Collectors.toList())
    } catch (IOException e) {
      throw new IllegalStateException("Unable to search the source directory", e)
    }
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
   * @param kotlinDirectory The directories that contains the kotlin files.
   * @param javaDirectory The java directories that contain classes kotlin might use.
   * @param buildDirectory The build directory to compile the kotlin files to.
   * @param dependencies The dependencies of the project to include in the compile classpath.
   */
  private void compileInternal(Path kotlinDirectory, Path javaDirectory, Path buildDirectory, List<Map<String, Object>> dependencies, Path... additionalClasspath) {

    Path resolvedBuildDir = project.directory.resolve(buildDirectory)
    Files.createDirectories(resolvedBuildDir)

    // Find kotlin files
    Path resolvedSourceDir = project.directory.resolve(kotlinDirectory)

    output.debugln("Looking for modified files to compile in [${resolvedSourceDir}] compared with [${resolvedBuildDir}]")

    Predicate<Path> kotlinFilter = FileTools.extensionFilter(".kt")
    Function<Path, Path> mapper = FileTools.extensionMapper(".kt", ".class")
    List<Path> filesToCompile = FileTools.modifiedFiles(resolvedSourceDir, resolvedBuildDir, kotlinFilter, mapper)
        .collect({ path -> kotlinDirectory.resolve(path) })


    if (filesToCompile.isEmpty()) {
      output.infoln("Skipping compile for source directory [${kotlinDirectory}]. No files need compiling")
      return
    }

    // Find java files
    Path resolvedJavaSourceDir = project.directory.resolve(javaDirectory)

    output.debugln("Looking for java files that kotlin might need in [${resolvedJavaSourceDir}]")

    Predicate<Path> javaFilter = FileTools.extensionFilter(".java")
    List<Path> javaFiles = allFiles(resolvedJavaSourceDir, javaFilter)
        .collect { path -> javaDirectory.resolve(path) }

    output.infoln "Compiling [${filesToCompile.size()}] Kotlin classes from [${kotlinDirectory}] to [${buildDirectory}]"

    String command = "${kotlincPath} ${settings.compilerArguments} ${classpath(dependencies, settings.libraryDirectories, additionalClasspath)} -jdk-home ${javaHome} -jvm-target ${settings.javaVersion} -d ${buildDirectory} ${filesToCompile.join(" ")} ${javaFiles.join(" ")}"
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

    jarInternal(project.toArtifact().getArtifactFile(), layout.mainBuildDirectory)
    jarInternal(project.toArtifact().getArtifactSourceFile(), layout.mainSourceDirectory, layout.mainResourceDirectory, layout.javaSourceDirectory)
    jarInternal(project.toArtifact().getArtifactTestFile(), layout.testBuildDirectory)
    jarInternal(project.toArtifact().getArtifactTestSourceFile(), layout.testSourceDirectory, layout.testResourceDirectory, layout.testJavaSourceDirectory)
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
  private void jarInternal(String jarFile, Path... directories) {
    def absolutePath = layout.jarOutputDirectory.resolve(jarFile)

    output.infoln("Creating JAR [${absolutePath}]")

    filePlugin.jar(file: absolutePath) {
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
