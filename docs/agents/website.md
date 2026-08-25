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
