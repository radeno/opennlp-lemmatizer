import cz.cuni.mff.ufal.udpipe.*;
import java.util.*;

/**
 * Prototype of the udpipe_lemmatizer token-filter logic.
 *
 * A Lucene TokenFilter receives an ALREADY-tokenized stream, so we must NOT let UDPipe
 * re-tokenize. We feed the tokens to UDPipe in "horizontal" input format (one sentence per line,
 * tokens separated by spaces), run the default tagger (POS + lemma), read CoNLL-U back, and map
 * each lemma onto the corresponding input token (1:1). This is exactly what the real TokenFilter
 * will do after buffering the incoming token stream.
 *
 *   java -Djava.library.path=native -cp lib/udpipe.jar:. --enable-native-access=ALL-UNNAMED \
 *        FilterProto models/slovak-snk.udpipe
 */
public class FilterProto {
  public static void main(String[] args) {
    String modelPath = args.length > 0 ? args[0] : "models/slovak-snk.udpipe";
    Model model = Model.load(modelPath);
    if (model == null) { System.err.println("MODEL LOAD FAILED: " + modelPath); System.exit(1); }

    // "horizontal" = pre-tokenized input (whitespace-separated), so UDPipe keeps OUR tokenization.
    Pipeline pipeline = new Pipeline(model, "horizontal", Model.getDEFAULT(), Pipeline.getNONE(), "conllu");
    ProcessingError err = new ProcessingError();

    String[] sentences = {
      "Včera sme v lese videli tri veľké medvede",
      "Moji priatelia čítali zaujímavé knihy o starých hradoch",
      "Ženy niesli ťažké tašky plné zrelých jabĺk",
      "Traja muži niesli tri jablká",
      "Bratislava je krásne mesto"
    };

    for (String s : sentences) {
      String[] inTokens = s.split(" ");
      String out = pipeline.process(s, err);
      if (err.occurred()) { System.out.println("ERR: " + err.getMessage()); continue; }
      List<String> lemmas = new ArrayList<>();
      for (String line : out.split("\n")) {
        if (line.isEmpty() || line.startsWith("#")) continue;
        String[] c = line.split("\t");        // ID FORM LEMMA UPOS ...
        if (c.length < 3) continue;
        if (c[0].contains("-") || c[0].contains(".")) continue;   // skip multiword ranges / empty nodes
        lemmas.add(c[2]);
      }
      boolean aligned = lemmas.size() == inTokens.length;
      System.out.println("in : " + String.join(" ", inTokens) + "   (" + inTokens.length + " tokens)");
      System.out.println("out: " + String.join(" ", lemmas) + "   (" + lemmas.size() + (aligned ? " OK 1:1" : " MISALIGNED") + ")");
      System.out.println();
    }
  }
}
