package io.johnsonlee.graphfold.engine.origin.javac;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import io.johnsonlee.graphfold.engine.origin.protocol.OriginProtocol.Backend;
import io.johnsonlee.graphfold.engine.origin.protocol.OriginProtocol.Compiler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.lang.model.element.Element;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;

/** Compiler-assigned identities captured after attribution and confirmed by class generation events. */
final class OriginListener implements TaskListener {
    private final JavacTask task;
    private final Trees trees;
    private final Path output;
    private final Path root;
    private final String runId;
    private final Map<String, Origin> origins = new LinkedHashMap<>();
    private final Set<String> generated = new HashSet<>();
    private final Map<String, String> outputs = new LinkedHashMap<>();
    private boolean failed;

    OriginListener(JavacTask task, Path output, Path root, String runId) {
        this.runId = runId;
        this.task = task;
        this.trees = Trees.instance(task);
        this.output = output.toAbsolutePath().normalize();
        try {
            this.root = root.toRealPath();
        } catch (IOException failure) {
            throw new IllegalArgumentException("Source root is not accessible: " + root, failure);
        }
    }

    @Override
    public void finished(TaskEvent event) {
        try {
            switch (event.getKind()) {
                case ANALYZE -> capture(event.getCompilationUnit());
                case GENERATE -> captureOutput(event);
                case COMPILATION -> write();
                default -> { }
            }
        } catch (RuntimeException | IOException failure) {
            failed = true;
            throw new IllegalStateException("GraphFold javac source provenance failed", failure);
        }
    }

    private void captureOutput(TaskEvent event) throws IOException {
        String owner = task.getElements().getBinaryName(event.getTypeElement()).toString();
        Path source = Path.of(event.getCompilationUnit().getSourceFile().toUri()).toRealPath();
        if (!source.startsWith(root)) throw new IllegalStateException("Generated source is outside checkout: " + source);
        generated.add(owner);
        outputs.put(owner, "{\"owner\":" + quote(owner) + ",\"sources\":[{\"path\":" + quote(source.toString())
            + ",\"hash\":" + quote(sha256(Files.readAllBytes(source))) + "}]}");
    }

    private void capture(CompilationUnitTree unit) throws IOException {
        Path source = Path.of(unit.getSourceFile().toUri()).toRealPath();
        if (!source.startsWith(root)) {
            throw new IllegalStateException("Source is outside the supplied checkout root: " + source);
        }
        byte[] bytes = Files.readAllBytes(source);
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!text.contentEquals(unit.getSourceFile().getCharContent(true))) {
            throw new IllegalStateException("Source encoding or compiler content differs from UTF-8 file: " + source);
        }
        String hash = sha256(bytes);
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree node, Void unused) {
                Element element = trees.getElement(getCurrentPath());
                if (element instanceof TypeElement type && type.getNestingKind() == NestingKind.ANONYMOUS) {
                    String owner = task.getElements().getBinaryName(type).toString();
                    Tree allocation = getCurrentPath().getParentPath().getLeaf();
                    long start = trees.getSourcePositions().getStartPosition(unit, node);
                    long end = trees.getSourcePositions().getEndPosition(unit, node);
                    long allocationStart = trees.getSourcePositions().getStartPosition(unit, allocation);
                    long allocationEnd = trees.getSourcePositions().getEndPosition(unit, allocation);
                    if (!valid(start, end, text.length()) || !valid(allocationStart, allocationEnd, text.length())) {
                        throw new IllegalStateException("Missing compiler source range for " + owner);
                    }
                    Origin origin = new Origin(owner, source.toString(), hash, start, end, allocationStart, allocationEnd);
                    Origin previous = origins.putIfAbsent(owner, origin);
                    if (previous != null && !previous.equals(origin)) {
                        throw new IllegalStateException("Conflicting compiler origins for " + owner);
                    }
                }
                return super.visitClass(node, unused);
            }
        }.scan(unit, null);
    }

    private void write() throws IOException {
        if (failed) return;
        // A failed compilation cannot provide complete provenance. An ungenerated attributed class
        // prevents completion; callers must additionally accept sidecars only after compiler success.
        if (!generated.containsAll(origins.keySet())) return;
        var records = new ArrayList<String>();
        origins.values().stream().sorted(java.util.Comparator.comparing(Origin::owner)).forEach(origin -> records.add(origin.json()));
        String json = "{\"schema\":1,\"runId\":" + quote(runId) + ",\"producer\":{\"compiler\":" + quote(Compiler.JAVAC.wireValue()) + ",\"version\":"
            + quote(System.getProperty("java.version")) + ",\"backend\":" + quote(Backend.JVM.name()) + "},\"complete\":true,\"records\":["
            + String.join(",", records) + "],\"outputs\":[" + String.join(",", outputs.values()) + "]}\n";
        Files.createDirectories(output);
        Path destination = output.resolve(UUID.randomUUID() + ".json");
        Path temporary = Files.createTempFile(output, "origins-", ".tmp");
        try {
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean valid(long start, long end, int size) {
        return start >= 0 && end > start && end <= size;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK SHA-256 implementation missing", impossible);
        }
    }

    private static String quote(String text) {
        StringBuilder result = new StringBuilder("\"");
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            switch (value) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (value < 0x20) result.append(String.format("\\u%04x", (int) value));
                    else result.append(value);
                }
            }
        }
        return result.append('"').toString();
    }

    private record Origin(String owner, String source, String hash, long start, long end, long allocationStart, long allocationEnd) {
        String json() {
            return "{\"owner\":" + quote(owner) + ",\"source\":" + quote(source) + ",\"sourceHash\":" + quote(hash)
                + ",\"declaration\":{\"start\":" + start + ",\"end\":" + end + "},\"allocation\":{\"start\":"
                + allocationStart + ",\"end\":" + allocationEnd + "}}";
        }
    }
}
