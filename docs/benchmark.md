# QualiScan Pipeline Benchmark

**Date:** 2026-10-26 18:40:31Z  
**Video Source:** `E:\QualiScan\cv_service\..\data\test_conveyor.mp4`  
**Frames Processed:** 600 frames  
**Total Run Time:** 2.31 s  
**Average Throughput:** **259.5 FPS**  
**Total Items Inspected:** 6  

## 1. Test Environment

- **CPU Model:** 12th Gen Intel(R) Core(TM) i5-12500H
- **Operating System:** Windows-11-10.0.26300-SP0
- **Python Version:** 3.14.4 (CPython)
- **OpenCV Version:** 5.0.0
- **NumPy Version:** 2.5.4

## 2. Latency Metrics (Milliseconds)

| Stage | Mean (ms) | Median (ms) | 95th %ile (ms) | Min (ms) | Max (ms) |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **Overall Frame Latency** | **3.83** | **3.56** | **6.08** | 2.10 | 25.03 |
| 1. Segmentation (`segment_items`) | 3.28 | 3.04 | 5.21 | 1.80 | 11.37 |
| 2. Identification (`identify`) | 0.01 | 0.00 | 0.00 | 0.00 | 3.65 |
| 3. Inspection Rules (`inspect`) | 0.00 | 0.00 | 0.00 | 0.00 | 0.34 |

## 3. Per-Item Inspection Latency (Crossing Events Only)

*Evaluated on 6 trigger-line item crossing events:*

| Stage (Per Item) | Mean (ms) | Median (ms) | 95th %ile (ms) | Max (ms) |
| :--- | :---: | :---: | :---: | :---: |
| ML Product Identification | 0.92 | 0.39 | 2.84 | 3.65 |
| Defect Rule Checks | 0.27 | 0.28 | 0.33 | 0.34 |

## 4. Bottleneck Analysis

> [!TIP]
> **Primary Bottleneck:** **Segmentation** represents **85.5%** of the mean per-frame processing time (3.28 ms).  
> Classical contour segmentation (morphology, thresholding, and contour filtering) dominates frame time, while scikit-learn feature classification and rule evaluation take negligible compute (< 1 ms per item).
