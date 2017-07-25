package cz.caver.concurrent;

import java.util.concurrent.atomic.AtomicInteger;
import net.tascalate.concurrent.CompletableTask;
import net.tascalate.concurrent.Promise;
import net.tascalate.concurrent.TaskExecutorService;
import net.tascalate.concurrent.TaskExecutors;
import org.junit.After;
import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.Test;

/**
 *
 * @author Adam Jurčík
 */
public class OnSuccessTest {
    
    private static final int UNIT = 100; // ms
    
    private TaskExecutorService executor;

    public OnSuccessTest() {
    }
    
    @Before
    public void setUp() {
        executor = TaskExecutors.newFixedThreadPool(4);
    }
    
    @After
    public void tearDown() {
        executor.shutdown();
    }
    
    @Test
    public void testForwardCancellation() {
        State s1 = new State("StateA");
        State s2 = new State("StateB");
        
        Promise<?> p1 = CompletableTask.asyncOn(executor)
                .thenRunAsync(() -> longTask(5, s1));
        
        // This stage should not be executed, previous stage will be cancelled
        Promise<?> p2 = p1.thenRunAsync(() -> longTask(5, s2));
        p2.whenComplete((r, e) -> {
           if (null != e) {
               System.out.println("P2 Exception!");
               e.printStackTrace(System.out);
           } else {
               System.out.println("P2 Result: " + r);
           }
        });
        
        trySleep(2);
        p1.cancel(true);
        trySleep(1);
        
        assertTrue("s1 is " + s1, s1.isCancelled());
        assertFalse("s2 was started", s2.wasStarted()); // I.e. not started
        System.out.println(s2);
    }
    
    private void longTask(int units, State state) {
        System.out.println("Processing: " + state);
        state.start();
        for (int i = 0; i < units; i++) {
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                state.cancel();
                return;
            }
            try {
                Thread.sleep(UNIT);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        state.finish();
    }
    
    private void trySleep(int units) {
        try {
            Thread.sleep(units * UNIT);
        } catch (InterruptedException ex) {
        }
    }
    
    static class State {
        
        static final int NONE = 0;
        static final int STARTED = 1;
        static final int DONE = 2;
        static final int CANCELLED = 3;
        
        AtomicInteger s = new AtomicInteger(NONE);
        String name;
        
        State(String name) {
            this.name = name;
        }
        
        void start() {
            s.set(STARTED);
        }
        
        void finish() {
            s.set(DONE);
        }
        
        void cancel() {
            s.set(CANCELLED);
        }
        
        boolean wasStarted() {
            return s.get() >= STARTED;
        }
        
        boolean isDone() {
            return s.get() == DONE;
        }
        
        boolean isCancelled() {
            return s.get() == CANCELLED;
        }
        
        @Override
        public String toString() {
            switch (s.get()) {
                case NONE:
                    return name + "@none";
                case STARTED:
                    return name + "@started";
                case DONE:
                    return name + "@done";
                case CANCELLED:
                    return name + "@cancelled";
                default:
                    return String.format("%s@unknown (%d)", name, s.get());
            }
        }
        
    }
    
}
