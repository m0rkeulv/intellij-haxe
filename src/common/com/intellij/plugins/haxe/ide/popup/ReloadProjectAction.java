/*
 * Copyright 2000-2013 JetBrains s.r.o.
 * Copyright 2014-2018 AS3Boyan
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
package com.intellij.plugins.haxe.ide.popup;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.haxelib.HaxelibProjectUpdater;
import com.intellij.plugins.haxe.util.HaxeUtil;

public class ReloadProjectAction extends AnAction implements DumbAware {
  @Override
  public void update(AnActionEvent e) {
    super.update(e);
    Presentation p = e.getPresentation();
    p.setEnabled(isAvailable(e));
    p.setVisible(isVisible(e));
  }

  @Override
  public void actionPerformed(AnActionEvent e) {
    DataContext context = e.getDataContext();
    Project project = CommonDataKeys.PROJECT.getData(context);
    HaxelibProjectUpdater.INSTANCE.reload(project);
  }

  protected boolean isAvailable(AnActionEvent e) {
    DataContext context = e.getDataContext();
    Project project = CommonDataKeys.PROJECT.getData(context);
    return project != null;
  }

  protected boolean isVisible(AnActionEvent e) {
    return true;
  }
}