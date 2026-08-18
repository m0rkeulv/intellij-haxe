# 11-resources-reporters

### should-fix — src/main/resources/messages/HaxeBundle.properties:429
`haxelib.explorer.filter.group=Filter` has no caller. Every sibling in that block
(`haxelib.explorer.filter.installed/.not.installed/.dev/.git/.only.updates/.behind.latest`)
is read from
`src/main/java/com/intellij/plugins/haxe/ide/toolWindow/haxelib/HaxelibExplorerFilters.java`,
but `.filter.group` appears nowhere outside the properties file — the group header it
was meant for is evidently rendered without it. Per the Dead-code checklist ("bundle
keys without a caller"), delete the key or wire it into the filter popup's group title.

### should-fix — src/main/resources/testing/tinkLiveReporter/intellij_tink/FlashSupport.hx:1
`FlashSupport.hx` exists three times, byte-identical except for its `package` line
(`intellij_tink`, `intellij_munit`, `intellij_utest` — `diff` reports only line 1
differing). The same applies to the reporter bodies: `escape()` (the six
`StringTools.replace` TeamCity escapes plus its identical comment),
`announceHostedRunFinished()` and the `#if sys/flash/js` `printLine()` are copied
verbatim into `intellij_utest/LiveReporter.hx`, `intellij_buddy/TcReporter.hx`,
`intellij_tink/TcReporter.hx` and `intellij_munit/LiveClient.hx` (munit's `printLine`
differs only by the leading `\n`). That is ~100 duplicated lines across four
directories, and a fix to the escaping or the browser-exit protocol has to be made
four times. The extraction mechanism already supports it: `HaxeTestReporterFiles.extract`
takes an explicit file list per framework, so a shared `intellij_haxe_test/` source
folder (its own `-cp` root, or added to each framework's file list) would give one
home for `FlashSupport`, `escape` and `printLine`.

### minor — src/main/resources/testing/utestLiveReporter/README.md:1
Only the utest reporter has a README; `buddyLiveReporter/`, `munitLiveReporter/` and
`tinkLiveReporter/` have none, yet
`src/main/java/com/intellij/plugins/haxe/v2/testing/run/HaxeTestReporterFiles.java:19`
sends the reader to "the READMEs under `resources/testing/`" (plural). The checklist
item "files cited by javadoc/comments are committed" is not met for three of the four.
Either add the missing per-framework READMEs (each has non-obvious wire facts already
buried in class doc: buddy's `-D reporter=` selection instead of a macro, munit's
`haxe.Log.trace` re-hijack, tink's default-argument patch) or narrow the javadoc to
name the one README that exists.

### minor — src/main/resources/testFrameworks/munit/singleSuite.hx:7
The header comment ends "...so setting a handler crashes the run's completion (verified
live)". `(verified live)` is exactly the investigation/authorship note the Code-style
rule bans ("Leave out ... 'live-verified/observed' ... anything else that reads like a
changelog"); the surrounding sentences already state the behaviour and the constraint,
which is all a reader needs. Drop the parenthetical.

### minor — src/main/resources/messages/HaxeBundle.properties:807
Two of the new key groups do not sort next to their siblings, which the bundle rule
("keys are dotted and grouped by feature, so a new key sorts next to its siblings")
asks for. `haxe.build.tools.live.test.reporting` and `.hint` sit at lines 807-808,
stranded in the middle of the `# V2 unit-test run configurations` block, ~250 lines
away from the rest of the `haxe.build.tools.*` family (lines 534-554) that renders the
same Build Tools | Haxe page. Likewise `haxe.register.build.file.module.chooser.title`
and `haxe.roots.offer.title`/`.message` are appended at lines 831-833 under the
`# V2 "compile & run" of the compiled program (tool window)` header, which does not
describe them — they belong to the new `Haxe.RegisterBuildFile` action and want their
own commented block.

### minor — src/main/resources/messages/HaxeBundle.properties:437
The new keys are internally inconsistent in three small ways worth normalising while
the block is fresh: `haxelib.explorer.loading=Loading libraries...` /
`.loading.versions` / `.loading.details` use three dots where the other new
action/progress keys use the ellipsis character (`haxe.trust.notification.action=Trust
Project…`); `haxe.toolwindow.tooltip.tests.group=Test runs for this tests build file`
(line 763) omits the trailing period that every other `haxe.toolwindow.tooltip.*`
value — including the sibling `haxe.toolwindow.tooltip.section` added in the same hunk
— ends with; and `haxe.test.config.framework.no.interp`/`.no.flash` (lines 795-796)
use an em dash where the neighbouring `haxe.test.*` messages use a plain hyphen.

### question — src/main/resources/testing/munitLiveReporter/intellij_munit/Macro.hx:44
`buildClassHelper` injects `if ($i{nameArg} != $v{selected}) return;` into
`massive.munit.TestClassHelper.addTest`, comparing the first parameter against the
`intellij_munit_test` define (a `String`). This is correct only if munit's `addTest`
takes the method NAME as its first argument; if it takes a field/function object (the
parameter is untyped `Dynamic` in some munit versions), the comparison is always true
and every test is filtered out — a silent empty green run rather than a compile error,
because the surrounding warning path is never reached. Can the branch confirm the
munit versions in scope, and should the macro assert the parameter's type (falling back
to the existing `Context.warning` + unmodified fields) when it is not a `String`?
