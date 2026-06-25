import cz.cuni.mff.ufal.udpipe.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Tag raw Slovak sentences with UDPipe -> emit OpenNLP "word_UPOS(.Gender)" lines (silver data). */
public class UdpipeTag {
  static Pattern GEN = Pattern.compile("Gender=(Masc|Fem|Neut)");
  public static void main(String[] a) throws Exception {
    String modelPath=a[0], inFile=a[1], outFile=a[2];
    int maxSents = a.length>3 ? Integer.parseInt(a[3]) : Integer.MAX_VALUE;
    Model model = Model.load(modelPath);
    if (model==null){ System.err.println("model load failed"); System.exit(1); }
    Pipeline pipe = new Pipeline(model, "tokenizer", Model.getDEFAULT(), Pipeline.getNONE(), "conllu");
    ProcessingError err = new ProcessingError();

    BufferedReader r = Files.newBufferedReader(Paths.get(inFile));
    BufferedWriter w = Files.newBufferedWriter(Paths.get(outFile));
    StringBuilder batch = new StringBuilder();
    String line; int read=0, sentsOut=0, tokOut=0, inBatch=0;
    long t0=System.currentTimeMillis();
    while ((line=r.readLine())!=null && read<maxSents) {
      int tab=line.indexOf('\t');
      String s = tab>=0 ? line.substring(tab+1) : line;
      if (s.isBlank()) continue;
      batch.append(s).append("\n"); read++; inBatch++;
      if (inBatch>=1000) { int[] c=flush(pipe,err,batch.toString(),w); sentsOut+=c[0]; tokOut+=c[1]; batch.setLength(0); inBatch=0; w.flush();
        if (read%20000==0) System.out.printf("  %d sents read, %d tok out, %.0fs%n", read, tokOut, (System.currentTimeMillis()-t0)/1000.0); }
    }
    if (batch.length()>0){ int[] c=flush(pipe,err,batch.toString(),w); sentsOut+=c[0]; tokOut+=c[1]; }
    r.close(); w.close();
    System.out.printf("DONE: %d sentences, %d tokens -> %s (%.0fs)%n", sentsOut, tokOut, outFile, (System.currentTimeMillis()-t0)/1000.0);
  }
  static int[] flush(Pipeline pipe, ProcessingError err, String text, BufferedWriter w) throws IOException {
    String out = pipe.process(text, err);
    if (err.occurred()) return new int[]{0,0};
    int sents=0, toks=0; StringBuilder sent=new StringBuilder();
    for (String l : out.split("\n")) {
      if (l.isEmpty()) { if (sent.length()>0){ w.write(sent.toString().strip()); w.write("\n"); sents++; sent.setLength(0);} continue; }
      if (l.startsWith("#")) continue;
      String[] c = l.split("\t");
      if (c.length<6 || c[0].contains("-") || c[0].contains(".")) continue;
      String form=c[1], upos=c[3], feats=c[5];
      if (form.isEmpty() || form.indexOf('_')>=0) continue;
      Matcher m=GEN.matcher(feats); String tag = upos + (m.find()? "."+m.group(1) : "");
      sent.append(form).append('_').append(tag).append(' '); toks++;
    }
    if (sent.length()>0){ w.write(sent.toString().strip()); w.write("\n"); sents++; }
    return new int[]{sents,toks};
  }
}
