public class MultipleMeasurementTest {

    public static void main(String[] args) throws InterruptedException {
        var demo = new PrimeGeneratorDemo();

        for (int i = 0; i < 10; i++) {
            System.out.println("---------------------------------------");
            System.out.println("Iteration " + (i + 1));
            demo.runDemo();
            System.out.println("\n");
        }
    }
}
