package com.shelby.zipsplitter;
import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
 Uri source, tree; TextView status; Button choose,folder,split,cancel;
 final AtomicBoolean stop=new AtomicBoolean(); boolean busy=false;
 void message(String s) { runOnUiThread(()->{ if(!isDestroyed()) status.setText(s); }); }
 Button button(LinearLayout root,String text,Runnable action) {
  Button b=new Button(this); b.setText(text); root.addView(b); b.setOnClickListener(v->action.run()); return b;
 }
 @Override public void onCreate(Bundle saved) {
  super.onCreate(saved);
  if(saved!=null) {
   String s=saved.getString("source"), t=saved.getString("tree");
   if(s!=null) source=Uri.parse(s); if(t!=null) tree=Uri.parse(t);
  }
  ScrollView scroll=new ScrollView(this); LinearLayout root=new LinearLayout(this);
  root.setOrientation(LinearLayout.VERTICAL); int pad=(int)(24*getResources().getDisplayMetrics().density);
  root.setPadding(pad,pad,pad,pad); scroll.addView(root); setContentView(scroll);
  TextView title=new TextView(this); title.setText("ZIP Splitter"); title.setTextSize(30); root.addView(title);
  TextView info=new TextView(this); info.setText("Independent ZIPs, each under 29 MB.\nFiles stay on your phone. Original ZIP is preserved.\n\nChoose a ZIP and an output folder, then split. Keep this app open until it finishes. Large ZIPs need temporary free space.\n"); info.setTextSize(17); root.addView(info);
  choose=button(root,"1. Choose ZIP",()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),1));
  folder=button(root,"2. Choose output folder",()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),2));
  split=button(root,"3. Split under 29 MB",this::start);
  cancel=button(root,"Cancel",()->{stop.set(true); message("Cancelling…");}); cancel.setEnabled(false);
  status=new TextView(this); status.setTextSize(16); status.setText("Ready. Select an empty output folder or create one in the folder picker."); root.addView(status);
 }
 @Override protected void onSaveInstanceState(Bundle b) {
  super.onSaveInstanceState(b); if(source!=null)b.putString("source",source.toString()); if(tree!=null)b.putString("tree",tree.toString());
 }
 @Override protected void onActivityResult(int request,int result,Intent data) {
  super.onActivityResult(request,result,data);
  if(result!=RESULT_OK || data==null || data.getData()==null)return;
  Uri uri=data.getData();
  try { getContentResolver().takePersistableUriPermission(uri,data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION)); } catch(SecurityException ignored) {}
  if(request==1) { source=uri; choose.setText("ZIP selected ✓"); }
  if(request==2) { tree=uri; folder.setText("Output folder selected ✓"); }
 }
 void controls(boolean running) {
  busy=running; choose.setEnabled(!running); folder.setEnabled(!running); split.setEnabled(!running); cancel.setEnabled(running);
  if(running)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
 }
 void start() {
  if(source==null||tree==null) { message("Choose a ZIP and an output folder first."); return; }
  stop.set(false); controls(true);
  new Thread(()->{
   File work=new File(getCacheDir(),"job-"+UUID.randomUUID()); Uri outputDir=null;
   try {
    if(!work.mkdirs())throw new IOException("Could not create temporary folder.");
    File input=new File(work,"input.zip");
    message("Reading the selected ZIP…");
    try(InputStream in=getContentResolver().openInputStream(source); OutputStream out=new BufferedOutputStream(new FileOutputStream(input))) {
     if(in==null)throw new IOException("Cannot read selected ZIP."); Splitter.copy(in,out,stop);
    }
    List<File> parts=Splitter.split(input,work,29000000L,stop,this::message);
    Splitter.check(stop);
    Uri parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
    String folderName="ZIP-Split-"+System.currentTimeMillis();
    outputDir=DocumentsContract.createDocument(getContentResolver(),parent,DocumentsContract.Document.MIME_TYPE_DIR,folderName);
    if(outputDir==null)throw new IOException("Cannot create output folder.");
    StringBuilder report=new StringBuilder("Done! Saved to "+folderName+"\n\n");
    for(File part:parts) {
     Splitter.check(stop); message("Saving "+part.getName()+"…");
     Uri dest=DocumentsContract.createDocument(getContentResolver(),outputDir,"application/zip",part.getName());
     if(dest==null)throw new IOException("Cannot create output ZIP.");
     try(InputStream in=new FileInputStream(part); OutputStream out=getContentResolver().openOutputStream(dest,"w")) {
      if(out==null)throw new IOException("Cannot write output ZIP."); Splitter.copy(in,out,stop);
     }
     // Verify the provider actually retained the exact bytes.
     try(InputStream a=new FileInputStream(part); InputStream b=new BufferedInputStream(getContentResolver().openInputStream(dest))) {
      if(b==null)throw new IOException("Cannot verify saved ZIP.");
      byte[] buf=new byte[65536]; int n;
      while((n=a.read(buf))!=-1) {
       Splitter.check(stop);
       for(int i=0;i<n;i++)if(b.read()!=(buf[i]&255))throw new IOException("Saved ZIP verification failed.");
      }
      if(b.read()!=-1)throw new IOException("Saved ZIP length verification failed.");
     }
     report.append(part.getName()).append(" — ").append(String.format(Locale.US,"%.2f MB",part.length()/1000000.0)).append("\n");
    }
    report.append("\nEach ZIP opens on its own. Upload each one separately.\nOriginal file unchanged.");
    message(report.toString());
   } catch(Exception ex) {
    String cleanup="";
    if(outputDir!=null)try { if(!DocumentsContract.deleteDocument(getContentResolver(),outputDir))cleanup=" Remove the incomplete ZIP-Split folder before retrying."; }
    catch(Exception ignored) { cleanup=" Remove the incomplete ZIP-Split folder before retrying."; }
    message((stop.get()?"Cancelled.":("Could not finish: "+ex.getMessage()))+cleanup+"\nOriginal file unchanged.");
   } finally {
    remove(work); runOnUiThread(()->{if(!isDestroyed())controls(false);});
   }
  },"zip-split").start();
 }
 static void remove(File f) { File[] files=f.listFiles(); if(files!=null)for(File c:files)remove(c); f.delete(); }
 @Override public void onBackPressed() { if(busy) new AlertDialog.Builder(this).setMessage("Cancel splitting?").setPositiveButton("Cancel job",(d,w)->stop.set(true)).setNegativeButton("Keep working",null).show(); else super.onBackPressed(); }
 @Override protected void onDestroy() { stop.set(true); super.onDestroy(); }
}
