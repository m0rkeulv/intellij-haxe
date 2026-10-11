package com.intellij.plugins.haxe.ide.references;

import com.intellij.patterns.XmlPatterns;
import com.intellij.plugins.haxe.v2.buildsystem.HaxeProjectXmlFiles;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceContributor;
import com.intellij.psi.PsiReferenceProvider;
import com.intellij.psi.PsiReferenceRegistrar;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Set;

/// Makes the path attributes of a Lime, OpenFL or NME project file navigable:
/// `<include path|name>`, `<source path>`, `<classpath path|name>`,
/// `<assets path>`, `<icon path>` and `<template path>` resolve against the
/// project file's folder, and an `<include>` naming a folder opens the
/// include.lime / include.nmml / include.xml Lime picks inside it. The
/// references are soft: a path Lime expands (`${haxelib:x}`, environment
/// variables) gets none, and one naming nothing is never an error.
/// TODO: `<haxelib name>` to the library's own include file needs the haxelib
///       repository root without running a process (today `haxelib config`).
public class HaxeProjectXmlPathReferenceContributor extends PsiReferenceContributor {

  /** The elements whose {@code path} attribute names a file or folder. */
  private static final Set<String> PATH_TAGS = Set.of("include", "source", "classpath", "assets", "icon", "template");
  /** The elements that also take the legacy {@code name} spelling of the path. */
  private static final Set<String> LEGACY_NAME_TAGS = Set.of("include", "classpath");
  private static final String PATH_ATTRIBUTE = "path";
  private static final String NAME_ATTRIBUTE = "name";

  @Override
  public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
    registrar.registerReferenceProvider(XmlPatterns.xmlAttributeValue(PATH_ATTRIBUTE, NAME_ATTRIBUTE), new PathReferenceProvider());
  }

  /** Whether the value belongs to a path attribute, by its attribute's and element's names. */
  private static boolean isPathAttribute(@NotNull XmlAttributeValue value) {
    XmlAttribute attribute = PsiTreeUtil.getParentOfType(value, XmlAttribute.class);
    XmlTag tag = attribute == null ? null : attribute.getParent();
    if (tag == null) return false;
    String tagName = tag.getLocalName().toLowerCase(Locale.ROOT);
    boolean path = PATH_ATTRIBUTE.equals(attribute.getLocalName()) && PATH_TAGS.contains(tagName);
    boolean legacyName = NAME_ATTRIBUTE.equals(attribute.getLocalName()) && LEGACY_NAME_TAGS.contains(tagName);
    return path || legacyName;
  }

  private static final class PathReferenceProvider extends PsiReferenceProvider {

    @Override
    public PsiReference @NotNull [] getReferencesByElement(@NotNull PsiElement element, @NotNull ProcessingContext context) {
      XmlAttributeValue value = (XmlAttributeValue)element;
      if (!isPathAttribute(value) || !HaxeProjectXmlFiles.isProjectXml(value.getContainingFile())) return PsiReference.EMPTY_ARRAY;
      String path = value.getValue().trim();
      if (path.isEmpty() || path.contains("${")) return PsiReference.EMPTY_ARRAY;
      return new PsiReference[]{new HaxeProjectXmlPathReference(value, path)};
    }
  }
}
