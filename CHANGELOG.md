# Changelog

All notable changes to SMART-Wound are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/).

## [1.0.0] - 2026-06-30
### Added
- First Java plug-in version of SMART-Wound, porting the validated
  `SMART-Wound.ijm` macro to a single ImageJ/Fiji plug-in.
- **Texture-based segmentation:** local standard-deviation texture map
  (Variance -> square root -> Gaussian) with **divisive illumination
  normalisation**, so cells (textured) are separated from the wound (smooth)
  robustly across illumination gradients and irregular / non-straight wounds.
- Scale-aware morphology: closing (Gap-fill) fills the inter-cell gaps of the
  confluent monolayer, Fill Holes removes enclosed gaps, opening removes specks.
  A real wound (large connected cell-free region of any shape) is kept; a
  nearly-closed wound correctly measures as negligible area.
- Component selection: **largest connected region** (central wound only) or
  **all regions above a minimum area** (scattered gaps).
- **Highlighted outputs:** wound-coloured image and its inverse cells-coloured
  image, drawn on the **original** (un-processed) image, with a free 0-100%
  fill opacity and a visual **JColorChooser** palette (plus hex entry).
- Reports: **CSV**, **TXT**, and a **native `.xlsx`** (Office Open XML, written
  with the JDK only - no external dependencies). Columns: Image, Free_area_px,
  Free_area_um2 (or NA), Wound_pct, Magnification.
- Optional calibration (um/px) for areas in um^2.
- I/O follows the Angio-CLEAN pattern: set an Input directory for batch, or
  leave it empty to process the active image; optional recursive subfolders.
- **Multi-threaded** batch processing; output is independent of the thread
  count (verified by an automated test).
- Public `processImage(ImagePlus, Params)` and `processFolder(...)` API for
  scripting.
- Self-contained `build.sh` and GitHub Actions CI with a headless test suite.

### Notes
- Built targeting **Java 8** bytecode (class file version 52) for compatibility
  with ImageJ/Fiji installations that bundle Java 8.
- The plug-in's measurement reproduces the `SMART-Wound.ijm` macro pipeline.
  A central protected-band option that was prototyped during development was
  **deliberately removed**, because on real images it over-counted the
  inter-cell gaps of the confluent monolayer; the uniform texture pipeline
  already handles wandering wounds and nearly-closed wounds correctly.
