package il.co.sportpredict.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public class CsvHelper {

    public static List<String> readLines(String filePath) {
        try {
            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                return new ArrayList<>();
            }
            return Files.readAllLines(path);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read CSV " + filePath, e);
        }
    }

    public static void writeLines(String filePath, List<String> lines) {
        try {
            Files.write(Path.of(filePath), lines);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write CSV " + filePath, e);
        }
    }

    public static void appendLine(String filePath, String line) {
        try {
            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                Files.createDirectories(path.getParent() != null ? path.getParent() : Path.of("."));
                Files.writeString(path, line + System.lineSeparator());
            } else {
                Files.writeString(path, line + System.lineSeparator(), StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to append to CSV " + filePath, e);
        }
    }
}
