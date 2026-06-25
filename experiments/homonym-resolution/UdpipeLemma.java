import cz.cuni.mff.ufal.udpipe.*;
import java.io.*; import java.nio.file.*;

/** Tag raw Slovak sentences with UDPipe -> emit "form_lower<TAB>lemma" lines (for corpus lemma frequency). */
public class UdpipeLemma {
  public static void main(String[] a) throws Exception {
    Model model = Model.load(a[0]);
    if (model == null) { System.err.println("model load failed"); System.exit(1); }
    Pipeline pipe = new Pipeline(model, "tokenizer", Model.getDEFAULT(), Pipeline.getNONE(), "conllu");
    ProcessingError err = new ProcessingError();
    BufferedReader r = Files.newBufferedReader(Paths.get(a[1]));
    BufferedWriter w = Files.newBufferedWriter(Paths.get(a[2]));
    StringBuilder batch = new StringBuilder(); String line; int n = 0;
    while ((line = r.readLine()) != null) {
      int tab = line.indexOf('\t'); String s = tab >= 0 ? line.substring(tab + 1) : line;
      if (s.isBlank()) continue;
      batch.append(s).append("\n");
      if (++n % 1000 == 0) { flush(pipe, err, batch.toString(), w); batch.setLength(0); w.flush(); }
    }
    if (batch.length() > 0) flush(pipe, err, batch.toString(), w);
    r.close(); w.close();
  }
  static void flush(Pipeline pipe, ProcessingError err, String text, BufferedWriter w) throws IOException {
    String out = pipe.process(text, err);
    if (err.occurred()) return;
    for (String l : out.split("\n")) {
      if (l.isEmpty() || l.charAt(0) == '#') continue;
      String[] c = l.split("\t");
      if (c.length < 3 || c[0].contains("-") || c[0].contains(".")) continue;
      String form = c[1], lemma = c[2];
      if (form.isEmpty() || lemma.isEmpty() || form.indexOf('_') >= 0) continue;
      w.write(form.toLowerCase()); w.write('\t'); w.write(lemma); w.write('\n');
    }
  }
}
