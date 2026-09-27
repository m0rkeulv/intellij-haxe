package com.intellij.plugins.haxe.display.protocol;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A {@code display/completion} result: the items the compiler offers at the
 * position, the completion mode it decided on ({@link #modeKind}: 0 Field,
 * 1 StructureField, 2 Toplevel, 3 Metadata, 4 TypeHint, 5 Extends,
 * 6 Implements, 7 StructExtension, 8 Import, 9 Using, 10 New, 11 Pattern,
 * 12 Override, 13 TypeRelation, 14 TypeDeclaration), the range of the text
 * an item replaces when the compiler reports one, and whether the list was
 * cut short (a toplevel list the server had not fully indexed yet).
 */
public record CompletionList(List<CompletionItem> items, int modeKind, @Nullable Range replaceRange, boolean incomplete) {
}
