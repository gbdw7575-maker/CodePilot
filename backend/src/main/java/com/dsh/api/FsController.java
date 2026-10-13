package com.dsh.api;

import com.dsh.api.dto.ErrorCode;
import com.dsh.application.DshProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 服务端目录浏览（本地开发工具端点，不在 OpenAPI 契约内）：
 * 为前端"选择工作区"提供资源管理器式逐级导航——浏览器无法弹出原生目录对话框，
 * 由后端列目录、前端渲染导航是本场景的标准替代。
 *
 * <p>安全边界：只列目录名，不读文件内容、不返回文件；路径归一化后校验；
 * dsh.security.allowed-roots 非空时，导航与列表结果限制在授权根内。</p>
 */
@RestController
@RequestMapping("/api/v1/fs")
public class FsController {

    private final DshProperties properties;

    public FsController(DshProperties properties) {
        this.properties = properties;
    }

    /** 目录条目：name 为显示名，path 为可直接回传给本接口或项目创建的绝对路径。 */
    public record FsEntry(String name, String path) {
    }

    /** path=null 表示盘符/授权根列表；parent=null 表示已到顶层。 */
    public record FsListing(String path, String parent, List<FsEntry> directories) {
    }

    @GetMapping("/entries")
    public FsListing entries(@RequestParam(required = false) String path) {
        List<String> allowedRoots = properties.security().allowedRoots();
        boolean restricted = allowedRoots != null && !allowedRoots.isEmpty();

        if (path == null || path.isBlank()) {
            if (restricted) {
                List<FsEntry> roots = allowedRoots.stream()
                        .map(root -> Path.of(root).toAbsolutePath().normalize())
                        .filter(Files::isDirectory)
                        .map(root -> new FsEntry(root.toString(), root.toString()))
                        .toList();
                return new FsListing(null, null, roots);
            }
            List<FsEntry> drives = new ArrayList<>();
            for (Path root : java.nio.file.FileSystems.getDefault().getRootDirectories()) {
                if (Files.isDirectory(root)) {
                    drives.add(new FsEntry(root.toString(), root.toString()));
                }
            }
            return new FsListing(null, null, drives);
        }

        Path current = Path.of(path).toAbsolutePath().normalize();
        if (restricted && !isInsideAnyRoot(current, allowedRoots)) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED,
                    "路径不在允许的根目录内: " + current);
        }
        if (!Files.isDirectory(current)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "目录不存在或不是目录: " + current);
        }

        List<FsEntry> directories = new ArrayList<>();
        try (Stream<Path> children = Files.list(current)) {
            children.filter(Files::isDirectory)
                    .filter(p -> !isHiddenOrJunk(p))
                    .filter(p -> !restricted || isInsideAnyRoot(p.toAbsolutePath().normalize(), allowedRoots))
                    .map(p -> new FsEntry(p.getFileName().toString(), p.toAbsolutePath().normalize().toString()))
                    .sorted(Comparator.comparing(FsEntry::name, String.CASE_INSENSITIVE_ORDER))
                    .forEach(directories::add);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "目录读取失败: " + e.getMessage());
        }

        Path parent = current.getParent();
        String parentPath = null;
        if (parent != null && (!restricted || isInsideAnyRoot(parent, allowedRoots))) {
            parentPath = parent.toString();
        }
        return new FsListing(current.toString(), parentPath, directories);
    }

    private boolean isInsideAnyRoot(Path candidate, List<String> roots) {
        for (String root : roots) {
            Path rootPath = Path.of(root).toAbsolutePath().normalize();
            if (candidate.equals(rootPath) || candidate.startsWith(rootPath)) {
                return true;
            }
        }
        return false;
    }

    private boolean isHiddenOrJunk(Path p) {
        String name = p.getFileName() == null ? "" : p.getFileName().toString();
        if (name.startsWith(".") || name.startsWith("$")) {
            return true;
        }
        try {
            return Files.isHidden(p);
        } catch (IOException e) {
            return false;
        }
    }
}
