package com.shelby.zipsplitter;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;
public class SplitterTest {
 static void expect(boolean b) { if(!b) throw new AssertionError(); }
 public static void main(String[] args)throws Exception {
  File dir=Files.createTempDirectory("split-test").toFile();
  Map<String,byte[]> files=new LinkedHashMap<>();
  Random r=new Random(42);
  for(int i=0;i<9;i++){byte[] b=new byte[8000];r.nextBytes(b);files.put("folder/file"+i+".bin",b);}
  files.put("empty/",new byte[0]); files.put("unicode-\u00e9.txt",new byte[0]);
  File input=new File(dir,"source.zip");
  try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(input))){
   for(Map.Entry<String,byte[]> e:files.entrySet()){z.putNextEntry(new ZipEntry(e.getKey()));z.write(e.getValue());z.closeEntry();}
  }
  List<File> parts=Splitter.split(input,dir,19000,new AtomicBoolean(),s->{});
  expect(parts.size()>1); Set<String> seen=new HashSet<>();
  for(File p:parts){expect(p.length()<19000);try(ZipFile z=new ZipFile(p)){
   Enumeration<? extends ZipEntry> entries=z.entries();
   while(entries.hasMoreElements()){ZipEntry e=entries.nextElement();expect(seen.add(e.getName()));
    try(InputStream in=z.getInputStream(e)){expect(Arrays.equals(files.get(e.getName()),in.readAllBytes()));}
   }
  }}
  expect(seen.equals(files.keySet()));
  boolean failed=false;
  try{Splitter.split(input,dir,1000,new AtomicBoolean(),s->{});}catch(IOException e){failed=e.getMessage().contains("whole file");}expect(failed);
  failed=false;try{Splitter.split(input,dir,19000,new AtomicBoolean(true),s->{});}catch(IOException e){failed=true;}expect(failed);
  System.out.println("PASS: independent ZIPs, strict size, paths, empty entries, exact bytes, oversized entry and cancellation.");
 }
}
