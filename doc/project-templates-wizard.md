# Haxe project generator with templates

Design + plan: a standalone "Haxe" entry in the New Project dialog's
generator list offering project TEMPLATES (Empty, HXML, Lime, OpenFL, NME,
Haxelib), each generating configured files. The existing language-dropdown
entry (`HaxeNewProjectWizard`, a `LanguageGeneratorNewProjectWizard`) stays
untouched.

## Platform findings

- The modern New Project dialog is SINGLE-PAGE everywhere; multi-page
  "Next" flows are not part of the new wizard API. Template-specific fields
  therefore live on the one page and swap with the selected template.
- The platform ships exactly the right base for that:
  `AbstractNewProjectWizardMultiStepBase` — a switcher (segmented button)
  over named child steps, each child owning its own `setupUI` panel
  (swapped via placeholder) and `setupProject`. This is how the platform's
  own template pickers work.
- A standalone generator = `GeneratorNewProjectWizard` registered through a
  `GeneratorNewProjectWizardBuilderAdapter` subclass as a `moduleBuilder`
  extension (the adapter's NPW. builder-id prefix suppresses the legacy
  settings step).
- SDK: other generator pages show an SDK selector; ours gets a combo
  filtered to `HaxeSdkType`, applied as the PROJECT SDK on create
  (write action, `ProjectRootManager.setProjectSdk`), with the existing
  `ensureSdk` auto-detection as the empty-state fallback.

## Page layout

Base fields (name, location) come from the standard base step. Below them:

- SDK: combo over configured Haxe SDKs (+ auto-detected candidates).
- Template: segmented switcher — Empty | HXML | Lime | OpenFL | NME |
  Haxelib — with per-template sections:

| Template | Fields (defaults) | Generates |
|---|---|---|
| Empty | — | module + src/ only |
| HXML | target combo (per-target research below), main class ("Main"), output (per-target default, follows the target until edited), dce (std/full/no), + per-target extras | src/<Main>.hx, build.hxml with the researched lines, registered as active build file |
| Lime | target (html5, windows/linux/mac, android, ios, neko, hl, flash); app title (= project name); package (com.example.<name>); window WxH (1280x720); fps (60) | project.xml (lime), src/Main.hx extending lime.app.Application, active build file + target selection stored |
| OpenFL | same fields as Lime | project.xml (openfl), src/Main.hx extending openfl.display.Sprite, active build file + target selection stored |
| NME | same fields, target set per NME support (windows/linux/mac, neko, flash, html5, android, ios) | project.nmml, src/Main.hx extending nme Sprite, active build file + target selection stored |
| Haxelib | name (= project name), license (schema enum: GPL, LGPL, BSD, Public, MIT, Apache; default MIT), version (0.0.1), description, contributors (comma list), url, tags, releasenote ("Initial release") | haxelib.json with classPath "src/", src/ folder, starter class named after the lib |

App title binds to the project name from the base step (updates until the
user edits it manually — the standard property-graph pattern the base data
exposes). haxelib.json's REQUIRED fields per the bundled schema
(src/main/resources/schema/haxelib/schema.json): name, license,
releasenote, contributors, version — the form must not create an invalid
manifest, so those carry defaults and validation.

## HXML per-target facts (from the manual's getting-started pages)

| Option | Output line | Notes |
|---|---|---|
| HashLink (VM) | `--hl out/app.hl` | run with `hl app.hl` |
| HashLink (HL/C) | `--hl out/c/main.c` | the `.c` EXTENSION selects C-source generation into the directory; needs the hashlink haxelib + a C compiler to build |
| JavaScript | `--js out/app.js` | optional `-D source-map` (form checkbox) |
| Neko | `--neko out/app.n` | run with `neko app.n` |
| C++ (hxcpp) | `--cpp out/cpp` | directory of sources + native build |
| JVM | `--jvm out/app.jar` | runnable jar; hxjava must be installed but needs no -lib line |
| Java (legacy) | `--java out/java` + `-lib hxjava` | generated sources + jar in the directory |
| C# | `--cs out/cs` + `-lib hxcs` | generated sources + exe; needs .NET/Mono |
| PHP | `--php out/php` | directory of PHP classes |
| Python | `--python out/app.py` | run with `python3` |
| Lua | `--lua out/app.lua` | stdlib features may need luarocks packages |
| Flash | `--swf out/app.swf` | form adds `--swf-version` and `--swf-header w:h:fps:color` (stage fields, defaults 960:640:60:ffffff) |
| Eval (interpreter) | `--interp` | no output — the compiler runs the program; output field hidden |

`-dce std|full|no` applies to every target (form combo, default std). The
starter class file is named after the configured main class.

## Post-generation wiring (all non-empty templates)

1. Create the module via the existing `HaxeModuleBuilder` (same as the
   language wizard does).
2. Apply the selected SDK as project SDK.
3. Register the generated build file in `HaxeActiveBuildFileStore` and, for
   lime-family templates, the chosen target in `HaxeTargetSelectionStore` —
   so the tool window opens fully configured.
4. Optional (checkbox on the template page, all templates incl. Empty): a
   project-local haxelib repository — an empty `.haxelib` directory in the
   content root (what `haxelib newrepo` creates; the haxelib binary uses it
   for every command run inside the project). Excluded from the module; gets
   a `.gitkeep` when the wizard's git option is on. Unchecked = global
   repository, i.e. whatever haxelib is configured to use.

## Structure

`v2/wizard/` gains:

- `HaxeProjectGenerator` (`GeneratorNewProjectWizard`) + its builder
  adapter, registered as `moduleBuilder`.
- `HaxeTemplateStep` extends `AbstractNewProjectWizardMultiStepBase` with
  the six child steps.
- One small file-content renderer per template (pure functions String →
  String where possible) — unit-testable without the wizard.
- Bundle keys for every label/comment.

## Tests

- Renderer unit tests: generated hxml/project.xml/nmml/haxelib.json content
  for given field values (incl. haxelib.json validating against required
  fields, window size substitution, target lines).
- One fixture test per template exercising the setupProject file-generation
  path into a temp project (module, files, store registrations) — the UI
  step itself is sandbox-verified.

## Open items to verify while building

- Exact EP/registration shape for the adapter in this platform version.
- SDK combo DSL for a custom SdkType in the new wizard (the JDK-specific
  helper does not apply; the generic `SdkComboBox` route does).
- NME's supported target list (older generation than lime's).
