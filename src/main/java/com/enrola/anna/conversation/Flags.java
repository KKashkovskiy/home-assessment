package com.enrola.anna.conversation;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JSON array of flag names to and from {@link EnumSet}. Unknown names are dropped with a WARN,
 * never an exception, so a renamed flag cannot make old rows unreadable. Hand-rolled: flag names
 * are {@code [A-Z_]} so nothing needs escaping, and the column stays a literal like {@code
 * ["TZ_ASSUMED"]} that a LIKE can match.
 */
final class Flags {

  private static final Logger log = LoggerFactory.getLogger(Flags.class);
  private static final Pattern NAME = Pattern.compile("[A-Z_]+");
  private static final Map<String, Flag> BY_NAME =
      Arrays.stream(Flag.values()).collect(Collectors.toMap(Flag::name, Function.identity()));

  private Flags() {}

  static EnumSet<Flag> parse(String json) {
    EnumSet<Flag> flags = EnumSet.noneOf(Flag.class);
    Matcher name = NAME.matcher(json);
    while (name.find()) {
      Flag flag = BY_NAME.get(name.group());
      if (flag == null) {
        log.warn("dropping unknown flag {}", name.group());
      } else {
        flags.add(flag);
      }
    }
    return flags;
  }

  static String write(Set<Flag> flags) {
    return flags.stream()
        .map(flag -> '"' + flag.name() + '"')
        .collect(Collectors.joining(",", "[", "]"));
  }
}
