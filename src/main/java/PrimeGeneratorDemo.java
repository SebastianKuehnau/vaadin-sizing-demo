import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.time.Duration;
import java.util.HashSet;

public class PrimeGeneratorDemo {

    public void runDemo() throws InterruptedException {
        //keep the start fields
        long heapStart = heapUsedAfterGc();
        var cpuStart = cpuTime();
        long timeStart = System.nanoTime();

        // Computation: uses the CPU
        var primeSet = new HashSet<>();
        primeSet.add(2L);

        // Computation: uses the CPU
        for (long number = 3L; number <= 10_000L; number += 2) {
            if (BigInteger.valueOf(number).isProbablePrime(100)) {
                primeSet.add(number);
            }
        }

        // Waiting: uses practically no CPU
        Thread.sleep(2_000);

        //calculate the results
        long elapsedNanos = System.nanoTime() - timeStart;
        Duration cpuUsed = cpuTime().minus(cpuStart);
        // primeSet is still used below, so it is still in the heap for this measurement
        long heapUsed = heapUsedAfterGc() - heapStart;

        //print the results
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
