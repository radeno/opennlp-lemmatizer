# Analyzer examples

Ready-made index-analyzer configs for **both** filters this plugin ships, per language. Apply one
(after the matching files are in `config/opennlp/` — see the main [README](../README.md#models)):

```bash
curl -XPUT localhost:9200/my-index -H 'Content-Type: application/json' \
  --data-binary @examples/cs-dictionary-analyzer.json
```

| file | filter | files it loads | when |
|---|---|---|---|
| [cs-analyzer.json](cs-analyzer.json) | `opennlp_lemmatizer` | `cs-pos.bin` + `cs-lemmas.bin` | Czech — best quality (POS-aware) |
| [sk-analyzer.json](sk-analyzer.json) | `opennlp_lemmatizer` | `sk-pos.bin` + `sk-lemmas.bin` | Slovak — best quality (POS-aware) |
| [cs-dictionary-analyzer.json](cs-dictionary-analyzer.json) | `dictionary_lemmatizer` | `cs-ud.txt` | Czech — max speed (flat, POS-free) |
| [sk-dictionary-analyzer.json](sk-dictionary-analyzer.json) | `dictionary_lemmatizer` | `sk-michmech.txt` | Slovak — max speed (flat, POS-free) |

**Choosing the filter.** `opennlp_lemmatizer` is POS-aware — it disambiguates homonyms in context
(`je → být`, not `jesť`) and writes the POS tag to the token's `type`, at the cost of speed.
`dictionary_lemmatizer` is a plain `form → lemma` lookup — far faster, but no context. They are two
filter *types* in the same plugin; you don't install anything extra to switch.

**Choosing the language file.** Nothing in the plugin is language-specific — you pick the language
purely by the file names in the settings: `*-pos.bin` / `*-lemmas.bin` for `opennlp_lemmatizer`, or
the `dictionary` file for `dictionary_lemmatizer`. Switch languages by switching the file names, no
rebuild. Fetch the files with [`scripts/fetch-models.sh`](../scripts/fetch-models.sh): models with
`fetch-models.sh cs`, and the dictionary with `fetch-models.sh cs-ud` (Czech, from Universal
Dependencies) or `fetch-models.sh sk-michmech` (Slovak, from michmech).
