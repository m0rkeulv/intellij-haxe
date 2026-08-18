# Haxe language level differences (3.4 → 5.0)

Reference for level-aware editor features: annotating syntax that the module's
configured language level does not support, offering "raise language level"
or rewrite quickfixes, and gating completion/resolution of std classes that do
not exist at a given level. Levels map 1:1 to `HaxeLanguageLevel`
(3.4, 4.0, 4.1, 4.2, 4.3, 5.0).

Sources: official `CHANGES.txt` shipped with Haxe 4.3.7 (covers every 4.x
release and the 4.0 preview/rc cycle where most 4.0 features landed) and with
Haxe 5.0.0-preview.1, plus a file-level diff of the two installs' `std/`
trees. Items sourced from release notes rather than CHANGES are marked
`[release-notes]`; disputed cases were settled against the tagged compiler
sources on GitHub (e.g. `is` parsing checked in the 3.4.7 and 4.0.5 parser).
5.0 content reflects **preview.1** — recheck when 5.0 final's CHANGES is
available.

How to read the tables:

- **Introduced in** — the minimum level; using the construct in a module set
  to a LOWER level deserves an error annotation with a "set language level to
  X" quickfix (and, where listed, a rewrite quickfix down-leveling the code).
- **Removed in** — constructs valid at OLDER levels that became errors; a
  module set to the newer level should flag them, ideally with the listed
  modernization quickfix.

---

## 3.4 → 4.0

The largest jump. Most 4.0 features shipped across 4.0.0-preview.1 … rc.3.

### New syntax (error below 4.0)

| Feature | Example | Rewrite quickfix (to 3.4) |
|---|---|---|
| Arrow functions | `x -> x + 1`, `(a, b) -> a + b` | convert to `function(x) return x + 1` |
| `final` keyword (fields, locals, classes, methods) | `final x = 1;` `final class C {}` | field/method: `@:final` meta; local: `var` (semantic loss) |
| New function type syntax | `(a:Int, b:String)->Void`, `(Int)->Void` | `Int->String->Void` (drops arg names) |
| `enum abstract` keyword form | `enum abstract Color(Int) {}` | `@:enum abstract` |
| `extern` as field modifier | `extern inline function f()` | `@:extern` meta |
| Intersection types | `T:Type1 & Type2`, `{> A, > B }` constraints | `T:(Type1, Type2)` (constraint position only) |
| Key-value iteration | `for (k => v in map)` | `for (k in map.keys())` + lookup |
| Map comprehension with `=>` | `[for (i in 0...3) i => i * 2]` | imperative loop |
| `inline` call / `inline new` | `inline f(x)`, `inline new C()` | drop `inline` |
| Markup literals | `var x = <xml/>;` (must be macro-processed) | none |
| Metadata with dotted names | `@:a.b` | none |
| `var ?x` / `final ?x` in structures | `{ var ?x:Int; }` | `@:optional var x:Int;` |
| `case var x` patterns | `case var x:` | `case x:` (loses shadow-typo detection) |
| Enum ctor without args as default arg / `static inline var` value | `function f(e = MyEnum.None)` | none |
| Write-mode `@:op(a.b)` on abstracts | field-write operator overload | none |
| `@:using` on type declarations | `@:using(Tools)` on class/enum | `using` import in each file |
| Auto-numbering / auto-string enum abstracts | `enum abstract E(Int) { var A; var B; }` | explicit values |
| Dotted conditional identifiers | `#if target.sys`, `#if (a.b)` | none |
| `@:bypassAccessor` | skip property accessor | none |
| Null safety (opt-in) | `--macro nullSafety("pack")`, `@:nullSafety` | none |

Also new at 4.0: `operator` and `overload` became **reserved keywords**
(identifiers named so are errors at 4.0+, valid at 3.4); the `haxe4` define;
Unicode strings on all targets; the JVM target.

### Removed / now errors at 4.0 (valid at 3.4)

| Construct | 4.0 replacement (modernization quickfix) |
|---|---|
| `var x(get_x, set_x):T` accessor-name property syntax | `var x(get, set):T` |
| Default values on interface `var`s | remove initializer |
| `implements Dynamic` on non-extern classes | remove; use `Dynamic` fields explicitly |
| `static var x;` without BOTH type hint and initializer | add type hint or initializer |
| `T:(A, B)` multi-constraint syntax | `T:A & B` |
| `@:fakeEnum` enums | none (removed) |
| `\x` string escapes above `0x7F` | `\u` escape |
| Metadata on lambda arguments | remove |
| `function() {}(e)` parsed as immediate call | wrap in parentheses |
| `return null` from `Void` functions | `return;` |
| Static extensions through abstract field casts / on implicit `this` | explicit receiver |
| `untyped __js__ / __php__ / __call__` (deprecated 4.0-era, removed later) | `js.Syntax.code` / `php.Syntax.code` |

### Std library (3.4 → 4.0)

Added: `haxe.iterators.*`, `Array.resize`, `EReg.escape`,
`StringTools.contains`, `UnicodeString` (deprecates `haxe.Utf8`),
`Std.downcast` (deprecates `Std.instance`), unified `sys.thread.*`,
`Map.clear`, `haxe.DynamicAccess.iterator/keyValueIterator`,
`haxe.display.*` (JSON-RPC display types), `haxe.xml.Access` (replaces
`haxe.xml.Fast` — kept as deprecated alias `[release-notes]`).

Moved OUT of std (error at 4.0+ unless `hx3compat`/`record-macros` lib
present): `haxe.unit.*`, `haxe.web.Request`, `haxe.web.Dispatch`,
`haxe.remoting.*` (partially; fully removed at 5.0), `js.JQuery`,
`js.SWFObject`, `js.XMLSocket`, `neko.net.*`, SPOD (`sys.db.Object`,
`sys.db.Manager`). `List` moved to `haxe.ds.List` (toplevel `List` kept as
deprecated alias). Typed arrays and several classes moved `js.html` →
`js.lib`. Behavioral: `Lambda` functions return `Array` instead of `List`.

---

## 4.0 → 4.1

### New syntax / language features (error below 4.1)

| Feature | Example | Notes |
|---|---|---|
| Unified exceptions | `haxe.Exception`, `catch (e:haxe.Exception)` | class exists only at 4.1+ |
| Untyped catch shorthand | `try {} catch (e) {}` | rewrite: `catch (e:Dynamic)` |
| `(get, default)` property combination | `var x(get, default):Int` | previously rejected |
| `++` / `--` on abstract member properties | | |

Semantics: tail recursion elimination; `@:overload` + `inline` combination
disallowed (was accepted before).

### Std library (4.0 → 4.1)

Added: `Std.isOfType` (deprecates `Std.is`), `Array.contains`,
`Array.keyValueIterator`, `Lambda.findIndex`, `Lambda.foldi`,
`haxe.Constraints.NotVoid`, `haxe.ds.HashMap` array access + key-value
iteration; `Array.iterator()` returns `haxe.iterators.ArrayIterator`.

Deprecated at 4.1 (warning + modernization quickfix): `Std.is` →
`Std.isOfType`; `untyped __js__(...)` → `js.Syntax.code(...)`; `neko.Web` /
`php.Web` (removed from std at 5.0).

---

## 4.1 → 4.2

### New syntax (error below 4.2)

| Feature | Example | Rewrite quickfix (to 4.1) |
|---|---|---|
| Module-level fields | `function main() {}` at file top level | wrap in a class with statics |
| `abstract class` / abstract methods | `abstract class Base { abstract function f():Void; }` | interface + base class |
| Rest arguments | `function f(...args:Int)`, `haxe.Rest<T>` | array argument |
| Unparenthesized `is` | `expr is SomeType` | `(expr is SomeType)` — the PARENTHESIZED form is valid at every level incl. 3.4 (verified in the 3.4.7 parser source: `(e is T)` desugars to `Std.is`) |
| Metadata in `var` declarations | `var @:meta x = 1;` | move meta before statement |
| `@:forward.new` on abstracts | constructor forwarding | explicit `new` |
| `@:forward.variance` | variance forwarding | none |
| `@:using` on typedefs | | none |
| `@:native` on enum constructors | | none |
| Extern method overloading (all targets) | multiple `extern` signatures | `@:overload` meta |
| `function` as a package name | `import function.x` | rename package |

Semantics: `Map` abstract became transitive; `Any` treated as `Dynamic` in
variance unification; `haxe.exceptions.*` common exception types added;
no-arg function types print as `()->...`.

### Std library (4.1 → 4.2)

Added: `haxe.Rest`, `haxe.exceptions.*`, `sys.thread.Thread.events`
(per-thread event loops), `StringTools.unsafeCharAt`, `eval.luv.*`,
`eval.integers.*`.

---

## 4.2 → 4.3

### New syntax (error below 4.3)

| Feature | Example | Rewrite quickfix (to 4.2) |
|---|---|---|
| Safe navigation | `a?.b?.c` | explicit null checks / ternary |
| Null coalescing | `a ?? b` | `a != null ? a : b` |
| Null coalescing assignment | `a ??= b` | `if (a == null) a = b;` |
| Default type parameters | `class C<T = String> {}` | remove default, spell out at use sites |
| Local (expression-level) `static var` | `static var count = 0;` inside a function | class-level static |
| `@:op(a())` call operator on abstracts | make abstract callable | named method |
| `abstract` keyword as self-reference in abstract body | `abstract.method()` | the impl-class idiom |
| Custom metadata/defines registration | `@:haxe.warning`, user meta declarations | none |

Semantics: trailing commas consistently allowed everywhere (call args, array
literals, …) — a 4.2-or-lower module may flag trailing commas the compiler
of that era rejected; null literals infer as `Null<?>`; `-w` warning
configuration; new error-reporting modes (pretty errors).

### Std library (4.2 → 4.3)

Added: atomic operations (`haxe.atomic.*`), `Vector.fill`,
`sys.thread.Condition`, `sys.thread.Semaphore`,
`Http.getResponseHeaderValues`. Behavioral: `Sys.putEnv(name, null)` unsets;
`Std.parseInt` consistency pass; PCRE2 migration (regex behavior details).

---

## 4.3 → 5.0 (from 5.0.0-preview.1)

### New syntax / features (error below 5.0)

| Feature | Example | Rewrite quickfix (to 4.3) |
|---|---|---|
| Binary integer literals | `0b1010` | decimal/hex literal |
| Safe-navigation bind | `f?.bind(x)` | null check + `bind` |
| Boolean operators in patterns | `case x if-less boolean pattern ops` | guard clause |
| Private getters/setters | accessor with narrower visibility | none |
| Explicit default type parameter application | apply `<>` defaults explicitly | none |
| Modifying the loop variable of an `IntIterator` loop | `for (i in 0...n) i += 1;` | `while` loop |
| Overloading true extern constructors | | `@:overload` |
| `haxe.Unit` enum | | none |

### Removed / breaking at 5.0 (valid at 4.3)

| Construct | Impact / quickfix |
|---|---|
| C# and Java (source-gen) targets | modules/externs `cs.*` and non-jvm `java.*` gone; JVM target remains |
| `haxe.Ucs2` | removed |
| `sys.db.*` (Connection, Mysql, Sqlite, ResultSet), `php.Web`, `neko.Web`, `haxe.remoting.*` | moved to `hx4compat` lib — resolve only when that lib is a dependency |
| Partial resolution `pack.SubType` when the module is imported | fully qualify or import the subtype |
| Duplicate function argument names | rename arguments |
| `?.new` and `?.match` | disallowed — expand manually |
| String inference on concatenation | `x = a + b` no longer infers `x:String` from monomorphs — add type hints |
| `bind` handling of optional arguments | behavior change — audit `bind` call sites |
| Module resolution rework | import edge cases can resolve differently |
| Defining types into existing modules from macros | disallowed |

### Std library (4.3.7 → 5.0.0-preview.1, from std/ diff + CHANGES)

Added: `haxe.Unit`, `haxe.math.bigint.*` (`BigInt`, `MutableBigInt`,
`BigIntArithmetic`, …), `haxe.runtime.Copy`, `haxe.Timer.milliseconds`,
`StringBuf.clear()`, settable `haxe.Exception.stack`, `Serializer.reset()`,
`haxe.hxb.WriterConfig`, `hl.GUID`, `jvm.NativeArray`/`jvm.NativeString`/
`jvm.Int8/16/64`/`jvm.Char16`, `jvm.net.SslSocket`, `js.lib.NativeStringTools`.

Removed (beyond the table above): the whole `cs/` and `java/` (non-jvm)
extern trees, `php.db.*` (Mysqli, PDO, SQLite3).

Tooling-relevant (not annotation-relevant): hxb server cache, pretty errors
by default, diagnostics as JSON-RPC (matches our display-protocol client),
`--undefine`, `-D fail-fast`, ipv6 `--wait`/`--connect`.

---

## Implementation notes for annotator/quickfix work

- **Parse-level features** (arrow functions, `?.`, `??`, `0b`, module-level
  fields, `abstract class`, rest args, markup literals): our grammar accepts
  the superset — the level check is a post-parse annotation on the PSI node
  kind, not a parser switch. Each table row above names the PSI construct to
  key on.
- **Keyword-level differences**: `final`, `enum abstract`, `is`, `operator`/
  `overload` reservation — flag on the token at lower levels.
- **Std availability**: resolution already follows the module's SDK, which
  usually matches the level. The level-based check matters when the level is
  set BELOW the SDK (targeting older syntax with a newer compiler) — then
  "class exists in SDK but not at level" should warn rather than error, since
  the code still compiles. The deprecation pairs (`Std.is`→`Std.isOfType`,
  `haxe.Utf8`→`UnicodeString`, `Std.instance`→`Std.downcast`,
  `untyped __js__`→`js.Syntax.code`, `haxe.xml.Fast`→`haxe.xml.Access`) are
  the highest-value modernization quickfixes.
- **Reverse direction** (construct removed at the module's level, e.g.
  `get_x/set_x` properties at 4.0+): these are compile errors the static
  annotator can report precisely with the modernization quickfix from the
  "Removed" tables.
- **Both directions need the effective level**, which is
  `HaxeCompilerSettings.getEffectiveLanguageLevel(moduleName)` — the same
  store behind Settings | Compiler | Haxe Compiler and the tool window's
  Language level row.
- 5.0 rows are preview-sourced; keep this file updated from the final 5.0
  CHANGES and mark items that change.
