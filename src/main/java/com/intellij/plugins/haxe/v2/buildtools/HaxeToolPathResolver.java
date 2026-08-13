package com.intellij.plugins.haxe.v2.buildtools;

import com.intellij.execution.configurations.PathEnvironmentVariableUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.plugins.haxe.config.sdk.HaxeSdkData;
import com.intellij.plugins.haxe.config.sdk.HaxeSdkType;
import com.intellij.plugins.haxe.util.HaxeSdkUtilBase;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeBuildToolSettings;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeEnvironmentStore;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Resolves tool executables for a project from the v2 build tool settings,
 * falling back to any registered Haxe SDK and finally the system PATH.
 */
public final class HaxeToolPathResolver {

  private HaxeToolPathResolver() {
  }

  /** Absolute path to the haxe compiler when resolvable, otherwise the bare executable name for PATH lookup. */
  @NotNull
  public static String resolveHaxeExecutable(@NotNull Project project) {
    return resolveHaxeExecutable(project, null);
  }

  /** Like {@link #resolveHaxeExecutable(Project)}, preferring the named SDK (a container's environment SDK). */
  @NotNull
  public static String resolveHaxeExecutable(@NotNull Project project, @Nullable String preferredSdkName) {
    Sdk sdk = findSdk(project, preferredSdkName);
    if (sdk != null && sdk.getHomePath() != null) {
      String compilerPath = HaxeSdkUtilBase.getCompilerPathByFolderPath(sdk.getHomePath());
      if (compilerPath != null) {
        return compilerPath;
      }
    }
    return HaxeSdkUtilBase.getExecutableName("haxe");
  }

  /** Absolute path to haxelib: the Build Tools setting when set, else the SDK's copy, else PATH lookup. */
  @NotNull
  public static String resolveHaxelibExecutable(@NotNull Project project) {
    return resolveHaxelibExecutable(project, null);
  }

  /** Like {@link #resolveHaxelibExecutable(Project)}, preferring the named SDK (a container's environment SDK). */
  @NotNull
  public static String resolveHaxelibExecutable(@NotNull Project project, @Nullable String preferredSdkName) {
    String configured = HaxeBuildToolSettings.getInstance(project).getHaxelibPath();
    if (!configured.isEmpty()) {
      return configured;
    }
    Sdk sdk = findSdk(project, preferredSdkName);
    if (sdk != null && sdk.getHomePath() != null) {
      String haxelibPath = HaxeSdkUtilBase.getHaxelibPathByFolderPath(sdk.getHomePath());
      if (haxelibPath != null) {
        return haxelibPath;
      }
    }
    return HaxeSdkUtilBase.getExecutableName("haxelib");
  }

  /**
   * Absolute path to the neko runtime: the Build Tools setting when set, else
   * the SDK's configured runtime, else a PATH lookup — the bare name only as
   * the last resort (run configurations validate the file exists, so a bare
   * name must have been genuinely unresolvable).
   */
  @NotNull
  public static String resolveNekoExecutable(@NotNull Project project, @Nullable String preferredSdkName) {
    String configured = HaxeBuildToolSettings.getInstance(project).getNekoPath();
    if (!configured.isEmpty()) {
      return configured;
    }
    Sdk sdk = findSdk(project, preferredSdkName);
    if (sdk != null && sdk.getSdkAdditionalData() instanceof HaxeSdkData data) {
      Path fromSdk = executableOrInDirectory(data.getNekoBinPath(), "neko");
      if (fromSdk != null) {
        return fromSdk.toString();
      }
    }
    File onPath = PathEnvironmentVariableUtil.findInPath(HaxeSdkUtilBase.getExecutableName("neko"));
    return onPath != null ? onPath.getAbsolutePath() : HaxeSdkUtilBase.getExecutableName("neko");
  }

  /** The configured value may point at the executable itself or its directory. */
  @Nullable
  private static Path executableOrInDirectory(@Nullable String configuredPath, @NotNull String executableName) {
    if (configuredPath == null || configuredPath.isBlank()) return null;
    Path candidate = Path.of(configuredPath);
    if (Files.isRegularFile(candidate)) return candidate;
    Path inDirectory = candidate.resolve(HaxeSdkUtilBase.getExecutableName(executableName));
    return Files.isRegularFile(inDirectory) ? inDirectory : null;
  }

  @Nullable
  private static Sdk findSdk(@NotNull Project project, @Nullable String preferredSdkName) {
    if (preferredSdkName != null) {
      Sdk preferred = ProjectJdkTable.getInstance().findJdk(preferredSdkName);
      if (preferred != null) {
        return preferred;
      }
    }
    return findConfiguredSdk(project);
  }

  /**
   * The SDK the container's tool invocations run with: its Environment SDK when
   * registered, else the Build Tools SDK, else any registered Haxe SDK — the
   * same fallback chain the resolve*Executable methods use. Contrast
   * {@link #effectiveSdkName}, which reports what is CONFIGURED; this reports
   * what will actually be USED.
   */
  @Nullable
  public static Sdk resolveSdk(@NotNull Project project, @NotNull String containerId) {
    return findSdk(project, HaxeEnvironmentStore.getInstance(project).getSdkName(containerId));
  }

  /**
   * The SDK a module compiles and resolves against: its Environment SDK when
   * set, else the project-wide one from Build Tools | Haxe. Null when neither
   * is configured — the editor then asks the user to pick one. This is the
   * single authority; nothing consults the Project SDK. For the SDK a tool
   * process should actually run with (permissive fallbacks), use
   * {@link #resolveSdk}.
   */
  @Nullable
  public static String effectiveSdkName(@NotNull Project project, @NotNull String containerId) {
    String environmentSdk = HaxeEnvironmentStore.getInstance(project).getSdkName(containerId);
    if (environmentSdk != null && ProjectJdkTable.getInstance().findJdk(environmentSdk) != null) {
      return environmentSdk;
    }
    String settingsSdk = HaxeBuildToolSettings.getInstance(project).getSdkName();
    return settingsSdk != null && ProjectJdkTable.getInstance().findJdk(settingsSdk) != null ? settingsSdk : null;
  }

  /** The SDK selected in Build Tools | Haxe, or the first registered Haxe SDK, or null. */
  @Nullable
  public static Sdk findConfiguredSdk(@NotNull Project project) {
    String sdkName = HaxeBuildToolSettings.getInstance(project).getSdkName();
    if (sdkName != null) {
      Sdk sdk = ProjectJdkTable.getInstance().findJdk(sdkName);
      if (sdk != null) {
        return sdk;
      }
    }
    List<Sdk> haxeSdks = ProjectJdkTable.getInstance().getSdksOfType(HaxeSdkType.getInstance());
    return haxeSdks.isEmpty() ? null : haxeSdks.get(0);
  }
}
