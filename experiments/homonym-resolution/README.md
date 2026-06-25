# Homonym resolution (experiment)

`pos_dictionary_lemmatizer` keys the dictionary on `(form, POS)` and **drops** any pair with more than
one lemma — ~4,793 Slovak forms where the coarse `NN` tag can't decide between two words (gender/case
homonyms). Dropped forms fall to the MaxEnt model, which guesses, often wrong:

```
hradu  -> hrada   (should be hrad — castle, not hrada/flower-bed)
hostia -> host    (should be hosť — guest, not the wafer/verb)
```

This recovers them by **corpus frequency**: UDPipe lemmatises a large corpus, and for each dropped
`(form, POS)` we keep the candidate lemma it most often produces. The result is a small
`sk-homonyms.txt` (`form<TAB>POS<TAB>lemma`) that `scripts/fetch-models.sh sk-mte-pos` **auto-merges**
into the dictionary.

## Measurements (general Slovak Wikipedia, 5.0M UDPipe lemmas)

| dropped `(form, POS)` | candidates | corpus freq | resolved |
|---|---|---|---|
| `hradu` / NN | hrad, hrada | hrad 393, hradu 12 | **hrad** ✓ |
| `hostia` / NN | hostia, hosť | hosť 48 | **hosť** ✓ |
| `autom` / NN | aut, auto | auto 51 | **auto** ✓ |
| `more` / NN | mor, mora, more | more 136 | **more** ✓ |
| `hrady` / NN | hrad, hrada | hrad 18, hrada 19 | hrada ✗ (near-tie noise) |
| `plese` / NN | ples, pleso | ples 14, plesa 6 | ples ✗ (domain: tourism wants `pleso`) |

- **4,793 dropped → 1,122 resolved** (the rest don't occur in the corpus → still dropped).
- Verified on OpenSearch: `do hradu → hrad`, `hostia prišli → hosť`, no regression (`je → byť`).
- **Domain matters.** Frequencies are general-text. `plese → ples` (dance) is correct for Wikipedia but
  wrong for a tourism corpus (`pleso`/lake). Regenerate from your own corpus for domain accuracy.

## Use / reproduce

```bash
# default (general Wikipedia) is committed as sk-homonyms.txt and auto-merged by fetch-models.sh.
# regenerate from a domain corpus (one sentence per line):
mise exec -- experiments/homonym-resolution/resolve-homonyms.sh sk my-tourism-corpus.txt
./scripts/fetch-models.sh sk-mte-pos     # picks up the regenerated sk-homonyms.txt automatically
```

Pairs with the POS-relax `*` rows (mis-tag recovery) the same build already emits — together they close
most of the gap to jLemmaGen on common words while keeping the POS-aware homonym advantage (`je → byť`).
