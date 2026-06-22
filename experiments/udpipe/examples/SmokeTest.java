import cz.cuni.mff.ufal.udpipe.*;

/**
 * Smoke test for the UDPipe JNI binding: load model -> tokenize -> tag -> lemmatize.
 * Usage:  java -Djava.library.path=native -cp lib/udpipe.jar:. SmokeTest models/slovak-snk.udpipe
 * (driven by scripts/smoke-test.sh)
 */
public class SmokeTest {
  public static void main(String[] args) {
    String modelPath = args.length > 0 ? args[0] : "models/slovak-snk.udpipe";

    Model model = Model.load(modelPath);
    if (model == null) {
      System.err.println("MODEL LOAD FAILED: " + modelPath);
      System.exit(1);
    }

    String[] sents = {
      "Ďakujem že si prišiel",
      "Ženu ženu na pole",
      "Traja muži niesli tri jablká",
      "Bratislava je krásne mesto",
      "Boli sme v lese a videli sme stromy"
    };

    // tokenizer -> default tagger (POS + lemma) -> no parser -> CoNLL-U output
    Pipeline pipeline = new Pipeline(model, "tokenizer", Model.getDEFAULT(), Pipeline.getNONE(), "conllu");
    ProcessingError err = new ProcessingError();

    System.out.println("\n===== UDPipe " + modelPath + " (POS-aware) =====");
    for (String s : sents) {
      String out = pipeline.process(s, err);
      if (err.occurred()) { System.out.println("ERR: " + err.getMessage()); continue; }
      System.out.println("# " + s);
      for (String line : out.split("\n")) {
        if (line.isEmpty() || line.startsWith("#")) continue;
        String[] c = line.split("\t");          // ID FORM LEMMA UPOS ...
        if (c.length >= 4) System.out.printf("    %-14s -> %-12s [%s]%n", c[1], c[2], c[3]);
      }
      System.out.println();
    }
  }
}
