package com.intellij.plugins.haxe.execution.console;

import com.intellij.execution.filters.Filter;
import com.intellij.execution.filters.OpenFileHyperlinkInfo;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.plugins.haxe.HaxeLightFixtureTestCase;
import com.intellij.psi.search.GlobalSearchScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DisplayName("Console: utest failure filter")
public class HaxeUtestFailureFilterTest extends HaxeLightFixtureTestCase {

  private Filter filter;

  @Override
  protected String getBasePath() {
    return "/console/";
  }

  @BeforeEach
  public void addTestSource() {
    myFixture.addFileToProject("src/ShapeTest.hx", "class ShapeTest {}");
    filter = new HaxeUtestFailureFilter(getProject(), GlobalSearchScope.allScope(getProject()));
  }

  /** (utest output line, 0-based line the link opens at, highlighted span). */
  static final List<Arguments> FAILURES = List.of(
    arguments("src/ShapeTest.hx:174: expected 0 but it is \"bogus\"", 173, "src/ShapeTest.hx:174"),
    arguments("src/ShapeTest.hx:176: exception of type Dynamic not raised", 175, "src/ShapeTest.hx:176"),
    arguments("  src/ShapeTest.hx:12: assertion failed", 11, "src/ShapeTest.hx:12"));

  @ParameterizedTest(name = "{0}")
  @FieldSource("FAILURES")
  public void testLinksTheFailureToItsAssertion(String line, int zeroBasedLine, String highlighted) {
    String console = "expected 0 but it is \"bogus\"\n" + line + "\n";

    Filter.Result result = filter.applyFilter(line + "\n", console.length());

    assertNotNull(result, "failure position must link");
    OpenFileDescriptor target = ((OpenFileHyperlinkInfo)result.getFirstHyperlinkInfo()).getDescriptor();
    assertEquals("ShapeTest.hx", target.getFile().getName());
    assertEquals(zeroBasedLine, target.getLine());
    assertEquals(highlighted, highlightedSpan(console, result));
  }

  /** Lines without a position, an unknown file, or a compiler message (the compiler filter's line) stay plain. */
  static final List<Arguments> PLAIN_LINES = List.of(
    arguments("expected 0 but it is \"bogus\""),
    arguments("src/Missing.hx:3: expected 1"),
    arguments("src/ShapeTest.hx:12: characters 4-9 : Unknown identifier"));

  @ParameterizedTest(name = "{0}")
  @FieldSource("PLAIN_LINES")
  public void testLeavesOtherLinesPlain(String line) {
    assertNull(applyToLine(line));
  }

  @Test
  @DisplayName("windows drive path")
  public void testWindowsDrivePath() {
    HaxeUtestFailureLine failure = HaxeUtestFailureLine.parse("C:/work/src/Other.hx:5: expected 1");

    assertNotNull(failure, "the drive letter is part of the path, not a separator");
    assertEquals("C:/work/src/Other.hx", failure.path());
    assertEquals(5, failure.line());
  }

  /** The filter over a console holding just this line (plus its newline). */
  private Filter.Result applyToLine(String line) {
    return filter.applyFilter(line + "\n", line.length() + 1);
  }

  private static String highlightedSpan(String console, Filter.Result result) {
    Filter.ResultItem item = result.getResultItems().getFirst();
    return console.substring(item.getHighlightStartOffset(), item.getHighlightEndOffset());
  }
}
