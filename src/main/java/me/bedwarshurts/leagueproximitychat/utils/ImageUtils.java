package me.bedwarshurts.leagueproximitychat.utils;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.Size;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;

public final class ImageUtils {

    public static double getStdDev(Mat mat) {
        Mat gray = new Mat();
        if (mat.channels() > 1) {
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY);
        } else {
            mat.copyTo(gray);
        }
        MatOfDouble mean = new MatOfDouble();
        MatOfDouble stddev = new MatOfDouble();
        Core.meanStdDev(gray, mean, stddev);
        double sigma = stddev.get(0, 0)[0];
        gray.release();
        return sigma;
    }

    public static Mat applyEnhancement(Mat bgrMat) {
        Mat gray = new Mat();
        Imgproc.cvtColor(bgrMat, gray, Imgproc.COLOR_BGR2GRAY);
        CLAHE clahe = Imgproc.createCLAHE(4.0, new Size(4, 4));
        clahe.apply(gray, gray);
        return gray;
    }
}
