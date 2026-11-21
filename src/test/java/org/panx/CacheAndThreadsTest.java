package org.panx;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CacheAndThreadsTest {
    @TempDir Path dir;
    static Article a(long n,String id,String refs){return new Article(n,"Subject "+n,"A <a@example.org>","today",id,refs,100,4,false,false);}
    @Test void cachePersistsFlagsSubscriptionsBodiesAndServerIsolation()throws Exception {
        long first,second;
        try(var store=new Store(dir)){
            first=store.saveServer(new Server(0,"one","one.invalid",563,true,"reader","secret",2),false).id();second=store.saveServer(new Server(0,"two","two.invalid",563,true,"","",2),false).id();
            store.subscribe(first,"comp.test",true);store.saveGroups(first,List.of(new NntpClient.Group("comp.test",2,1,2,"y",false)));assertTrue(store.groups(first,"test",true).get(0).subscribed());
            var article=a(1,"<one@a>","");store.saveArticles(first,"comp.test",List.of(article));store.saveArticles(second,"comp.test",List.of(article));store.mark(first,"comp.test",article.messageId(),true,true);store.saveArticles(first,"comp.test",List.of(article));
            assertTrue(store.articles(first,"comp.test","",false,false,10).get(0).read());assertTrue(store.articles(first,"comp.test","",false,false,10).get(0).flagged());assertFalse(store.articles(second,"comp.test","",false,false,10).get(0).read());
            store.body(first,"<one@a>","\u00ff\u0000body\r\n");assertEquals("\u00ff\u0000body\r\n",store.body(first,"<one@a>"));assertNull(store.body(second,"<one@a>"));
            store.saveSetting("identity","A <a@example.org>");
        }
        try(var store=new Store(dir)){assertEquals(2,store.servers().size());assertNotEquals("secret",store.servers().get(0).password());assertEquals("A <a@example.org>",store.setting("identity",""));assertTrue(store.articles(first,"comp.test","",true,false,10).isEmpty());}
    }
    @Test void threadsMissingParentsCyclesAndKillRules() {
        List<Article> input=List.of(a(4,"<c>","<missing> <a> <b>"),a(3,"<b>","<a>"),a(2,"<a>",""),a(1,"<x>","<y>"),a(5,"<y>","<x>"));
        var result=Threads.build(input,List.of(),true,true);assertEquals(5,result.size());assertEquals("<a>",result.get(0).article().messageId());assertEquals(0,result.get(0).depth());assertEquals(1,result.get(1).depth());assertEquals(2,result.get(2).depth());
        var killed=Threads.build(input,List.of(new Store.Rule(1,"subject","Subject 3",-999)),true,true);assertEquals(4,killed.size());assertFalse(killed.stream().anyMatch(r->r.article().number()==3));
    }
    @Test void largeThreadDoesNotOverflowStack() {
        List<Article> list=new ArrayList<>();for(int i=0;i<50000;i++)list.add(a(i,"<"+i+">",i==0?"":"<"+(i-1)+">"));
        long start=System.nanoTime();var rows=Threads.build(list,List.of(),true,true);assertEquals(50000,rows.size());assertTrue(rows.stream().allMatch(r->r.depth()<=30));System.out.printf("Threaded 50,000 articles in %.1f ms%n",(System.nanoTime()-start)/1e6);
    }
    @Test void restartingPausesInterruptedJobs()throws Exception {
        long id;
        try(var store=new Store(dir)){long sid=store.saveServer(new Server(0,"one","one.invalid",119,false,"","",1),false).id();id=store.addJob(sid,"file","<one@a>",dir);store.jobState(id,"Running","1/2");}
        try(var store=new Store(dir)){assertEquals("Paused",store.jobs().get(0).state());}
    }
    @Test void sameDataDirectoryCannotHaveTwoOwners()throws Exception {
        try(var store=new Store(dir)) {
            assertThrows(java.io.IOException.class,()->new Store(dir));
        }
        try(var reopened=new Store(dir)) { assertTrue(reopened.servers().isEmpty()); }
    }
}
