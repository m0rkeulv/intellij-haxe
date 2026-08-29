package com.intellij.plugins.haxe.profiler.bridge.chart;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.plugins.haxe.HaxeProfilerBundle;
import com.intellij.plugins.haxe.profiler.bridge.chart.HaxeCallChartTab.CurveCategory;
import com.intellij.ui.components.JBCheckBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.event.ActionEvent;
import java.util.EnumMap;
import java.util.Map;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * The Call Chart's Configure View dialog: one checkbox per lane. OK applies
 * and saves the choices, Cancel discards them, Default returns every box to
 * its data-driven default (a lane with data shown, one without hidden).
 * Layout in the bound .form.
 */
final class HaxeChartViewDialog extends DialogWrapper {

  /** One snapshot of every lane's visibility; {@code calls} is the call chart itself. */
  record Lanes(boolean frames, boolean gc, boolean events, boolean calls,
               @NotNull Map<CurveCategory, Boolean> curves) {
  }

  private final Lanes defaults;
  private JPanel panel;
  private JBCheckBox framesBox;
  private JBCheckBox gcBox;
  private JBCheckBox memoryBox;
  private JBCheckBox gpuMemoryBox;
  private JBCheckBox cpuBox;
  private JBCheckBox gpuLoadBox;
  private JBCheckBox eventsBox;
  private JBCheckBox callsBox;

  HaxeChartViewDialog(@Nullable Project project, @NotNull Lanes current, @NotNull Lanes defaults) {
    super(project);
    this.defaults = defaults;
    setTitle(HaxeProfilerBundle.message("haxe.profiler.callchart.configure.view"));
    init();
    setLanes(current);
  }

  @Override
  protected @Nullable JComponent createCenterPanel() {
    return panel;
  }

  @Override
  protected Action @NotNull [] createLeftSideActions() {
    return new Action[]{new DialogWrapperAction(HaxeProfilerBundle.message("haxe.profiler.callchart.view.default")) {
      @Override
      protected void doAction(ActionEvent event) {
        setLanes(defaults);
      }
    }};
  }

  private void setLanes(Lanes lanes) {
    framesBox.setSelected(lanes.frames());
    gcBox.setSelected(lanes.gc());
    eventsBox.setSelected(lanes.events());
    callsBox.setSelected(lanes.calls());
    memoryBox.setSelected(lanes.curves().getOrDefault(CurveCategory.MEMORY, false));
    gpuMemoryBox.setSelected(lanes.curves().getOrDefault(CurveCategory.GPU_MEMORY, false));
    cpuBox.setSelected(lanes.curves().getOrDefault(CurveCategory.CPU_LOAD, false));
    gpuLoadBox.setSelected(lanes.curves().getOrDefault(CurveCategory.GPU_LOAD, false));
  }

  /** The chosen visibility after OK. */
  @NotNull
  Lanes lanes() {
    Map<CurveCategory, Boolean> curves = new EnumMap<>(CurveCategory.class);
    curves.put(CurveCategory.MEMORY, memoryBox.isSelected());
    curves.put(CurveCategory.GPU_MEMORY, gpuMemoryBox.isSelected());
    curves.put(CurveCategory.CPU_LOAD, cpuBox.isSelected());
    curves.put(CurveCategory.GPU_LOAD, gpuLoadBox.isSelected());
    return new Lanes(framesBox.isSelected(), gcBox.isSelected(), eventsBox.isSelected(),
                     callsBox.isSelected(), curves);
  }
}
