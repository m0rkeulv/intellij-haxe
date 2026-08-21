package com.intellij.plugins.haxe.ide;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.intellij.codeInspection.InspectionToolProvider;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ex.InspectionProfileImpl;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.HaxeCodeInsightFixtureTestCase;
import com.intellij.profile.codeInspection.InspectionProfileManager;
import com.intellij.util.ArrayUtil;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.intellij.plugins.haxe.ide.inspections.unused.HaxeUnusedFieldInspection;
import com.intellij.plugins.haxe.ide.inspections.unused.HaxeUnusedFunctionInspection;
import com.intellij.plugins.haxe.ide.inspections.unused.HaxeUnusedLocalVarInspection;
import com.intellij.plugins.haxe.ide.inspections.unused.HaxeUnusedMethodInspection;


@DisplayName("Annotation: unused annotator")
public class HaxeUnusedAnnotatorTest extends HaxeCodeInsightFixtureTestCase {
    @Override
    public void setUp() throws Exception {
        useHaxeToolkit();
        super.setUp();
        setTestStyleSettings(2);
    }

    @Override
    protected String getBasePath() {
        return "/annotation.unused/";
    }

    private void doTest(boolean checkWarnings, boolean checkInfos, boolean checkWeakWarnings,
                        @Nullable Set<Class<? extends LocalInspectionTool>> unsetInspections,
                        String... additionalFiles)
            throws Exception {
        myFixture.configureByFiles(ArrayUtil.mergeArrays(new String[]{getTestName(false) + ".hx"}, additionalFiles));
        myFixture.enableInspections(getAnnotatorBasedInspection());
        myFixture.enableInspections(HaxeInspectionTestTools.semanticInspections(unsetInspections));
        myFixture.testHighlighting(checkWarnings, checkInfos, checkWeakWarnings);
    }

    private void doTest(String... additionalFiles) throws Exception {
        doTest(true, false, true, null, additionalFiles);
    }

    @Test
    @DisplayName("unused fields and variables test")
    public void testUnusedFieldsAndVariablesTest() throws Exception {
        myFixture.enableInspections(
                HaxeUnusedFieldInspection.class,
                HaxeUnusedLocalVarInspection.class
        );
        doTest("UnusedFieldsTestOutside.hx");
    }

    @Test
    @DisplayName("unused methods and functions test")
    public void testUnusedMethodsAndFunctionsTest() throws Exception {
        myFixture.enableInspections(
                HaxeUnusedFunctionInspection.class,
                HaxeUnusedMethodInspection.class
        );
        doTest("UnusedMethodsTestOutside.hx");
    }

    @Test
    @DisplayName("unused method abstract impl test")
    public void testUnusedMethodAbstractImplTest() throws Exception {
        myFixture.enableInspections(
                HaxeUnusedFunctionInspection.class,
                HaxeUnusedMethodInspection.class
        );
        doTest();
    }

    @Test
    @DisplayName("unused module level function test")
    public void testUnusedModuleLevelFunctionTest() throws Exception {
        myFixture.enableInspections(
                HaxeUnusedFunctionInspection.class,
                HaxeUnusedMethodInspection.class
        );
        doTest();
    }
}
