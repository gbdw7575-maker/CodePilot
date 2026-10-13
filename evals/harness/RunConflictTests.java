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
 * DSH dsh-orch-conflict-v2 留出评分器（CONFLICT 类样本扩充，harness）。
 *
 * 由 EvalGrader 在「干净基线 + Agent 最终补丁」副本中以如下方式调用：
 *   java .grader-meta/RunConflictTests.java <sampleId>
 * 工作目录即评分副本根目录（src/ 的父目录）。
 *
 * harness 本体位于 Agent 工作区之外（evals/harness/），评分前才复制到独立评分副本；
 * Agent 无法读取或修改；断言规格随数据集冻结。输出 JUnit 风格统计行供 EvalGrader 解析：
 *   Tests run: N, Failures: X, Skipped: 0
 * 全部通过退出码 0，否则 1；编译失败按失败用例计。
 *
 * 样本类别（与数据集 category 一致，全部为 CONFLICT）：
 *   c05 Slugify：分隔符合并/去首尾 与 Unicode 字母保留，冲突于同一字符分类逻辑；
 *   c06 DiscountPrice：8 折修正 与 满额立减，冲突于同一返回表达式（顺序敏感）；
 *   c07 Rle：单字符省数字 与 超长游程分段，冲突于同一 append(run) 输出逻辑。
 *
 * 单文件源码模式（JEP 330）运行，无需预编译，JDK 21。
 */
public class RunConflictTests {

    static int failures = 0;
    static int tests = 0;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: java RunConflictTests.java <sampleId>");
            System.exit(2);
        }
        String sampleId = args[0];
        Path projectRoot = Path.of("").toAbsolutePath();

        List<Path> sources = new ArrayList<>();
        try (var stream = Files.walk(projectRoot.resolve("src"))) {
            stream.filter(p -> p.toString().endsWith(".java")).forEach(sources::add);
        }

        Path classes = Files.createTempDirectory("dsh-conflict-classes");
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
            List<String> options = List.of("-encoding", "UTF-8", "-d", classes.toString());
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
                case "c05" -> gradeC05(loader);
                case "c06" -> gradeC06(loader);
                case "c07" -> gradeC07(loader);
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

    /** 断言式调用：被测实现抛异常（含 NPE）记为一条 FAIL，不中断其余断言。 */
    static void checkEqInvoke(String name, Object expected, URLClassLoader loader,
                              String cls, String method, Class<?>[] sig, Object... args) {
        Object actual;
        try {
            actual = invoke(loader, cls, method, sig, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            check(name, false, "threw " + e.getCause());
            return;
        } catch (Exception e) {
            check(name, false, "threw " + e);
            return;
        }
        checkEq(name, expected, actual);
    }

    // ---- c05（conflict）：Slugify 分隔符合并/去首尾 与 Unicode 字母保留 ----
    static void gradeC05(URLClassLoader loader) throws Exception {
        Class<?>[] sig = {String.class};
        checkEqInvoke("c05 基本空格转连字符", "hello-world", loader,
                "text.Slugify", "slugify", sig, "Hello, World!");
        checkEqInvoke("c05 去首尾连字符（需求A）", "foo", loader,
                "text.Slugify", "slugify", sig, "  --foo--  ");
        checkEqInvoke("c05 保留带音标字母（需求B）", "café-au-lait", loader,
                "text.Slugify", "slugify", sig, "Café au lait");
        checkEqInvoke("c05 保留中文（需求B）", "中文标题-test", loader,
                "text.Slugify", "slugify", sig, "中文标题 Test");
        checkEqInvoke("c05 连续下划合并为单个（需求A）", "a-b-c", loader,
                "text.Slugify", "slugify", sig, "a_b__c");
        checkEqInvoke("c05 全标点结果为空（需求A）", "", loader,
                "text.Slugify", "slugify", sig, "!!!");
    }

    // ---- c06（conflict）：DiscountPrice 8 折修正 与 满额立减（顺序敏感） ----
    static void gradeC06(URLClassLoader loader) throws Exception {
        Class<?>[] sig = {long.class, boolean.class};
        checkEqInvoke("c06 50000 边界 8 折（需求A）", 40000L, loader,
                "shop.DiscountPrice", "price", sig, 50000L, true);
        checkEqInvoke("c06 50001 先乘后除向下取整", 40000L, loader,
                "shop.DiscountPrice", "price", sig, 50001L, true);
        checkEqInvoke("c06 10000 档 9 折", 9000L, loader,
                "shop.DiscountPrice", "price", sig, 10000L, true);
        checkEqInvoke("c06 满 100000 折后再减 500（需求B）", 79500L, loader,
                "shop.DiscountPrice", "price", sig, 100000L, true);
        checkEqInvoke("c06 满额立减按折后价计算", 119500L, loader,
                "shop.DiscountPrice", "price", sig, 150000L, true);
        checkEqInvoke("c06 非会员原价且不立减", 60000L, loader,
                "shop.DiscountPrice", "price", sig, 60000L, false);
        checkEqInvoke("c06 非会员满额也不立减", 120000L, loader,
                "shop.DiscountPrice", "price", sig, 120000L, false);
    }

    // ---- c07（conflict）：Rle 单字符省数字 与 超长游程按 9 分段 ----
    static void gradeC07(URLClassLoader loader) throws Exception {
        Class<?>[] sig = {String.class};
        checkEqInvoke("c07 单字符全程不写数字（需求A）", "abcd", loader,
                "codec.Rle", "encode", sig, "abcd");
        checkEqInvoke("c07 基本游程计数", "a2b3", loader,
                "codec.Rle", "encode", sig, "aabbb");
        checkEqInvoke("c07 中间单字符不写数字（需求A）", "a3b2", loader,
                "codec.Rle", "encode", sig, "aaabb");
        checkEqInvoke("c07 12 连按 9 分段（需求B）", "a9a3", loader,
                "codec.Rle", "encode", sig, "aaaaaaaaaaaa");
        checkEqInvoke("c07 单字符输入", "a", loader,
                "codec.Rle", "encode", sig, "a");
        checkEqInvoke("c07 空输入", "", loader,
                "codec.Rle", "encode", sig, "");
        checkEqInvoke("c07 分段后接续下一个字符", "a4b9b2", loader,
                "codec.Rle", "encode", sig, "aaaabbbbbbbbbbb");
    }
}
