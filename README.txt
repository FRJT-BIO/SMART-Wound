========================================================================
  SMART-WOUND
========================================================================

A free, open-source ImageJ/Fiji plug-in that segments and quantifies the
cell-free area in in-vitro wound-healing (scratch) assays using local
image texture.

SMART-Wound separates cells (textured) from the wound (smooth) with a
local standard-deviation texture map and a divisive illumination
normalisation, so the migrating-cell front is delineated robustly across
illumination gradients and irregular / non-straight wounds, while the
inter-cell gaps of the confluent monolayer are not counted as wound. A
nearly-closed wound correctly measures as negligible area.

License : GNU General Public License v3.0 (see the LICENSE file)
Build   : status shown on the "Actions" tab of the GitHub repository


------------------------------------------------------------------------
  QUICK INSTALL  (pre-built plugin)
------------------------------------------------------------------------

  1. Download  SMART-Wound_.jar  from the repository's "Releases" page
     (or from the  download_plugin/  folder).
  2. Copy it into your  ImageJ/plugins   (or  Fiji.app/plugins)  folder.
  3. Restart ImageJ/Fiji.
  4. Run it from:   Plugins > SMART-Wound...

  No compilation or dependencies required.

  Compatibility: the plug-in is built for Java 8, so it runs on
  ImageJ/Fiji installations bundling Java 8 or any newer Java.

  CZI / proprietary formats: open them with Bio-Formats (bundled in
  Fiji) before running, or process exported TIFF/PNG.


------------------------------------------------------------------------
  USAGE
------------------------------------------------------------------------

  Open  Plugins > SMART-Wound...  and set:

  Input directory     Folder of images. Leave empty to process the
                      image currently open instead.
  Output directory    Where results are written (batch mode). If empty
                      in batch, a SMART-Wound_output folder is created
                      next to the input.
  Texture radius      Local-variance texture filter radius (default 5).
  Smoothing sigma     Gaussian smoothing of the texture map (default 6).
  Normalis. sigma     Divisive illumination normalisation (default 80).
  Threshold           Otsu (default), Default, Huang, Li, Triangle.
  Gap-fill radius     Closing radius that fills inter-cell gaps of the
                      monolayer (default 20). Min resolvable wound width
                      ~ 2x this value; lower it for nearly-closed wounds.
  Speck radius        Opening radius that removes small specks (def. 8).
  Min wound area %    Minimum component kept when Central region only is
                      off (default 1.0).
  Central region only On = keep the single largest cell-free region
                      (the central wound). Off = keep all regions above
                      Min wound area %.
  Wound / Cells col.  Highlight colours (pick with a colour chooser or
                      type a hex code).
  Fill opacity %      Overlay opacity 0-100 (default 50).
  Save images         Save the highlighted images (batch).
  Image format        TIFF / PNG / JPEG.
  Magnification       Free-text label stored in the report (optional).
  um/px               Pixel size for areas in um^2. 0 = pixels only (NA
                      in the um^2 column).
  Recursive           Process sub-folders.
  Write CSV/TXT/XLSX  Report formats.
  Threads             Parallel workers (batch). Output is identical
                      regardless of this value.

  Supported inputs:  .tif .tiff .jpg .jpeg .png .bmp .gif

  Algorithm (for each image):
    1. Texture map: Variance(radius) -> sqrt -> Gaussian(sigma).
    2. Divisive normalisation: divide by Gaussian(normSigma) copy
       (removes illumination / contrast gradients).
    3. Threshold (Otsu, dark) in 8-bit: cells = high texture.
    4. Morphology: closing (Gap-fill) fills inter-cell gaps; Fill Holes
       removes enclosed gaps; opening (Speck) removes specks.
    5. Invert -> wound mask, Fill Holes.
    6. Component selection: largest region, or all > Min wound area %.
    7. Measure cell-free (wound) pixels; optionally convert to um^2.
    8. Highlight wound + inverse cells on the ORIGINAL image.

  Outputs (in the output folder):
    - Wound_Area_Highlighted/<name>_Analyzed.<ext>  (wound coloured)
    - Cells_Highlighted/<name>_Analyzed.<ext>       (cells coloured)
    - SMART-Wound_results .csv / .txt / .xlsx
      columns: Image, Free_area_px, Free_area_um2, Wound_pct,
               Magnification.


------------------------------------------------------------------------
  BUILD FROM SOURCE
------------------------------------------------------------------------

  Option A - Maven (standard ImageJ ecosystem):
      mvn clean package
      -> target/SMART-Wound_-1.0.0.jar

  Option B - No Maven (self-contained script):
      ./build.sh
      -> SMART-Wound_.jar

  build.sh fetches the dependency-free ImageJ1 source, compiles it into
  a local ij.jar, then builds the plugin (targeting Java 8).

  Tests:
      ./build.sh
      mkdir -p .classes .test
      javac --release 8 -cp ij.jar -d .classes \
            src/main/java/SMART_Wound.java
      javac -cp ij.jar:.classes -d .test \
            src/test/java/TestSMARTWound.java
      java -Djava.awt.headless=true \
           -cp ij.jar:.classes:.test TestSMARTWound


------------------------------------------------------------------------
  HOW TO CITE
------------------------------------------------------------------------

  SMART-Wound is free to use under the GPL-3.0 license. If you use it
  in work that leads to a publication, please also cite the article
  describing the plug-in:

      Jimenez-Trinidad FR, Sardine A, et al. SMART-Wound: a
      user-friendly ImageJ plug-in for texture-based wound /
      scratch-assay quantification.
      DOI: to be added upon publication.

  A CITATION.cff file is included, so GitHub shows a "Cite this
  repository" button in the sidebar.


------------------------------------------------------------------------
  LICENSE
------------------------------------------------------------------------

  GNU General Public License v3.0 (see the LICENSE file).
  (c) 2024-2026 Francisco Rafael Jimenez-Trinidad and contributors.


------------------------------------------------------------------------
  ACKNOWLEDGEMENTS
------------------------------------------------------------------------

  Built on ImageJ (Wayne Rasband, NIH). Companion tool to Angio-CLEAN.
