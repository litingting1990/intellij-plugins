package com.intellij.lang.javascript.flex.build;

import com.intellij.lang.javascript.flex.FlexFacet;
import com.intellij.lang.javascript.flex.FlexUtils;
import com.intellij.lang.javascript.flex.actions.airdescriptor.AirDescriptorParameters;
import com.intellij.lang.javascript.flex.actions.airdescriptor.CreateAirDescriptorAction;
import com.intellij.lang.javascript.flex.projectStructure.FlexSdk;
import com.intellij.lang.javascript.flex.projectStructure.FlexSdkManager;
import com.intellij.lang.javascript.flex.projectStructure.options.BCUtils;
import com.intellij.lang.javascript.flex.projectStructure.options.FlexIdeBuildConfiguration;
import com.intellij.lang.javascript.flex.projectStructure.options.SdkEntry;
import com.intellij.lang.javascript.flex.sdk.AirMobileSdkType;
import com.intellij.lang.javascript.flex.sdk.AirSdkType;
import com.intellij.lang.javascript.flex.sdk.FlexSdkUtils;
import com.intellij.lang.javascript.flex.sdk.FlexmojosSdkType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.compiler.CompilerMessageCategory;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.openapi.util.NullableComputable;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.ReadonlyStatusHandler;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.SystemProperties;
import com.intellij.util.text.StringTokenizer;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;

public class FlexCompilationUtils {
  private FlexCompilationUtils() {
  }

  static void deleteCacheForFile(final String filePath) throws IOException {
    final VirtualFile cacheFile = LocalFileSystem.getInstance().findFileByPath(filePath + ".cache");
    if (cacheFile != null) {
      final Ref<IOException> exceptionRef = new Ref<IOException>();

      ApplicationManager.getApplication().invokeAndWait(new Runnable() {
        public void run() {
          ApplicationManager.getApplication().runWriteAction(new Runnable() {
            public void run() {
              if (cacheFile.exists()) {
                try {
                  cacheFile.delete(this);
                }
                catch (IOException e) {
                  exceptionRef.set(e);
                }
              }
            }
          });
        }
      }, ProgressManager.getInstance().getProgressIndicator().getModalityState());

      if (!exceptionRef.isNull()) {
        throw exceptionRef.get();
      }
    }
  }

  static List<VirtualFile> getConfigFiles(final FlexBuildConfiguration config,
                                          final @NotNull Module module,
                                          final @Nullable FlexFacet flexFacet,
                                          final @Nullable String cssFilePath) throws IOException {

    final List<VirtualFile> result = new ArrayList<VirtualFile>();

    if (config.USE_CUSTOM_CONFIG_FILE && !FlexCompilerHandler.needToMergeAutogeneratedAndCustomConfigFile(config, cssFilePath != null)) {
      final String customConfigFilePath =
        config.getType() == FlexBuildConfiguration.Type.FlexUnit && config.USE_CUSTOM_CONFIG_FILE_FOR_TESTS
        ? config.CUSTOM_CONFIG_FILE_FOR_TESTS
        : config.CUSTOM_CONFIG_FILE;
      final VirtualFile customConfigFile =
        VfsUtil.findRelativeFile(customConfigFilePath, FlexUtils.getFlexCompilerWorkDir(module.getProject(), null));
      if (customConfigFile != null) {
        result.add(customConfigFile);
      }
    }

    if (!config.USE_CUSTOM_CONFIG_FILE ||
        config.getType() == FlexBuildConfiguration.Type.FlexUnit ||
        config.getType() == FlexBuildConfiguration.Type.OverriddenMainClass ||
        cssFilePath != null) {
      final String cssFileName = cssFilePath == null ? null : cssFilePath.substring(cssFilePath.lastIndexOf('/') + 1);
      final String postfix = cssFileName == null ? null : FileUtil.getNameWithoutExtension(cssFileName);

      final String facetName = flexFacet == null ? null : flexFacet.getName();
      final String name = FlexCompilerHandler.generateConfigFileName(module, facetName, config.getType().getConfigFilePrefix(), postfix);
      final String configText = FlexCompilerHandler.generateConfigFileText(module, config, cssFilePath);
      result.add(getOrCreateConfigFile(module.getProject(), name, configText));
    }
    return result;
  }

  static VirtualFile getOrCreateConfigFile(final Project project, final String name, final String text) throws IOException {
    final VirtualFile existingConfigFile = VfsUtil.findRelativeFile(name, FlexUtils.getFlexCompilerWorkDir(project, null));

    if (existingConfigFile != null && Arrays.equals(text.getBytes(), existingConfigFile.contentsToByteArray())) {
      return existingConfigFile;
    }

    final Ref<VirtualFile> fileRef = new Ref<VirtualFile>();
    final Ref<IOException> error = new Ref<IOException>();
    final Runnable runnable = new Runnable() {
      public void run() {
        fileRef.set(ApplicationManager.getApplication().runWriteAction(new NullableComputable<VirtualFile>() {
          public VirtualFile compute() {
            try {
              final String baseDirPath = FlexUtils.getTempFlexConfigsDirPath();
              final VirtualFile baseDir = VfsUtil.createDirectories(baseDirPath);

              VirtualFile configFile = baseDir.findChild(name);
              if (configFile == null) {
                configFile = baseDir.createChildData(this, name);
              }
              VfsUtil.saveText(configFile, text);
              return configFile;
            }
            catch (IOException ex) {
              error.set(ex);
            }
            return null;
          }
        }));
      }
    };

    if (ApplicationManager.getApplication().isUnitTestMode()) {
      runnable.run();
    }
    else {
      ApplicationManager.getApplication()
        .invokeAndWait(runnable, ProgressManager.getInstance().getProgressIndicator().getModalityState());
    }

    if (!error.isNull()) {
      throw error.get();
    }
    return fileRef.get();
  }

  static List<String> buildCommand(final List<String> compilerCommand,
                                   final List<VirtualFile> configFiles,
                                   final Module module,
                                   final FlexBuildConfiguration config) {
    final Sdk flexSdk = FlexUtils.getFlexSdkForFlexModuleOrItsFlexFacets(module);
    assert flexSdk != null;

    final List<String> command = new ArrayList<String>(compilerCommand);

    if (flexSdk.getSdkType() instanceof AirSdkType) {
      command.add("+configname=air");
    }
    else if (flexSdk.getSdkType() instanceof AirMobileSdkType) {
      command.add("+configname=airmobile");
    }

    final boolean useSdkConfig = config.USE_DEFAULT_SDK_CONFIG_FILE && !(flexSdk.getSdkType() instanceof FlexmojosSdkType);

    for (VirtualFile configFile : configFiles) {
      command.add("-load-config" + (useSdkConfig ? "+=" : "=") + configFile.getPath());
    }

    if (!StringUtil.isEmpty(config.ADDITIONAL_COMPILER_OPTIONS)) {
      // TODO handle -option="path with spaces"
      for (final String s : StringUtil.split(config.ADDITIONAL_COMPILER_OPTIONS, " ")) {
        command.add(FlexUtils.replacePathMacros(s, module, flexSdk.getHomePath()));
      }
    }

    return command;
  }

  static List<String> buildCommand(final List<String> compilerCommand, final List<VirtualFile> configFiles) {
    final List<String> command = new ArrayList<String>(compilerCommand);
    for (VirtualFile configFile : configFiles) {
      command.add("-load-config=" + configFile.getPath());
    }
    return command;
  }

  static List<String> getMxmlcCompcCommand(final Project project, final Sdk flexSdk, final boolean isApp) {
    final String sdkVersion = flexSdk.getVersionString();
    final List<String> command = new ArrayList<String>();

    final String className =
      isApp ? (FlexSdkUtils.isFlex4Sdk(flexSdk) ? "flex2.tools.Mxmlc" : "flex2.tools.Compiler") : "flex2.tools.Compc";


    if (!StringUtil.isEmpty(sdkVersion) &&
        StringUtil.compareVersionNumbers(sdkVersion, "3.2") >= 0 &&
        StringUtil.compareVersionNumbers(sdkVersion, "4") < 0) {

      String additionalClasspath = FileUtil.toSystemDependentName(FlexUtils.getPathToBundledJar("idea-flex-compiler-fix.jar"));
      if (!(flexSdk.getSdkType() instanceof FlexmojosSdkType)) {
        additionalClasspath += File.pathSeparator + FileUtil.toSystemDependentName(flexSdk.getHomePath() + "/lib/compc.jar");
      }

      command.addAll(FlexSdkUtils.getCommandLineForSdkTool(project, flexSdk, additionalClasspath, className, null));
    }
    else {
      command
        .addAll(FlexSdkUtils.getCommandLineForSdkTool(project, flexSdk, null, className, isApp ? "mxmlc.jar" : "compc.jar"));
    }
    return command;
  }

  /**
   * returns <code>false</code> if compilation error found in output
   */
  static boolean handleCompilerOutput(final FlexCompilationManager compilationManager,
                                      final FlexCompilationTask task,
                                      final String output) {
    boolean failureDetected = false;
    final StringTokenizer tokenizer = new StringTokenizer(output, "\r\n");

    while (tokenizer.hasMoreElements()) {
      final String text = tokenizer.nextElement();
      if (!StringUtil.isEmptyOrSpaces(text)) {

        final Matcher matcher = FlexCompilerHandler.errorPattern.matcher(text);

        if (matcher.matches()) {
          final String file = matcher.group(1);
          final String additionalInfo = matcher.group(2);
          final String line = matcher.group(3);
          final String column = matcher.group(4);
          final String type = matcher.group(5);
          final String message = matcher.group(6);

          final CompilerMessageCategory messageCategory =
            "Warning".equals(type) ? CompilerMessageCategory.WARNING : CompilerMessageCategory.ERROR;
          final VirtualFile relativeFile = VfsUtil.findRelativeFile(file, null);
          final String fullMessage = additionalInfo == null ? message : additionalInfo + " " + message;
          compilationManager.addMessage(task, messageCategory, fullMessage, relativeFile != null ? relativeFile.getUrl() : null,
                                        line != null ? Integer.parseInt(line) : 0, column != null ? Integer.parseInt(column) : 0);
          failureDetected |= messageCategory == CompilerMessageCategory.ERROR;
        }
        else if (text.startsWith("Error: ") || text.startsWith("Exception in thread \"main\" ")) {
          final String updatedText = text.startsWith("Error: ") ? text.substring("Error: ".length()) : text;
          compilationManager.addMessage(task, CompilerMessageCategory.ERROR, updatedText, null, -1, -1);
          failureDetected = true;
        }
        else {
          compilationManager.addMessage(task, CompilerMessageCategory.INFORMATION, text, null, -1, -1);
        }
      }
    }

    return !failureDetected;
  }

  private static String getOutputSwfFileNameForCssFile(final Project project, final String cssFilePath) {
    final VirtualFile cssFile = LocalFileSystem.getInstance().findFileByPath(cssFilePath);
    final VirtualFile sourceRoot = cssFile == null
                                   ? null
                                   : ProjectRootManager.getInstance(project).getFileIndex().getSourceRootForFile(cssFile);
    final String relativePath = sourceRoot == null ? null : VfsUtil.getRelativePath(cssFile, sourceRoot, '/');
    final String cssFileName = cssFilePath.substring(FileUtil.toSystemIndependentName(cssFilePath).lastIndexOf("/") + 1);
    final String relativeFolder = relativePath == null ? "" : relativePath.substring(0, relativePath.lastIndexOf('/') + 1);
    return relativeFolder + FileUtil.getNameWithoutExtension(cssFileName) + ".swf";
  }

  static FlexBuildConfiguration createCssConfig(final FlexBuildConfiguration config, final String cssFilePath) {
    final FlexBuildConfiguration cssConfig = config.clone();
    cssConfig.setType(FlexBuildConfiguration.Type.Default);
    cssConfig.OUTPUT_FILE_NAME = getOutputSwfFileNameForCssFile(config.getModule().getProject(), cssFilePath);
    cssConfig.OUTPUT_TYPE = FlexBuildConfiguration.APPLICATION;
    cssConfig.CSS_FILES_LIST.clear();
    cssConfig.PATH_TO_SERVICES_CONFIG_XML = "";
    cssConfig.CONTEXT_ROOT = "";
    return cssConfig;
  }

  public static void ensureOutputFileWritable(final Project project, final String filePath) {
    final VirtualFile file = LocalFileSystem.getInstance().findFileByPath(filePath);
    if (file != null && !file.isWritable()) {
      ApplicationManager.getApplication().invokeAndWait(new Runnable() {
        public void run() {
          ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(file);
        }
      }, ModalityState.defaultModalityState());
    }
  }

  public static void performPostCompileActions(final @NotNull FlexIdeBuildConfiguration config) throws FlexCompilerException {
    switch (config.TARGET_PLATFORM) {
      case Web:
        if (config.USE_HTML_WRAPPER) {
          // todo copy to out and replace {swf}
        }
        break;
      case Desktop:
        if (config.AIR_DESKTOP_PACKAGING_OPTIONS.USE_GENERATED_DESCRIPTOR) {
          generateAirDescriptor(config);
        }
        else {
          copyAndFixCustomAirDescriptor(config);
        }
        break;
      case Mobile:
        break;
    }
  }

  private static void generateAirDescriptor(final FlexIdeBuildConfiguration config) throws FlexCompilerException {
    final Ref<FlexCompilerException> exceptionRef = new Ref<FlexCompilerException>();

    ApplicationManager.getApplication().invokeAndWait(new Runnable() {
      public void run() {
        try {
          final String descriptorFileName = BCUtils.getGeneratedAirDescriptorName(config);
          final SdkEntry sdkEntry = config.DEPENDENCIES.getSdkEntry();
          assert sdkEntry != null;
          final FlexSdk sdk = FlexSdkManager.getInstance().findSdk(sdkEntry.getHomePath());
          assert sdk != null;
          final String airVersion = FlexSdkUtils.getAirVersion(sdk.getFlexVersion());
          final String fileName = FileUtil.getNameWithoutExtension(config.OUTPUT_FILE_NAME);

          CreateAirDescriptorAction.createAirDescriptor(
            new AirDescriptorParameters(descriptorFileName, config.OUTPUT_FOLDER, airVersion, config.MAIN_CLASS, fileName, fileName,
                                        "1.0", config.OUTPUT_FILE_NAME, fileName, 400, 300, false));
        }
        catch (IOException e) {
          exceptionRef.set(new FlexCompilerException("Failed to generate AIR descriptor: " + e));
        }
      }
    }, ModalityState.any());

    if (!exceptionRef.isNull()) {
      throw exceptionRef.get();
    }
  }

  private static void copyAndFixCustomAirDescriptor(final FlexIdeBuildConfiguration config) throws FlexCompilerException {
    final String path = config.AIR_DESKTOP_PACKAGING_OPTIONS.CUSTOM_DESCRIPTOR_PATH;
    final VirtualFile descriptorTemplateFile = LocalFileSystem.getInstance().findFileByPath(path);
    if (descriptorTemplateFile == null) {
      throw new FlexCompilerException("Custom AIR descriptor file not found: " + path);
    }

    final VirtualFile outputFolder = LocalFileSystem.getInstance().findFileByPath(config.OUTPUT_FOLDER);
    assert outputFolder != null;


    final Ref<FlexCompilerException> exceptionRef = new Ref<FlexCompilerException>();

    ApplicationManager.getApplication().invokeAndWait(new Runnable() {
      public void run() {
        ApplicationManager.getApplication().runWriteAction(new Runnable() {
          public void run() {
            try {
              final String content = fixInitialContent(descriptorTemplateFile, config.OUTPUT_FILE_NAME);
              FlexUtils.addFileWithContent(descriptorTemplateFile.getName(), content, outputFolder);
            }
            catch (FlexCompilerException e) {
              exceptionRef.set(e);
            }
            catch (IOException e) {
              exceptionRef.set(new FlexCompilerException("Failed to copy AIR descriptor to output folder", null, -1, -1));
            }
          }
        });
      }
    }, ModalityState.any());

    if (!exceptionRef.isNull()) {
      throw exceptionRef.get();
    }
  }

  private static String fixInitialContent(final VirtualFile descriptorFile, final String swfName) throws FlexCompilerException {
    try {
      final Document document;
      try {
        document = JDOMUtil.loadDocument(descriptorFile.getInputStream());
      }
      catch (IOException e) {
        throw new FlexCompilerException("Failed to read AIR descriptor content: " + e.getMessage(), descriptorFile.getUrl(), -1, -1);
      }

      final Element rootElement = document.getRootElement();
      if (rootElement == null || !"application".equals(rootElement.getName())) {
        throw new FlexCompilerException("AIR descriptor file has incorrect root tag", descriptorFile.getUrl(), -1, -1);
      }

      Element initialWindowElement = rootElement.getChild("initialWindow", rootElement.getNamespace());
      if (initialWindowElement == null) {
        initialWindowElement = new Element("initialWindow", rootElement.getNamespace());
        rootElement.addContent(initialWindowElement);
      }

      Element contentElement = initialWindowElement.getChild("content", rootElement.getNamespace());
      if (contentElement == null) {
        contentElement = new Element("content", rootElement.getNamespace());
        initialWindowElement.addContent(contentElement);
      }

      contentElement.setText(swfName);

      return JDOMUtil.writeDocument(document, SystemProperties.getLineSeparator());
    }
    catch (JDOMException e) {
      throw new FlexCompilerException("AIR descriptor file has incorrect format: " + e.getMessage(), descriptorFile.getUrl(), -1, -1);
    }
  }
}
