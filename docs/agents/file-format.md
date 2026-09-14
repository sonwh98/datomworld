---
description: File format specifications and rules for .chp and .blog files (EDN with Hiccup markup)
---

# FILE FORMATS

`.chp` and `.blog` files are EDN files with Hiccup markup.

## Structure for .blog files

```clojure
#:blog{:title "Title Here"
       :date #inst "YYYY-MM-DDTHH:MM:SS.000-00:00"
       :abstract [:p "Abstract with hiccup markup..."]
       :content [:section.blog-article
                 [:div.section-inner
                  [:article
                   :$blog-title
                   :$blog-date
                   ;; Hiccup content here
                   ]]]}
```

## Guidelines for .blog files

- Use EDN syntax with namespaced maps (`#:blog{...}`)
- Use Hiccup vectors for all markup (`[:p "text"]`, `[:strong "bold"]`, `[:a {:href "..."} "link"]`)
- Never use markdown syntax
- Include `:$blog-title` and `:$blog-date` where appropriate
- Never use em dashes (—). Use commas, colons, periods, or parentheses instead.

## Tables in .md files

Tables in `.md` files (design documents, agent guides) are ASCII box tables,
never markdown pipe tables:

+-------------------+---------------------------------------------------------------------------+
| Rule              | Detail                                                                    |
+===================+===========================================================================+
| Borders           | `+`, `-`, and `|` draw the box; a `+===+` rule separates the header row   |
|                   | from the body; every row closes with a full `+---+` border                |
+-------------------+---------------------------------------------------------------------------+
| Maximum width     | 170 columns, counted in characters (UTF-8 aware), including the borders   |
+-------------------+---------------------------------------------------------------------------+
| Column widths     | Columns that fit at their natural width stay unwrapped; wide columns      |
|                   | share the remaining budget proportionally to their content                |
+-------------------+---------------------------------------------------------------------------+
| Wrapping          | Long cell content word-wraps onto continuation lines that carry only the  |
|                   | wrapped column; other columns are blank on continuation lines; an         |
|                   | unbreakable token longer than its column hard-breaks at the column edge   |
+-------------------+---------------------------------------------------------------------------+

The example table above is itself in the format: `docs/agents/team.md` and
`docs/design/yin.vm.semantic.md` are the reference instances.
