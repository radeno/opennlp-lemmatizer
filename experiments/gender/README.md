# Gender-aware lemmatization (experiment)

Slovak has ~1,025 common **gender-homonyms** — forms the coarse Penn `NN` tag cannot tell apart:
`hrady` = `hrad` (m, *castle*) **or** `hrada` (f); `autom` = `auto` (n, *car*) **or** `aut` (m, *out*);
`banku` = `banka` (f) **or** `bank` (m). The shipped `pos_dictionary_lemmatizer` drops these (its
`(form, POS)` key is ambiguous) and the model fallback guesses, often wrong.

This experiment disambiguates them with **grammatical gender**: a POS model trained to emit a
`UPOS+gender` tagset (`NOUN.Masc`/`NOUN.Fem`/`NOUN.Neut`), plus a dictionary keyed on `form + UPOS.gender`
(so `hrady` splits into `NOUN.Masc→hrad` / `NOUN.Fem→hrada`). It rides the **shipped**
`pos_dictionary_lemmatizer` filter via the `pos_format: native` setting — no new filter.

> Status: **works end-to-end on OpenSearch**, but the gender model (~8–24 MB) and dict are not yet
> distributed as release artifacts. Reproduce with `build-gender-model.sh` below. The gender tagger
> caps at ~87% (so ~13% of gender slots are wrong) — net-positive on the homonym class, not perfect.

## Use (`pos_format: native`)

```bash
curl -XPOST localhost:9201/_analyze -H 'Content-Type: application/json' -d '{
  "tokenizer": "whitespace",
  "filter": [ "lowercase", {
    "type": "pos_dictionary_lemmatizer",
    "pos_model": "sk-gender.bin",
    "lemmatizer_model": "sk-lemmas.bin",
    "dictionary": "sk-gender-dict.txt",
    "pos_format": "native"
  } ],
  "text": "Cukor sa vyrába z repy a deti jedia jablká"
}'
# -> cukor sa vyrábať z repa a dieťa jesť jablko
```

`pos_format` is a real plugin setting: `penn` (default, normalises to the Penn tagset — what the existing
dict needs) or `native` (keeps the model's own UD/UPOS+gender tags). See [analyzer.json](analyzer.json).

## Measurements

### 1. Disambiguation on the node — `pos_dictionary` (Penn) vs gender (`native`), 15 homonyms

| target | gold | penn | gender |
|---|---|---|---|
| repy | repa | repy ✗ | **repa** ✓ |
| diel | diel | dielo ✗ | **diel** ✓ |
| autom | auto | auto ✓ | auto ✓ |
| banke | banka | banka ✓ | banka ✓ |
| lese | les | les ✓ | les ✓ |
| jablká | jablko | jablko ✓ | jablko ✓ |
| more / meno / vína / radu / veko / vinu / silu | — | ✓ | ✓ |
| hrady | hrad | hrada ✗ | hrada ✗ (gender mistag) |
| angínu | angína | angín ✗ | angín ✗ |
| **total** | | **11/15 (73%)** | **13/15 (86%)** |

Gender fixes the two genuine homonyms (`repy`, `diel`); the two it still misses are the model's own
gender errors (`hrady`→Fem) or out-of-dict (`angínu`), not the format. No regressions vs Penn here.

### 2. Distillation — training the gender POS model (eval on UD Slovak-SNK gold test)

| training data | tokens | overall tag | **gender** |
|---|---|---|---|
| UD Slovak-SNK (gold) | 80k | 83.8% | 80.5% |
| + MTE-1984 corpus (gold) | 184k | 82.6% | 82.5% |
| UDPipe-tagged Wikipedia (silver) | 1.13M | 87.6% | 85.8% |
| silver + gold | 1.31M | 88.0% | 86.2% |
| **silver (full 300k) + gold** | 5.18M | 89.1% | **87.89%** |
| lowercased 5M + gold (shipped model) | 5.18M | 88.6% | 87.92% |
| **UDPipe teacher (ceiling)** | — | — | 87.25% |

Distilling UDPipe's tags into a pure-Java OpenNLP MaxEnt model **matches the neural teacher** on gender —
with no native dependency at runtime. More silver data is the lever (80.5%→87.9%); UDPipe segfaulted at
70k/300k, so the full re-tag is chunked (separate JVM per chunk).

### 3. Model size (MaxEnt size scales with training vocabulary, not duplication)

| model | size | trained on |
|---|---|---|
| `sk-pos.bin` (official) | 0.4 MB | UD-SNK ~100k |
| `cs-pos.bin` (official) | 9.0 MB | Czech PDT ~2M |
| gender, 1.13M silver | 8.3 MB | 1.13M |
| gender, 5M silver+gold | 24 MB | 5.18M, ~395k word types |

24 MB is reducible with a higher MaxEnt `Cutoff` (drops rare features) at little accuracy cost.

### 4. Scope — the gender-homonym class

| | count |
|---|---|
| ambiguous `(form, POS)` dropped by the Penn dict | 4,793 |
| of which **gender-resolvable** | 1,122 |
| → common-word homonyms (`auto`/`aut`, `banka`/`bank`) | 1,025 |
| → proper-name pairs (`Adrián`/`Adriána`) | 97 |

~0.12% of the dictionary — meaningful (`auto` vs `aut`!) but a small, hard class.

## Reproduce

`./build-gender-model.sh` runs the whole pipeline (needs `experiments/udpipe` set up as the teacher,
plus `python3`, `gzip`, JDK 25):

1. download a raw Slovak corpus (Leipzig `slk_wikipedia_2021_300K`)
2. tag it with UDPipe in 5,000-line chunks (separate JVM per chunk — sidesteps a native segfault) → silver
3. fetch + convert UD Slovak-SNK and the MTE-1984 corpus to the `UPOS.gender` tagset (gold)
4. lowercase + combine, train an OpenNLP POS model → `sk-gender.bin`
5. build the gender-keyed dictionary from MULTEXT-East → `sk-gender-dict.txt`

Then copy both into the node's `config/opennlp/` and analyze with `pos_format: native`.
