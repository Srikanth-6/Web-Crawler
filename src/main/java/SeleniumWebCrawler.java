import io.github.bonigarcia.wdm.WebDriverManager;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

public class SeleniumWebCrawler {

    private static final Pattern PRODUCT_URL_PATTERN = Pattern.compile(".*(product|man|woman|item).*");
    private static final int THREAD_POOL_SIZE = 5;

    private final ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    private final Set<String> visited = ConcurrentHashMap.newKeySet();

    public static void main(String[] args) {
        List<String> baseUrls = Arrays.asList(
//                "https://example.com/category/shoes",
                "https://www.tatacliq.com/"
        );

        new SeleniumWebCrawler().crawl(baseUrls);
    }

    public void crawl(List<String> baseUrls) {
        for (String url : baseUrls) {
            executor.submit(() -> crawlPage(url));
        }

        executor.shutdown();
    }

    private void crawlPage(String url) {
        if (visited.contains(url)) return;
        visited.add(url);

        WebDriver driver = null;
        try {
            driver = createHeadlessDriver();
            System.out.println("[Crawling] " + url);
            driver.get(url);

            Thread.sleep(3000); // wait for JS-rendered content

            String pageSource = driver.getPageSource();
            Document doc = Jsoup.parse(pageSource, url);

            Set<String> links = extractLinks(doc);
            for (String link : links) {
                if (PRODUCT_URL_PATTERN.matcher(link).matches()) {
                    System.out.println("[Product] " + link);
                }
            }

        } catch (Exception e) {
            System.err.println("[Error] " + url + ": " + e.getMessage());
        } finally {
            if (driver != null) driver.quit();
        }
    }

    private Set<String> extractLinks(Document doc) {
        Set<String> links = new HashSet<>();
        Elements anchors = doc.select("a[href]");
        for (Element anchor : anchors) {
            String href = anchor.absUrl("href");
            if (!href.isEmpty()) {
                links.add(href);
            }
        }
        return links;
    }

    private WebDriver createHeadlessDriver() {
        // WebDriverManager handles driver binary for you
        WebDriverManager.chromedriver().setup();

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");

        return new ChromeDriver(options);
    }
}