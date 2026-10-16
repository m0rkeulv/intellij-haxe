package com.intellij.plugins.haxe.buildsystem;

import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Set;

/**
 * The element and attribute names of Lime, OpenFL and NME project files that
 * the plugin reads, and the path conventions those tools share. Element names
 * compare case-insensitively, as the tools read them.
 */
public final class ProjectXmlVocabulary {

  public static final String ROOT_ELEMENT = "project";
  /** {@code <haxelib name version>}: a library dependency. */
  public static final String LIBRARY_ELEMENT = "haxelib";
  /** {@code <include path>}: another project file merged into this one; with a {@code haxelib} attribute it names a library's include file instead. */
  public static final String INCLUDE_ELEMENT = "include";
  /** {@code <app file path>}: the launcher the tool produces and the output root it exports under. */
  public static final String APP_ELEMENT = "app";
  /** The elements that declare a compiler define through their {@code name} (and optional {@code value}) attribute. */
  public static final Set<String> DEFINE_ELEMENTS = Set.of("haxedef", "define");
  /** {@code <source path>} and its alias {@code <classpath path>}: a class path. */
  public static final Set<String> SOURCE_ELEMENTS = Set.of("source", "classpath");
  /** The elements whose {@code path} attribute names a file or folder. */
  public static final Set<String> PATH_ELEMENTS = Set.of("include", "source", "classpath", "assets", "icon", "template");
  /** The elements that also take the legacy {@code name} spelling of their path (historic nmml wrote {@code <classpath name>}). */
  public static final Set<String> LEGACY_NAME_ELEMENTS = Set.of("include", "source", "classpath");

  public static final String NAME_ATTRIBUTE = "name";
  public static final String PATH_ATTRIBUTE = "path";
  public static final String VALUE_ATTRIBUTE = "value";
  public static final String VERSION_ATTRIBUTE = "version";
  public static final String HAXELIB_ATTRIBUTE = "haxelib";

  private ProjectXmlVocabulary() {
  }

  /** Whether the tool substitutes into the path before using it ({@code ${haxelib:x}}, {@code ${define}}); the IDE cannot resolve such a path. */
  public static boolean isToolExpanded(@NotNull String path) {
    return path.contains("${");
  }

  /** Whether the element takes the legacy {@code name} spelling of its path. */
  public static boolean takesLegacyName(@NotNull String elementName) {
    return LEGACY_NAME_ELEMENTS.contains(elementName.toLowerCase(Locale.ROOT));
  }

  /** The attribute carrying the element's path: {@code path}, else the legacy {@code name} where the element takes it; null when neither is present. */
  @Nullable
  public static XmlAttribute pathAttribute(@NotNull XmlTag tag) {
    XmlAttribute path = tag.getAttribute(PATH_ATTRIBUTE);
    if (path != null) return path;
    return takesLegacyName(tag.getLocalName()) ? tag.getAttribute(NAME_ATTRIBUTE) : null;
  }

  /** Whether the attribute names a file or folder: {@code path} on a path element, or the legacy {@code name} where the element takes it. */
  public static boolean isPathAttribute(@NotNull XmlAttribute attribute) {
    XmlTag tag = attribute.getParent();
    if (tag == null) return false;
    String element = tag.getLocalName().toLowerCase(Locale.ROOT);
    String name = attribute.getLocalName();
    boolean path = PATH_ATTRIBUTE.equals(name) && PATH_ELEMENTS.contains(element);
    boolean legacyName = NAME_ATTRIBUTE.equals(name) && takesLegacyName(element);
    return path || legacyName;
  }
}
