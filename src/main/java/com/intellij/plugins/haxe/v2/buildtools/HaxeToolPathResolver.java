package com.intellij.plugins.haxe.v2.buildtools;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.plugins.haxe.config.sdk.HaxeSdkType;
import com.intellij.plugins.haxe.util.HaxeSdkUtilBase;
import com.intellij.plugins.haxe.v2.buildtools.settings.HaxeBuildToolSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
