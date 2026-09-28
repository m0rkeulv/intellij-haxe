package com.intellij.plugins.haxe.model;

import com.intellij.plugins.haxe.lang.psi.HaxeClass;
import com.intellij.plugins.haxe.lang.psi.HaxeComponentName;
import com.intellij.plugins.haxe.lang.psi.HaxeFieldDeclaration;
import com.intellij.plugins.haxe.lang.psi.HaxeMethod;
import com.intellij.plugins.haxe.lang.psi.HaxeNamedComponent;
import com.intellij.plugins.haxe.util.HaxeNamedSubComponentUtil;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.search.searches.DefinitionsScopedSearch;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// A property together with the accessor methods its declaration binds by
/// name. `var width(get, set):Int` is served by `get_width` and
/// `set_width`, whether the class declares them, inherits them or a subclass
/// overrides them; an interface property is implemented by a property of the
/// same name in each implementing class, with accessors of its own. The
/// family holds every declaration the name reaches, so that a rename moves
/// them together and the compiler keeps finding the accessors.
///
/// TODO: module-level properties (`static var x(get, never)` beside a module
///  function `get_x`) get no family; the lookup goes through the class model.
public record HaxePropertyFamily(@NotNull List<HaxeFieldDeclaration> properties,
                                 @NotNull List<HaxeMethod> getters,
                                 @NotNull List<HaxeMethod> setters) {
  public static final String GETTER_PREFIX = "get_";
  public static final String SETTER_PREFIX = "set_";

  /** Whether the element is a property with a bound accessor, one of its accessors, or the name element of either. No index search. */
  public static boolean isPropertyOrAccessor(@Nullable PsiElement element) {
    return propertyOf(element) != null;
  }

  /** The family of a property, of one of its accessors, or of either one's name element; null for any other element. */
  @Nullable
  public static HaxePropertyFamily of(@Nullable PsiElement element) {
    HaxeFieldDeclaration property = propertyOf(element);
    return property == null ? null : familyOf(property);
  }

  /** The property the element declares or serves: the declaration itself, or the property an accessor method is bound to. */
  @Nullable
  public static HaxeFieldDeclaration propertyOf(@Nullable PsiElement element) {
    return switch (declarationOf(element)) {
      case HaxeFieldDeclaration field when hasBoundAccessor(field) -> field;
      case HaxeMethod method -> propertyOfAccessor(method);
      case null, default -> null;
    };
  }

  /** The property an accessor serves: for {@code get_width}, the field {@code width} of the method's class when it is declared with {@code get}. Null for any other method. */
  @Nullable
  public static HaxeFieldDeclaration propertyOfAccessor(@NotNull HaxeMethod method) {
    String name = method.getName();
    String prefix = accessorPrefixOf(name);
    HaxeClassModel declaringClass = method.getModel().getDeclaringClass();
    if (prefix == null || declaringClass == null) return null;
    HaxeFieldModel property = declaringClass.getField(name.substring(prefix.length()), null);
    if (property == null || !(property.getBasePsi() instanceof HaxeFieldDeclaration field)) return null;
    boolean bound = prefix.equals(GETTER_PREFIX) ? property.getGetterType().isGetter() : property.getSetterType().isSetter();
    return bound ? field : null;
  }

  /** The declaration behind an element: the parent of a name element, otherwise the element itself. */
  @Nullable
  public static PsiElement declarationOf(@Nullable PsiElement element) {
    return element instanceof HaxeComponentName name ? name.getParent() : element;
  }

  public String propertyName() {
    return properties.getFirst().getName();
  }

  /** Whether the element, or the declaration it names, is one of the family's members. */
  public boolean contains(@Nullable PsiElement element) {
    PsiElement declaration = declarationOf(element);
    return properties.contains(declaration) || getters.contains(declaration) || setters.contains(declaration);
  }

  /**
   * The new name of every other member when {@code member} is renamed to
   * {@code newName}: the properties take the property name and the
   * accessors their prefix plus it. Empty when no property name follows,
   * which is an accessor renamed to a name without its prefix.
   */
  @NotNull
  public Map<PsiNamedElement, String> renamesFor(@NotNull PsiElement member, @NotNull String newName) {
    String propertyName = propertyNameFor(member, newName);
    if (propertyName == null) return Map.of();
    Map<PsiNamedElement, String> renames = new LinkedHashMap<>();
    for (HaxeFieldDeclaration property : properties) renames.put(property, propertyName);
    for (HaxeMethod getter : getters) renames.put(getter, GETTER_PREFIX + propertyName);
    for (HaxeMethod setter : setters) renames.put(setter, SETTER_PREFIX + propertyName);
    renames.remove(declarationOf(member));
    return renames;
  }

  /** The property name a member's new name implies: the name itself for a property, the name behind the prefix for an accessor. Null when an accessor's new name lacks its prefix. */
  @Nullable
  public String propertyNameFor(@NotNull PsiElement member, @NotNull String newName) {
    String prefix = declarationOf(member) instanceof HaxeMethod accessor ? accessorPrefixOf(accessor.getName()) : null;
    if (prefix == null) return newName;
    return newName.startsWith(prefix) ? newName.substring(prefix.length()) : null;
  }

  @Nullable
  private static String accessorPrefixOf(@Nullable String methodName) {
    if (methodName == null) return null;
    if (methodName.startsWith(GETTER_PREFIX)) return GETTER_PREFIX;
    return methodName.startsWith(SETTER_PREFIX) ? SETTER_PREFIX : null;
  }

  private static boolean hasBoundAccessor(@NotNull HaxeFieldDeclaration field) {
    return field.getModel() instanceof HaxeFieldModel model
           && (model.getGetterType().isGetter() || model.getSetterType().isSetter());
  }

  @NotNull
  private static HaxePropertyFamily familyOf(@NotNull HaxeFieldDeclaration property) {
    Set<HaxeFieldDeclaration> properties = new LinkedHashSet<>();
    collectDeclaredAbove(property, properties);
    for (HaxeFieldDeclaration declared : List.copyOf(properties)) properties.addAll(implementationsOf(declared));

    Set<HaxeMethod> getters = new LinkedHashSet<>();
    Set<HaxeMethod> setters = new LinkedHashSet<>();
    for (HaxeFieldDeclaration declared : properties) {
      if (!(declared.getModel() instanceof HaxeFieldModel model)) continue;
      getters.addAll(withOverrides(model.getGetterMethod()));
      setters.addAll(withOverrides(model.getSetterMethod()));
    }
    return new HaxePropertyFamily(List.copyOf(properties), List.copyOf(getters), List.copyOf(setters));
  }

  /**
   * Adds the property and every same-named property declared above it, in a
   * parent class or an implemented interface at any distance. Each type is
   * asked for its own members, since the model's field lookups leave
   * interface bodies out.
   */
  private static void collectDeclaredAbove(@NotNull HaxeFieldDeclaration property, @NotNull Set<HaxeFieldDeclaration> out) {
    out.add(property);
    if (!(property.getModel() instanceof HaxeFieldModel model) || model.getDeclaringClass() == null) return;
    for (HaxeClassModel supertype : allSupertypesOf(model.getDeclaringClass())) {
      for (HaxeNamedComponent member : HaxeNamedSubComponentUtil.getNamedSubComponentsFromClassType(supertype.haxeClass)) {
        boolean sameProperty = member instanceof HaxeFieldDeclaration declaration
                               && model.getName().equals(declaration.getName())
                               && hasBoundAccessor(declaration);
        if (sameProperty) out.add((HaxeFieldDeclaration)member);
      }
    }
  }

  /** The parent classes and implemented interfaces of the type, transitively, nearest first. */
  @NotNull
  private static List<HaxeClassModel> allSupertypesOf(@NotNull HaxeClassModel type) {
    List<HaxeClassModel> supertypes = new ArrayList<>();
    Set<HaxeClass> visited = new HashSet<>();
    Deque<HaxeClassModel> pending = new ArrayDeque<>(directSupertypesOf(type));
    while (!pending.isEmpty()) {
      HaxeClassModel supertype = pending.poll();
      if (!visited.add(supertype.haxeClass)) continue;
      supertypes.add(supertype);
      pending.addAll(directSupertypesOf(supertype));
    }
    return supertypes;
  }

  @NotNull
  private static List<HaxeClassModel> directSupertypesOf(@NotNull HaxeClassModel type) {
    List<HaxeClassModel> supertypes = new ArrayList<>();
    if (type.getParentClass() != null) supertypes.add(type.getParentClass());
    for (HaxeClassReferenceModel implemented : type.getImplementingInterfaces()) {
      if (implemented.getHaxeClassModel() != null) supertypes.add(implemented.getHaxeClassModel());
    }
    return supertypes;
  }

  /** The same-named properties of the classes implementing or extending the property's class, all the way down. */
  @NotNull
  private static List<HaxeFieldDeclaration> implementationsOf(@NotNull HaxeFieldDeclaration property) {
    List<HaxeFieldDeclaration> implementations = new ArrayList<>();
    for (PsiElement definition : definitionsBelow(property.getComponentName())) {
      if (definition instanceof HaxeFieldDeclaration implementation) implementations.add(implementation);
    }
    return implementations;
  }

  /** The accessor and its overrides, all the way down; empty when the property binds no such accessor. */
  @NotNull
  private static List<HaxeMethod> withOverrides(@Nullable HaxeMethodModel accessor) {
    if (accessor == null) return List.of();
    List<HaxeMethod> methods = new ArrayList<>();
    methods.add(accessor.getMethod());
    for (PsiElement definition : definitionsBelow(accessor.getMethod().getComponentName())) {
      if (definition instanceof HaxeMethod override) methods.add(override);
    }
    return methods;
  }

  /** The same-named members of every inheritor of the member's class; the search takes the name element. */
  @NotNull
  private static List<PsiElement> definitionsBelow(@Nullable HaxeComponentName name) {
    if (name == null) return List.of();
    return new ArrayList<>(DefinitionsScopedSearch.search(name).findAll());
  }
}
