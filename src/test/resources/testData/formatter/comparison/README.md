# Formatter comparison fixtures

Three-way comparison against [haxe-formatter](https://github.com/HaxeCheckstyle/haxe-formatter)
(HaxeCheckstyle, the vshaxe formatter), driven by `HaxeFormatterComparisonTest`.

Each rule directory holds:

| file | content |
|---|---|
| `input.hx` | deliberately misformatted source, violating the rule under test |
| `hxformat.hx` | `input.hx` as formatted by haxe-formatter (default config) — the ground truth |
| `plugin.hx` | our formatter's output — present ONLY for rules that do not yet reach parity; its diff against `hxformat.hx` documents the gap |

The test configures our code style to the haxe-formatter DEFAULTS (tabs,
end-of-line braces, spaced keywords/operators, ...) — see
`applyHxformatDefaults` in the test. Rules claiming parity assert our output
equals `hxformat.hx` byte-for-byte (modulo the trailing newline, which the
IDE manages at save time); the rest pin `plugin.hx` as a regression baseline.

Regenerating `hxformat.hx` after editing an `input.hx` (needs
`haxelib install formatter`; run PER FILE — running on the directory would
reformat the inputs too):

```
copy <rule>\input.hx <rule>\hxformat.hx
haxelib run formatter -s <rule>\hxformat.hx
```

Generated with formatter 1.18.0.
