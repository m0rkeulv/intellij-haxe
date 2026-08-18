# 21-resources-config

### should-fix — src/main/resources/messages/HaxeBundle.properties (multiple lines)
The branch deletes the v1 configuration surface (HaxeProjectSettingsConfigurable, HaxeConfigurationEditor, the module builder/import UI, the run-config editor, HaxeTargetStatusBarWidgetFactory) but leaves 46 of their bundle keys behind with zero remaining callers — the checklist's "keys whose callers were deleted". Verified: each key had a caller on `develop` and has none in the current tree (code, forms, or plugin.xml). The groups: `haxe.run.*` (module/target/parameters/file-to-run etc., 12 keys), `haxe.configuration.*` (hxml/nmml/openfl build choices, 6 keys), `haxe.target.widget.*` (4 keys, lines 551-554), `haxe.settings.name` (153), `haxe.settings.edit` (159), `haxe.conditional.compilation.defined.macros` (154), `haxe.conditional.compilation.setting` (158), `module.settings.synchronize.dependencies[.title]`, `flex.sdk.label`, `flex.sdk.not.specified`, `dialog.module.name-conflict.title/.message`, `haxe.main.class`, `choose.haxe.main.class`, `haxe.project`, `haxe.project.configuration.reading`, `haxe.output.file.name`, `haxe.output.folder`, `haxe.nme.arguments`, `haxe.openfl.arguments`, `haxe.openfl.xmlproject`, `haxe.compile.with`, `haxe.compiler.description`, `haxe.proper.debug.targets`, `haxe.run.bad.neko.bin.path`, `autodetected.source.root.type`, `runner.configuration.name`, `haxe.module.editor.haxe`, `haxe.inspections.semantic.annotator.name`. Delete them (the branch already did this correctly for `haxe.build.process.*` and the `haxe.tests.*` keys).

### should-fix — src/main/resources/icons/Haxe_logo_13.svg, src/main/resources/icons/Haxe_logo_gray.svg
Both SVGs are added by the branch but nothing references them — not `HaxeIcons`, not plugin.xml, not any resource (the only icon actually used is `Haxe_logo_gray_13.svg`, and neither file is a `_dark`/`@2x` variant the platform would pick up by convention). Dead resources shipped in the plugin zip; delete them (or wire the intended consumers).

### should-fix — src/main/resources/schema/nmml/nmml.xsd:19-24
The new header comment is authorship/history narration, which the comment rule forbids: `NOTE MLO:` (author initials) and "with some help from Claude guessing the attribute list" is investigation notes, not behaviour. It also has the typo "NMMLfiles". State the fact only, e.g.: "No official NMML schema exists; element/attribute lists follow NMMLParser.hx. Deprecated or replaced values are still accepted."

### question — gradle.properties:38
`pluginSinceBuild` moves from 261 to 262, narrowing support to 2026.2 only, while CLAUDE.md's hard-rule section still states the target is "IntelliJ 2026.1–2026.2 (`pluginSinceBuild=261`, ...)". If the v2 work genuinely requires a 262-only API this is fine but the documented range is now stale; if not, the bump silently drops 2026.1 users. Confirm which is intended.

### minor — src/main/java/icons/HaxeIcons.java:64
`HAXE_LOGO_GRAY_13` is added but has zero consumers — the three tool-window registrations in plugin.xml reference the icon by raw path (`icon="/icons/Haxe_logo_gray_13.svg"`), not through this field. Consumer-less API without a TODO; either delete the field or have plugin.xml reference it (`icon="icons.HaxeIcons.HAXE_LOGO_GRAY_13"`), which also gives compile-adjacent checking the path string lacks.

### minor — CHANGELOG.md:2-14
The 2.0.0 entry is user-visible (rendered into the marketplace changelog by the changelog plugin) and has formatting defects: double spaces in "with  1.x" and "should  be  highlighted"; under "Changes:" the first change ("Project/module Configuration is now done...") is not a bullet while the rest are; and the sub-points use broken `* - ` pseudo-nesting instead of indented list items under "When compilation server is enabled:".

### minor — src/main/resources/messages/HaxeBundle.properties:603
The section header `# V2 new module wizard` has no keys beneath it (the wizard keys live in the new HaxeWizardBundle.properties) — an orphan comment left behind; delete it.

### minor — src/main/resources/META-INF/plugin.xml:157, 773-777
Two whitespace leftovers in changed regions: the new `<programRunner  implementation=".../LegacyHxcppDebugRunner"/>` has a double space its four sibling registrations lack, and removing the Load As Project / sync / purge actions left a run of five consecutive blank lines at the end of `<actions>` (blank lines separate groups; there are no groups left there).
