package org.panx;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.awt.Robot;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "PANX_UI_TEST", matches = "1")
@Timeout(40)
class GroupSearchDesktopTest {
    @TempDir Path data;
    @BeforeAll static void rendering() { DesktopRendering.configure(); }

    @Test @SuppressWarnings({"unchecked", "rawtypes"})
    void largeSearchRendersOnlyVisibleRowsPreservesReaderAndDiscardsSupersededQueries() throws Exception {
        Store store = new Store(data);
        Server server = store.saveServer(new Server(0,"Fixture","fixture.invalid",563,true,"","",40),false);
        var entries = new ArrayList<NntpClient.Group>();
        for (int i = 0; i < 25000; i++) entries.add(new NntpClient.Group(String.format(Locale.ROOT, "comp.test.group.%05d", i), 1, 1, 1, "y", false));
        store.saveGroups(server.id(), entries);
        String first = entries.getFirst().name();
        store.saveArticles(server.id(), first, List.of(new Article(1,"Kept article","Fixture","Today","<kept@fixture>","",100,1,false,false)));
        store.body(server.id(), "<kept@fixture>", "Subject: Kept article\r\n\r\nKeep this message open while searching.\r\n");
        PanxWindow window = edt(() -> { com.formdev.flatlaf.FlatLightLaf.setup(); var w = new PanxWindow(store,true); w.setVisible(true); return w; });
        CountDownLatch release = new CountDownLatch(1);
        try {
            JList<NntpClient.Group> groups = field(window,"groups",JList.class);
            JTable articles = field(window,"articles",JTable.class);
            JTextField search = field(window,"groupSearch",JTextField.class);
            JTextArea body = field(window,"body",JTextArea.class);
            await(() -> groups.getModel().getSize() == 10000 && articles.getRowCount() == 1);
            edt(() -> { articles.setRowSelectionInterval(0,0); return null; });
            await(() -> body.getText().contains("Keep this message open"));
            await(() -> field(window,"pending",Integer.class) == 0);
            var rendererCalls = new AtomicInteger();
            edt(() -> {
                ListCellRenderer original = groups.getCellRenderer();
                groups.setCellRenderer((list,value,index,selected,focus) -> {
                    rendererCalls.incrementAndGet();
                    return original.getListCellRendererComponent(list,value,index,selected,focus);
                });
                return null;
            });
            Robot robot = new Robot(); robot.waitForIdle(); rendererCalls.set(0);
            ListModel<?> before = edt(groups::getModel);
            edt(() -> { search.setText("group.0"); return null; });
            await(() -> groups.getModel() != before && groups.getModel().getSize() == 10000);
            robot.waitForIdle();
            assertTrue(rendererCalls.get() < 500, "Search laid out offscreen rows: " + rendererCalls.get());
            edt(() -> { assertEquals(first,groups.getSelectedValue().name()); assertTrue(body.getText().contains("Keep this message open")); return null; });

            // Hold the database while a search is requested, then type a newer query before it can finish.
            CountDownLatch locked = new CountDownLatch(1);
            Thread blocker = Thread.ofVirtual().start(() -> {
                synchronized (store) {
                    locked.countDown();
                    try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
            });
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            List<String> displayed = new ArrayList<>();
            edt(() -> {
                groups.addPropertyChangeListener("model", event -> {
                    if (groups.getModel().getSize() > 0) displayed.add(groups.getModel().getElementAt(0).name());
                });
                search.setText("group.1");
                var load = PanxWindow.class.getDeclaredMethod("loadGroups"); load.setAccessible(true); load.invoke(window);
                search.setText("group.24999");
                return null;
            });
            release.countDown(); blocker.join(5000);
            await(() -> groups.getModel().getSize() == 1 && groups.getModel().getElementAt(0).name().endsWith("24999"));
            edt(() -> {
                assertTrue(displayed.stream().noneMatch(name -> name.contains("group.1")), "An obsolete search replaced the list: " + displayed);
                assertTrue(body.getText().isEmpty());
                return null;
            });
        } finally { release.countDown(); edt(() -> { window.closeResources(); return null; }); }
    }

    private static <T> T field(Object owner,String name,Class<T> type) throws Exception { Field field=owner.getClass().getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(owner)); }
    private static <T> T edt(Callable<T> action) throws Exception { var task=new FutureTask<T>(action); SwingUtilities.invokeAndWait(task); return task.get(); }
    private static void await(Callable<Boolean> test) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<deadline) { if(edt(test))return; Thread.sleep(25); }
        fail("Timed out waiting for group search");
    }
}
