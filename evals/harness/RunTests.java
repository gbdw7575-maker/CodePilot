import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * DSH dsh-baseline-v1 留出评分器（harness）。
 *
 * 由 EvalGrader 在「干净基线 + Agent 最终补丁」副本中以如下方式调用：
 *   java .grader-meta/RunTests.java <sampleId>
 * 工作目录即评分副本根目录（src/ 的父目录）。
 *
 * harness 本体位于 Agent 工作区之外（evals/harness/），评分前才复制到独立评分副本；Agent 无法读取或修改；
 * 断言规格随数据集冻结。输出 JUnit 风格统计行供 EvalGrader 解析：
 *   Tests run: N, Failures: X, Skipped: 0
 * 全部通过退出码 0，否则 1；编译失败按失败用例计。
 *
 * 单文件源码模式（JEP 330）运行，无需预编译，JDK 21。
 */
public class RunTests {

    static int failures = 0;
    static int tests = 0;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: java RunTests.java <sampleId>");
            System.exit(2);
        }
        String sampleId = args[0];
        Path projectRoot = Path.of("").toAbsolutePath();

        List<Path> sources = new ArrayList<>();
        try (var stream = Files.walk(projectRoot.resolve("src"))) {
            stream.filter(p -> p.toString().endsWith(".java")).forEach(sources::add);
        }

        Path classes = Files.createTempDirectory("dsh-grade-classes");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            System.out.println("Tests run: 1, Failures: 1, Skipped: 0");
            System.out.println("[harness] 无系统 Java 编译器（需要 JDK）");
            System.exit(1);
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StringWriter compilerOut = new StringWriter();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(
                diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(sources);
            List<String> options = List.of("-d", classes.toString());
            boolean ok = compiler.getTask(new PrintWriter(compilerOut), fm, diagnostics,
                    options, null, units).call();
            if (!ok) {
                tests = 1;
                failures = 1;
                System.out.println("[harness] 编译失败：");
                diagnostics.getDiagnostics().forEach(d -> System.out.println("  " + d));
                System.out.print(compilerOut);
                printSummary();
                System.exit(1);
            }
        }

        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{classes.toUri().toURL()}, ClassLoader.getSystemClassLoader())) {
            switch (sampleId) {
                case "s01" -> gradeS01(loader);
                case "s02" -> gradeS02(loader);
                case "s03" -> gradeS03(loader);
                case "s04" -> gradeS04(loader);
                case "s05" -> gradeS05(loader);
                case "s06" -> gradeS06(loader);
                default -> {
                    System.out.println("Tests run: 1, Failures: 1, Skipped: 0");
                    System.err.println("[harness] 未知样本: " + sampleId);
                    System.exit(1);
                }
            }
        }
        printSummary();
        System.exit(failures == 0 ? 0 : 1);
    }

    static void printSummary() {
        System.out.printf("Tests run: %d, Failures: %d, Skipped: 0%n", tests, failures);
    }

    static void check(String name, boolean ok, String detail) {
        tests++;
        if (ok) {
            System.out.println("[PASS] " + name);
        } else {
            failures++;
            System.out.println("[FAIL] " + name + "  " + detail);
        }
    }

    static void checkEq(String name, Object expected, Object actual) {
        check(name, Objects.equals(expected, actual),
                "expected=" + expected + " actual=" + actual);
    }

    static Object invoke(URLClassLoader loader, String cls, String method,
                         Class<?>[] sig, Object... args) throws Exception {
        Class<?> c = Class.forName(cls, true, loader);
        Method m = c.getMethod(method, sig);
        return m.invoke(null, args);
    }

    // ---- s01: shop.DiscountPrice（满 50000 分享 8 折；满 10000 分享 9 折；非会员原价）----
    static void gradeS01(URLClassLoader loader) throws Exception {
        Class<?>[] sig = {long.class, boolean.class};
        checkEq("s01 非会员原价", 49999L, invoke(loader, "shop.DiscountPrice", "price", sig, 49999L, false));
        checkEq("s01 非会员大额原价", 60000L, invoke(loader, "shop.DiscountPrice", "price", sig, 60000L, false));
        checkEq("s01 会员未达门槛", 9999L, invoke(loader, "shop.DiscountPrice", "price", sig, 9999L, true));
        checkEq("s01 会员 10000 边界 9 折", 9000L, invoke(loader, "shop.DiscountPrice", "price", sig, 10000L, true));
        checkEq("s01 会员 49999 为 9 折", 44999L, invoke(loader, "shop.DiscountPrice", "price", sig, 49999L, true));
        checkEq("s01 会员 50000 边界 8 折", 40000L, invoke(loader, "shop.DiscountPrice", "price", sig, 50000L, true));
        checkEq("s01 会员 99900 为 8 折", 79920L, invoke(loader, "shop.DiscountPrice", "price", sig, 99900L, true));
        checkEq("s01 零金额", 0L, invoke(loader, "shop.DiscountPrice", "price", sig, 0L, true));
    }

    // ---- s02: text.Slugify（小写；非字母数字合并为单个连字符；无首尾连字符）----
    static void gradeS02(URLClassLoader loader) throws Exception {
        Class<?>[] sig = {String.class};
        checkEq("s02 基本空格", "hello-world",
                invoke(loader, "text.Slugify", "slugify", sig, "Hello World"));
        checkEq("s02 连续分隔符合并", "a-b",
                invoke(loader, "text.Slugify", "slugify", sig, "a  b"));
        checkEq("s02 首尾分隔符去除", "a-b",
                invoke(loader, "text.Slugify", "slugify", sig, " --A B-- "));
        checkEq("s02 标点替换", "java-21",
                invoke(loader, "text.Slugify", "slugify", sig, "Java_21"));
        checkEq("s02 仅分隔符为空串", "",
                invoke(loader, "text.Slugify", "slugify", sig, "!!!"));
        checkEq("s02 数字保留", "abc123",
                invoke(loader, "text.Slugify", "slugify", sig, "abc123"));
    }

    // ---- s03: collec.TopK（最大的 k 个，降序返回；k<=0 空；k>size 全量降序；不改原列表）----
    @SuppressWarnings("unchecked")
    static void gradeS03(URLClassLoader loader) throws Exception {
        Class<?>[] sig = {List.class, int.class};
        List<Integer> base = List.of(3, 1, 4, 1, 5, 9, 2, 6);
        checkEq("s03 取最大 3 个并降序", List.of(9, 6, 5),
                invoke(loader, "collec.TopK", "topK", sig, new ArrayList<>(base), 3));
        checkEq("s03 k=0 返空", List.of(),
                invoke(loader, "collec.TopK", "topK", sig, new ArrayList<>(base), 0));
        checkEq("s03 负 k 返空", List.of(),
                invoke(loader, "collec.TopK", "topK", sig, new ArrayList<>(base), -2));
        checkEq("s03 k 超过长度返全量降序", List.of(9, 6, 5, 4, 3, 2, 1, 1),
                invoke(loader, "collec.TopK", "topK", sig, new ArrayList<>(base), 100));
        List<Integer> original = new ArrayList<>(List.of(2, 1, 3));
        invoke(loader, "collec.TopK", "topK", sig, original, 2);
        checkEq("s03 不修改输入列表", List.of(2, 1, 3), original);
        checkEq("s03 空列表", List.of(),
                invoke(loader, "collec.TopK", "topK", sig, new ArrayList<Integer>(), 1));
    }

    // ---- s04: codec.Rle（游程编码；run=1 不写数字）----
    static void gradeS04(URLClassLoader loader) throws Exception {
        Class<?>[] sig = {String.class};
        checkEq("s04 基本编码", "a3b2", invoke(loader, "codec.Rle", "encode", sig, "aaabb"));
        checkEq("s04 单字符不写计数", "a2b", invoke(loader, "codec.Rle", "encode", sig, "aab"));
        checkEq("s04 全不重复", "abcd", invoke(loader, "codec.Rle", "encode", sig, "abcd"));
        checkEq("s04 空串", "", invoke(loader, "codec.Rle", "encode", sig, ""));
        checkEq("s04 单字母长 run", "a6", invoke(loader, "codec.Rle", "encode", sig, "aaaaaa"));
        checkEq("s04 交替字符", "abab", invoke(loader, "codec.Rle", "encode", sig, "abab"));
    }

    // ---- s05: text.WordWrap（按单词换行，不拆词；超长单词独占行允许硬切的规格见下）----
    @SuppressWarnings("unchecked")
    static void gradeS05(URLClassLoader loader) throws Exception {
        Class<?>[] sig = {String.class, int.class};
        List<String> r1 = (List<String>) invoke(loader, "text.WordWrap", "wrap", sig,
                "one two three four five", 9);
        check("s05 每行不超宽", r1.stream().allMatch(l -> l.length() <= 9),
                "lines=" + r1);
        checkEq("s05 贪心换行结果", List.of("one two", "three", "four five"), r1);
        check("s05 单词不被拆开",
                String.join(" ", r1).equals("one two three four five"),
                "joined=" + String.join(" ", r1));
        List<String> r2 = (List<String>) invoke(loader, "text.WordWrap", "wrap", sig, "hello", 3);
        check("s05 超长单词处理后全部行不超宽",
                r2.stream().allMatch(l -> l.length() <= 3), "lines=" + r2);
        checkEq("s05 单词恰好一行", List.of("hello"),
                invoke(loader, "text.WordWrap", "wrap", sig, "hello", 5));
    }

    // ---- s06: num.Roman（标准减法规则，1..3999）----
    static void gradeS06(URLClassLoader loader) throws Exception {
        Class<?>[] sig = {int.class};
        checkEq("s06 1", "I", invoke(loader, "num.Roman", "toRoman", sig, 1));
        checkEq("s06 4 减法 IV", "IV", invoke(loader, "num.Roman", "toRoman", sig, 4));
        checkEq("s06 9 减法 IX", "IX", invoke(loader, "num.Roman", "toRoman", sig, 9));
        checkEq("s06 58", "LVIII", invoke(loader, "num.Roman", "toRoman", sig, 58));
        checkEq("s06 400", "CD", invoke(loader, "num.Roman", "toRoman", sig, 400));
        checkEq("s06 1994", "MCMXCIV", invoke(loader, "num.Roman", "toRoman", sig, 1994));
        checkEq("s06 3999", "MMMCMXCIX", invoke(loader, "num.Roman", "toRoman", sig, 3999));
        checkEq("s06 3888", "MMMDCCCLXXXVIII", invoke(loader, "num.Roman", "toRoman", sig, 3888));
    }
}
