package com.dsh.search;

import com.dsh.common.IdGen;
import com.dsh.sandbox.CommandOutcome;
import com.dsh.sandbox.CommandSpec;
import com.dsh.sandbox.SandboxRunner;
import com.dsh.workspace.WorkspaceLease;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * ripgrep 进程执行器（D-05）：search_code 的主路径。
 *
 * <p>安全语义：</p>
 * <ul>
 *   <li>query 与目标路径作为独立 argv 传递（不经 shell），无命令注入面；</li>
 *   <li>默认不跟随符号链接（不加 --follow）、尊重 .gitignore、跳过隐藏文件与二进制；</li>
 *   <li>搜索根由调用方经 SafePaths 收敛在任务副本内，rg 只拿到副本内相对目标；</li>
 *   <li>超时后按进程树强杀（含子孙进程），stdout 收集有界；</li>
 *   <li>退出码：0 有命中，1 零命中，2 用法/正则错误。</li>
 * </ul>
 */
@Component
public class RipgrepRunner {

    private static final Logger log = LoggerFactory.getLogger(RipgrepRunner.class);

    /** 大仓搜索墙钟上限：超时即放弃并杀进程树，不拖垮 Agent Loop。 */
    static final int TIMEOUT_SECONDS = 15;
    /** stdout 收集上限：与沙箱命令同一量级，超限按 truncated 处理。 */
    static final int MAX_OUTPUT_BYTES = 256 * 1024;
    /** stderr 仅用于错误归因，固定小上限。 */
    private static final int MAX_ERROR_BYTES = 4096;
    /** 单行展示上限：与旧字面扫描器一致（500 字符）。 */
    static final int MAX_LINE_CHARS = 500;
    /** 默认排除的纯生成/类型声明文件 glob（负模式）；这些文件从不包含可修改实现。 */
    static final List<String> GENERATED_FILE_GLOBS = List.of(
            "!*.d.ts",      // TypeScript 环境类型声明（生成或手写，非实现）
            "!*.min.js",    // 压缩混淆 JS
            "!*.min.css");  // 压缩 CSS

    private final String executable;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean probeDone = new AtomicBoolean(false);
    private volatile boolean available;

    public RipgrepRunner() {
        this("rg", new ObjectMapper());
    }

    /** 测试构造：可指定不存在的可执行名以验证回退路径。 */
    public RipgrepRunner(String executable, ObjectMapper objectMapper) {
        this.executable = executable;
        this.objectMapper = objectMapper;
    }

    /** 惰性探测 rg 是否可用（rg --version，短超时），进程内只探一次。 */
    public boolean isAvailable() {
        if (probeDone.compareAndSet(false, true)) {
            available = probe();
            log.info("ripgrep 可用性探测: {} ({})", available, executable);
        }
        return available;
    }

    private boolean probe() {
        try {
            Process process = new ProcessBuilder(executable, "--version")
                    .redirectErrorStream(true).start();
            boolean finished = process.waitFor(3, TimeUnit.SECONDS);
            if (!finished) {
                killTree(process);
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /**
     * 在 searchDir 下执行搜索。base 为文件时 searchDir 取其父目录、目标取文件名。
     * 返回路径相对 searchDir（POSIX 风格），由工具层拼回工作区相对路径。
     */
    public SearchResult search(Path base, String query, boolean regex, int limit) {
        return search(base, query, regex, limit, List.of(), List.of());
    }

    public SearchResult search(Path base, String query, boolean regex, int limit,
                               List<String> includeGlobs, List<String> excludeGlobs) {
        Path searchDir = Files.isDirectory(base) ? base : base.getParent();
        String target = Files.isDirectory(base) ? "." : base.getFileName().toString();

        List<String> argv = new ArrayList<>(List.of(
                executable, "--json", "-n", "--encoding", "utf-8", "--no-text"));
        if (!regex) {
            argv.add("-F"); // 固定字符串，大小写敏感
        }
        // 排除纯生成/类型声明文件：它们只产生符号搜索假阳性，从不包含可修改实现。
        for (String g : GENERATED_FILE_GLOBS) {
            argv.add("--glob");
            argv.add(g);
        }
        for (String g : includeGlobs) {
            argv.add("--glob");
            argv.add(g);
        }
        for (String g : excludeGlobs) {
            argv.add("--glob");
            argv.add("!" + g);
        }
        argv.add("--");
        argv.add(query);
        argv.add(target);

        Process process;
        try {
            process = new ProcessBuilder(argv).directory(searchDir.toFile()).start();
        } catch (IOException e) {
            return SearchResult.error("RG_UNAVAILABLE", "ripgrep 启动失败: " + e.getMessage());
        }

        List<CodeMatch> matches = new ArrayList<>();
        StringBuilder stderr = new StringBuilder();
        AtomicBoolean outputLimited = new AtomicBoolean(false);
        AtomicBoolean enough = new AtomicBoolean(false);

        // stderr 独立虚拟线程排空，防止管道阻塞
        Thread errReader = Thread.ofVirtual().start(() ->
                drainBounded(process.getErrorStream(), stderr, MAX_ERROR_BYTES));
        Thread outReader = Thread.ofVirtual().start(() ->
                parseStdout(process.getInputStream(), matches, outputLimited, enough, limit));

        boolean finished;
        try {
            finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killTree(process);
            return SearchResult.error("SEARCH_INTERRUPTED", "代码搜索被中断");
        }
        if (!finished) {
            killTree(process);
            awaitQuietly(outReader, errReader);
            return SearchResult.timeout();
        }
        awaitQuietly(outReader, errReader);

        int exit = process.exitValue();
        if (exit == 0 || exit == 1) {
            boolean truncated = enough.get() || outputLimited.get();
            return SearchResult.ok(matches, truncated);
        }
        String detail = stderr.toString().trim();
        if (detail.contains("regex parse error") || detail.contains("unclosed")
                || detail.contains("invalid")) {
            return SearchResult.error("INVALID_REGEX",
                    "正则表达式无效: " + abbreviate(detail, 300));
        }
        return SearchResult.error("SEARCH_FAILED",
                "ripgrep 退出码 " + exit + (detail.isEmpty() ? "" : ": " + abbreviate(detail, 300)));
    }

    /** 生产路径：rg 只在任务副本的 Docker 命令沙箱内运行，用户参数按 POSIX 单引号转义。 */
    public SearchResult searchInSandbox(Path base, String query, boolean regex, int limit,
                                        List<String> includeGlobs, List<String> excludeGlobs,
                                        WorkspaceLease lease, SandboxRunner sandbox) {
        Path searchDir = Files.isDirectory(base) ? base : base.getParent();
        String target = Files.isDirectory(base) ? "." : base.getFileName().toString();
        StringBuilder command = new StringBuilder("rg --json -n --encoding utf-8 --no-text");
        if (!regex) command.append(" -F");
        for (String glob : GENERATED_FILE_GLOBS) {
            command.append(" --glob '").append(glob).append("'");
        }
        for (String glob : includeGlobs) command.append(" --glob ").append(shellQuote(glob));
        for (String glob : excludeGlobs) command.append(" --glob ").append(shellQuote("!" + glob));
        command.append(" -- ").append(shellQuote(query)).append(' ').append(shellQuote(target));
        CommandOutcome outcome = sandbox.execute(new CommandSpec(
                IdGen.next("op_"), command.toString(), searchDir, TIMEOUT_SECONDS), lease);
        if (outcome.termination() == CommandSpec.Termination.TIMEOUT) return SearchResult.timeout();
        if (outcome.termination() != CommandSpec.Termination.COMPLETED) {
            return SearchResult.error("RG_UNAVAILABLE", "Docker 代码搜索无法启动");
        }
        int exit = outcome.exitCode() == null ? -1 : outcome.exitCode();
        if (exit == 127) return SearchResult.error("RG_UNAVAILABLE", "沙箱镜像未安装 ripgrep");
        if (exit != 0 && exit != 1) {
            String detail = outcome.stderr() == null ? "" : outcome.stderr().trim();
            if (detail.contains("regex parse error") || detail.contains("unclosed")
                    || detail.contains("invalid")) {
                return SearchResult.error("INVALID_REGEX", "正则表达式无效: " + abbreviate(detail, 300));
            }
            return SearchResult.error("SEARCH_FAILED", "沙箱代码搜索失败，退出码 " + exit
                    + (detail.isEmpty() ? "" : ": " + abbreviate(detail, 300)));
        }
        List<CodeMatch> matches = new ArrayList<>();
        AtomicBoolean outputLimited = new AtomicBoolean(false);
        AtomicBoolean enough = new AtomicBoolean(false);
        parseStdout(new ByteArrayInputStream(outcome.stdout().getBytes(StandardCharsets.UTF_8)),
                matches, outputLimited, enough, limit);
        return SearchResult.ok(matches, outcome.truncated() || outputLimited.get() || enough.get());
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private void parseStdout(InputStream in, List<CodeMatch> sink, AtomicBoolean outputLimited,
                             AtomicBoolean enough, int limit) {
        long bytes = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String raw;
            while ((raw = reader.readLine()) != null) {
                bytes += raw.getBytes(StandardCharsets.UTF_8).length + 1L;
                if (bytes > MAX_OUTPUT_BYTES) {
                    outputLimited.set(true);
                    break;
                }
                JsonNode event;
                try {
                    event = objectMapper.readTree(raw);
                } catch (IOException e) {
                    continue; // 非 JSON 行（理论上 --json 不产生）忽略
                }
                if (!"match".equals(event.path("type").asText())) {
                    continue;
                }
                JsonNode data = event.path("data");
                String rel = normalizePath(data.path("path").path("text").asText(""));
                int lineNo = data.path("line_number").asInt();
                String text = stripEol(data.path("lines").path("text").asText(""));
                boolean lineTruncated = false;
                if (text.length() > MAX_LINE_CHARS) {
                    text = text.substring(0, MAX_LINE_CHARS);
                    lineTruncated = true;
                }
                synchronized (sink) {
                    if (sink.size() >= limit) {
                        enough.set(true);
                        break;
                    }
                    sink.add(new CodeMatch(rel, lineNo, text, lineTruncated));
                }
            }
        } catch (IOException e) {
            // 进程被杀时读流抛异常属正常路径，由退出码/超时分支统一归因
        }
    }

    /** rg 在 Windows 输出反斜杠相对路径（".\\src\\A.java"），统一为 POSIX 形式。 */
    private static String normalizePath(String raw) {
        String p = raw.replace('\\', '/');
        if (p.startsWith("./")) {
            p = p.substring(2);
        }
        return p;
    }

    private static String stripEol(String line) {
        if (line.endsWith("\n")) {
            line = line.substring(0, line.length() - 1);
        }
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        return line;
    }

    private static void drainBounded(InputStream in, StringBuilder sink, int maxBytes) {
        byte[] buffer = new byte[1024];
        int total = 0;
        try {
            int n;
            while ((n = in.read(buffer)) >= 0) {
                if (total < maxBytes) {
                    int allowed = Math.min(n, maxBytes - total);
                    sink.append(new String(buffer, 0, allowed, StandardCharsets.UTF_8));
                }
                total += n;
            }
        } catch (IOException e) {
            // 进程销毁时读流失败可忽略
        }
    }

    private static void awaitQuietly(Thread... threads) {
        for (Thread t : threads) {
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 超时/中断回收：先子孙后自身，全部强制终止并等待，确保无派生进程残留。 */
    @SuppressWarnings("resource")
    static void killTree(Process process) {
        process.destroy();
        try {
            if (process.waitFor(2, TimeUnit.SECONDS)) {
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try (Stream<ProcessHandle> descendants = process.toHandle().descendants()) {
            descendants.forEach(handle -> {
                handle.destroyForcibly();
                try {
                    handle.onExit().get(2, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // 尽力回收
                }
            });
        }
        process.destroyForcibly();
    }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
