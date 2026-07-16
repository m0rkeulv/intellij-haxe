# Research: hscript as the HashLink debugger's expression evaluator

Status: **assessed, parked as backlog** (2026-07-16). No code change; do this
when an expression the current evaluator cannot parse actually bites, not
before.

## Question

The hxcpp debug server (`debuggers/intellij-hxcpp-debugger`) evaluates watch/
hover/condition expressions with hscript. Could the HashLink debugger use
hscript too, or is the wiring too complicated given that it pulls values out
of debuggee memory directly?

## Short answer

Feasible — but only **half** of hscript transfers, and it matters which half:
reuse the **Parser**, do not reuse the **Interp**.

## Why the Interp half does not fit

hscript's `Interp` works in the hxcpp server because that server runs INSIDE
the debuggee: frame locals are real in-process Haxe objects, `Interp.call` is
literally `Reflect.callMethod(o, f, args)`, and `+`/`==`/field access are
ordinary Haxe operations on `Dynamic`.

The HashLink debugger is out-of-process. Adapter-side values are **proxies**
(address + hlType) decoded from debuggee memory by the resolvers. Reflection
on a proxy is meaningless. Subclassing `Interp` and overriding
`get`/`set`/`call`/`cnew` to route through the memory layer covers field
access and calls, but the binary operators still execute adapter-side on
whatever `Dynamic` they are handed:

- primitives are fine — ints/floats/bools/strings are already materialized
  by the existing decoders;
- object identity (`==` on two proxies), string building, and anything that
  should allocate in the debuggee would silently compute the wrong thing on
  proxy objects.

You would be fighting the class instead of using it.

## Why the Parser half fits perfectly

hscript cleanly separates `Parser` (string → `Expr` AST) from `Interp`
(AST → value). Reusing just the parser buys a complete, battle-tested Haxe
expression grammar (ternaries, compound expressions, array/object literals,
operator precedence) for free.

The evaluator side is then a small AST walker — a few hundred lines switching
on `hscript.Tools.expr(e)` — that maps each node onto machinery the HL
debugger **already has**:

| AST node            | Existing HL machinery                          |
|---------------------|------------------------------------------------|
| field access        | memory resolvers / RuntimeTypes                |
| method call         | eval-call trampoline (`EvalCallInjector`, x64 + x86 cdecl) |
| assignment          | mutation path                                  |
| literals, arithmetic| adapter-side materialized values               |
| `new`               | debuggee-side allocation — see caution below   |

## Cautions (from the hxcpp work, they transfer)

- **GC safety.** Evaluated calls run inside a paused VM via the trampoline;
  anything that triggers GC allocation mid-call (string concat, `new`) needs
  the same care the existing eval-call path already takes. Another reason the
  thin-walker approach beats making `Interp` work.
- **Argument marshalling.** Ints are trivial; strings/objects passed INTO a
  call need debuggee-side allocation.
- **hxcpp enum-catch bug does not transfer, but the lesson does.** On hxcpp,
  `catch` typed to one enum catches ANY enum, which made hscript's
  `Interp.exprReturn` swallow its own `Error` enum (evaluate errors became
  silent `null`s on native only — see intellij-hxcpp-debugger README gotcha
  14). The HL adapter runs on a different target so the specific bug does not
  apply, but it is the canonical example of interpreter-green/native-broken:
  verify any hscript integration against a live session, not just unit tests.

## Cost/benefit

The wiring is NOT prohibitively complicated — the hard parts (calls into the
debuggee, field resolution, writes) already exist. But the payoff is grammar
completeness, not a new capability: the current HL evaluator already handles
what the IDE needs today. Hence: backlog, trigger on real need.

## Pointers

- Working in-process reference: `debuggers/intellij-hxcpp-debugger/haxelib/
  intellij/hxcpp/debug/eval/Evaluator.hx` (`ResolvingInterp` shows the
  subclass points: `resolve`, `execute`, and the dotted-package-path
  pre-binding, which an HL walker would need an equivalent of).
- hscript AST tools: `hscript.Tools.expr`/`iter` (handle hscriptPos both ways).
- `Parser` keeps full dotted paths in `ENew`; field chains parse as nested
  `EField` rooted at a free identifier — a walker resolves dotted type paths
  itself (longest-prefix, like `bindTypePaths`).
