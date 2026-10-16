package com.intellij.plugins.haxe.v2.display;

import com.intellij.plugins.haxe.display.protocol.JsonTypeRef;
import com.intellij.plugins.haxe.lang.psi.HaxeClass;
import com.intellij.plugins.haxe.model.HaxeMethodModel;
import com.intellij.plugins.haxe.model.type.*;
import com.intellij.plugins.haxe.util.HaxeResolveUtil;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a compiler type ({@code haxe.display.JsonModuleTypes.JsonType}) into
 * the plugin's type model, so a compiler answer can stand in for an
 * evaluator result. Named types resolve through the class indexes, so the
 * result navigates like a statically resolved one. Null when the type cannot
 * be expressed: an unresolved monomorph, a type the indexes do not know, or
 * an anonymous structure.
 * <p>
 * TODO: anonymous structures ({@code TAnonymous}) need a synthetic typedef, as
 * the blueprint path renders them; until then they fall back to the IDE.
 */
final class HaxeCompilerTypeConverter {

  private HaxeCompilerTypeConverter() {
  }

  @Nullable
  static ResultHolder toResultHolder(@Nullable JsonTypeRef type, @NotNull PsiElement context) {
    if (type == null) return null;
    return switch (type.kind()) {
      case "TInst", "TEnum", "TType", "TAbstract" -> classType(type, context);
      case "TFun" -> functionType(type, context);
      case "TDynamic" -> SpecificTypeReference.getDynamic(context).createHolder();
      default -> null;
    };
  }

  @Nullable
  private static ResultHolder classType(@NotNull JsonTypeRef type, @NotNull PsiElement context) {
    HaxeClass haxeClass = findClass(type, context);
    if (haxeClass == null) return null;
    List<ResultHolder> specifics = new ArrayList<>();
    for (JsonNode param : type.args().path("params")) {
      ResultHolder specific = toResultHolder(JsonTypeRef.of(param), context);
      if (specific == null) return null;
      specifics.add(specific);
    }
    HaxeClassReference reference = new HaxeClassReference(haxeClass.getModel(), context);
    return SpecificHaxeClassReference.withGenerics(reference, specifics.toArray(ResultHolder[]::new)).createHolder();
  }

  /** The index form ({@code pack.Module.SubType}) first, the printed dot path as the fallback. */
  /** The declaration of a named compiler type, through the class indexes: by its qualified name, else by its dot path. */
  @Nullable
  static HaxeClass findClass(@NotNull JsonTypeRef type, @NotNull PsiElement context) {
    String qualifiedName = JsonTypeRef.qualifiedNameOf(type.args().path("path"));
    HaxeClass byQualifiedName = HaxeResolveUtil.findClassByQName(qualifiedName, context);
    if (byQualifiedName != null) return byQualifiedName;
    return HaxeResolveUtil.findClassByQName(type.dotPath(), context);
  }

  @Nullable
  private static ResultHolder functionType(@NotNull JsonTypeRef type, @NotNull PsiElement context) {
    List<HaxeArgument> arguments = new ArrayList<>();
    int index = 0;
    for (JsonNode argument : type.args().path("args")) {
      ResultHolder argumentType = toResultHolder(JsonTypeRef.of(argument.path("t")), context);
      if (argumentType == null) return null;
      String name = argument.path("name").asString("");
      boolean optional = argument.path("opt").asBoolean(false);
      arguments.add(new HaxeArgument(context, index++, optional, false, argumentType, name.isEmpty() ? null : name));
    }
    ResultHolder returnType = toResultHolder(JsonTypeRef.of(type.args().path("ret")), context);
    if (returnType == null) return null;
    return new SpecificFunctionReference(arguments, returnType, (HaxeMethodModel)null, context).createHolder();
  }
}
