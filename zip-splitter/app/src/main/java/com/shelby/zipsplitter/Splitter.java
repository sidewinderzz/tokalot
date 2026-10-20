package com.shelby.zipsplitter;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;

/** Copies file bytes and names; ZIP container metadata is regenerated. */
public final class Splitter {
 public interface Progress { void show(String message); }
 static void check(AtomicBoolean cancel) throws IOException {
  if(cancel.get()) throw new IOException("Cancelled.");
 }
 static void copy(InputStream in, OutputStream out, AtomicBoolean cancel) throws IOException {
  byte[] b=new byte[65536]; int n;
  while((n=in.read(b))!=-1) { check(cancel); out.write(b,0,n); }
 }
 static void safe(String name) throws IOException {
  if(name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.indexOf(':')>=0)
   throw new IOException("Unsupported unsafe path: "+name);
  for(String p:name.split("/")) if(p.equals("..")) throw new IOException("Unsafe path: "+name);
 }
 static final class Limited extends FilterOutputStream {
  long count=0, max;
  Limited(OutputStream out,long max) { super(out); this.max=max; }
  public void write(int b) throws IOException { if(++count>=max) throw new IOException("Size limit exceeded"); out.write(b); }
  public void write(byte[] b,int o,int n) throws IOException {
   count+=n; if(count>=max) throw new IOException("Size limit exceeded"); out.write(b,o,n);
  }
 }
 static void entry(ZipFile source, ZipEntry original, ZipOutputStream out, AtomicBoolean cancel) throws IOException {
  ZipEntry e=new ZipEntry(original.getName());
  e.setTime(315532800000L); // fixed timestamp for reproducible sizing
  out.putNextEntry(e);
  CRC32 crc=new CRC32(); long size=0;
  try(InputStream in=source.getInputStream(original)) {
   byte[] b=new byte[65536]; int n;
   while((n=in.read(b))!=-1) { check(cancel); crc.update(b,0,n); size+=n; out.write(b,0,n); }
  }
  if(size!=original.getSize() || crc.getValue()!=original.getCrc())
   throw new IOException("Damaged ZIP entry: "+original.getName());
  out.closeEntry();
 }
 static void write(ZipFile zip,List<ZipEntry> entries,File file,long limit,AtomicBoolean cancel) throws IOException {
  try(ZipOutputStream out=new ZipOutputStream(new Limited(new BufferedOutputStream(new FileOutputStream(file)),limit))) {
   out.setLevel(6);
   for(ZipEntry e:entries) { check(cancel); entry(zip,e,out,cancel); }
  }
 }
 public static List<File> split(File source,File work,long limit,AtomicBoolean cancel,Progress progress) throws IOException {
  List<File> result=new ArrayList<>();
  try(ZipFile zip=new ZipFile(source)) {
   List<ZipEntry> entries=new ArrayList<>(Collections.list(zip.entries()));
   if(entries.size()>60000) throw new IOException("This ZIP has more than 60,000 entries.");
   Set<String> names=new HashSet<>();
   for(ZipEntry e:entries) { safe(e.getName()); if(!names.add(e.getName())) throw new IOException("Duplicate ZIP path: "+e.getName()); }
   List<List<ZipEntry>> groups=new ArrayList<>();
   List<Long> sizes=new ArrayList<>();
   File probe=new File(work,"probe.tmp");
   int index=0;
   for(ZipEntry e:entries) {
    check(cancel); progress.show("Measuring "+(++index)+" / "+entries.size()+"\n"+e.getName());
    try { write(zip,Collections.singletonList(e),probe,limit,cancel); }
    catch(IOException ex) {
     if(!cancel.get() && ex.getMessage()!=null && ex.getMessage().contains("Size limit"))
      throw new IOException("A whole file cannot fit under the limit: "+e.getName()+". It cannot be split into independently extractable ZIPs without dividing that file.");
     throw ex;
    }
    long cost=probe.length()-22; // each archive has one 22-byte end record
    int best=-1; long smallest=Long.MAX_VALUE;
    for(int i=0;i<groups.size();i++)
     if(sizes.get(i)+cost<limit && sizes.get(i)<smallest) { best=i; smallest=sizes.get(i); }
    if(best<0) { best=groups.size(); groups.add(new ArrayList<>()); sizes.add(22L); }
    groups.get(best).add(e); sizes.set(best,sizes.get(best)+cost);
    if(!probe.delete()) throw new IOException("Could not remove temporary measurement file.");
   }
   if(groups.isEmpty()) groups.add(new ArrayList<>());
   for(int i=0;i<groups.size();i++) {
    progress.show("Creating ZIP "+(i+1)+" of "+groups.size());
    File f=new File(work,String.format(Locale.US,"Part-%02d.zip",i+1));
    result.add(f); write(zip,groups.get(i),f,limit,cancel);
    if(f.length()>=limit) throw new IOException("Output exceeded the size limit.");
    // Re-read every output entry and verify its CRC before exposing results.
    try(ZipFile verify=new ZipFile(f)) {
     Enumeration<? extends ZipEntry> all=verify.entries();
     while(all.hasMoreElements()) {
      ZipEntry e=all.nextElement(); CRC32 crc=new CRC32(); long bytes=0;
      try(InputStream in=verify.getInputStream(e)) {
       byte[] b=new byte[65536]; int n;
       while((n=in.read(b))!=-1) { check(cancel); crc.update(b,0,n); bytes+=n; }
      }
      if(crc.getValue()!=e.getCrc() || bytes!=e.getSize()) throw new IOException("Output verification failed.");
     }
    }
   }
   return result;
  } catch(IOException ex) { for(File f:result) f.delete(); throw ex; }
 }
}
