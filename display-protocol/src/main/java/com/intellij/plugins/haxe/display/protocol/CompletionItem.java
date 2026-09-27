package com.intellij.plugins.haxe.display.protocol;

import org.jetbrains.annotations.Nullable;

/**
 * One item of a {@code display/completion} result, reduced to what a lookup
 * needs: the item kind ({@code Local}, {@code ClassField}, {@code EnumField},
 * {@code EnumAbstractField}, {@code Type}, {@code Package}, {@code Module},
 * {@code Literal}, {@code Metadata}, {@code Keyword}, {@code TypeParameter},
 * {@code Define}), the text to insert, a detail (a type's qualified path, a
 * package's path), a type's declaration kind, the item's type when the
 * compiler knows one, the doc comment a field, type, metadata or define
 * carries, and the index a {@code display/completionItem/resolve} request
 * names it by.
 */
public record CompletionItem(String kind, String name, @Nullable String detail, @Nullable String moduleTypeKind,
                             @Nullable JsonTypeRef type, @Nullable String doc, int index) {

  public boolean isKeyword() {
    return "Keyword".equals(kind) || "Literal".equals(kind);
  }

  public boolean isType() {
    return "Type".equals(kind);
  }

  public boolean isField() {
    return "ClassField".equals(kind) || "EnumAbstractField".equals(kind);
  }

  public boolean isEnumField() {
    return "EnumField".equals(kind);
  }

  public boolean isLocal() {
    return "Local".equals(kind) || "TypeParameter".equals(kind);
  }

  public boolean isPackageOrModule() {
    return "Package".equals(kind) || "Module".equals(kind);
  }
}
