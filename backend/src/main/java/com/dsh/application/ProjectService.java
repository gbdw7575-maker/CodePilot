package com.dsh.application;

import com.dsh.api.ApiException;
import com.dsh.api.dto.ErrorCode;
import com.dsh.api.dto.Project;
import com.dsh.common.IdGen;
import com.dsh.storage.ProjectEntity;
import com.dsh.storage.ProjectRepository;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** 项目登记与信任；未信任项目阻止执行类动作，不自动回滚已执行动作。 */
@Service
public class ProjectService {

    private final ProjectRepository repository;
    private final DshProperties properties;

    public ProjectService(ProjectRepository repository, DshProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public Project create(com.dsh.api.dto.CreateProject request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "name 必填且不能为空白");
        }
        if (request.name().length() > 200) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "name 长度不能超过 200");
        }
        String rootPath = request.rootPath();
        if (rootPath == null || rootPath.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "rootPath 必填");
        }
        if (!isAbsolutePath(rootPath)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "rootPath 必须是绝对路径");
        }
        checkAllowedRoots(rootPath);

        Instant now = Instant.now();
        ProjectEntity saved = repository.save(
                new ProjectEntity(IdGen.next("proj_"), request.name(), rootPath, false, now, now));
        return toDto(saved);
    }

    public Project get(String projectId) {
        return repository.findById(projectId).map(ProjectService::toDto)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "项目不存在"));
    }

    public List<ProjectEntity> listOrdered() {
        return repository.listOrdered();
    }

    public Project trust(String projectId, boolean trusted) {
        ProjectEntity project = repository.findById(projectId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "项目不存在"));
        project.setTrusted(trusted);
        project.setUpdatedAt(Instant.now());
        return toDto(repository.save(project));
    }

    public ProjectEntity requireEntity(String projectId) {
        return repository.findById(projectId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "项目不存在"));
    }

    private static boolean isAbsolutePath(String path) {
        return path.matches("^[A-Za-z]:[\\\\/].*") || path.startsWith("\\\\") || path.startsWith("/");
    }

    private void checkAllowedRoots(String rootPath) {
        if (!isWithinAllowedRoots(rootPath, properties.security().allowedRoots())) {
            throw new ApiException(ErrorCode.PATH_OUTSIDE_WORKSPACE, "rootPath 不在允许的项目根目录内");
        }
    }

    /**
     * 授权根核验（D-01）：先做字面规范化前缀比较（Windows 大小写不敏感），
     * 再对请求路径与授权根的已存在部分做 {@code toRealPath} 符号链接核验，
     * 拒绝"授权根内符号链接指向授权根外目标"的绕过；无法核验真实路径时按拒绝处理。
     *
     * @return true 表示通过（allowedRoots 为空时不限制）；false 表示越界或无法核验
     */
    static boolean isWithinAllowedRoots(String rootPath, List<String> allowedRoots) {
        if (allowedRoots == null || allowedRoots.isEmpty()) {
            return true;
        }
        Path requested = Path.of(rootPath).toAbsolutePath().normalize();
        Path requestedReal;
        try {
            requestedReal = nearestRealPath(requested);
        } catch (IOException e) {
            return false;
        }
        for (String root : allowedRoots) {
            Path allowed = Path.of(root).toAbsolutePath().normalize();
            Path allowedReal = allowed;
            if (Files.exists(allowed)) {
                try {
                    allowedReal = allowed.toRealPath();
                } catch (IOException e) {
                    return false;
                }
            }
            // 授权根若为符号链接，允许使用其词法名称或同一真实根名称。
            // 两者都必须再次通过真实路径包含检查，根内链接跳到外部仍拒绝。
            if ((literalInside(requested, allowed) || literalInside(requested, allowedReal))
                    && requestedReal.startsWith(allowedReal)) {
                return true;
            }
        }
        return false;
    }

    /** 字面（不解析符号链接）包含关系：等于授权根或在其下一级；Windows 盘符大小写不敏感。 */
    private static boolean literalInside(Path requested, Path allowed) {
        String child = normalizeKey(requested);
        String base = normalizeKey(allowed);
        String separator = File.separator.toUpperCase(Locale.ROOT);
        return child.equals(base) || child.startsWith(base + separator);
    }

    /** 最近存在祖先的真实路径，拼回尚不存在的尾部；已存在路径直接解析全部符号链接。 */
    private static Path nearestRealPath(Path path) throws IOException {
        Path existing = path;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return path;
        }
        Path realExisting = existing.toRealPath();
        return existing.equals(path) ? realExisting
                : realExisting.resolve(existing.relativize(path)).normalize();
    }

    private static String normalizeKey(Path path) {
        String normalized = path.toString().toUpperCase(Locale.ROOT);
        return normalized.endsWith("\\") && normalized.length() > 3
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
    }

    public static Project toDto(ProjectEntity entity) {
        return new Project(entity.getId(), entity.getName(), entity.getRootPath(), entity.isTrusted(),
                entity.getVersion() + 1, entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
