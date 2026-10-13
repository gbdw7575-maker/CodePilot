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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * DSH dsh-orch-delegation-v1 留出评分器（MA-11 真实委派/并行覆盖，harness）。
 *
 * 由 EvalGrader 在「干净基线 + Agent 最终补丁」副本中以如下方式调用：
 *   java .grader-meta/RunDelegationTests.java <sampleId>
 * 工作目录即评分副本根目录（src/ 的父目录）。
 *
 * 与 RunContrastTests（dsh-orch-contrast-v1）互相独立：两份 harness 各自冻结，
 * 不共用样本 ID，避免把自主选择对照与强制委派覆盖两组实验混为一组统计。
 * harness 本体位于 Agent 工作区之外（evals/harness/），评分前才复制到独立评分副本，
 * Agent 无法读取或修改；断言规格随数据集冻结。输出 JUnit 风格统计行供 EvalGrader 解析：
 *   Tests run: N, Failures: X, Skipped: 0
 * 全部通过退出码 0，否则 1；编译失败按失败用例计。
 *
 * 样本（与数据集 category 一致，均为 INDEPENDENT：写范围互不相交）：
 *   d01 independent 三独立缺陷：text.Initials / collec.Join / codec.Rle
 *   d02 independent 三独立缺陷：shop.DiscountPrice / text.Slugify / codec.Caesar
 *
 * 只校验最终补丁的实际行为，不校验执行过程是否委派；过程证据由评测报告与
 * 运行态导出（子 Run、工具调用计数）单独提供，两组证据互不替代。
 *
 * 单文件源码模式（JEP 330）运行，无需预编译，JDK 21。
 */
public class RunDelegationTests {

    static int failures = 0;
    static int tests = 0;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: java RunDelegationTests.java <sampleId>");
            System.exit(2);
        }
        String sampleId = args[0];
        Path projectRoot = Path.of("").toAbsolutePath();

        List<Path> sources = new ArrayList<>();
        try (var stream = Files.walk(projectRoot.resolve("src"))) {
            stream.filter(p -> p.toString().endsWith(".java")).forEach(sources::add);
        }

        Path classes = Files.createTempDirectory("dsh-delegation-classes");
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
                case "d01" -> gradeD01(loader);
                case "d02" -> gradeD02(loader);
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

    // ---- d01（independent，三文件写范围互不相交）----
    static void gradeD01(URLClassLoader loader) throws Exception {
        Class<?>[] strSig = {String.class};
        checkEqInvoke("d01 Initials 多单词", "JRT", loader,
                "text.Initials", "initials", strSig, "john ronald tolkien");
        checkEqInvoke("d01 Initials 单单词", "A", loader,
                "text.Initials", "initials", strSig, "ada");
        checkEqInvoke("d01 Initials 空白入参", "", loader,
                "text.Initials", "initials", strSig, "   ");

        Class<?>[] joinSig = {List.class, String.class};
        checkEqInvoke("d01 Join 使用指定分隔符", "a/b", loader,
                "collec.Join", "join", joinSig, List.of("a", "b"), "/");
        checkEqInvoke("d01 Join 跳过 null 元素", "a-b", loader,
                "collec.Join", "join", joinSig,
                Arrays.asList("a", null, "b"), "-");
        checkEqInvoke("d01 Join 空列表", "", loader,
                "collec.Join", "join", joinSig, List.of(), "/");

        checkEqInvoke("d01 RLE 基本编码", "a3b2", loader,
                "codec.Rle", "encode", strSig, "aaabb");
        checkEqInvoke("d01 RLE 单字符不写计数", "abcd", loader,
                "codec.Rle", "encode", strSig, "abcd");
    }

    // ---- d02（independent，三文件写范围互不相交）----
    static void gradeD02(URLClassLoader loader) throws Exception {
        Class<?>[] longSig = {long.class, boolean.class};
        checkEqInvoke("d02 会员 50000 边界 8 折", 40000L, loader,
                "shop.DiscountPrice", "price", longSig, 50000L, true);
        checkEqInvoke("d02 会员 10000 边界 9 折", 9000L, loader,
                "shop.DiscountPrice", "price", longSig, 10000L, true);
        checkEqInvoke("d02 非会员原价", 60000L, loader,
                "shop.DiscountPrice", "price", longSig, 60000L, false);

        Class<?>[] strSig = {String.class};
        checkEqInvoke("d02 Slug 基本空格", "hello-world", loader,
                "text.Slugify", "slugify", strSig, "Hello World");
        checkEqInvoke("d02 Slug 连续分隔符合并并去首尾", "a-b", loader,
                "text.Slugify", "slugify", strSig, " --A  B-- ");

        Class<?>[] caesarSig = {String.class, int.class};
        checkEqInvoke("d02 大写字母保留并位移", "Ca!", loader,
                "codec.Caesar", "shift", caesarSig, "Bz!", 1);
        checkEqInvoke("d02 负位移方向正确", "Bz!", loader,
                "codec.Caesar", "shift", caesarSig, "Ca!", -1);
        checkEqInvoke("d02 大写正向回绕", "A", loader,
                "codec.Caesar", "shift", caesarSig, "Y", 2);
        checkEqInvoke("d02 大写负向回绕", "Y", loader,
                "codec.Caesar", "shift", caesarSig, "A", -2);
    }
}
