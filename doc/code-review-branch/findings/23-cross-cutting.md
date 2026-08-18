# 23-cross-cutting

### should-fix — jps-plugin/src/main/java/org/jetbrains/jps/haxe/build/HaxeModuleLevelBuilder.java:117
Dangling reference to a runner this branch deleted. `processModule` gates on
`"HaxeDebugRunner".equals(context.getBuilderParameter("RUNNER_ID"))`, but
`src/main/java/com/intellij/plugins/haxe/runner/debugger/HaxeDebugRunner.java`
(the only thing that ever set that runner id) is deleted by the branch and no
surviving runner uses the id `HaxeDebugRunner` (they are all
`HaxeBrowserDebugRunner`, `HashLinkDebugRunner`, etc.). `isDebugRunner` is now
always false, so the debug-builder instance registered by
`HaxeBuilderService.java:34` (`new HaxeModuleLevelBuilder(true)`) can never
process a module — permanently dead code. Remove the debug-builder variant (and
the `myDebugBuilder` XOR gate) or leave a TODO naming what replaces it.

### minor — src/main/java/com/intellij/plugins/haxe/haxelib/HaxeLibrary.java:48
Comment cites a file this branch deletes: `// TODO: Add the extraParams.hxml
data here.  Use the hxml parser; see LimeUtil.getLimeProjectModel() as an
example.` — `compilation/LimeUtil.java` is gone, so the pointed-at example no
longer exists. Reword the TODO to point at the surviving hxml parsing home
(`v2/buildsystem/HxmlFileParser`) or drop the example reference.

## Sweep results (clean areas)

- **Deleted-file references (64 D lines):** class-name word-grep over src,
  common, jps-plugin and all XML found only the two hits above. The
  `HaxeModuleBuilder` hits resolve to the new `v2/wizard/HaxeModuleBuilder`,
  not the deleted `ide/module` one.
- **plugin.xml / flex-debugger-support.xml registrations:** every
  `com.intellij.plugins.haxe.*` FQN on an added/changed line resolves —
  including the `HaxeFrameworkConfigurables$*` inner classes and
  `HaxeProjectGeneratorBuilder` (declared in `HaxeProjectGenerator.kt`, so a
  filename-based check alone misses it). Added `internalFileTemplate` names
  and the `/icons/Haxe_logo_gray_13.svg` icon all exist under resources.
- **Bundle keys:** all 309 keys added to HaxeBundle, HaxeDebuggerBundle,
  HaxeProjectBundle and the new HaxeWizardBundle properties have at least one
  quoted-string caller in src/common (code or XML).
- **Orphan classes (214 added .java/.kt):** the only files with zero external
  word-references are JUnit test classes (framework-instantiated) and
  `HaxeProjectGenerator.kt` (its `HaxeProjectGeneratorBuilder` is registered
  in plugin.xml). No high-confidence orphans.
