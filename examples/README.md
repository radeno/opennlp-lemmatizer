# Analyzer examples

Ready-made index-analyzer configs for the filters this plugin ships, per language. Apply one
(after the matching files are in `config/opennlp/` — see the main [README](../README.md#models)):

```bash
curl -XPUT localhost:9200/my-index -H 'Content-Type: application/json' \
  --data-binary @examples/cs-dictionary-analyzer.json
```

| file | filter | files it loads | when |
|---|---|---|---|
| [cs-analyzer.json](cs-analyzer.json) | `opennlp_lemmatizer` | `cs-pos.bin` + `cs-lemmas.bin` | Czech — best generalisation (POS-aware model) |
| [sk-analyzer.json](sk-analyzer.json) | `opennlp_lemmatizer` | `sk-pos.bin` + `sk-lemmas.bin` | Slovak — best generalisation (POS-aware model) |
| [sk-pos-dictionary-analyzer.json](sk-pos-dictionary-analyzer.json) | `pos_dictionary_lemmatizer` | `sk-pos.bin` + `sk-lemmas.bin` + `sk-mte-pos.txt` | Slovak — best precision on known words (POS dict + model) |
| [cs-dictionary-analyzer.json](cs-dictionary-analyzer.json) | `dictionary_lemmatizer` | `cs-ud.txt` | Czech — max speed (flat, POS-free) |
| [sk-dictionary-analyzer.json](sk-dictionary-analyzer.json) | `dictionary_lemmatizer` | `sk-mte.txt` | Slovak — max speed (flat, POS-free) |
| [sk-gender-analyzer.json](sk-gender-analyzer.json) | `pos_dictionary_lemmatizer` (`pos_format: native`) | `sk-gender.bin` + `sk-lemmas.bin` + `sk-gender-dict.txt` | Slovak — disambiguates gender-homonyms (`hrady → hrad`/`hrada`) |

> **Gender model + dictionary** come from a GitHub Release, not the official model repos — fetch them
> with `./scripts/fetch-models.sh sk-gender` (also run `fetch-models.sh sk` for `sk-lemmas.bin`). They
> are reproducible/rebuildable with
> [`experiments/gender/build-gender-model.sh`](../experiments/gender/build-gender-model.sh); see
> [experiments/gender/](../experiments/gender/README.md) for the measurements.

**Choosing the filter.** `opennlp_lemmatizer` is the POS-aware MaxEnt model — it disambiguates homonyms
in context (`je → být`, not `jesť`) and lemmatises unseen words, at the cost of speed.
`pos_dictionary_lemmatizer` adds a POS-aware `form/POS/lemma` dictionary in front of that model for exact
lemmas on known words (with `pos_format: native` it can use a finer UD/UPOS+gender tagset).
`dictionary_lemmatizer` is a plain `form → lemma` lookup — far faster, but no context. They are all
filter *types* in the same plugin; you don't install anything extra to switch.

**Choosing the language file.** Nothing in the plugin is language-specific — you pick the language
purely by the file names in the settings: `*-pos.bin` / `*-lemmas.bin` for `opennlp_lemmatizer`, or
the `dictionary` file for `dictionary_lemmatizer`. Switch languages by switching the file names, no
rebuild. Fetch the files with [`scripts/fetch-models.sh`](../scripts/fetch-models.sh): models with
`fetch-models.sh cs`, and the dictionary with `fetch-models.sh cs-ud` (Czech, from Universal
Dependencies) or `fetch-models.sh sk-mte` (Slovak, from MULTEXT-East).
