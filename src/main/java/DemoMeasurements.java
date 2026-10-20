import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.time.Duration;
import java.util.HashSet;

public class DemoMeasurements {

    public static void main(String[] args) throws InterruptedException {
        var demo = new DemoMeasurements();
        demo.runDemo();
//        for (int i = 0; i < 10; i++) {
//            System.out.println("---------------------------------------");
//            System.out.println("Iteration " + (i + 1));
//            demo.runDemo();
//            System.out.println("\n");
//        }
    }

    private void runDemo() throws InterruptedException {
        long heapStart = heapUsedAfterGc();
        var cpuStart = cpuTime();
        long timeStart = System.nanoTime();

        var primeSet = new HashSet<>();
        primeSet.add(2L);

        // Computation: uses the CPU
        for (long number = 3L; number <= 100_000L; number += 2) {
            if (BigInteger.valueOf(number).isProbablePrime(100)) {
                primeSet.add(number);
            }
        }

        // Waiting: uses practically no CPU
        Thread.sleep(2_000);

        long elapsedNanos = System.nanoTime() - timeStart;
        Duration cpuUsed = cpuTime().minus(cpuStart);
        // primeSet is still used below, so it is still in the heap for this measurement
        long heapUsed = heapUsedAfterGc() - heapStart;

        System.out.println("Primes found:  " + primeSet.size());
        System.out.printf("Elapsed time:  %.3f seconds%n",
                elapsedNanos / 1_000_000_000.0);
        System.out.printf("CPU time:      %.3f seconds%n",
                cpuUsed.toNanos() / 1_000_000_000.0);
        System.out.printf("Heap:          %.1f kB (growth from the data)%n",
                heapUsed / 1024.0);
        // Classes and JIT code: a fixed base amount that does not grow with the data
        System.out.printf("Non-heap:      %.1f MB (total, fixed)%n",
                ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed()
                        / (1024.0 * 1024));
    }

    private long heapUsedAfterGc() {
        // Without a GC, the value would also include objects that are no longer needed
        System.gc();
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private Duration cpuTime() {
        // Total CPU time of the whole Java process
        return ProcessHandle.current()
                .info()
                .totalCpuDuration()
                .orElseThrow(() ->
                        new UnsupportedOperationException(
                                "CPU time is not available on this system"));
    }
}
