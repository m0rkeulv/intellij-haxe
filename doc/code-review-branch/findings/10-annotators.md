# 10-annotators

### should-fix — src/main/java/com/intellij/plugins/haxe/ide/annotator/semantics/HaxeLanguageFeatureAnnotator.java:27
The class javadoc says "The feature/level pairs come from doc/haxe-language-levels.md",
but that file is untracked (`?? doc/haxe-language-levels.md` in git status) — it exists
only in the working tree and is not part of the branch. Checklist: files cited by
javadoc/comments must be committed. Commit the doc alongside the annotator (or drop
the citation).

### minor — src/main/java/com/intellij/plugins/haxe/ide/annotator/HaxeStandardAnnotation.java:55 (removedAtLanguageLevel)
`HaxeLanguageLevel lastSupported = HaxeLanguageLevel.values()[removedIn.ordinal() - 1];`
computes "the level before X" by raw ordinal arithmetic inside the annotation helper.
This is enum domain knowledge living in a caller (duplication checklist: domain
knowledge lives in its domain's class), and it throws ArrayIndexOutOfBoundsException
if any future caller passes the first constant (`HAXE_3_4`) — nothing in the public
signature prevents that. Move it onto the enum as e.g. `HaxeLanguageLevel.previous()`
with a defined answer (or assertion) for the floor level.

### minor — src/main/java/com/intellij/plugins/haxe/ide/annotator/semantics/HaxeAbstractClassAnnotator.java:45-53 (annotateAbstractModifierRequiresLevel)
Each branch re-evaluates the model three times: the method branch spells
`haxeMethod.getModel() != null && haxeMethod.getModel().isAbstract()` in the head and
then `haxeMethod.getModel().getModifiers()` in the body (the class branch likewise
calls `haxeClass.getModel()` twice). Per the style rule "the same expression evaluated
in several branches becomes one local", pull `var model = haxeMethod.getModel();` into
each branch (or, since the whole chain is type tests on one value, use a pattern
switch) and read `model` from there.

### minor — src/main/java/com/intellij/plugins/haxe/ide/annotator/color/HaxeStringLinkColorAnnotator.java:14-19 (class javadoc)
The javadoc states "The references only EXIST when their target resolved … so presence
is the whole signal; no resolution happens here beyond what reference collection
already did", but the body then gates path painting on `lastSegment.resolve() != null`
— an actual resolve of the last file-path segment, needed precisely because segment
references attach to every clean constant string (as the in-method comment correctly
explains). The javadoc overstates: presence is the whole signal only for the
qname/file-link reference types; per "comments state behaviour", reword it so it does
not contradict the resolve call below.

### minor — src/main/java/com/intellij/plugins/haxe/ide/annotator/semantics/* (20 files, e.g. HaxeAccessAnnotator.java:41)
The identical two-line preamble `if (AnnotatorUtil.isInGeneratedPreview(element))
return; if(!element.isValid()) return;` is now stamped into every semantic annotator's
`annotate()`. The predicate itself has a named home, but the guard *pair* is the same
expression in 20 places; a single `AnnotatorUtil.shouldSkip(element)` (preview OR
invalid) — or a small shared base class with a final `annotate()` — would leave one
line per annotator and one place to extend when the next global skip condition
arrives.
