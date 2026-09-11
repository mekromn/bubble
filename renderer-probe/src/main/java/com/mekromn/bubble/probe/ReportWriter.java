package com.mekromn.bubble.probe;
import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
final class ReportWriter {
 static String asset(Context c,String name)throws IOException{try(InputStream s=c.getAssets().open(name)){return new String(s.readAllBytes(),StandardCharsets.UTF_8);}}
 static File latest(Context c){File[] dirs=new File(c.getFilesDir(),"benchmarks").listFiles(File::isDirectory);if(dirs==null||dirs.length==0)return null;Arrays.sort(dirs,(a,b)->b.getName().compareTo(a.getName()));return dirs[0];}
 static void write(Context c,File suite,File output)throws Exception{
  try(BufferedWriter w=Files.newBufferedWriter(output.toPath(),StandardCharsets.UTF_8)){
   w.write(asset(c,"report-head.html").replace("__ANALYSIS_JS__",asset(c,"probe-analysis.js")));
   File[] reports=suite.listFiles((d,n)->n.endsWith(".json")&&!n.equals("plan.json")&&!n.endsWith(".pending.json")&&!n.endsWith(".partial.json"));
   if(reports!=null){Arrays.sort(reports,(a,b)->Long.compare(a.lastModified(),b.lastModified()));for(File f:reports){
    if(f.length()>16_000_000L)throw new IOException("Oversized trial "+f.getName());
    JSONObject report=new JSONObject(new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));if(!report.has("spec"))continue;
    String data=TrialPlan.json("file",suite.getName()+"/"+f.getName(),"suite",suite.getName(),"report",report).toString().replace("<","\\u003c").replace("\u2028","\\u2028").replace("\u2029","\\u2029");
    w.write("<script>ProbeReports.push(ProbeAnalysis.analyzeTrial(");w.write(data);w.write("));document.currentScript.remove();</script>\n");
   }}w.write(asset(c,"report-tail.html"));
  }
 }
}
