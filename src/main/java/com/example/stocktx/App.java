package com.example.stocktx;

import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URL;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import javax.print.DocFlavor.STRING;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;

public class App {

	public static final String BROWSER_DRIVER_KEY = "webdriver.chrome.driver";
	public static final String APP_REPO_KEY = "app.repo";
	public static final String BASE_URL = "http://bsr.twse.com.tw/bshtm/";

	public static void printHelp(Options options) {
		HelpFormatter formatter = new HelpFormatter();
		formatter.printHelp("stocktx <options>", options);
	}

	public static void configBrowserDriver() {
		String basePath = System.getProperty(APP_REPO_KEY) + File.separator + "drivers" + File.separator;

		if (SystemUtils.IS_OS_WINDOWS) {
			System.setProperty(BROWSER_DRIVER_KEY, basePath + "chromedriver.exe");
		} else if (SystemUtils.IS_OS_MAC) {
			System.setProperty(BROWSER_DRIVER_KEY, basePath + "chromedriver-mac");
		} else if (SystemUtils.IS_OS_LINUX) {
			System.setProperty(BROWSER_DRIVER_KEY, basePath + "chromedriver-linux");
		}

	}

	public static void main(String[] args) {

		// 自動設定 Browser Driver
		if (System.getProperty(APP_REPO_KEY) != null) {
			configBrowserDriver();
		}

		// 定義 CLI 參數
		Options options = new Options();
		options.addOption("n", "number", true, "證券代號");
		options.addOption("o", "output-file", true, "CSV檔輸出路徑");
		options.addOption("h", "help", false, "印出CLI說明");

		DefaultParser parser = new DefaultParser();
		try {

			// 解析使用者在指令行輸入的參數
			CommandLine cmdLine = parser.parse(options, args);

			// 如果使用者帶入 -h 參數，則印出操作說明，並結束程式
			if (cmdLine.hasOption('h')) {
				printHelp(options);
				System.exit(0);
			}

			// 處理使用者未提供 -n
			if (!cmdLine.hasOption('n')) {
				System.out.println("未提供證券代號");
				printHelp(options);
				System.exit(1);
			}
			int stockNo = Integer.parseInt(cmdLine.getOptionValue('n'));

			// 處理使用者未提供 -o
			if (!cmdLine.hasOption('o')) {
				System.out.println("未提供CSV檔輸出路徑");
				printHelp(options);
				System.exit(1);
			}

			File outputFile = new File(cmdLine.getOptionValue('o'));
			File outputDir = outputFile.getParentFile();
			outputDir.mkdirs();

			// 實作之後要用來解驗證碼的 CaptchaSolver 實例
			try (CaptchaSolver captchaSolver = new CaptchaSolver()) {
				
				// 透過 Selenium 開始命令 Chrome Driver 開啟一個網頁
				WebDriver browser = new ChromeDriver();
				browser.get(BASE_URL);

				// 程式暫停 5 秒鐘，確保瀏覽器內相關畫面已經呈現好了
				Thread.sleep(5000);

				// 透過迴圈不斷嘗試解驗證碼，直到成功為止
				while (true) {

					// 切換視野到 page1 (左邊畫面)
					browser.switchTo().frame("page1");

					// 找出驗證碼的圖片元素
					WebElement captchaElement = browser.findElement(By.cssSelector("div#Panel_bshtm img"));
					// 抓取驗證碼圖片網址
					String captchaUrl = captchaElement.getAttribute("src");

					// 找出驗證碼輸入方塊
					WebElement captchaInput = browser
							.findElement(By.cssSelector("div#Panel_bshtm input[name=CaptchaControl1]"));

					// 解驗證碼
					try (InputStream imgIn = new URL(captchaUrl).openStream();) {

						String solved = captchaSolver.solve(imgIn);
						System.out.println("推測驗證碼為: " + solved);

						// 輸入驗證碼到輸入方塊
						captchaInput.clear();
						captchaInput.sendKeys(solved);
					}

					// 輸入股票代號
					WebElement stockNoInput = browser.findElement(By.cssSelector("#TextBox_Stkno"));
					stockNoInput.clear();
					stockNoInput.sendKeys(String.valueOf(stockNo));

					// 點擊查詢按鈕
					WebElement okButton = browser.findElement(By.cssSelector("#btnOK"));
					okButton.click();

					// 等待 2 秒鐘，確保結果已經出來
					Thread.sleep(2000);

					// 切換視野到 page2 (右邊畫面)
					browser.switchTo().parentFrame();
					browser.switchTo().frame("page2");

					try {
						// 如果在右邊畫面有找到 "td#stock_id" 元素，代表驗證碼成功，交易資料呈現出來了。
						// 成功後要離開迴圈
						WebElement stockIdElement = browser.findElement(By.cssSelector("td#stock_id"));
						break;
					} catch (NoSuchElementException e) {
						System.out.println("Captcha  解析失敗");
						browser.switchTo().parentFrame();
					}
				}

				// 產生輸出CSV檔
				try (CSVPrinter csvPrinter = new CSVPrinter(new FileWriter(outputFile),
						CSVFormat.EXCEL.withHeader("序號", "證券商", "成交單價", "買進股數", "賣出股數"))) {

					// 產生確保能回傳 BigDecimal 型態數值的數字解析器
					DecimalFormat decimalFormat = (DecimalFormat) NumberFormat.getInstance(java.util.Locale.US);
					decimalFormat.setParseBigDecimal(true);

					// 產生確保能回傳 Integer 型態數值的數字解析器
					NumberFormat intFormat = NumberFormat.getIntegerInstance(java.util.Locale.US);

					// 找出所有資料列元素 (各筆交易)
					List<WebElement> colValPrices = browser
							.findElements(By.cssSelector(".column_value_price_2,.column_value_price_3"));

					// 走訪各資料列
					for (WebElement colValPrice : colValPrices) {
						try {
							// 找出資料列的子元素們
							List<WebElement> columns = colValPrice.findElements(By.xpath("child::*"));

							// 0: 序號
							int sn = Integer.parseInt(columns.get(0).getText());
							// 1: 證券商
							String dealer = columns.get(1).getText();
							// 2: 成交單價
							BigDecimal price = (BigDecimal) decimalFormat.parse(columns.get(2).getText());
							// 3: 買進股數
							int buy = intFormat.parse(columns.get(3).getText()).intValue();
							// 4: 賣出股數
							int sale = intFormat.parse(columns.get(4).getText()).intValue();

							System.out.println("正在寫出序號: " + sn);

							// 寫出本筆紀錄到CSV
							csvPrinter.printRecord(sn, dealer, price, buy, sale);

						} catch (NoSuchElementException | NumberFormatException e) {
							// 解析資料列過程中，如發生意外，則跳過本筆
							continue;
						}
					}
					System.out.println("抓取完成");

					csvPrinter.flush();
				}
				browser.close();
			}
			

		} catch (Exception ex) {
			ex.printStackTrace();
			printHelp(options);
		}
	}
}
