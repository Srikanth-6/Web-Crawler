import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProductUrlCrawlerChecker {

    private static final Logger logger = LoggerFactory.getLogger(ProductUrlCrawlerChecker.class);

    private static final int MAX_THREADS = 5;
    private static final int MAX_PRODUCT_URLS_PER_DOMAIN = 20;

    private static final List<String> START_DOMAINS = List.of(
            "https://www.flipkart.com/",
            "https://www.westside.com/",
            "https://www.tatacliq.com/",
            "https://www.virgio.com/"
    );

    private static final Pattern PRODUCT_URL_PATTERN = Pattern.compile(".*(product|women|men|item).*", Pattern.CASE_INSENSITIVE);

    private static final ExecutorService executor = Executors.newFixedThreadPool(MAX_THREADS);
    private static final Map<String, Set<String>> productUrlsByDomain = new ConcurrentHashMap<>();

    public static void main(String[] args) throws InterruptedException, IOException, ExecutionException {
        Queue<String> urlQueue = new LinkedList<>(START_DOMAINS);
        Set<String> visitedUrls = ConcurrentHashMap.newKeySet();
        List<Future<Set<String>>> futures = new ArrayList<>();

        logger.info("Starting the crawler with {} domains...", START_DOMAINS.size());

        while (true) {

            if (urlQueue.isEmpty()) {
                long pending = futures.stream().filter(f -> !f.isDone()).count();
                if (pending > 0) {
                    logger.info("Waiting for {} tasks to complete...", pending);
                    getFutures(futures, urlQueue);
                } else {
                    logger.info("No more URLs to crawl. Shutting down executor.");
                    executor.shutdownNow();
                    break;
                }
            }

            String url = urlQueue.poll();
            if (url == null || visitedUrls.contains(url)) continue;
            visitedUrls.add(url);

            String baseDomain = extractBaseDomain(url);
            if (baseDomain == null) continue;

            Set<String> productUrls = productUrlsByDomain.computeIfAbsent(baseDomain, k -> ConcurrentHashMap.newKeySet());
            if (productUrls.size() >= MAX_PRODUCT_URLS_PER_DOMAIN) continue;

            logger.info("Submitting crawl task for URL: {}", url);
            Future<Set<String>> future = executor.submit(() -> crawlPage(baseDomain, url));
            futures.add(future);
        }

        productUrlsByDomain.forEach((domain, urls) ->
                logger.info("Collected {} product URLs for domain: {}", urls.size(), domain)
        );

        saveProductUrlsToFile();
        executor.shutdown();
        logger.info("Crawling complete. Results written to product_urls.json");
    }

    private static void getFutures(List<Future<Set<String>>> futures, Queue<String> urlQueue) throws ExecutionException, InterruptedException {
        for (Future<Set<String>> future : futures) {
            Set<String> discoveredUrls = future.get();

            for (String url : discoveredUrls) {
                if (PRODUCT_URL_PATTERN.matcher(url).matches()) {
                    String baseDomain = extractBaseDomain(url);
                    if (baseDomain == null) continue;

                    Set<String> productUrls = productUrlsByDomain.computeIfAbsent(baseDomain, k -> ConcurrentHashMap.newKeySet());

                    if (!productUrls.contains(url)) {
                        urlQueue.add(url);
                        logger.debug("Adding new potential product URL: {}", url);
                    }

                    if (productUrls.size() < MAX_PRODUCT_URLS_PER_DOMAIN) {
                        productUrls.add(url);
                        logger.info("Product URL added under domain {}: {}", baseDomain, url);
                    }
                }
            }
        }
        futures.clear();
    }

    private static String extractBaseDomain(String url) {
        String[] parts = url.split("\\.");
        if (parts.length >= 2) {
            String domain = parts[1];
            return "https://www." + domain + ".com";
        }
        return null;
    }

    private static Set<String> crawlPage(String baseDomain, String currentUrl) {
        Set<String> discoveredUrls = new HashSet<>();

        try {
            logger.debug("Crawling page: {}", currentUrl);
            String pageHtml = fetchHtmlContent(currentUrl);
            Document document = Jsoup.parse(pageHtml, currentUrl);

            Elements links = new Elements();
            links.addAll(document.select("a[href]"));
            links.addAll(document.select("link[href]"));

            for (Element element : links) {
                String href = element.absUrl("href");
                if (isInvalidUrl(href, baseDomain)) continue;
                discoveredUrls.add(href);
            }

            logger.debug("Discovered {} URLs on page: {}", discoveredUrls.size(), currentUrl);
        } catch (Exception e) {
            logger.error("Failed to crawl: {} | Error: {}", currentUrl, e.getMessage());
        }

        return discoveredUrls;
    }

    private static boolean isInvalidUrl(String url, String domain) {
        return url.isEmpty() ||
                url.contains("#") ||
                url.endsWith(".jpg") ||
                url.endsWith(".png") ||
                !url.startsWith(domain);
    }

    private static String fetchHtmlContent(String url) {
        WebDriverManager.chromedriver().setup();

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-gpu");

        WebDriver driver = new ChromeDriver(options);
        driver.get(url);
        String pageSource = driver.getPageSource();
        driver.quit();
        return pageSource;
    }

    private static void saveProductUrlsToFile() {
        try (FileWriter writer = new FileWriter("product_urls.json")) {
            writer.write("{\n");
            int domainIndex = 0;
            for (Map.Entry<String, Set<String>> entry : productUrlsByDomain.entrySet()) {
                writer.write(String.format("  \"%s\": [\n", entry.getKey()));
                int urlIndex = 0;
                for (String url : entry.getValue()) {
                    writer.write(String.format("    \"%s\"%s\n", url, ++urlIndex < entry.getValue().size() ? "," : ""));
                }
                writer.write("  ]" + (++domainIndex < productUrlsByDomain.size() ? ",\n" : "\n"));
            }
            writer.write("}\n");
        } catch (IOException e) {
            logger.error("Error writing product URLs to file: {}", e.getMessage());
        }
    }
}