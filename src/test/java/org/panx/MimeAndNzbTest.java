package org.panx;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import java.util.zip.CRC32;
import static org.junit.jupiter.api.Assertions.*;

class MimeAndNzbTest {
    @TempDir Path dir;
    @Test void mimeRoundTripsUnicodeAndAttachment()throws Exception {
        Path file=dir.resolve("café.bin");byte[] data={0,1,2,127,(byte)255};Files.write(file,data);
        String raw=Mime.compose("Alice <alice@example.org>","comp.test,comp.other","Héllo 世界","Message with a café","<parent@example.org>",List.of(file));
        var decoded=Mime.decode(raw);assertEquals("Héllo 世界",Mime.message(raw).getSubject());assertTrue(decoded.text().contains("café"));assertEquals(1,decoded.attachments().size());assertArrayEquals(data,decoded.attachments().get(0).bytes());assertEquals("café.bin",decoded.attachments().get(0).name());
        assertThrows(IllegalArgumentException.class,()->Mime.compose("a@example.org","comp.test","Hi\r\nX: bad","body","",List.of()));
        assertThrows(IllegalArgumentException.class,()->Mime.compose("a@example.org","comp.test,","Hi","body","",List.of()));
    }
    @Test void filenamesCannotEscapeOrClobberFiles()throws Exception {
        var a=new Mime.Attachment("../../test.txt",new byte[]{1});Path first=Mime.saveAttachment(dir,a),second=Mime.saveAttachment(dir,a);assertEquals(dir.resolve("test.txt"),first);assertNotEquals(first,second);assertArrayEquals(new byte[]{1},Files.readAllBytes(first));assertEquals("secret.txt",Mime.safeName("C:\\files\\secret.txt"));
    }
    static String yenc(byte[] bytes,String name,long begin,long total,boolean multipart){
        var encoded=new StringBuilder();for(byte b:bytes){int c=((b&255)+42)&255;if(c==0||c==10||c==13||c==61){encoded.append('=');c=(c+64)&255;}encoded.append((char)c);}
        CRC32 crc=new CRC32();crc.update(bytes);return "Subject: binary\r\n\r\n=ybegin "+(multipart?"part=1 ":"")+"line=128 size="+total+" name="+name+"\r\n"+(multipart?"=ypart begin="+begin+" end="+(begin+bytes.length-1)+"\r\n":"")+encoded+"\r\n=yend size="+bytes.length+" "+(multipart?"pcrc32":"crc32")+"="+String.format("%08x",crc.getValue())+"\r\n";
    }
    @Test void yencDecodesEscapesAndRejectsCorruption()throws Exception {
        byte[] bytes=new byte[256];for(int i=0;i<256;i++)bytes[i]=(byte)i;String raw=yenc(bytes,"test.bin",1,256,false);assertArrayEquals(bytes,Mime.decodeYenc(raw).bytes());assertEquals(1,Mime.decode(raw).attachments().size());
        assertThrows(IOException.class,()->Mime.decodeYenc(raw.replaceFirst("crc32=[0-9a-f]+","crc32=00000000")));assertThrows(IOException.class,()->Mime.decodeYenc(raw.replace("=yend","=broken")));
    }
    @Test void nzbSortsSegmentsRejectsGapsAndDoesNotExpandEntities()throws Exception {
        Path file=dir.resolve("in.nzb");Files.writeString(file,"<nzb><file subject=\"test\"><segments><segment number=\"2\">two@a</segment><segment number=\"1\">one@a</segment></segments></file></nzb>");assertEquals(List.of("<one@a>","<two@a>"),Nzb.read(file).get(0).ids());
        Files.writeString(file,"<nzb><file><segments><segment number=\"2\">two@a</segment></segments></file></nzb>");assertThrows(IOException.class,()->Nzb.read(file));
        Path secret=dir.resolve("secret");Files.writeString(secret,"should-not-be-read@private");Files.writeString(file,"<!DOCTYPE nzb [<!ENTITY leak SYSTEM '"+secret.toUri()+"'>]><nzb><file><segments><segment number=\"1\">&leak;</segment></segments></file></nzb>");assertThrows(Exception.class,()->Nzb.read(file));
    }
    @Test void downloadQueueAssemblesCachedMultipartAndRefusesIncompleteData()throws Exception {
        try(var store=new Store(dir.resolve("data"));var news=new NewsService(store);var queue=new DownloadQueue(store,news)){
            Server s=store.saveServer(new Server(0,"offline fixture","demo.invalid",119,false,"","",2),false);
            store.body(s.id(),"<one@a>",yenc(new byte[]{1,2,3},"../../out.bin",1,5,true));store.body(s.id(),"<two@a>",yenc(new byte[]{4,5},"../../out.bin",4,5,true));
            long job=queue.add(s,"test",List.of("<one@a>","<two@a>"),dir.resolve("downloads"));queue.start(job);await(queue);
            assertEquals("Complete",store.jobs().get(0).state());assertArrayEquals(new byte[]{1,2,3,4,5},Files.readAllBytes(dir.resolve("downloads/out.bin")));
            long bad=queue.add(s,"incomplete",List.of("<one@a>"),dir.resolve("downloads"));queue.start(bad);await(queue);assertEquals("Failed",store.jobs().get(0).state());assertEquals(1,Files.list(dir.resolve("downloads")).count());
        }
    }
    private static void await(DownloadQueue queue)throws Exception {long deadline=System.nanoTime()+10_000_000_000L;while(queue.active()&&System.nanoTime()<deadline)Thread.sleep(20);assertFalse(queue.active(),"Queue timed out");}
}
