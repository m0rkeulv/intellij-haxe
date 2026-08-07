package com.intellij.plugins.haxe.v2.display;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Compiler services: generated code preview sanitizer")
public class HaxeGeneratedCodePreviewSanitizeTest {

  @Test
  @DisplayName("un-dots qualified declaration headers into a package statement")
  public void testUnDotsQualifiedDeclarationHeadersIntoAPackageStatement() {
    String sanitized = HaxeGeneratedCodePreview.sanitize("""
      @:keep @:used
      class haxe.iterators.ArrayIterator<T> {
      }
      """);
    assertTrue(sanitized.contains("package haxe.iterators;"), "package statement derived from the qualified name");
    assertTrue(sanitized.contains("class ArrayIterator<T>"), "declared name reduced to its simple form");
    assertFalse(sanitized.contains("class haxe.iterators.ArrayIterator"), "qualified declaration removed");
  }

  @Test
  @DisplayName("unqualified module dump gets no package statement")
  public void testUnqualifiedModuleDumpGetsNoPackageStatement() {
    String sanitized = HaxeGeneratedCodePreview.sanitize("""
      @:used
      class Main {
      	@:keep
      	static function main() {
      		`trace("hello", {fileName : "Main.hx"});
      	}
      }
      """);
    assertFalse(sanitized.contains("package "), "no package for a root-package module");
    assertTrue(sanitized.contains("`trace"), "backtick identifiers pass through untouched - the grammar parses them");
  }

  @Test
  @DisplayName("qualified type references in bodies stay untouched")
  public void testQualifiedTypeReferencesInBodiesStayUntouched() {
    String sanitized = HaxeGeneratedCodePreview.sanitize("""
      class haxe.Example {
      	var field:Array<haxe.iterators.ArrayIterator.T>;
      }
      """);
    assertTrue(sanitized.contains("Array<haxe.iterators.ArrayIterator.T>"), "type references are valid dot paths and keep their qualification");
    assertTrue(sanitized.contains("class Example"), "only the declaration header is un-dotted");
  }
}
