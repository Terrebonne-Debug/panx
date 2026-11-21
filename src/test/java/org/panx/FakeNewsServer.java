package org.panx;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

final class FakeNewsServer implements AutoCloseable {
    final ServerSocket listener;
    final ExecutorService threads=Executors.newVirtualThreadPerTaskExecutor();
    final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    final AtomicInteger connections=new AtomicInteger(),articleRequests=new AtomicInteger();
    final List<String> commands=new CopyOnWriteArrayList<>();final Map<String,String> articles=new ConcurrentHashMap<>();
    volatile String posted;volatile boolean rejectPost;volatile boolean closed;
    volatile CountDownLatch articleArrivals, articleRelease;
    volatile CountDownLatch authArrivals, authRelease;
    FakeNewsServer()throws IOException {this(new ServerSocket(0,50,InetAddress.getLoopbackAddress()));}
    FakeNewsServer(ServerSocket listener)throws IOException {this.listener=listener;threads.submit(()->{while(!closed)try{Socket socket=listener.accept();sockets.add(socket);connections.incrementAndGet();threads.submit(()->serve(socket));}catch(IOException ignored){}});}
    Server profile(){return new Server(0,"fixture","localhost",listener.getLocalPort(),false,"reader","secret",2);}
    private void serve(Socket socket){
        try(socket;var in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.ISO_8859_1));var out=new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.ISO_8859_1))){
            write(out,"200 fixture ready");boolean authenticated=false;
            for(String line;(line=in.readLine())!=null;){commands.add(line);
                if(line.equals("AUTHINFO USER reader"))write(out,"381 Password required");
                else if(line.equals("AUTHINFO PASS secret")){if(authArrivals!=null)authArrivals.countDown();if(authRelease!=null&&!authRelease.await(10,TimeUnit.SECONDS))throw new IOException("Login gate timed out");authenticated=true;write(out,"281 Welcome");}
                else if(line.equals("MODE READER"))write(out,authenticated?"200 Reader":"480 Authentication required");
                else if(!authenticated)write(out,"480 Authenticate first");
                else if(line.equals("LIST ACTIVE")){write(out,"215 list follows");write(out,"comp.test 6500 1 y");write(out,"alt.empty 0 1 n");write(out,".");}
                else if(line.startsWith("GROUP "))write(out,"211 6500 1 6500 "+line.substring(6));
                else if(line.startsWith("OVER "))write(out,"500 Use XOVER");
                else if(line.startsWith("XOVER ")){String[] range=line.substring(6).split("-");write(out,"224 Overview follows");for(long n=Long.parseLong(range[0]);n<=Long.parseLong(range[1]);n++)write(out,n+"\tSubject "+n+"\tAuthor <a@example.org>\tThu, 19 Nov 2026 12:00:00 +0000\t<"+n+"@fixture>\t\t100\t3");write(out,".");}
                else if(line.startsWith("ARTICLE ")){articleRequests.incrementAndGet();if(articleArrivals!=null)articleArrivals.countDown();if(articleRelease!=null&&!articleRelease.await(10,TimeUnit.SECONDS))throw new IOException("Article gate timed out");String id=line.substring(8);String raw=articles.get(id);if(raw==null)write(out,"430 No such article");else{write(out,"220 1 "+id);for(String s:raw.replace("\r\n","\n").stripTrailing().split("\n",-1))write(out,s.startsWith(".")?"."+s:s);write(out,".");}}
                else if(line.equals("POST")){if(rejectPost){write(out,"440 Posting denied");continue;}write(out,"340 Send article");var data=new StringBuilder();for(String s;!(s=in.readLine()).equals(".");){if(s.startsWith(".."))s=s.substring(1);data.append(s).append("\r\n");}posted=data.toString();write(out,"240 Article received");}
                else write(out,"500 Unknown command");
            }
        }catch(IOException ignored){}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{sockets.remove(socket);}
    }
    private static void write(BufferedWriter out,String s)throws IOException {out.write(s);out.write("\r\n");out.flush();}
    @Override public void close()throws IOException {closed=true;listener.close();for(Socket s:sockets)s.close();threads.shutdownNow();}
}
