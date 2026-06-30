/*
 * SMART-Wound - texture-based wound / scratch segmentation for in-vitro
 * wound-healing (scratch) assays, as an ImageJ/Fiji plug-in.
 *
 * Algorithm (identical to the validated SMART-Wound.ijm macro):
 *   1. Local texture map      : Variance(radius) -> sqrt -> Gaussian(sigma)
 *   2. Divisive normalisation : texture / Gaussian(texture, normSigma)  (illumination-robust)
 *   3. Threshold (Otsu, dark) : cells = high normalised texture
 *   4. Morphology             : closing (Gap-fill) -> fill holes -> opening (Speck)
 *   5. Invert -> wound mask   : fill holes
 *   6. Component selection    : largest connected region, or all regions > Min area
 *   7. Measure                : cell-free (wound) pixels
 *   8. Highlighted overlays   : wound coloured + cells coloured (drawn on the ORIGINAL)
 *
 * The Java plug-in adds over the macro: a visual JColorChooser palette, a native
 * .xlsx report (zipped, JDK-only), and multi-threaded batch processing.
 *
 * Copyright (C) 2024-2026 Francisco Rafael Jimenez-Trinidad and contributors.
 * License: GNU General Public License v3.0 or later.
 */

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.GenericDialog;
import ij.io.FileSaver;
import ij.plugin.PlugIn;
import ij.plugin.filter.GaussianBlur;
import ij.plugin.filter.RankFilters;
import ij.process.AutoThresholder;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import javax.swing.JColorChooser;
import java.awt.Color;
import java.awt.TextField;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class SMART_Wound implements PlugIn {

    public static final String VERSION = "1.0.0";

    // ============================ Parameters ================================
    public static class Params {
        // segmentation
        public int     texRadius   = 5;
        public double  texSigma    = 6;
        public double  normSigma   = 80;
        public String  threshold   = "Otsu";   // Otsu/Default/Huang/Li/Triangle
        public int     gapRadius   = 20;
        public int     speckRadius = 8;
        public double  minAreaPct  = 1.0;
        public boolean largestOnly = true;
        // highlight
        public Color   woundColor  = Color.YELLOW;
        public Color   cellsColor  = Color.CYAN;
        public int     opacity     = 50;        // 0..100
        public boolean saveImages  = true;
        public String  format      = "TIFF";    // TIFF/PNG/JPEG
        // calibration
        public String  magnification = "";
        public double  umPerPx     = 0;         // 0 -> NA
        // batch / reports
        public boolean recursive   = false;
        public boolean writeCSV    = true;
        public boolean writeTXT    = true;
        public boolean writeXLSX   = true;
        public int     threads     = Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    // ============================== Result ==================================
    public static class Result {
        public String     name;
        public long       woundPx;
        public double     woundPct;
        public String     areaUm2 = "NA";
        public String     mag     = "NA";
        public ImagePlus  woundHi;            // highlighted: wound coloured
        public ImagePlus  cellsHi;            // highlighted: cells coloured
    }

    // ============================ PlugIn entry ==============================
    public void run(String arg) {
        Params p = new Params();
        GenericDialog gd = new GenericDialog("SMART-Wound v" + VERSION);
        gd.addMessage("Leave 'Input' empty to process the active image, or pick a folder for batch analysis.");
        gd.addDirectoryField("Input directory", "");
        gd.addDirectoryField("Output directory", "");

        gd.addMessage("--- Segmentation ---");
        gd.addNumericField("Texture radius", p.texRadius, 0);
        gd.addNumericField("Smoothing sigma", p.texSigma, 0);
        gd.addNumericField("Normalis. sigma", p.normSigma, 0);
        gd.addChoice("Threshold", new String[]{"Otsu", "Default", "Huang", "Li", "Triangle"}, p.threshold);
        gd.addNumericField("Gap-fill radius", p.gapRadius, 0);
        gd.addNumericField("Speck radius", p.speckRadius, 0);
        gd.addNumericField("Min wound area %", p.minAreaPct, 1);
        gd.addCheckbox("Central region only", p.largestOnly);

        gd.addMessage("--- Highlight colours (click to pick) ---");
        gd.addStringField("Wound colour (hex)", toHex(p.woundColor));
        final TextField woundField = (TextField) gd.getStringFields().lastElement();
        gd.addButton("Pick wound colour...", e -> {
            Color c = JColorChooser.showDialog(null, "Wound colour", hexToColor(woundField.getText()));
            if (c != null) woundField.setText(toHex(c));
        });
        gd.addStringField("Cells colour (hex)", toHex(p.cellsColor));
        final TextField cellsField = (TextField) gd.getStringFields().lastElement();
        gd.addButton("Pick cells colour...", e -> {
            Color c = JColorChooser.showDialog(null, "Cells colour", hexToColor(cellsField.getText()));
            if (c != null) cellsField.setText(toHex(c));
        });
        gd.addNumericField("Fill opacity %", p.opacity, 0);
        gd.addCheckbox("Save images", p.saveImages);
        gd.addChoice("Image format", new String[]{"TIFF", "PNG", "JPEG"}, p.format);

        gd.addMessage("--- Calibration (optional) ---");
        gd.addStringField("Magnification", p.magnification);
        gd.addNumericField("um/px (0=none)", p.umPerPx, 4);

        gd.addMessage("--- Batch / reports ---");
        gd.addCheckbox("Recursive", p.recursive);
        gd.addCheckbox("Write CSV", p.writeCSV);
        gd.addCheckbox("Write TXT", p.writeTXT);
        gd.addCheckbox("Write XLSX (Excel)", p.writeXLSX);
        gd.addNumericField("Threads", p.threads, 0);

        gd.showDialog();
        if (gd.wasCanceled()) return;

        String inDir  = trim(gd.getNextString());
        String outDir = trim(gd.getNextString());
        p.texRadius   = (int) gd.getNextNumber();
        p.texSigma    = gd.getNextNumber();
        p.normSigma   = gd.getNextNumber();
        p.threshold   = gd.getNextChoice();
        p.gapRadius   = (int) gd.getNextNumber();
        p.speckRadius = (int) gd.getNextNumber();
        p.minAreaPct  = gd.getNextNumber();
        p.largestOnly = gd.getNextBoolean();
        p.woundColor  = hexToColor(gd.getNextString());
        p.cellsColor  = hexToColor(gd.getNextString());
        p.opacity     = clamp((int) gd.getNextNumber(), 0, 100);
        p.saveImages  = gd.getNextBoolean();
        p.format      = gd.getNextChoice();
        p.magnification = gd.getNextString();
        p.umPerPx     = gd.getNextNumber();
        p.recursive   = gd.getNextBoolean();
        p.writeCSV    = gd.getNextBoolean();
        p.writeTXT    = gd.getNextBoolean();
        p.writeXLSX   = gd.getNextBoolean();
        p.threads     = Math.max(1, (int) gd.getNextNumber());

        try {
            if (inDir.isEmpty()) {
                // ---- single active image ----
                ImagePlus imp = IJ.getImage();   // throws/var if none; guard below
                if (imp == null) { IJ.error("SMART-Wound", "No image open and no input folder set."); return; }
                Result r = processImage(imp, p);
                File out = outDir.isEmpty() ? null : new File(outDir);
                if (out != null) {
                    out.mkdirs();
                    saveHighlights(r, out, p);
                    writeReports(out, Collections.singletonList(r), p);
                }
                r.woundHi.show();
                r.cellsHi.show();
                String msg = "Active image analyzed.\n \nFree (cell-free) area: " + r.woundPx + " px";
                if (!r.areaUm2.equals("NA")) msg += "  (" + r.areaUm2 + " um^2)";
                msg += "\nWound: " + fmt(r.woundPct, 3) + " %";
                if (out != null) msg += "\n \nResults saved in:\n" + out.getAbsolutePath();
                IJ.showMessage("SMART-Wound", msg);
            } else {
                // ---- batch over a folder ----
                File in = new File(inDir);
                if (!in.isDirectory()) { IJ.error("SMART-Wound", "Input folder does not exist:\n" + inDir); return; }
                File out;
                if (outDir.isEmpty()) {
                    File parent = in.getParentFile();
                    out = new File(parent != null ? parent : in, "SMART-Wound_output");
                } else {
                    out = new File(outDir);
                }
                out.mkdirs();
                List<Result> results = processFolder(in, out, p);
                IJ.showMessage("SMART-Wound",
                        "All " + results.size() + " image(s) analyzed.\n \n" +
                        "Highlighted images and result tables are in:\n" + out.getAbsolutePath() + "\n \n" +
                        "  - Wound_Area_Highlighted/\n" +
                        "  - Cells_Highlighted/\n" +
                        "  - SMART-Wound_results .csv / .txt / .xlsx");
            }
        } catch (Exception ex) {
            IJ.handleException(ex);
        }
    }

    // ====================== Core processing (thread-safe) ===================
    // Operates purely on ImageProcessors; never touches the WindowManager, so it
    // is safe to run many images in parallel.
    public static Result processImage(ImagePlus imp, Params p) {
        ImageProcessor src = imp.getProcessor();
        int w = src.getWidth(), h = src.getHeight();
        long imgArea = (long) w * h;

        // 1. texture map: local standard deviation
        FloatProcessor tex = src.convertToFloatProcessor();
        new RankFilters().rank(tex, p.texRadius, RankFilters.VARIANCE);
        tex.sqrt();
        new GaussianBlur().blurGaussian(tex, p.texSigma, p.texSigma, 0.002);

        // 2. divisive local normalisation (illumination-robust)
        FloatProcessor big = (FloatProcessor) tex.duplicate();
        new GaussianBlur().blurGaussian(big, p.normSigma, p.normSigma, 0.002);
        float[] tpx = (float[]) tex.getPixels();
        float[] bpx = (float[]) big.getPixels();
        FloatProcessor norm = new FloatProcessor(w, h);
        float[] npx = (float[]) norm.getPixels();
        for (int i = 0; i < tpx.length; i++) npx[i] = tpx[i] / (bpx[i] + 0.001f);

        // 3. threshold in 8-bit (cells = high normalised texture)
        norm.resetMinAndMax();
        ByteProcessor g8 = (ByteProcessor) norm.convertToByte(true);
        int t = new AutoThresholder().getThreshold(thrMethod(p.threshold), g8.getHistogram());
        byte[] g = (byte[]) g8.getPixels();
        ByteProcessor mask = new ByteProcessor(w, h);
        byte[] mk = (byte[]) mask.getPixels();
        for (int i = 0; i < g.length; i++) mk[i] = ((g[i] & 0xff) > t) ? (byte) 255 : 0;

        // 4. scale-aware morphology on the CELL mask
        RankFilters rf = new RankFilters();
        rf.rank(mask, p.gapRadius, RankFilters.MAX);   // closing: dilate
        rf.rank(mask, p.gapRadius, RankFilters.MIN);   //          erode
        fillHoles(mask);
        rf.rank(mask, p.speckRadius, RankFilters.MIN); // opening: erode
        rf.rank(mask, p.speckRadius, RankFilters.MAX); //          dilate

        // 5. invert -> WOUND mask -> fill holes
        invert(mask);
        fillHoles(mask);

        // 6. component selection
        if (p.largestOnly) keepLargest(mask);
        else keepAboveArea(mask, (long) ((p.minAreaPct / 100.0) * imgArea));

        // 7. measure
        long woundPx = 0;
        byte[] wm = (byte[]) mask.getPixels();
        for (int i = 0; i < wm.length; i++) if ((wm[i] & 0xff) == 255) woundPx++;

        Result r = new Result();
        r.name     = imp.getTitle();
        r.woundPx  = woundPx;
        r.woundPct = 100.0 * woundPx / imgArea;
        r.areaUm2  = (p.umPerPx > 0) ? fmt(woundPx * p.umPerPx * p.umPerPx, 2) : "NA";
        r.mag      = (p.magnification == null || p.magnification.isEmpty()) ? "NA" : p.magnification;

        // 8. highlighted overlays on the ORIGINAL image
        ColorProcessor base = (ColorProcessor) src.duplicate().convertToRGB();
        String b = stripExt(imp.getTitle());
        r.woundHi = new ImagePlus(b + "_wound", overlay(base, mask, p.woundColor, p.opacity, false));
        r.cellsHi = new ImagePlus(b + "_cells", overlay(base, mask, p.cellsColor, p.opacity, true));
        return r;
    }

    // Process a whole folder, multi-threaded. Output is independent of thread count.
    public List<Result> processFolder(File inDir, File outDir, Params p) throws InterruptedException {
        List<File> files = new ArrayList<File>();
        collectImages(inDir, p.recursive, files);

        final File woundDir, cellsDir;
        if (p.saveImages) {
            woundDir = new File(outDir, "Wound_Area_Highlighted"); woundDir.mkdirs();
            cellsDir = new File(outDir, "Cells_Highlighted");      cellsDir.mkdirs();
        } else { woundDir = null; cellsDir = null; }

        final List<Result> results = Collections.synchronizedList(new ArrayList<Result>());
        final Params fp = p;
        ExecutorService ex = Executors.newFixedThreadPool(Math.max(1, p.threads));
        for (final File f : files) {
            ex.submit(new Runnable() {
                public void run() {
                    try {
                        ImagePlus imp = IJ.openImage(f.getAbsolutePath());
                        if (imp == null) { IJ.log("SMART-Wound: could not open " + f.getName()); return; }
                        Result r = processImage(imp, fp);
                        r.name = f.getName();
                        String base = stripExt(f.getName());
                        if (woundDir != null) save(r.woundHi, new File(woundDir, base + "_Analyzed" + ext(fp.format)), fp.format);
                        if (cellsDir != null) save(r.cellsHi, new File(cellsDir, base + "_Analyzed" + ext(fp.format)), fp.format);
                        r.woundHi = null; r.cellsHi = null;   // free memory in batch
                        results.add(r);
                    } catch (Throwable th) {
                        IJ.log("SMART-Wound error on " + f.getName() + ": " + th);
                    }
                }
            });
        }
        ex.shutdown();
        ex.awaitTermination(7, TimeUnit.DAYS);

        Collections.sort(results, (a, b) -> a.name.compareToIgnoreCase(b.name));
        writeReports(outDir, results, p);
        return results;
    }

    // ============================ Morphology ================================
    static void invert(ByteProcessor bp) {
        byte[] px = (byte[]) bp.getPixels();
        for (int i = 0; i < px.length; i++) px[i] = (byte) (255 - (px[i] & 0xff));
    }

    // Fill enclosed background (0) regions not connected to the image border.
    static void fillHoles(ByteProcessor bp) {
        int w = bp.getWidth(), h = bp.getHeight();
        byte[] px = (byte[]) bp.getPixels();
        boolean[] reach = new boolean[w * h];
        ArrayDeque<Integer> st = new ArrayDeque<Integer>();
        // seed all four borders with background pixels
        for (int x = 0; x < w; x++) { seed(px, reach, st, x); seed(px, reach, st, (h - 1) * w + x); }
        for (int y = 0; y < h; y++) { seed(px, reach, st, y * w); seed(px, reach, st, y * w + (w - 1)); }
        // flood-fill background (4-connectivity)
        while (!st.isEmpty()) {
            int idx = st.pop();
            int x = idx % w, y = idx / w;
            if (x > 0)     seed(px, reach, st, idx - 1);
            if (x < w - 1) seed(px, reach, st, idx + 1);
            if (y > 0)     seed(px, reach, st, idx - w);
            if (y < h - 1) seed(px, reach, st, idx + w);
        }
        // any background not reached from the border is an enclosed hole -> fill
        for (int i = 0; i < px.length; i++) if ((px[i] & 0xff) != 255 && !reach[i]) px[i] = (byte) 255;
    }
    private static void seed(byte[] px, boolean[] reach, ArrayDeque<Integer> st, int idx) {
        if ((px[idx] & 0xff) != 255 && !reach[idx]) { reach[idx] = true; st.push(idx); }
    }

    // Keep only the largest 8-connected foreground (255) component.
    static void keepLargest(ByteProcessor bp) {
        int[] labels = new int[bp.getWidth() * bp.getHeight()];
        long[] sizes = labelComponents(bp, labels);
        int best = 0; long bestSize = -1;
        for (int L = 1; L < sizes.length; L++) if (sizes[L] > bestSize) { bestSize = sizes[L]; best = L; }
        byte[] px = (byte[]) bp.getPixels();
        for (int i = 0; i < px.length; i++) px[i] = (labels[i] == best && best != 0) ? (byte) 255 : 0;
    }

    // Keep all foreground components whose area exceeds minAreaPx.
    static void keepAboveArea(ByteProcessor bp, long minAreaPx) {
        int[] labels = new int[bp.getWidth() * bp.getHeight()];
        long[] sizes = labelComponents(bp, labels);
        byte[] px = (byte[]) bp.getPixels();
        for (int i = 0; i < px.length; i++) {
            int L = labels[i];
            px[i] = (L != 0 && sizes[L] > minAreaPx) ? (byte) 255 : 0;
        }
    }

    // 8-connected component labelling of foreground (255). Returns sizes[label].
    static long[] labelComponents(ByteProcessor bp, int[] labels) {
        int w = bp.getWidth(), h = bp.getHeight();
        byte[] px = (byte[]) bp.getPixels();
        List<Long> sizes = new ArrayList<Long>();
        sizes.add(0L);            // label 0 = background
        int next = 1;
        ArrayDeque<Integer> st = new ArrayDeque<Integer>();
        for (int s = 0; s < px.length; s++) {
            if ((px[s] & 0xff) != 255 || labels[s] != 0) continue;
            int L = next++;
            long count = 0;
            labels[s] = L; st.push(s);
            while (!st.isEmpty()) {
                int idx = st.pop(); count++;
                int x = idx % w, y = idx / w;
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) continue;
                    int nx = x + dx, ny = y + dy;
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    int nIdx = ny * w + nx;
                    if ((px[nIdx] & 0xff) == 255 && labels[nIdx] == 0) { labels[nIdx] = L; st.push(nIdx); }
                }
            }
            sizes.add(count);
        }
        long[] out = new long[sizes.size()];
        for (int i = 0; i < out.length; i++) out[i] = sizes.get(i);
        return out;
    }

    // ============================== Overlay =================================
    static ColorProcessor overlay(ColorProcessor base, ByteProcessor mask, Color c, int opacity, boolean invRegion) {
        ColorProcessor out = (ColorProcessor) base.duplicate();
        int[] px = (int[]) out.getPixels();
        byte[] mk = (byte[]) mask.getPixels();
        double a = clamp(opacity, 0, 100) / 100.0;
        int cr = c.getRed(), cg = c.getGreen(), cb = c.getBlue();
        for (int i = 0; i < px.length; i++) {
            boolean fg = (mk[i] & 0xff) == 255;
            boolean region = invRegion ? !fg : fg;
            if (!region) continue;
            int v = px[i];
            int r = (v >> 16) & 0xff, g = (v >> 8) & 0xff, b = v & 0xff;
            r = (int) Math.round(r * (1 - a) + cr * a);
            g = (int) Math.round(g * (1 - a) + cg * a);
            b = (int) Math.round(b * (1 - a) + cb * a);
            px[i] = (r << 16) | (g << 8) | b;
        }
        return out;
    }

    // ============================== Reports =================================
    static final String[] HEADER = {"Image", "Free_area_px", "Free_area_um2", "Wound_pct", "Magnification"};

    public static void writeReports(File outDir, List<Result> rs, Params p) {
        try {
            if (p.writeCSV)  writeDelimited(new File(outDir, "SMART-Wound_results.csv"), rs, ",");
            if (p.writeTXT)  writeDelimited(new File(outDir, "SMART-Wound_results.txt"), rs, "\t");
            if (p.writeXLSX) writeXlsx(new File(outDir, "SMART-Wound_results.xlsx"), rs);
        } catch (IOException e) {
            IJ.log("SMART-Wound: could not write reports: " + e);
        }
    }

    static void writeDelimited(File f, List<Result> rs, String sep) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < HEADER.length; i++) { if (i > 0) sb.append(sep); sb.append(HEADER[i]); }
        sb.append('\n');
        for (Result r : rs) {
            sb.append(r.name).append(sep)
              .append(r.woundPx).append(sep)
              .append(r.areaUm2).append(sep)
              .append(fmt(r.woundPct, 3)).append(sep)
              .append(r.mag).append('\n');
        }
        writeString(f, sb.toString());
    }

    // Minimal, valid .xlsx (Office Open XML) written with the JDK only (no POI).
    static void writeXlsx(File f, List<Result> rs) throws IOException {
        ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f));
        try {
            putEntry(z, "[Content_Types].xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
                "</Types>");
            putEntry(z, "_rels/.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
                "</Relationships>");
            putEntry(z, "xl/workbook.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                "<sheets><sheet name=\"Results\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            putEntry(z, "xl/_rels/workbook.xml.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
                "</Relationships>");

            StringBuilder sd = new StringBuilder();
            sd.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
            sd.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
            boolean[] allStr = {false, false, false, false, false};
            sd.append(xlsxRow(1, HEADER, allStr));
            int rn = 2;
            for (Result r : rs) {
                String[] vals = {r.name, "" + r.woundPx, r.areaUm2, fmt(r.woundPct, 3), r.mag};
                // which columns are numeric (vs inline string)
                boolean[] num = {false, true, !r.areaUm2.equals("NA"), true, false};
                sd.append(xlsxRow(rn++, vals, num));
            }
            sd.append("</sheetData></worksheet>");
            putEntry(z, "xl/worksheets/sheet1.xml", sd.toString());
        } finally {
            z.close();
        }
    }

    static String xlsxRow(int rn, String[] vals, boolean[] isNumber) {
        StringBuilder sb = new StringBuilder("<row r=\"").append(rn).append("\">");
        for (int c = 0; c < vals.length; c++) {
            String ref = colName(c) + rn;
            if (isNumber[c]) {
                sb.append("<c r=\"").append(ref).append("\"><v>").append(vals[c]).append("</v></c>");
            } else {
                sb.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t>")
                  .append(xmlEsc(vals[c])).append("</t></is></c>");
            }
        }
        return sb.append("</row>").toString();
    }

    static String colName(int i) {
        StringBuilder s = new StringBuilder();
        i++;
        while (i > 0) { int m = (i - 1) % 26; s.insert(0, (char) ('A' + m)); i = (i - 1) / 26; }
        return s.toString();
    }

    static void putEntry(ZipOutputStream z, String name, String content) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    // ============================== Helpers =================================
    static AutoThresholder.Method thrMethod(String s) {
        if ("Default".equals(s))  return AutoThresholder.Method.Default;
        if ("Huang".equals(s))    return AutoThresholder.Method.Huang;
        if ("Li".equals(s))       return AutoThresholder.Method.Li;
        if ("Triangle".equals(s)) return AutoThresholder.Method.Triangle;
        return AutoThresholder.Method.Otsu;
    }

    static void saveHighlights(Result r, File outDir, Params p) {
        if (!p.saveImages) return;
        File wd = new File(outDir, "Wound_Area_Highlighted"); wd.mkdirs();
        File cd = new File(outDir, "Cells_Highlighted");      cd.mkdirs();
        String base = stripExt(r.name);
        save(r.woundHi, new File(wd, base + "_Analyzed" + ext(p.format)), p.format);
        save(r.cellsHi, new File(cd, base + "_Analyzed" + ext(p.format)), p.format);
    }

    static void save(ImagePlus imp, File f, String fmt) {
        FileSaver fs = new FileSaver(imp);
        if ("PNG".equals(fmt))       fs.saveAsPng(f.getAbsolutePath());
        else if ("JPEG".equals(fmt)) fs.saveAsJpeg(f.getAbsolutePath());
        else                         fs.saveAsTiff(f.getAbsolutePath());
    }

    static String ext(String fmt) {
        if ("PNG".equals(fmt))  return ".png";
        if ("JPEG".equals(fmt)) return ".jpg";
        return ".tif";
    }

    static void collectImages(File dir, boolean recurse, List<File> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        java.util.Arrays.sort(fs);
        for (File f : fs) {
            if (f.isDirectory()) { if (recurse) collectImages(f, recurse, out); }
            else if (isImageFile(f.getName())) out.add(f);
        }
    }

    static boolean isImageFile(String n) {
        n = n.toLowerCase(Locale.US);
        return n.endsWith(".tif") || n.endsWith(".tiff") || n.endsWith(".jpg") || n.endsWith(".jpeg")
            || n.endsWith(".png") || n.endsWith(".bmp") || n.endsWith(".gif");
    }

    static String stripExt(String n) {
        int dot = n.lastIndexOf('.');
        return (dot > 0) ? n.substring(0, dot) : n;
    }

    static String toHex(Color c) {
        return String.format("%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    static Color hexToColor(String hex) {
        if (hex == null) return Color.YELLOW;
        hex = hex.trim().replace("#", "");
        if (hex.length() < 6) return Color.YELLOW;
        try {
            int r = Integer.parseInt(hex.substring(0, 2), 16);
            int g = Integer.parseInt(hex.substring(2, 4), 16);
            int b = Integer.parseInt(hex.substring(4, 6), 16);
            return new Color(r, g, b);
        } catch (NumberFormatException e) {
            return Color.YELLOW;
        }
    }

    static String fmt(double v, int dec) { return String.format(Locale.US, "%." + dec + "f", v); }
    static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
    static String trim(String s) { return s == null ? "" : s.trim(); }

    static String xmlEsc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static void writeString(File f, String content) throws IOException {
        Writer wtr = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8);
        try { wtr.write(content); } finally { wtr.close(); }
    }
}
