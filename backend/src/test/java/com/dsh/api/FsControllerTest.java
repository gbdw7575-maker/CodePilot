package com.dsh.api;

import com.dsh.application.DshProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务端目录浏览（选工作区）：盘符根列表、子目录导航、隐藏过滤、授权根限制。
 */
class FsControllerTest {

    @TempDir
    Path tempRoot;

    private FsController controller(List<String> allowedRoots) {
        return new FsController(new DshProperties(new DshProperties.Security(allowedRoots),
                new DshProperties.Budget(24, 80, 200000, 20, 128000),
                new DshProperties.Loop(true), new DshProperties.Workspace(""),
                new DshProperties.Sandbox("local", "dsh-sandbox:latest", "512m", "2.0", 60, 600, 4096),
                new DshProperties.Policy(10, List.of()),
                new DshProperties.Provider(10, 120, 3, 500),
                null, null, null, null, null, null, null, null));
    }

    @Test
    void rootListingReturnsDrivesWhenUnrestricted() {
        FsController.FsListing listing = controller(List.of()).entries(null);
        assertEquals(null, listing.path());
        assertEquals(null, listing.parent());
        assertFalse(listing.directories().isEmpty(), "Windows 下至少应列出 C:\\");
        assertTrue(listing.directories().stream().allMatch(d -> d.path().endsWith("\\")
                || d.path().endsWith("/")));
    }

    @Test
    void listsSubdirectoriesAndFiltersHidden() throws Exception {
        Files.createDirectories(tempRoot.resolve("visible-dir"));
        Files.createDirectories(tempRoot.resolve(".hidden-dir"));
        FsController.FsListing listing = controller(List.of()).entries(tempRoot.toString());

        assertEquals(tempRoot.toString(), listing.path());
        List<String> names = listing.directories().stream().map(FsController.FsEntry::name).toList();
        assertTrue(names.contains("visible-dir"));
        assertFalse(names.contains(".hidden-dir"), "以点开头的目录不进入选择器");
        assertNotNull(listing.parent());
    }

    @Test
    void invalidPathFailsWithValidation() {
        FsController c = controller(List.of());
        ApiException e1 = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> c.entries(tempRoot.resolve("no-such-dir").toString()));
        ApiException e2 = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> c.entries(tempRoot.resolve("sample.txt").toString()));
        assertEquals(com.dsh.api.dto.ErrorCode.VALIDATION_FAILED, e1.code());
        assertEquals(com.dsh.api.dto.ErrorCode.VALIDATION_FAILED, e2.code());
    }

    @Test
    void restrictedRootsLimitNavigation() throws Exception {
        Path outside = Files.createDirectories(tempRoot.resolve("outside-zone"));
        Path inside = Files.createDirectories(tempRoot.resolve("inside-zone"));
        Files.createDirectories(inside.resolve("child"));
        FsController c = controller(List.of(inside.toString()));

        // 根列表只显示授权根
        FsController.FsListing roots = c.entries(null);
        assertEquals(1, roots.directories().size());
        assertEquals(inside.toString(), roots.directories().get(0).path());

        // 授权根内正常导航
        FsController.FsListing inListing = c.entries(inside.toString());
        assertTrue(inListing.directories().stream().anyMatch(d -> d.name().equals("child")));

        // 根外路径拒绝
        ApiException denied = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> c.entries(outside.toString()));
        assertEquals(com.dsh.api.dto.ErrorCode.PERMISSION_DENIED, denied.code());
    }
}
