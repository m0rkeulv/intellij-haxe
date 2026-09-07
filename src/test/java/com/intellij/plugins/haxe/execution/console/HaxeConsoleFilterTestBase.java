package com.intellij.plugins.haxe.execution.console;

import com.intellij.execution.filters.Filter;
import com.intellij.plugins.haxe.HaxeLightFixtureTestCase;

/** The console-filter tests' shared shape: one filter over a light project, applied line by line. */
abstract class HaxeConsoleFilterTestBase extends HaxeLightFixtureTestCase {

  protected Filter filter;

  @Override
  protected String getBasePath() {
    return "/console/";
  }

  /** The filter over a console holding just this line (plus its newline). */
  protected Filter.Result applyToLine(String line) {
    return filter.applyFilter(line + "\n", line.length() + 1);
  }

  protected static String highlightedSpan(String console, Filter.Result result) {
    Filter.ResultItem item = result.getResultItems().getFirst();
    return console.substring(item.getHighlightStartOffset(), item.getHighlightEndOffset());
  }
}
