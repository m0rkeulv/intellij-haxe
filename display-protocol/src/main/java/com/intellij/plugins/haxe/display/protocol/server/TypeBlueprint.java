package com.intellij.plugins.haxe.display.protocol.server;

import com.intellij.plugins.haxe.display.protocol.JsonTypeRef;
import java.util.List;

/**
 * The post-macro shape of one type ({@code server/type}): every member with
 * its resolved type, INCLUDING macro-generated ones that exist in no source
 * file. This is what member lookups on statically-unresolvable receivers
 * resolve against.
 */
public record TypeBlueprint(String name,
                            String kind,
                            List<Member> fields,
                            List<Member> statics) {

  /** {@code fieldKind} is the JsonClassField kind: FMethod, FVar or FProp. */
  public record Member(String name, JsonTypeRef type, String fieldKind) {
    public boolean isMethod() {
      return "FMethod".equals(fieldKind);
    }
  }

  public Member findMember(String memberName) {
    for (Member member : fields) {
      if (member.name().equals(memberName)) return member;
    }
    for (Member member : statics) {
      if (member.name().equals(memberName)) return member;
    }
    return null;
  }
}
