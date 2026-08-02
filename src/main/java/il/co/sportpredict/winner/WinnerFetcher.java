package il.co.sportpredict.winner;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;
import il.co.sportpredict.config.SportPredictProperties;
import il.co.sportpredict.util.Names;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Fetches a Winner.co.il round page.
 *
 * <p>Two stages, cheapest first:
 * <ol>
 *   <li>Plain HTTP + Jsoup. Works if the page ships its data server-side or embeds it in
 *       a JSON script tag.</li>
 *   <li>Headless Chromium via Playwright, which also records every JSON response the page
 *       fetches - the internal API payload is usually far easier to parse than the DOM.</li>
 * </ol>
 *
 * <p>Note: automated access is against the site's terms of use, the markup changes without
 * notice, and the IP can be blocked. {@code /api/winner/debug} returns whatever was
 * actually fetched, which is the fastest way to re-tune the parser when that happens.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WinnerFetcher {

    private static final Pattern ODDS = Pattern.compile("\\b\\d{1,2}\\.\\d{2}\\b");

    private final RestClient scraperRestClient;
    private final SportPredictProperties props;

    public record Fetched(String url, String method, String html, List<String> jsonPayloads, String note) {

        public boolean hasJson() {
            return jsonPayloads != null && !jsonPayloads.isEmpty();
        }
    }

    public Fetched fetch(String url) {
        String html = null;
        String note = null;
        try {
            html = scraperRestClient.get().uri(url).retrieve().body(String.class);
        } catch (Exception e) {
            note = "http fetch failed: " + e.getMessage();
            log.warn("winner HTTP fetch failed for {}: {}", url, e.getMessage());
        }

        if (html != null) {
            List<String> embedded = embeddedJson(html);
            if (!embedded.isEmpty()) {
                return new Fetched(url, "HTML_JSON", html, embedded, note);
            }
            if (looksLikeItHasEvents(html)) {
                return new Fetched(url, "HTML", html, List.of(), note);
            }
            note = (note == null ? "" : note + "; ") + "static HTML had no event rows";
        }

        if (!props.getWinner().isPlaywrightEnabled()) {
            return new Fetched(url, "HTML", html, List.of(),
                    (note == null ? "" : note + "; ") + "playwright disabled");
        }
        return renderWithBrowser(url, note);
    }

    private Fetched renderWithBrowser(String url, String note) {
        List<String> captured = Collections.synchronizedList(new ArrayList<>());
        double timeoutMs = props.getWinner().getTimeoutSeconds() * 1000.0;
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    // Required when running as a service user on a bare Ubuntu box.
                    .setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage")));
            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setUserAgent(props.getWinner().getUserAgent())
                    .setLocale("he-IL"));
            Page page = context.newPage();
            page.onResponse(response -> {
                String responseUrl = response.url();
                if (response.status() != 200) {
                    return;
                }
                String contentType = response.headerValue("content-type");
                boolean json = (contentType != null && contentType.contains("json"))
                        || responseUrl.contains("/api/");
                if (!json) {
                    return;
                }
                try {
                    String body = response.text();
                    if (body != null && body.length() > 200 && Names.containsHebrew(body)) {
                        captured.add(body);
                    }
                } catch (Exception ignored) {
                    // Streamed or already-consumed bodies are not worth failing over.
                }
            });
            page.navigate(url, new Page.NavigateOptions()
                    .setTimeout(timeoutMs)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.waitForTimeout(Math.min(6000, timeoutMs / 2));
            String rendered = page.content();
            browser.close();
            return new Fetched(url, "PLAYWRIGHT", rendered, List.copyOf(captured), note);
        } catch (Exception e) {
            log.warn("playwright render failed for {}: {}", url, e.toString());
            return new Fetched(url, "PLAYWRIGHT_FAILED", null, List.copyOf(captured),
                    (note == null ? "" : note + "; ") + "playwright: " + e.getMessage());
        }
    }

    /** JSON blobs SPAs leave in the page: __NEXT_DATA__, __INITIAL_STATE__, ld+json, etc. */
    private List<String> embeddedJson(String html) {
        List<String> out = new ArrayList<>();
        Document doc = Jsoup.parse(html);
        for (Element script : doc.select("script")) {
            String type = script.attr("type");
            String data = script.data();
            if (data.length() < 200) {
                continue;
            }
            boolean jsonType = type.contains("json");
            boolean stateAssignment = data.contains("__INITIAL_STATE__") || data.contains("__NEXT_DATA__");
            if ((jsonType || stateAssignment) && Names.containsHebrew(data)) {
                out.add(extractJson(data));
            }
        }
        return out;
    }

    /** Strips a leading "window.X = " and a trailing semicolon from a state assignment. */
    private String extractJson(String script) {
        int brace = script.indexOf('{');
        int bracket = script.indexOf('[');
        int start = brace < 0 ? bracket : (bracket < 0 ? brace : Math.min(brace, bracket));
        if (start < 0) {
            return script;
        }
        String body = script.substring(start).trim();
        return body.endsWith(";") ? body.substring(0, body.length() - 1) : body;
    }

    /** Heuristic: a real round page carries several decimal odds. */
    private boolean looksLikeItHasEvents(String html) {
        String text = Jsoup.parse(html).text();
        return ODDS.matcher(text).results().count() >= 6 && Names.containsHebrew(text);
    }
}
