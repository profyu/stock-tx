package com.example.stocktx;

import static org.bytedeco.javacpp.opencv_imgcodecs.IMREAD_GRAYSCALE;
import static org.bytedeco.javacpp.opencv_imgcodecs.imread;
import static org.bytedeco.javacpp.opencv_imgproc.CV_THRESH_OTSU;
import static org.bytedeco.javacpp.opencv_imgproc.MORPH_RECT;
import static org.bytedeco.javacpp.opencv_imgproc.THRESH_BINARY;
import static org.bytedeco.javacpp.opencv_imgproc.erode;
import static org.bytedeco.javacpp.opencv_imgproc.getStructuringElement;
import static org.bytedeco.javacpp.opencv_imgproc.threshold;
import static org.bytedeco.javacpp.opencv_photo.fastNlMeansDenoising;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

import org.apache.commons.io.FileUtils;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.opencv_core.Mat;
import org.bytedeco.javacpp.opencv_core.Size;
import org.bytedeco.javacpp.tesseract.TessBaseAPI;

public class CaptchaSolver implements AutoCloseable {
	// 工作區資料夾
	private File workspaceDir;
	// tesseract 訓練資料檔資料夾
	private File tessdataDir;
	
	private TessBaseAPI tessApi;

	public CaptchaSolver() throws IOException {

		this.workspaceDir = Files.createTempDirectory("captcha-solver-ws").toFile();
		this.tessdataDir = new File(workspaceDir, "tessdata");
		tessdataDir.mkdir();

		File trainedFile = new File(tessdataDir, "eng.traineddata");
		FileUtils.copyToFile(CaptchaSolver.class.getResourceAsStream("/trained/eng.traineddata"), trainedFile);

		this.tessApi = new TessBaseAPI();
		if (tessApi.Init(tessdataDir.getAbsolutePath(), "eng") != 0) {
			throw new IllegalStateException("Could not initialize tesseract.");
		}

	}

	private Mat cleanCaptcha(File imgFile) {

		// 讀取圖片(灰階)
		Mat captcha = imread(imgFile.getAbsolutePath(), IMREAD_GRAYSCALE);

		if (captcha.empty()) {
			throw new RuntimeException("Cannot read captcha image file");
		}

		// 二值化
		Mat captchaBw = new Mat();
		threshold(captcha, captchaBw, 128, 255, THRESH_BINARY | CV_THRESH_OTSU);

		// 侵蝕
		Mat captchaEroded = new Mat();
		Mat element = getStructuringElement(MORPH_RECT, new Size(3, 3));
		erode(captchaBw, captchaEroded, element);
		
		// 降噪
		Mat captchaDenoised = new Mat();
		fastNlMeansDenoising(captchaEroded, captchaDenoised, 7, 7, 21);		

		return captchaDenoised;

	}

	private String imgToString(Mat img) {
		
		tessApi.SetImage(img.data(), img.cols(), img.rows(), 1, img.cols());

		BytePointer outText = tessApi.GetUTF8Text();
		String s = outText.getString();

		// Destroy used object and release memory
		outText.deallocate();

		return s.replaceAll("[^0-9-A-Z]", "");
	}

	public String solve(InputStream input) {
		File tmpImg = null;
		try {
			// 把 InputStream 中的圖片存成臨時檔案
			tmpImg = File.createTempFile("img", ".tmp");
			FileUtils.copyToFile(input, tmpImg);

			// 利用 OpenCV 對影像進行前置處理
			Mat cleaned = cleanCaptcha(tmpImg);
			
			// 利用 Tesseract 對處理後的影像進行文字辨識
			return imgToString(cleaned);

		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			if (tmpImg != null) {
				FileUtils.deleteQuietly(tmpImg);
			}

		}

	}

	public void close() throws Exception {
		FileUtils.deleteQuietly(workspaceDir);
		tessApi.End();

	}
}
