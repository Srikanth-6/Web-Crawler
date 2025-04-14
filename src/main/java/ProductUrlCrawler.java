import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.*;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;

public class ProductUrlCrawler {
    private static final int MAX_THREADS = 1;
    private static final int MAX_DEPTH = 3;
    private static final int MAX_URLS = 100;
    private static WebDriver driver = new ChromeDriver();
    private static final List<String> DOMAINS = Arrays.asList(
//        "https://www.virgio.com/"
        "https://www.tatacliq.com/"
//        "https://www.nykaafashion.com/"
//        "https://www.westside.com/"
    );

    private static final AtomicInteger taskCounter = new AtomicInteger(0);
    private static final Pattern PRODUCT_URL_PATTERN = Pattern.compile(".*(product|women|men|item).*", Pattern.CASE_INSENSITIVE);    private static final ExecutorService executor = Executors.newFixedThreadPool(MAX_THREADS);
    private static final AtomicInteger urlCounter = new AtomicInteger(0);
    private static final Map<String, HashSet<String>> productUrlsMap = new ConcurrentHashMap<>();
    private static final List<String> USER_AGENTS = List.of(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36"
    );

    public static void main(String[] args) throws InterruptedException, IOException, ExecutionException {

        List<Future<Set<String>>> futures = new ArrayList<>();
        Queue<String> q = new LinkedList<>();
        int depth = 0;
        for(String domain : DOMAINS) q.add(domain);

        Set<String> visited = new HashSet<>();

        while(!q.isEmpty() && depth <= MAX_DEPTH && urlCounter.get() < MAX_URLS) {
            String url = q.poll();
            if(visited.contains(url)) continue;
            String domain = getDomain(url);
            futures.add(executor.submit(() ->{
//                Thread.sleep(2000);
                if(urlCounter.get() >= MAX_URLS) return new HashSet<>();
                return crawlDomain(domain, url, visited);
//                return new HashSet<>(Arrays.asList("Setting value "+count.incrementAndGet()));
                    }
            ));
            if(q.isEmpty()) {
                for(Future<Set<String>> future : futures) {
                    for(String nestedUrl : future.get()) {
                        q.add(nestedUrl);
                    }
                }
                depth++;
            }
        }
        System.out.println("Final Prods size" +  urlCounter.get());
        for(String prods : productUrlsMap.keySet()) {
            System.out.print(productUrlsMap.get(prods).size());
        }
        driver.quit();
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.HOURS);

        saveResultsToFile();
    }

    public static String getDomain(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host == null) return null;

            String[] parts = host.split("\\.");
            if (parts.length >= 2) {
                String domain = parts[parts.length - 2];
                String mainDomain = "https://www."+domain+".com/";
                return mainDomain;
            }
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static Set<String> crawlDomain(String domain, String url, Set<String> visited) {
//        System.out.println("domain = "+domain + " url = "+url);
        System.out.println("Prods size" +  urlCounter.get());
        visited.add(url);
        Set<String> urlsList = new HashSet<>();
        try {
            String html = fetchHtmlWithRotatedUA(url);
            Document doc = Jsoup.parse(html, url);
            Elements links1 = doc.select("a[href]");
            Elements links2 = doc.select("link[href]");

            for (Element link : links1) {
                String href = link.absUrl("href");
                if (href.isEmpty() || href.contains("#") || href.endsWith(".jpg") || href.endsWith(".png")) continue;
                if (!href.startsWith(domain)) continue;

                if (PRODUCT_URL_PATTERN.matcher(href).matches()) {
                    HashSet<String> productUrls = productUrlsMap.getOrDefault(domain, new HashSet<String>());
                    if(productUrls.add(href)) urlCounter.incrementAndGet();
                    System.out.println("Before 1 Prods size" +  urlCounter.get());
                    if(urlCounter.get() >= MAX_URLS) return urlsList;
                    System.out.println("After 1 Prods size" +  urlCounter.get());
                    productUrlsMap.put(domain, productUrls);
                }

                if (!visited.contains(href)) {
                    String nextUrl = href;
                    urlsList.add(nextUrl);
                }
            }

            for (Element link : links2) {
                String href = link.absUrl("href");
                if (href.isEmpty() || href.contains("#") || href.endsWith(".jpg") || href.endsWith(".png")) continue;
                if (!href.startsWith(domain)) continue;

                if (PRODUCT_URL_PATTERN.matcher(href).matches()) {
                    HashSet<String> productUrls = productUrlsMap.getOrDefault(domain, new HashSet<String>());
                    if(productUrls.add(href)) urlCounter.incrementAndGet();
                    System.out.println("Before 2 Prods size" +  urlCounter.get());
                    if(urlCounter.get() >= MAX_URLS) return urlsList;
                    System.out.println("After 2 Prods size" +  urlCounter.get());
                    productUrlsMap.put(domain, productUrls);
                }

                if (!visited.contains(href)) {
                    String nextUrl = href;
                    urlsList.add(nextUrl);
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to crawl: " + url);
        }
        return urlsList;
    }

    public static String fetchHtmlWithRotatedUA(String url) throws IOException, InterruptedException {
        Random random = new Random();
        String user_agent = USER_AGENTS.get(random.nextInt(USER_AGENTS.size()));

//        System.setProperty("webdriver.chrome.driver", "/Users/srikanth/Downloads/chromedriver_mac64/chromedriver");
        WebDriverManager.chromedriver().setup();
        driver.get(url);
        String html = driver.getPageSource();

        return html;
    }

    private static void saveResultsToFile() {
        try (FileWriter writer = new FileWriter("product_urls.json")) {
            writer.write("{\n");
            int domainCount = 0;
            for (Map.Entry<String, HashSet<String>> entry : productUrlsMap.entrySet()) {
                writer.write(String.format("  \"%s\": [\n", entry.getKey()));
                int urlCount = 0;
                for (String url : entry.getValue()) {
                    writer.write(String.format("    \"%s\"%s\n", url, ++urlCount < entry.getValue().size() ? "," : ""));
                }
                writer.write("  ]" + (++domainCount < productUrlsMap.size() ? ",\n" : "\n"));
            }
            writer.write("}\n");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
