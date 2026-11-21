package org.panx;

import java.util.*;
import java.util.regex.Pattern;

public record Article(
    long number,
    String subject,
    String from,
    String date,
    String messageId,
    String references,
    long bytes,
    long lines,
    boolean read,
    boolean flagged) {
  private static final Pattern IDS = Pattern.compile("<[^<>\\s]+>");

  public static Article overview(String line) {
    String[] p = line.split("\t", -1);
    if (p.length < 8) throw new IllegalArgumentException("Invalid overview");
    return new Article(
        Long.parseLong(p[0]),
        Mime.decodeHeader(p[1]),
        Mime.decodeHeader(p[2]),
        p[3],
        p[4],
        p[5],
        number(p[6]),
        number(p[7]),
        false,
        false);
  }

  private static long number(String s) {
    try {
      return Long.parseLong(s);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  public List<String> parents() {
    var result = new ArrayList<String>();
    var m = IDS.matcher(references);
    while (m.find()) result.add(m.group());
    return result;
  }
}
