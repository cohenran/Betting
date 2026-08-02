package il.co.sportpredict.web;

import il.co.sportpredict.winner.WinnerFetcher;
import il.co.sportpredict.winner.WinnerParser;
import il.co.sportpredict.winner.WinnerService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/winner")
@RequiredArgsConstructor
public class WinnerController {

    private static final int SNIPPET = 4000;

    private final WinnerService winner;
    private final WinnerFetcher fetcher;
    private final WinnerParser parser;

    public record UrlRequest(@NotBlank String url) {
    }

    public record TextRequest(@NotBlank String text) {
    }

    @PostMapping("/analyze")
    public WinnerService.RoundAnalysis analyze(@RequestBody UrlRequest request) {
        return winner.analyzeUrl(request.url());
    }

    /** Fallback path: paste the round's lines straight from the site. */
    @PostMapping("/analyze-text")
    public WinnerService.RoundAnalysis analyzeText(@RequestBody TextRequest request) {
        return winner.analyzePastedText(request.text());
    }

    /**
     * Shows exactly what was fetched and how many lines the parser found. This is the tool
     * to reach for when Winner changes their markup and the parse comes back empty.
     */
    @GetMapping("/debug")
    public Map<String, Object> debug(@RequestParam String url) {
        WinnerFetcher.Fetched fetched = fetcher.fetch(url);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("method", fetched.method());
        out.put("note", fetched.note());
        out.put("htmlLength", fetched.html() == null ? 0 : fetched.html().length());
        out.put("jsonPayloads", fetched.jsonPayloads().size());
        out.put("parsedLines", parser.parse(fetched).size());
        out.put("htmlSnippet", snippet(fetched.html()));
        out.put("jsonSnippets", fetched.jsonPayloads().stream().map(this::snippet).toList());
        return out;
    }

    @GetMapping("/lines")
    public List<?> lines(@RequestParam String url) {
        return parser.parse(fetcher.fetch(url));
    }

    private String snippet(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= SNIPPET ? s : s.substring(0, SNIPPET) + "...[truncated]";
    }
}
