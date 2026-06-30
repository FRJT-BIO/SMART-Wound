import ij.IJ;
import ij.ImagePlus;
import ij.io.FileSaver;
import ij.process.ByteProcessor;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Headless test suite for SMART-Wound.
 * Build:  javac --release 8 -cp ij.jar -d .classes src/main/java/SMART_Wound.java
 *         javac -cp ij.jar:.classes -d .test src/test/java/TestSMARTWound.java
 * Run:    java -Djava.awt.headless=true -cp ij.jar:.classes:.test TestSMARTWound
 */
public class TestSMARTWound {

    static int pass = 0, fail = 0;
    static void check(String n, boolean c) {
        if (c) { pass++; System.out.println("[PASS] " + n); }
        else   { fail++; System.out.println("[FAIL] " + n); }
    }

    // Textured field (high local variance) with optional smooth vertical bands (wounds).
    static ByteProcessor synth(int w, int h, int[][] bands, long seed) {
        ByteProcessor bp = new ByteProcessor(w, h);
        Random rng = new Random(seed);
        byte[] px = (byte[]) bp.getPixels();
        for (int i = 0; i < px.length; i++) px[i] = (byte) rng.nextInt(256);   // textured cells
        for (int[] band : bands) {                                              // smooth wound bands
            int x0 = band[0], bw = band[1];
            for (int y = 0; y < h; y++)
                for (int x = x0; x < x0 + bw && x < w; x++)
                    px[y * w + x] = (byte) 128;
        }
        return bp;
    }

    static double frac(SMART_Wound.Result r, int w, int h) { return (double) r.woundPx / ((long) w * h); }

    public static void main(String[] a) throws Exception {
        int W = 400, H = 400;
        File tmp = new File(System.getProperty("java.io.tmpdir"), "smartwound_test");
        deleteDir(tmp); tmp.mkdirs();

        SMART_Wound plugin = new SMART_Wound();

        // ---- Test 1: central smooth band is detected as wound ----
        SMART_Wound.Params p = new SMART_Wound.Params();
        ImagePlus band = new ImagePlus("band", synth(W, H, new int[][]{{140, 120}}, 1));  // 30% central band
        SMART_Wound.Result r1 = SMART_Wound.processImage(band, p);
        double f1 = frac(r1, W, H);
        System.out.println("   central-band wound fraction = " + f1);
        check("central smooth band detected as wound (0.12..0.45)", f1 > 0.12 && f1 < 0.45);

        // ---- Test 2: fully textured image -> almost no wound ----
        ImagePlus full = new ImagePlus("full", synth(W, H, new int[][]{}, 2));
        SMART_Wound.Result r2 = SMART_Wound.processImage(full, p);
        double f2 = frac(r2, W, H);
        System.out.println("   no-band wound fraction = " + f2);
        check("fully textured -> wound < 0.05", f2 < 0.05);

        // ---- Test 3: overlays are RGB and differ from the original in the wound ----
        check("wound overlay is RGB", r1.woundHi.getType() == ImagePlus.COLOR_RGB);
        check("cells overlay is RGB", r1.cellsHi.getType() == ImagePlus.COLOR_RGB);
        int cx = W / 2, cy = H / 2;
        int origGray = 128;                      // band pixel value
        int ov = r1.woundHi.getProcessor().getPixel(cx, cy);
        check("wound overlay tints the wound centre", ((ov >> 16) & 0xff) != origGray || ((ov >> 8) & 0xff) != origGray);

        // ---- Test 4: um/px -> numeric area; 0 -> NA ----
        check("um/px=0 gives NA area", r1.areaUm2.equals("NA"));
        SMART_Wound.Params pcal = new SMART_Wound.Params();
        pcal.umPerPx = 0.908;
        SMART_Wound.Result r4 = SMART_Wound.processImage(new ImagePlus("b", synth(W, H, new int[][]{{140, 120}}, 1)), pcal);
        check("um/px>0 gives numeric area", !r4.areaUm2.equals("NA") && Double.parseDouble(r4.areaUm2) > 0);

        // ---- Test 5: largest-only vs all-regions ----
        int[][] twoBands = {{50, 70}, {250, 110}};   // a smaller and a larger band (both above 2*gapRadius)
        SMART_Wound.Params pAll = new SMART_Wound.Params(); pAll.largestOnly = false; pAll.minAreaPct = 0.2;
        SMART_Wound.Params pLarge = new SMART_Wound.Params(); pLarge.largestOnly = true;
        SMART_Wound.Result rAll   = SMART_Wound.processImage(new ImagePlus("t", synth(W, H, twoBands, 3)), pAll);
        SMART_Wound.Result rLarge = SMART_Wound.processImage(new ImagePlus("t", synth(W, H, twoBands, 3)), pLarge);
        System.out.println("   all=" + frac(rAll, W, H) + "  largest=" + frac(rLarge, W, H));
        check("all-regions wound > largest-only wound", rAll.woundPx > rLarge.woundPx);

        // ---- Test 6: batch + reports written ----
        File in = new File(tmp, "in"), out = new File(tmp, "out");
        in.mkdirs();
        new FileSaver(new ImagePlus("img_a", synth(W, H, new int[][]{{140, 120}}, 4))).saveAsTiff(new File(in, "img_a.tif").getAbsolutePath());
        new FileSaver(new ImagePlus("img_b", synth(W, H, new int[][]{{150, 100}}, 5))).saveAsTiff(new File(in, "img_b.tif").getAbsolutePath());
        new FileSaver(new ImagePlus("img_c", synth(W, H, new int[][]{{130, 140}}, 6))).saveAsTiff(new File(in, "img_c.tif").getAbsolutePath());

        SMART_Wound.Params pb = new SMART_Wound.Params(); pb.threads = 1;
        List<SMART_Wound.Result> res1 = plugin.processFolder(in, out, pb);
        check("batch processed 3 images", res1.size() == 3);
        check("CSV report written",  new File(out, "SMART-Wound_results.csv").exists());
        check("TXT report written",  new File(out, "SMART-Wound_results.txt").exists());
        check("XLSX report written", new File(out, "SMART-Wound_results.xlsx").exists());
        check("wound images saved",  new File(out, "Wound_Area_Highlighted/img_a_Analyzed.tif").exists());
        check("cells images saved",  new File(out, "Cells_Highlighted/img_a_Analyzed.tif").exists());

        // ---- Test 7: XLSX is a valid zip containing the worksheet ----
        check("XLSX is a valid OOXML zip", xlsxHasSheet(new File(out, "SMART-Wound_results.xlsx")));

        // ---- Test 8: determinism (1 thread == 4 threads) ----
        File out4 = new File(tmp, "out4"); out4.mkdirs();
        SMART_Wound.Params pb4 = new SMART_Wound.Params(); pb4.threads = 4;
        List<SMART_Wound.Result> res4 = plugin.processFolder(in, out4, pb4);
        Map<String, Long> m1 = new HashMap<String, Long>(), m4 = new HashMap<String, Long>();
        for (SMART_Wound.Result r : res1) m1.put(r.name, r.woundPx);
        for (SMART_Wound.Result r : res4) m4.put(r.name, r.woundPx);
        check("1-thread and 4-thread wound counts identical", m1.equals(m4));

        // ---- Test 9: CSV header and content sanity ----
        String csv = new String(java.nio.file.Files.readAllBytes(new File(out, "SMART-Wound_results.csv").toPath()));
        check("CSV has expected header", csv.startsWith("Image,Free_area_px,Free_area_um2,Wound_pct,Magnification"));
        check("CSV lists img_a", csv.contains("img_a.tif"));

        System.out.println("\nRESULT passed=" + pass + " failed=" + fail);
        if (fail > 0) System.exit(1);
        System.out.println("ALL SMART-WOUND TESTS PASSED");
    }

    static boolean xlsxHasSheet(File f) {
        ZipInputStream z = null;
        try {
            z = new ZipInputStream(new FileInputStream(f));
            ZipEntry e; boolean sheet = false, ctypes = false;
            while ((e = z.getNextEntry()) != null) {
                if (e.getName().equals("xl/worksheets/sheet1.xml")) sheet = true;
                if (e.getName().equals("[Content_Types].xml")) ctypes = true;
            }
            return sheet && ctypes;
        } catch (Exception ex) {
            return false;
        } finally {
            if (z != null) try { z.close(); } catch (Exception ignore) {}
        }
    }

    static void deleteDir(File d) {
        if (d.exists()) {
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) { if (f.isDirectory()) deleteDir(f); else f.delete(); }
            d.delete();
        }
    }
}
