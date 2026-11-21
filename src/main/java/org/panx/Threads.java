package org.panx;

import java.util.*;
import java.util.regex.Pattern;

/** Linear-time Message-ID/References threading with cycle handling and stable sibling order. */
public final class Threads {
  private Threads() {}

  public static List<Row> build(
      List<Article> articles, List<Store.Rule> rules, boolean threaded, boolean hideKilled) {
    List<Compiled> compiled =
        rules.stream()
            .map(r ->
                new Compiled(
                    r,
                    Pattern.compile(
                        r.expression(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)))
            .toList();
    Map<String, Article> byId = new LinkedHashMap<>();
    for (var a : articles) byId.putIfAbsent(a.messageId(), a);
    Map<String, List<Article>> children = new HashMap<>();
    List<Article> roots = new ArrayList<>();
    for (var a : byId.values()) {
      String parent = null;
      if (threaded) {
        List<String> parents = a.parents();
        for (int i = parents.size() - 1; i >= 0; i--) {
          String id = parents.get(i);
          if (!id.equals(a.messageId()) && byId.containsKey(id)) {
            parent = id;
            break;
          }
        }
      }
      if (parent == null) roots.add(a);
      else children.computeIfAbsent(parent, k -> new ArrayList<>()).add(a);
    }
    List<Row> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (var a : roots) append(a, children, seen, result, compiled, hideKilled);
    for (var a : byId.values())
      if (!seen.contains(a.messageId())) append(a, children, seen, result, compiled, hideKilled);
    return result;
  }

  private static void append(
      Article root,
      Map<String, List<Article>> children,
      Set<String> seen,
      List<Row> result,
      List<Compiled> rules,
      boolean hide) {
    record Item(Article article, int depth) {}
    var stack = new ArrayDeque<Item>();
    stack.push(new Item(root, 0));
    while (!stack.isEmpty()) {
      var item = stack.pop();
      var a = item.article();
      if (!seen.add(a.messageId())) continue;
      int score = 0;
      for (var c : rules)
        if (c.regex().matcher(c.rule().field().equals("from") ? a.from() : a.subject()).find())
          score += c.rule().score();
      if (!hide || score > -999) result.add(new Row(a, Math.min(item.depth(), 30), score));
      var list = children.getOrDefault(a.messageId(), List.of());
      for (int i = list.size() - 1; i >= 0; i--)
        stack.push(new Item(list.get(i), item.depth() + 1));
    }
  }

  public record Row(Article article, int depth, int score) {}

  private record Compiled(Store.Rule rule, Pattern regex) {}
}
