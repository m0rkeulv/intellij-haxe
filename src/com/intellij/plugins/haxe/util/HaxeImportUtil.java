/*
 * Copyright 2000-2013 JetBrains s.r.o.
 * Copyright 2014-2014 AS3Boyan
 * Copyright 2014-2014 Elias Ku
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.plugins.haxe.util;

import com.intellij.openapi.util.Condition;
import com.intellij.plugins.haxe.lang.psi.*;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by fedorkorotkov.
 */
public class HaxeImportUtil {
  public static List<HaxeImportStatementRegular> findUnusedImports(PsiFile file) {
    final List<HaxeClass> classesInFile = new ArrayList<HaxeClass>();
    file.acceptChildren(new HaxeRecursiveVisitor() {
      @Override
      public void visitElement(PsiElement element) {
        super.visitElement(element);
        if (element instanceof HaxeReference) {
          HaxeClass haxeClass = ((HaxeReference)element).resolveHaxeClass().getHaxeClass();
          if (haxeClass != null) {
            classesInFile.add(haxeClass);
          }
        }
      }

      @Override
      public void visitImportStatementRegular(@NotNull HaxeImportStatementRegular o) {
        // stop
      }
    });

    List<HaxeImportStatementRegular> filteredUsefulImports = new ArrayList<HaxeImportStatementRegular>();

    List<HaxeImportStatementRegular> allImportStatements = UsefulPsiTreeUtil.getAllImportStatements(file);
    final List<HaxeImportStatementRegular> usefulImportStatements = ContainerUtil.findAll(allImportStatements, new Condition<HaxeImportStatementRegular>() {
      @Override
      public boolean value(HaxeImportStatementRegular statement) {
        final HaxeImportStatementRegular regularImport = statement;
        if(regularImport != null) {
          final HaxeReferenceExpression referenceExpression = regularImport.getReferenceExpression();

          if (referenceExpression.resolve() == null) {
            // don't know for sure
            return true;
          }
        }
        for (HaxeClass haxeClass : classesInFile) {
          if (UsefulPsiTreeUtil.importStatementForClass(statement, haxeClass)) {
            return true;
          }
        }
        return false;
      }
    });

    boolean alreadyAdded = false;

    for (int i = 0; i < usefulImportStatements.size(); i++) {
      for (int j = 0; j < filteredUsefulImports.size(); j++) {
        if (usefulImportStatements.get(i).getReferenceExpression().getText().equals(filteredUsefulImports.get(j).getReferenceExpression().getText())) {
          alreadyAdded = true;
          break;
        }
      }

      if (!alreadyAdded) {
        filteredUsefulImports.add(usefulImportStatements.get(i));
      }
    }

    List<HaxeImportStatementRegular> uselessImportStatements = new ArrayList<HaxeImportStatementRegular>(allImportStatements);
    uselessImportStatements.removeAll(filteredUsefulImports);

    return uselessImportStatements;
  }
}
