package com.intellij.plugins.haxe.ide.projectStructure.detection;

import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.plugins.haxe.buildsystem.lime.LimeProjectShape;
import com.intellij.util.xml.NanoXmlUtil;
import lombok.CustomLog;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@CustomLog
@UtilityClass
public class HaxeProjectFileDetectionUtil {

  public boolean isOpenFLProject(VirtualFile file) {
    return hasLimeXmlExtension(file) && shapeOf(file).isOpenfl();
  }

  public boolean isLimeProject(VirtualFile file) {
    return hasLimeXmlExtension(file) && shapeOf(file).isLime();
  }

  public boolean isHxmlProject(VirtualFile file) {
    return hasExtension(file, "hxml");
  }

  /**
   * Any file under NME's extension with a {@code <project>} root. NME needs no
   * {@code <haxelib>} or {@code <source>} (it adds itself and uses the project
   * folder), and a project declaring the openfl lib is still built by NME.
   */
  public boolean isNMMLProject(VirtualFile file) {
    return hasExtension(file, "nmml") && shapeOf(file).projectRoot();
  }

  public static List<String> sourcePaths(VirtualFile file) {
    HaxeXmlProjectParser parsed = parse(file);
    return parsed == null ? List.of() : parsed.sources;
  }

  @NotNull
  private static LimeProjectShape shapeOf(VirtualFile file) {
    HaxeXmlProjectParser parsed = parse(file);
    return parsed == null ? LimeProjectShape.NONE : parsed.shape.build();
  }

  /** The file's project elements; null when it cannot be read. */
  @Nullable
  private static HaxeXmlProjectParser parse(VirtualFile file) {
    try {
      byte[] data = VfsUtil.loadBytes(file);
      HaxeXmlProjectParser builder = new HaxeXmlProjectParser();
      NanoXmlUtil.parse(new ByteArrayInputStream(data), builder);
      return builder;
    }
    catch (IOException e) {
      log.warn("Unable to read content of file");
      return null;
    }
  }

  /** Feeds the shape from the byte stream and collects the source paths on the way. */
  private static class HaxeXmlProjectParser extends NanoXmlUtil.BaseXmlBuilder {
    private static final Set<String> SOURCE_PATH_ATTRIBUTES = Set.of("path", "name");

    final LimeProjectShape.Builder shape = new LimeProjectShape.Builder();
    final List<String> sources = new ArrayList<>();
    private String currentElement = "";
    private boolean rootSeen;

    @Override
    public void startElement(String name, @Nullable String nsPrefix, @Nullable String nsSystemID, String systemID, int lineNr) throws Exception {
      super.startElement(name, nsPrefix, nsSystemID, systemID, lineNr);
      currentElement = name.toLowerCase(Locale.ROOT);
      if (rootSeen) {
        shape.element(name);
      }
      else {
        shape.root(name);
        rootSeen = true;
      }
    }

    @Override
    public void addAttribute(String key, @Nullable String nsPrefix, @Nullable String nsSystemID, String value, String type) throws Exception {
      super.addAttribute(key, nsPrefix, nsSystemID, value, type);
      String attribute = key.toLowerCase(Locale.ROOT);
      if (attribute.equals("name")) {
        shape.library(currentElement, value);
      }
      if (LimeProjectShape.SOURCE_ELEMENTS.contains(currentElement) && SOURCE_PATH_ATTRIBUTES.contains(attribute)) {
        sources.add(value);
      }
    }
  }

  private static boolean hasLimeXmlExtension(@Nullable VirtualFile file) {
    return isFile(file) && LimeProjectShape.FILE_EXTENSIONS.contains(extensionOf(file));
  }

  private static boolean hasExtension(@Nullable VirtualFile file, String extension) {
    return isFile(file) && extensionOf(file).equals(extension);
  }

  private static boolean isFile(@Nullable VirtualFile file) {
    return file != null && !file.isDirectory();
  }

  private static String extensionOf(VirtualFile file) {
    return StringUtil.toLowerCase(StringUtil.notNullize(file.getExtension()));
  }
}
