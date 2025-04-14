import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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

public class ProductUrlCrawlerChecker {

    private static final int MAX_THREADS = 2;
    private static final int MAX_PRODUCT_URLS_PER_DOMAIN = 31;

    private static final List<String> START_DOMAINS = List.of(
            "https://www.nykaa.com/",
            "https://www.westside.com/"
//            "https://www.tatacliq.com/",
//            "https://www.virgio.com/",
//            "https://www.westside.com/"
    );

    private static final Pattern PRODUCT_URL_PATTERN = Pattern.compile(".*(product|women|men|item).*", Pattern.CASE_INSENSITIVE);
    private static final List<String> USER_AGENTS = List.of(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36"
    );

    private static final ExecutorService executor = Executors.newFixedThreadPool(MAX_THREADS);
    private static final Map<String, Set<String>> productUrlsByDomain = new ConcurrentHashMap<>();
    private static final WebDriver driver = new ChromeDriver();

    public static void main(String[] args) throws InterruptedException, IOException, ExecutionException {
        Queue<String> urlQueue = new LinkedList<>(START_DOMAINS);
        Set<String> visitedUrls = ConcurrentHashMap.newKeySet();
        List<Future<Set<String>>> futures = new ArrayList<>();

        int currentDepth = 0;

        while (!urlQueue.isEmpty()) {
            while (!urlQueue.isEmpty()) {
                String url = urlQueue.poll();
                if (visitedUrls.contains(url)) continue;

                String baseDomain = extractBaseDomain(url);
                if (baseDomain == null) continue;
                // domain size check.
                Set<String> productUrls = productUrlsByDomain.computeIfAbsent(baseDomain, k -> ConcurrentHashMap.newKeySet());
                System.out.println("Before basDom" + baseDomain + " size = "+productUrls.size());
                if(productUrls.size() >= MAX_PRODUCT_URLS_PER_DOMAIN) continue;
                System.out.println("After basDom" + baseDomain + " url = "+url);
                Future<Set<String>> future = executor.submit(() -> crawlPage(baseDomain, url, visitedUrls));

                futures.add(future);
            }

            for (Future<Set<String>> future : futures) {
                Set<String> discoveredUrls = future.get();

                for(String url : discoveredUrls) {
                    if (PRODUCT_URL_PATTERN.matcher(url).matches()) {
                        String baseDomain = extractBaseDomain(url);
                        System.out.println("baseDom = "+baseDomain + " url = "+ url);
                        Set<String> productUrls = productUrlsByDomain.computeIfAbsent(baseDomain, k ->  ConcurrentHashMap.newKeySet());
                        if(!productUrls.contains(url)) {
                            urlQueue.add(url);
                        }
                        if(productUrls.size() >= MAX_PRODUCT_URLS_PER_DOMAIN) break;
                        productUrls.add(url);
                    }
                }
            }
            futures.clear();
            currentDepth++;
        }

        System.out.println("Total depth: " + currentDepth);

        productUrlsByDomain.forEach((domain, urls) ->
            System.out.println("Domain: " + domain + " | URLs: " + urls.size())
        );

        driver.quit();
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.HOURS);
        saveProductUrlsToFile();
    }

    private static String extractBaseDomain(String url) {
        System.out.println("Check url = " + url);
        String[] parts = url.split("\\.");
        if (parts.length >= 2) {
            // To correctly extract base domain, return the second-to-last part and the last part
            String domain = parts[1];
            return "https://www." + domain + ".com";
        }
        return null;
    }

    private static Set<String> crawlPage(String baseDomain, String currentUrl, Set<String> visited) {
//        System.out.println("Processing: " + currentUrl + " | Total: " + productUrlsByDomain.get(baseDomain).size());
        visited.add(currentUrl);
        Set<String> discoveredUrls = new HashSet<>();

        try {
            String pageHtml = fetchHtmlContent(currentUrl);
            Document document = Jsoup.parse(pageHtml, currentUrl);

            // Combine <a> and <link> elements with hrefs
            Elements links = new Elements();
            links.addAll(document.select("a[href]"));
            links.addAll(document.select("link[href]"));

            for (Element element : links) {
                String href = element.absUrl("href");
                if (isInvalidUrl(href, baseDomain)) continue;

                if (!visited.contains(href)) {
                    discoveredUrls.add(href);
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to crawl: " + currentUrl + " | Error: " + e.getMessage());
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
        driver.get(url);
        return driver.getPageSource();
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
            System.err.println("Error writing results: " + e.getMessage());
        }
    }
}