package com.dropsync.domain.sensor.calibration

/**
 * Jacobi eigenvalue decomposition of a symmetric 3x3 matrix (port of
 * _jacobiEigen3 in calibration_controller.dart). The design doc requires no
 * extra dependency for the 3D-gyro PCA ("Neue Dependencies": 3x3 is enough).
 *
 * Returns eigenvalues [w] and eigenvectors [v] (columns).
 *
 * D2 (MatchingDeclarationName): eigene Datei statt Beifang in `Pca.kt`.
 */
internal data class JacobiResult(
    val w: DoubleArray,
    val v: Array<DoubleArray>,
)
